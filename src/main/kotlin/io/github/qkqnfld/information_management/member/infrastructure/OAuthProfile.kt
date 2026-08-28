package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.AuthProvider

/**
 * SNS에서 검증을 마치고 받아온 사용자 정보.
 * 제공자마다 응답 형태가 제각각이므로, 각 클라이언트가 이 공통 형태로 변환해서 돌려준다 —
 * 덕분에 application 계층(AuthService)은 어느 SNS인지와 무관하게 같은 로직으로 처리한다.
 */
data class OAuthProfile(
    val provider: AuthProvider,
    /** SNS 쪽 사용자 고유 id (카카오 회원번호, 구글 sub) */
    val providerId: String,
    /** 동의 항목에 따라 없을 수 있다 */
    val email: String?,
    val nickname: String?,
)
