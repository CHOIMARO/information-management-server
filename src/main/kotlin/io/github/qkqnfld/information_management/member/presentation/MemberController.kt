package io.github.qkqnfld.information_management.member.presentation

import io.github.qkqnfld.information_management.member.application.AuthService
import io.github.qkqnfld.information_management.member.application.MemberService
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 회원 HTTP 엔드포인트: 내 정보, 프로필 수정, 탈퇴, 계정 연동.
 * 가입 API는 없다. 첫 SNS 로그인(POST /auth/login/kakao 등)이 곧 가입이다.
 * 모두 인증된 세션이 전제라 permitAll이 아니다.
 */
@RestController
@RequestMapping("/members")
class MemberController(
    private val memberService: MemberService,
    private val authService: AuthService,
) {

    /**
     * 내 정보 조회. @AuthenticationPrincipal은 JwtAuthenticationFilter가
     * SecurityContext에 넣어둔 principal(회원 id)을 꺼내 주입한다.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal memberId: Long): MemberResponse {
        val member = memberService.findById(memberId)
        return MemberResponse.from(member, memberService.findProviders(memberId))
    }

    /**
     * 프로필 수정 (마이 > [프로필 수정]). 지금은 이름만 바꿀 수 있다.
     * PATCH: 리소스의 일부만 바꾸는 요청이다. 전체를 바꾸는 PUT과 구분한다.
     */
    @PatchMapping("/me")
    fun updateProfile(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): MemberResponse {
        val member = memberService.updateNickname(memberId, request.nickname)
        return MemberResponse.from(member, memberService.findProviders(memberId))
    }

    /**
     * 회원 탈퇴. 계정을 바로 지우고 204를 응답한다.
     * SNS 연결 끊기와 더빙 파일 삭제 같은 후처리는 서버가 뒤에서 처리한다 (MemberService.withdraw 참고).
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdraw(@AuthenticationPrincipal memberId: Long) {
        memberService.withdraw(memberId)
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

    /** 내 계정에 Apple 로그인 수단 연결. */
    @PostMapping("/me/links/apple")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun linkApple(@AuthenticationPrincipal memberId: Long, @Valid @RequestBody request: AppleLinkRequest) {
        authService.linkApple(memberId, request.token, request.nonce, request.authorizationCode)
    }

    /** 로그인 수단 연결 해제. provider는 KAKAO/GOOGLE/APPLE (대문자). 마지막 수단은 해제 불가(409). */
    @DeleteMapping("/me/links/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(@AuthenticationPrincipal memberId: Long, @PathVariable provider: AuthProvider) {
        authService.unlink(memberId, provider)
    }
}
