package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.Member
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 회원 리포지토리. 로그인 수단 관련 조회는 AuthCredentialRepository가 담당한다.
 */
interface MemberRepository : JpaRepository<Member, Long> {

    fun findByEmail(email: String): Member?

    fun existsByEmail(email: String): Boolean
}
