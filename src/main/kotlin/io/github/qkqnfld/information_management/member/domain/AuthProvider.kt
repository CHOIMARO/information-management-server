package io.github.qkqnfld.information_management.member.domain

/**
 * 회원이 어떤 경로로 가입/인증하는지.
 * DB에는 이름 문자열로 저장된다 (@Enumerated(STRING) — 순서 기반 저장은 enum 순서가
 * 바뀌면 데이터가 오염되므로 금물).
 */
enum class AuthProvider {
    /** 이메일 + 비밀번호 자체 가입 */
    LOCAL,

    /** 카카오 로그인 */
    KAKAO,

    /** 구글 로그인 */
    GOOGLE,
}
