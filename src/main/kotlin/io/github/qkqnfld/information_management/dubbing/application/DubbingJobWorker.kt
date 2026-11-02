package io.github.qkqnfld.information_management.dubbing.application

import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsErrorType
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 더빙 작업 처리기. DB를 큐로 쓴다: PENDING 작업을 하나씩 꺼내 합성하고 S3에 올린다.
 *
 * 처리 순서와 트랜잭션:
 * 1. 작업 가져오기 (짧은 트랜잭션 1, DubbingJobService.claimNext)
 * 2. Typecast 합성 + S3 업로드 (트랜잭션 밖)
 * 3. 결과 기록 (짧은 트랜잭션 2, complete 또는 recordFailure)
 * 2번을 트랜잭션 밖에서 하는 이유: 트랜잭션은 DB 커넥션을 잡고 있다.
 * 수십 초 걸리는 외부 호출 동안 커넥션을 잡으면, 커넥션 풀(기본 10개)이 바닥나 다른 요청이 멈춘다.
 *
 * 한 번에 한 작업만 처리하므로 Typecast 동시 요청 한도(Lite 5개)를 넘지 않는다.
 * 요청이 몰리면 나머지 작업은 DB에서 차례를 기다린다.
 */
@Component
class DubbingJobWorker(
    private val dubbingJobService: DubbingJobService,
    private val ttsClient: TtsClient,
    private val audioStorage: AudioStorage,
) {

    private val log = LoggerFactory.getLogger(DubbingJobWorker::class.java)

    /**
     * 처리할 작업을 하나 꺼내 처리한다.
     * fixedDelay: 이전 실행이 끝난 뒤 지정한 시간(기본 1초)이 지나면 다시 실행한다.
     * 테스트는 initial-delay를 길게 잡아 자동 실행을 막고, 이 메서드를 직접 부른다.
     */
    @Scheduled(
        fixedDelayString = "\${dubbing.worker.fixed-delay-ms:1000}",
        initialDelayString = "\${dubbing.worker.initial-delay-ms:5000}",
    )
    fun processNext() {
        val task = dubbingJobService.claimNext() ?: return
        try {
            val audio = ttsClient.synthesize(task.text, task.voice)
            val audioKey = "dubbings/${task.memberId}/${task.jobId}.mp3"
            audioStorage.upload(audioKey, audio, AUDIO_CONTENT_TYPE)
            if (!dubbingJobService.complete(task.jobId, audioKey)) {
                // 처리하는 사이에 사용자가 작업을 삭제했다 → 방금 올린 파일도 지운다
                audioStorage.delete(audioKey)
            }
        } catch (e: TtsException) {
            dubbingJobService.recordFailure(task.jobId, e.message ?: "TTS 실패", retryable = e.type == TtsErrorType.TEMPORARY)
        } catch (e: Exception) {
            // S3 업로드 실패처럼 예상하지 못한 오류는 일시적인 문제로 보고 재시도한다
            log.error("더빙 작업 처리 실패: jobId={}", task.jobId, e)
            dubbingJobService.recordFailure(task.jobId, e.message ?: e.javaClass.simpleName, retryable = true)
        }
    }

    companion object {
        private const val AUDIO_CONTENT_TYPE = "audio/mpeg"
    }
}
