package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.member.domain.AuthCredential
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.DuplicateEmailException
import io.github.qkqnfld.information_management.member.domain.Member
import io.github.qkqnfld.information_management.member.domain.MemberNotFoundException
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.MemberRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회원 비즈니스 로직.
 */
@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberRepository,
    private val authCredentialRepository: AuthCredentialRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    /**
     * 자체 회원가입: 회원("사람")과 LOCAL 로그인 수단("문")이 한 트랜잭션에서 함께 만들어진다.
     * 어느 한쪽만 만들어지는 일이 없도록 보장하는 것이 트랜잭션의 역할이다.
     * 비밀번호는 BCrypt로 해싱해 수단 쪽에 저장한다.
     */
    @Transactional
    fun signup(email: String, rawPassword: String, nickname: String): Member {
        if (memberRepository.existsByEmail(email)) {
            throw DuplicateEmailException()
        }
        val member = try {
            memberRepository.save(Member(email = email, nickname = nickname))
        } catch (e: DataIntegrityViolationException) {
            // 두 요청이 완전히 동시에 들어와 existsByEmail을 둘 다 통과한 경우,
            // DB의 unique 제약이 최종 방어선이 된다. 500이 아니라 409로 변환한다.
            throw DuplicateEmailException()
        }
        authCredentialRepository.save(
            AuthCredential(
                memberId = member.id,
                provider = AuthProvider.LOCAL,
                // encode()는 시그니처상 null 가능이지만 BCrypt 구현은 항상 값을 돌려준다
                password = checkNotNull(passwordEncoder.encode(rawPassword)),
            ),
        )
        return member
    }

    fun findById(id: Long): Member {
        return memberRepository.findByIdOrNull(id) ?: throw MemberNotFoundException(id)
    }

    /** 이 회원에게 연결된 로그인 수단 목록 (설정 화면의 "연결된 계정" 데이터). */
    fun findProviders(memberId: Long): List<AuthProvider> {
        return authCredentialRepository.findByMemberId(memberId).map { it.provider }
    }
}
