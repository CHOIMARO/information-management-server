package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 만료된 refresh 토큰 정리 배치.
 * 만료 행은 요청 흐름에서 지울 계기가 없어(재발급 실패 시 지우면 롤백으로 되돌아감 —
 * AuthService.refresh 참고) 주기 작업으로 정리한다.
 *
 * 주의: @Scheduled는 서버 프로세스마다 실행되므로, 인스턴스를 여러 대로 늘리면
 * 같은 작업이 중복 실행된다. 삭제는 멱등이라 지금은 무해하지만,
 * 다중 인스턴스 전환 시 분산 잠금(ShedLock 등)을 검토한다.
 */
@Component
class RefreshTokenCleanupScheduler(
    private val refreshTokenRepository: RefreshTokenRepository,
) {

    private val log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler::class.java)

    /** 매일 새벽 4시(트래픽이 가장 적은 시간대)에 만료된 refresh 토큰을 삭제한다. */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    fun deleteExpiredTokens() {
        val deleted = refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now())
        log.info("만료된 refresh 토큰 {}건 정리 완료", deleted)
    }
}
