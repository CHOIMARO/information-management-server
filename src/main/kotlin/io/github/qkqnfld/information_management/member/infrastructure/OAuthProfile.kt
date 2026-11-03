package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.AuthProvider

/**
 * SNS에서 검증을 마치고 받아온 사용자 정보.
 * 제공자마다 응답 형태가 제각각이므로, 각 클라이언트가 이 공통 형태로 변환해서 돌려준다 —
 * 덕분에 application 계층(AuthService)은 어느 SNS인지와 무관하게 같은 로직으로 처리한다.
 */
data class OAuthProfile(
    val provider: AuthProvider,
    /** SNS 쪽 사용자 고유 id (카카오 회원번호, 구글 sub, Apple sub) */
    val providerId: String,
    /** 동의 항목에 따라 없을 수 있다 */
    val email: String?,
    val nickname: String?,
    /** 프로필 사진 주소. 동의하지 않았거나 Apple 로그인이면 없다 */
    val profileImageUrl: String? = null,
    /**
     * 토큰을 받은 앱의 ID (토큰의 aud). Apple만 채운다.
     * Apple 토큰 교환·취소 요청의 client_id는 토큰을 받은 앱과 같아야 하기 때문이다.
     */
    val clientId: String? = null,
)
