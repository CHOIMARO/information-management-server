package io.github.qkqnfld.information_management.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 회원 — "사람"만 표현한다.
 * 어떤 수단(이메일+비밀번호, 카카오, 구글)으로 로그인하는지는 AuthCredential(1:N)이 담당한다.
 * 덕분에 한 회원이 여러 로그인 수단을 연결(계정 연동)할 수 있다.
 */
@Entity
@Table(name = "members")
class Member(
    /** 대표 이메일. SNS 가입 시 동의하지 않으면 없을 수 있다 */
    @Column(length = 100, unique = true)
    val email: String?,

    @Column(nullable = false, length = 20)
    val nickname: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
