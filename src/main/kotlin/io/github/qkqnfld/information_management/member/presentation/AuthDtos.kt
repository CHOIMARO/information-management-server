package io.github.qkqnfld.information_management.member.presentation

import jakarta.validation.constraints.NotBlank

/**
 * 로그인 요청(HTTP 계약).
 * 형식 검증은 최소한만 한다 — 형식이 틀렸다는 것 자체가 로그인 실패이므로
 * 어차피 자격 증명 검증에서 걸러진다.
 */
data class LoginRequest(
    @field:NotBlank(message = "이메일은 비어 있을 수 없습니다")
    val email: String,

    @field:NotBlank(message = "비밀번호는 비어 있을 수 없습니다")
    val password: String,
)

/**
 * 로그인/재발급 성공 응답.
 * 클라이언트는 둘 다 저장하고, 평소 요청에는 accessToken만 쓴다.
 */
data class TokenResponse(
    /** 모든 API 요청의 Authorization: Bearer 헤더에 넣는 토큰 (수명 1시간) */
    val accessToken: String,
    /** accessToken 만료 시 재발급에만 쓰는 토큰 (수명 30일, 서버 DB 저장) */
    val refreshToken: String,
)

/**
 * 재발급/로그아웃 요청(HTTP 계약): 대상 refresh 토큰.
 */
data class RefreshTokenRequest(
    @field:NotBlank(message = "refreshToken은 비어 있을 수 없습니다")
    val refreshToken: String,
)

/**
 * SNS 로그인 요청(HTTP 계약): 앱이 SNS SDK로 받은 토큰.
 * 카카오는 access 토큰, 구글은 idToken을 담는다.
 */
data class SnsLoginRequest(
    @field:NotBlank(message = "token은 비어 있을 수 없습니다")
    val token: String,
)
