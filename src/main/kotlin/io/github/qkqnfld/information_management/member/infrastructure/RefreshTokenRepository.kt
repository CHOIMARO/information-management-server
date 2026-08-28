package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * refresh 토큰 리포지토리.
 */
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByToken(token: String): RefreshToken?

    /** 로그아웃: 해당 토큰을 삭제한다. 없는 토큰이어도 조용히 넘어간다 (멱등). */
    fun deleteByToken(token: String)

    /** 만료 시각이 기준 시각 이전인 토큰을 전부 삭제하고 삭제 건수를 반환한다 (정리 배치용). */
    fun deleteByExpiresAtBefore(cutoff: LocalDateTime): Long
}
