package io.github.qkqnfld.information_management.dubbing.application

import io.github.qkqnfld.information_management.dubbing.domain.CustomVoice
import io.github.qkqnfld.information_management.dubbing.domain.CustomVoiceLimitExceededException
import io.github.qkqnfld.information_management.dubbing.domain.CustomVoiceNotFoundException
import io.github.qkqnfld.information_management.dubbing.domain.InvalidVoiceSampleException
import io.github.qkqnfld.information_management.dubbing.domain.VoiceServiceUnavailableException
import io.github.qkqnfld.information_management.dubbing.infrastructure.CustomVoiceRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsErrorType
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 내 목소리(복제 목소리) 등록·조회·삭제.
 * 클래스 기본은 읽기 전용 트랜잭션이다.
 * 외부 API를 부르는 쓰기 메서드는 트랜잭션 없이(NOT_SUPPORTED) 실행한다 — 각 메서드 설명 참고.
 */
@Service
@Transactional(readOnly = true)
class VoiceService(
    private val customVoiceRepository: CustomVoiceRepository,
    private val ttsClient: TtsClient,
) {

    private val log = LoggerFactory.getLogger(VoiceService::class.java)

    fun findMyVoices(memberId: Long): List<CustomVoice> {
        return customVoiceRepository.findAllByMemberIdOrderByIdAsc(memberId)
    }

    /**
     * 녹음 샘플로 내 목소리를 등록한다 (회원당 1개). 동의 여부는 컨트롤러 진입 전에 검증된다.
     *
     * NOT_SUPPORTED = 이 메서드는 트랜잭션 없이 실행한다.
     * 클래스 기본값(readOnly 트랜잭션)을 그대로 쓰면 두 가지 문제가 생긴다.
     * 1. 외부 API를 기다리는 동안 트랜잭션이 열려 있다.
     * 2. 읽기 전용 트랜잭션 안에서는 INSERT가 거절된다.
     * DB 작업은 리포지토리 메서드가 각자 짧은 트랜잭션으로 처리한다.
     *
     * @param sample 녹음 파일 내용 (서버에 저장하지 않고 업체로만 보낸다)
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun register(memberId: Long, name: String, sample: ByteArray, filename: String, contentType: String?): CustomVoice {
        val consentedAt = LocalDateTime.now()
        if (customVoiceRepository.existsByMemberId(memberId)) {
            throw CustomVoiceLimitExceededException()
        }

        // 업체 쪽 이름에는 회원 id만 쓴다. 사용자가 정한 이름은 개인정보일 수 있어 우리 DB에만 둔다.
        val providerVoiceId = try {
            ttsClient.cloneVoice(name = "member-$memberId", sample = sample, filename = filename, contentType = contentType)
        } catch (e: TtsException) {
            throw if (e.type == TtsErrorType.INVALID_INPUT) InvalidVoiceSampleException() else VoiceServiceUnavailableException()
        }

        try {
            return customVoiceRepository.save(
                CustomVoice(memberId = memberId, name = name, providerVoiceId = providerVoiceId, consentedAt = consentedAt),
            )
        } catch (e: Exception) {
            // 보상 처리: 외부 서비스와 DB는 한 트랜잭션으로 묶을 수 없다.
            // DB 저장이 실패하면 방금 만든 업체 쪽 목소리를 지워 슬롯을 돌려놓는다.
            deleteProviderVoiceQuietly(providerVoiceId)
            // 유니크 제약 위반 = 같은 회원의 동시 등록 요청이 먼저 저장됐다
            if (e is DataIntegrityViolationException) throw CustomVoiceLimitExceededException()
            throw e
        }
    }

    /**
     * 내 목소리를 삭제한다. 업체 쪽 목소리를 먼저 지우고, 성공하면 DB 행을 지운다.
     * 업체 삭제가 실패하면 DB 행이 남으므로 사용자가 다시 삭제를 시도할 수 있다.
     * 이 목소리를 쓰던 더빙 작업의 customVoiceId는 DB가 null로 바꾼다 (on delete set null).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun delete(memberId: Long, id: Long) {
        val voice = customVoiceRepository.findByIdAndMemberId(id, memberId) ?: throw CustomVoiceNotFoundException(id)
        try {
            ttsClient.deleteVoice(voice.providerVoiceId)
        } catch (e: TtsException) {
            throw VoiceServiceUnavailableException()
        }
        customVoiceRepository.delete(voice)
    }

    private fun deleteProviderVoiceQuietly(providerVoiceId: String) {
        try {
            ttsClient.deleteVoice(providerVoiceId)
        } catch (e: Exception) {
            // 보상 처리까지 실패하면 슬롯 하나가 버려진다. 운영자가 정리할 수 있게 id를 남긴다.
            log.error("업체 목소리 보상 삭제 실패: providerVoiceId={}", providerVoiceId, e)
        }
    }
}
