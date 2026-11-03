package io.github.qkqnfld.information_management.member.presentation

import io.github.qkqnfld.information_management.member.application.AuthService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 인증 HTTP 엔드포인트. 회원 리소스(/members)와 구분되는 행위성 경로라 분리한다.
 * 모든 엔드포인트는 access 토큰 없이 호출 가능하다 (SecurityConfig에서 permitAll) —
 * 로그인 전이거나 access가 만료된 상태에서도 재발급과 로그아웃은 가능해야 하기 때문이다.
 * 로그인 수단은 SNS(카카오·구글·Apple)뿐이고, 첫 로그인이 곧 가입이다.
 */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {

    /** 카카오 로그인. 처음이면 자동 가입된다. */
    @PostMapping("/login/kakao")
    fun kakaoLogin(@Valid @RequestBody request: SnsLoginRequest): TokenResponse {
        val tokens = authService.kakaoLogin(request.token)
        return TokenResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    /** 구글 로그인. 처음이면 자동 가입된다. */
    @PostMapping("/login/google")
    fun googleLogin(@Valid @RequestBody request: SnsLoginRequest): TokenResponse {
        val tokens = authService.googleLogin(request.token)
        return TokenResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    /** Apple 로그인. 처음이면 자동 가입된다. */
    @PostMapping("/login/apple")
    fun appleLogin(@Valid @RequestBody request: AppleLoginRequest): TokenResponse {
        val tokens = authService.appleLogin(request.token, request.nonce, request.nickname, request.authorizationCode)
        return TokenResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    /** access 토큰 재발급. refresh 토큰이 무효/만료면 401 → 클라이언트는 재로그인. */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): TokenResponse {
        val tokens = authService.refresh(request.refreshToken)
        return TokenResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
    }

    /** 로그아웃. 멱등 — 이미 없는 토큰이어도 204를 응답한다. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: RefreshTokenRequest) {
        authService.logout(request.refreshToken)
    }
}
