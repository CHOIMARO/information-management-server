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
 * 어떤 SNS로 로그인하는지는 AuthCredential(1:N)이 담당한다.
 * 덕분에 한 회원이 여러 로그인 수단을 연결(계정 연동)할 수 있다.
 */
@Entity
@Table(name = "members")
class Member(
    /**
     * SNS가 준 이메일. 동의하지 않으면 없다.
     * 유니크가 아니다: 같은 이메일이라도 로그인 수단이 다르면 다른 계정이다 (PRD 9.17).
     */
    @Column(length = 100)
    val email: String?,

    nickname: String,

    /** SNS가 준 프로필 사진 주소. 동의하지 않았거나 Apple 로그인이면 없다. */
    @Column(length = 500)
    val profileImageUrl: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {

    /** 화면에 보이는 이름. 처음에는 SNS 닉네임이고, 마이 > [프로필 수정]에서 바꿀 수 있다. */
    @Column(nullable = false, length = NICKNAME_MAX_LENGTH)
    var nickname: String = normalizeNickname(nickname)
        protected set

    /** 이름을 바꾼다. 앞뒤 공백을 지운 값을 저장한다. */
    fun changeNickname(newNickname: String) {
        nickname = normalizeNickname(newNickname)
    }

    companion object {
        const val NICKNAME_MAX_LENGTH = 20

        /** SNS가 이름을 주지 않았을 때 쓰는 이름 (PRD 9.17) */
        const val DEFAULT_NICKNAME = "magic 사용자"

        /**
         * SNS가 준 이름을 저장할 수 있는 형태로 바꾼다.
         * SNS 이름은 길이 제한이 없다. 그래서 20자를 넘으면 자르고, 비어 있으면 기본 이름을 쓴다.
         */
        fun normalizeNickname(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT_NICKNAME
            // 이모지처럼 두 칸(서로게이트 쌍)을 쓰는 글자를 반으로 자르지 않는다
            val cut = trimmed.take(NICKNAME_MAX_LENGTH)
            return (if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut).trim()
        }
    }
}
