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
 * 모든 수단은 SNS 쪽 사용자 고유 id(providerId)를 가진다.
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

    /** SNS 쪽 사용자 고유 id (카카오 회원번호, 구글 sub, Apple sub) */
    @Column(name = "provider_id", nullable = false, length = 100)
    val providerId: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {

    /**
     * 탈퇴할 때 SNS 연결을 철회하는 데 쓰는 토큰 (암호화한 값).
     * 지금은 Apple만 저장한다. Apple 토큰 취소에는 로그인 때 받은 refresh 토큰이 필요하기 때문이다.
     */
    @Column(length = 1000)
    var revokeToken: String? = null
        protected set

    /** 철회용 토큰을 새 값으로 바꾼다. Apple은 로그인할 때마다 새 토큰을 받으므로 최신 값으로 덮어쓴다. */
    fun replaceRevokeToken(encryptedToken: String) {
        revokeToken = encryptedToken
    }
}
