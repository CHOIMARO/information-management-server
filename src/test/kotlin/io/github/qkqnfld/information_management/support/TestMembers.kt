package io.github.qkqnfld.information_management.support

import io.github.qkqnfld.information_management.common.security.JwtTokenProvider
import io.github.qkqnfld.information_management.member.domain.AuthCredential
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.Member
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.MemberRepository
import org.springframework.stereotype.Component

/**
 * 테스트용 회원을 만들고 access 토큰을 발급한다.
 *
 * 이메일 로그인이 없어져서, 회원을 만들려면 SNS 로그인을 거쳐야 한다.
 * SNS 로그인 자체를 검증하지 않는 테스트(더빙, 메모 등)는 SNS 클라이언트를 가짜로 바꾸지 않고
 * 이 클래스로 회원과 카카오 로그인 수단을 DB에 직접 만든다.
 * SNS 로그인 흐름은 SnsLoginApiTest가 따로 검증한다.
 *
 * 테스트 소스에 있는 @Component도 @SpringBootTest의 컴포넌트 스캔 대상이다.
 */
@Component
class TestMembers(
    private val memberRepository: MemberRepository,
    private val authCredentialRepository: AuthCredentialRepository,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    /** 새 회원을 만들고 그 회원의 access 토큰을 돌려준다. */
    fun newMemberToken(nickname: String = "테스터"): String = jwtTokenProvider.createToken(newMemberId(nickname))

    /** 새 회원(카카오 로그인 수단 1개)을 만들고 회원 id를 돌려준다. */
    fun newMemberId(nickname: String = "테스터"): Long {
        val member = memberRepository.save(Member(email = null, nickname = nickname))
        authCredentialRepository.save(
            AuthCredential(memberId = member.id, provider = AuthProvider.KAKAO, providerId = "test-${System.nanoTime()}"),
        )
        return member.id
    }
}
