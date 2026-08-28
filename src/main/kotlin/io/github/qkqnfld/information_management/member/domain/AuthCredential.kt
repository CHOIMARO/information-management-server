package io.github.qkqnfld.information_management.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

/**
 * 회원의 로그인 수단("문"). 한 회원이 여러 개를 가질 수 있다 (계정 연동).
 * - LOCAL: password(BCrypt 해시)가 있고 providerId는 null
 * - KAKAO/GOOGLE: providerId(SNS 쪽 사용자 고유 id)가 있고 password는 null
 * 제약: SNS 계정 하나는 전체에서 한 회원에게만 연결되고(provider, provider_id 유니크),
 * 한 회원은 provider별로 수단 하나만 가진다(member_id, provider 유니크).
 */
@Entity
@Table(
    name = "auth_credentials",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["provider", "provider_id"]),
        UniqueConstraint(columnNames = ["member_id", "provider"]),
    ],
)
class AuthCredential(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: AuthProvider,

    @Column(name = "provider_id", length = 100)
    val providerId: String? = null,

    /** BCrypt 해시 (LOCAL 전용) */
    @Column(length = 100)
    val password: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
