package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.application.RefreshTokenCleanupScheduler
import io.github.qkqnfld.information_management.member.domain.RefreshToken
import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 만료 refresh 토큰 정리 배치 테스트.
 * 스케줄(새벽 4시)을 기다릴 수는 없으므로, 스케줄에 걸린 메서드를 직접 호출해서
 * 로직만 검증한다 — "언제 도는가"(스케줄)와 "무엇을 하는가"(로직)를 분리해서 보는 것.
 */
@SpringBootTest
class RefreshTokenCleanupTest {

    @Autowired
    private lateinit var scheduler: RefreshTokenCleanupScheduler

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Test
    fun `만료된 토큰만 삭제되고 유효한 토큰은 남는다`() {
        val expired = refreshTokenRepository.save(
            RefreshToken(token = "expired-${System.nanoTime()}", memberId = 1L, expiresAt = LocalDateTime.now().minusDays(1)),
        )
        val valid = refreshTokenRepository.save(
            RefreshToken(token = "valid-${System.nanoTime()}", memberId = 1L, expiresAt = LocalDateTime.now().plusDays(30)),
        )

        scheduler.deleteExpiredTokens()

        assertNull(refreshTokenRepository.findByToken(expired.token), "만료된 토큰은 삭제되어야 한다")
        assertNotNull(refreshTokenRepository.findByToken(valid.token), "유효한 토큰은 남아 있어야 한다")
    }
}
