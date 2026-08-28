package io.github.qkqnfld.information_management.member.presentation

import io.github.qkqnfld.information_management.member.application.AuthService
import io.github.qkqnfld.information_management.member.application.MemberService
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 회원 HTTP 엔드포인트. me/links 하위 경로는 계정 연동(로그인 수단 관리)이다 —
 * 인증된 세션이 전제라 auth 경로들과 달리 permitAll이 아니다.
 */
@RestController
@RequestMapping("/members")
class MemberController(
    private val memberService: MemberService,
    private val authService: AuthService,
) {

    /** 회원가입. 인증 없이 접근 가능한 문이다 (SecurityConfig에서 permitAll). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): MemberResponse {
        val member = memberService.signup(request.email, request.password, request.nickname)
        return MemberResponse.from(member, listOf(AuthProvider.LOCAL))
    }

    /**
     * 내 정보 조회. @AuthenticationPrincipal은 JwtAuthenticationFilter가
     * SecurityContext에 넣어둔 principal(회원 id)을 꺼내 주입한다.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal memberId: Long): MemberResponse {
        val member = memberService.findById(memberId)
        return MemberResponse.from(member, memberService.findProviders(memberId))
    }

    /** 내 계정에 카카오 로그인 수단 연결. 이메일 일치 여부와 무관하게 동작한다. */
    @PostMapping("/me/links/kakao")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun linkKakao(@AuthenticationPrincipal memberId: Long, @Valid @RequestBody request: SnsLoginRequest) {
        authService.linkKakao(memberId, request.token)
    }

    /** 내 계정에 구글 로그인 수단 연결. */
    @PostMapping("/me/links/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun linkGoogle(@AuthenticationPrincipal memberId: Long, @Valid @RequestBody request: SnsLoginRequest) {
        authService.linkGoogle(memberId, request.token)
    }

    /** 로그인 수단 연결 해제. provider는 LOCAL/KAKAO/GOOGLE (대문자). 마지막 수단은 해제 불가(409). */
    @DeleteMapping("/me/links/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(@AuthenticationPrincipal memberId: Long, @PathVariable provider: AuthProvider) {
        authService.unlink(memberId, provider)
    }
}
