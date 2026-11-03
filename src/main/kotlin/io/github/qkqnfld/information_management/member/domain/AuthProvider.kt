package io.github.qkqnfld.information_management.member.domain

/**
 * 회원이 어떤 SNS로 가입/인증하는지.
 * DB에는 이름 문자열로 저장된다 (@Enumerated(STRING) — 순서 기반 저장은 enum 순서가
 * 바뀌면 데이터가 오염되므로 금물).
 * 이메일·비밀번호 로그인은 없다. 로그인 수단은 PRD 9.17(D69)에 따라 SNS뿐이다.
 */
enum class AuthProvider {
    /** 카카오 로그인 */
    KAKAO,

    /** 구글 로그인 */
    GOOGLE,

    /** Apple 로그인 (iOS 출시에 필요) */
    APPLE,
}
