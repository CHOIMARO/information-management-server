package io.github.qkqnfld.information_management.common.security

/**
 * 토큰의 회원이 아직 있는지(탈퇴하지 않았는지) 확인하는 계약. 회원 도메인이 구현한다.
 *
 * 왜 필요한가: access 토큰은 서버에 저장하지 않는 무상태 토큰이라, 탈퇴한 뒤에도 남은 수명(최대 1시간) 동안 유효하다.
 * 이 시간에 더빙을 접수하면 비용이 들고, 지운 회원의 데이터가 다시 생긴다.
 * 그래서 JwtAuthenticationFilter가 토큰을 받아들이기 전에 회원이 있는지 확인한다 (PRD 9.17 "계정 잠금").
 *
 * common이 회원 도메인을 직접 알지 않도록 인터페이스만 여기에 두고, 구현은 회원 도메인에 둔다.
 */
fun interface ActiveMemberChecker {
    fun isActive(memberId: Long): Boolean
}
