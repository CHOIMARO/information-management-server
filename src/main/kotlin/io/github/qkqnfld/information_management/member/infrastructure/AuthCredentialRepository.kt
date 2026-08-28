package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.AuthCredential
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 로그인 수단 리포지토리.
 */
interface AuthCredentialRepository : JpaRepository<AuthCredential, Long> {

    /** SNS 계정 신원 조회: 이 SNS 계정이 어느 회원에게 연결되어 있는가 */
    fun findByProviderAndProviderId(provider: AuthProvider, providerId: String): AuthCredential?

    fun findByMemberIdAndProvider(memberId: Long, provider: AuthProvider): AuthCredential?

    fun findByMemberId(memberId: Long): List<AuthCredential>

    fun existsByMemberIdAndProvider(memberId: Long, provider: AuthProvider): Boolean

    fun countByMemberId(memberId: Long): Long
}
