package io.github.qkqnfld.information_management.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * refresh 토큰. access 토큰이 만료됐을 때 재발급받는 용도로만 쓰인다.
 * access와 달리 DB에 저장되므로 서버가 삭제(= 즉시 로그아웃)할 수 있다.
 * 내용을 담을 필요가 없어 JWT가 아니라 의미 없는 난수 문자열(opaque token)을 쓴다 —
 * 어차피 DB에서 조회하므로 자체 서명이 필요 없다.
 * 로그인할 때마다 한 행씩 생기므로 기기별 로그인/로그아웃이 자연히 분리된다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    @Column(nullable = false, length = 100, unique = true)
    val token: String,

    @Column(nullable = false)
    val memberId: Long,

    /** 이 시각이 지나면 재발급 불가 = 재로그인 필요 (자동 로그아웃) */
    @Column(nullable = false)
    val expiresAt: LocalDateTime,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    fun isExpired(): Boolean = expiresAt.isBefore(LocalDateTime.now())
}
