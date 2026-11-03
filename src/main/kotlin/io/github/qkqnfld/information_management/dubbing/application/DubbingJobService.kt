package io.github.qkqnfld.information_management.dubbing.application

import io.github.qkqnfld.information_management.dubbing.domain.CustomVoiceNotFoundException
import io.github.qkqnfld.information_management.dubbing.domain.DubbingJob
import io.github.qkqnfld.information_management.dubbing.domain.DubbingJobNotFoundException
import io.github.qkqnfld.information_management.dubbing.domain.DubbingJobStatus
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.CustomVoiceRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.DubbingJobRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsVoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 더빙 작업의 생성·조회·삭제와 상태 변경을 담당한다 (트랜잭션 경계).
 * 실제 합성과 업로드는 DubbingJobWorker가 트랜잭션 밖에서 한다.
 * 이 클래스는 그 앞뒤의 짧은 트랜잭션(작업 가져오기, 결과 기록)만 제공한다.
 */
@Service
@Transactional(readOnly = true)
class DubbingJobService(
    private val dubbingJobRepository: DubbingJobRepository,
    private val customVoiceRepository: CustomVoiceRepository,
    private val audioStorage: AudioStorage,
    @Value("\${dubbing.worker.max-attempts:3}") private val maxAttempts: Int,
    @Value("\${dubbing.worker.stale-after-minutes:5}") private val staleAfterMinutes: Long,
) {

    private val log = LoggerFactory.getLogger(DubbingJobService::class.java)

    // ==================== 앱 요청 처리 ====================

    /**
     * 더빙 작업을 PENDING 상태로 접수한다. 실제 처리는 Worker가 나중에 한다.
     * 내 목소리를 지정했다면 그 목소리의 주인인지 먼저 확인한다.
     */
    @Transactional
    fun create(memberId: Long, text: String, presetVoice: PresetVoice?, customVoiceId: Long?, speed: Double): DubbingJobResult {
        if (customVoiceId != null) {
            customVoiceRepository.findByIdAndMemberId(customVoiceId, memberId)
                ?: throw CustomVoiceNotFoundException(customVoiceId)
        }
        val job = dubbingJobRepository.save(
            DubbingJob(memberId = memberId, text = text, presetVoice = presetVoice, customVoiceId = customVoiceId, speed = speed),
        )
        return DubbingJobResult(job = job, audioUrl = null)
    }

    /** 내 작업 단건 조회. 남의 작업은 존재하더라도 404로 응답한다 (리소스 존재 은닉). */
    fun findById(memberId: Long, id: Long): DubbingJobResult {
        return toResult(findOwnedJob(memberId, id))
    }

    fun findAll(memberId: Long, pageable: Pageable): Page<DubbingJobResult> {
        return dubbingJobRepository.findAllByMemberId(memberId, pageable).map { toResult(it) }
    }

    /**
     * 작업과 결과 파일을 함께 삭제한다.
     * S3 삭제가 실패하면 예외로 트랜잭션이 롤백되어 DB 행이 남는다. 그래서 사용자가 다시 삭제할 수 있다.
     */
    @Transactional
    fun delete(memberId: Long, id: Long) {
        val job = findOwnedJob(memberId, id)
        dubbingJobRepository.delete(job)
        job.audioKey?.let { audioStorage.delete(it) }
    }

    /**
     * 탈퇴한 회원의 작업과 결과 파일을 모두 지운다 (DubbingDataCleaner가 부른다).
     * S3 삭제가 하나라도 실패하면 트랜잭션이 롤백되고, 탈퇴 Worker가 나중에 처음부터 다시 부른다.
     * S3 삭제는 멱등이라 이미 지운 파일을 다시 지워도 괜찮다.
     *
     * 처리 중(PROCESSING)인 작업도 지운다. Worker가 그 뒤에 결과를 올려도
     * complete()가 작업을 찾지 못하므로 Worker가 방금 올린 파일을 지운다.
     */
    @Transactional
    fun deleteAllOfMember(memberId: Long) {
        val jobs = dubbingJobRepository.findAllByMemberId(memberId)
        jobs.mapNotNull { it.audioKey }.forEach { audioStorage.delete(it) }
        dubbingJobRepository.deleteAll(jobs)
    }

    // ==================== Worker 전용 ====================

    /**
     * 처리할 작업 하나를 가져와 PROCESSING으로 바꾼다. 처리할 작업이 없으면 null을 반환한다.
     * 엔티티 대신 값 사본(DubbingTask)을 반환한다. Worker는 트랜잭션 밖에서 일하기 때문이다.
     */
    @Transactional
    fun claimNext(now: LocalDateTime = LocalDateTime.now()): DubbingTask? {
        val job = dubbingJobRepository.findNextForProcessing(staleBefore = now.minusMinutes(staleAfterMinutes))
            ?: return null

        // PROCESSING에 멈춰 있던 작업이 이미 한도만큼 시도했다면 더는 시도하지 않는다
        if (!job.hasAttemptsLeft(maxAttempts)) {
            job.fail("재시도 한도를 넘었습니다", now)
            return null
        }
        val voice = resolveVoice(job) ?: run {
            job.fail("목소리가 삭제되었습니다", now)
            return null
        }
        job.start(now)
        return DubbingTask(jobId = job.id, memberId = job.memberId, text = job.text, voice = voice, speed = job.speed)
    }

    /**
     * 결과 파일 업로드를 마친 작업을 완료 처리한다.
     * @return 작업이 처리 도중 삭제되었거나 이미 다른 상태로 바뀌었으면 false
     */
    @Transactional
    fun complete(jobId: Long, audioKey: String): Boolean {
        val job = dubbingJobRepository.findByIdOrNull(jobId)
        if (job == null || job.status != DubbingJobStatus.PROCESSING) {
            log.warn("완료 처리를 건너뜀: jobId={}, status={}", jobId, job?.status)
            return false
        }
        job.complete(audioKey)
        return true
    }

    /**
     * 처리 실패를 기록한다.
     * 일시적인 오류이고 재시도 횟수가 남았으면 PENDING으로 되돌리고, 아니면 FAILED로 끝낸다.
     */
    @Transactional
    fun recordFailure(jobId: Long, reason: String, retryable: Boolean) {
        val job = dubbingJobRepository.findByIdOrNull(jobId)
        if (job == null || job.status != DubbingJobStatus.PROCESSING) return
        if (retryable && job.hasAttemptsLeft(maxAttempts)) {
            job.retryLater(reason)
        } else {
            job.fail(reason)
        }
    }

    private fun resolveVoice(job: DubbingJob): TtsVoice? {
        job.presetVoice?.let { return TtsVoice.Preset(it) }
        val customVoiceId = job.customVoiceId ?: return null
        return customVoiceRepository.findByIdOrNull(customVoiceId)?.let { TtsVoice.Custom(it.providerVoiceId) }
    }

    private fun findOwnedJob(memberId: Long, id: Long): DubbingJob {
        return dubbingJobRepository.findByIdAndMemberId(id, memberId) ?: throw DubbingJobNotFoundException(id)
    }

    /** 완료된 작업에만 다운로드 URL을 붙인다. URL은 조회할 때마다 새로 발급한다. */
    private fun toResult(job: DubbingJob): DubbingJobResult {
        val audioUrl = job.audioKey
            ?.takeIf { job.status == DubbingJobStatus.COMPLETED }
            ?.let { audioStorage.createDownloadUrl(it) }
        return DubbingJobResult(job = job, audioUrl = audioUrl)
    }
}

/** 작업 조회 결과: 엔티티와, 완료된 작업이면 다운로드 URL. */
data class DubbingJobResult(
    val job: DubbingJob,
    val audioUrl: String?,
)

/**
 * Worker가 트랜잭션 밖에서 쓰는 작업 정보 사본.
 * 엔티티를 트랜잭션 밖으로 가져가지 않으려고 필요한 값만 복사한다.
 */
data class DubbingTask(
    val jobId: Long,
    val memberId: Long,
    val text: String,
    val voice: TtsVoice,
    val speed: Double,
)
