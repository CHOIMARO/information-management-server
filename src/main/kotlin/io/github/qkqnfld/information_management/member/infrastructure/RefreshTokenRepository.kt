package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * refresh 토큰 리포지토리. 토큰은 원문이 아니라 해시(RefreshToken.hash)로 조회한다.
 */
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    /** 로그아웃·재사용 감지: 같은 로그인에서 이어진 토큰을 모두 삭제한다. 다른 기기의 로그인은 남는다. */
    fun deleteByFamilyId(familyId: String): Long

    /** 만료 시각이 기준 시각 이전인 토큰을 전부 삭제하고 삭제 건수를 반환한다 (정리 배치용). */
    fun deleteByExpiresAtBefore(cutoff: LocalDateTime): Long

    /** 탈퇴: 이 회원의 refresh 토큰을 모두 삭제한다. 다른 기기의 로그인도 함께 끊긴다. */
    fun deleteByMemberId(memberId: Long): Long
}
