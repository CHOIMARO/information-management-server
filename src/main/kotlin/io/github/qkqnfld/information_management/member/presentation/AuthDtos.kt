package io.github.qkqnfld.information_management.member.presentation

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

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

/**
 * Apple 로그인 요청(HTTP 계약).
 * Apple 토큰에는 이름이 없다. 이름은 Apple이 첫 로그인 때 앱에만 주므로, 앱이 그때 nickname으로 함께 보낸다.
 */
data class AppleLoginRequest(
    /** Apple이 준 identityToken (JWT) */
    @field:NotBlank(message = "token은 비어 있을 수 없습니다")
    val token: String,

    /**
     * 앱이 만든 원본 nonce. 앱은 Apple 요청에 이 값의 SHA-256(16진수 소문자)을 넣는다.
     * 보내면 서버가 토큰의 nonce와 비교한다. 같은 토큰을 다시 쓰는 공격(재사용)을 막는다.
     */
    val nonce: String? = null,

    /**
     * 첫 로그인 때 받은 이름. 처음 가입할 때만 쓰고, 이미 가입된 계정이면 쓰지 않는다.
     * 20자를 넘으면 서버가 20자로 자른다.
     */
    @field:Size(max = 100, message = "닉네임은 100자 이하여야 합니다")
    val nickname: String? = null,

    /**
     * Apple이 준 authorizationCode. 서버가 Apple refresh 토큰으로 바꿔 저장하고, 탈퇴할 때 Apple 토큰을 취소하는 데 쓴다.
     * 5분 동안 한 번만 쓸 수 있으므로 로그인할 때마다 새로 받은 값을 보낸다.
     */
    val authorizationCode: String? = null,
)

/** Apple 계정 연동 요청(HTTP 계약). nonce·authorizationCode 규칙은 AppleLoginRequest와 같다. */
data class AppleLinkRequest(
    @field:NotBlank(message = "token은 비어 있을 수 없습니다")
    val token: String,

    val nonce: String? = null,

    val authorizationCode: String? = null,
)
