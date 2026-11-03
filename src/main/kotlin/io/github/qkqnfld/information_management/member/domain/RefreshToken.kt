package io.github.qkqnfld.information_management.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime

/**
 * refresh 토큰 한 개 (서버 DB에 저장하는 로그인 상태).
 *
 * 교체(rotation) 방식:
 * - 재발급할 때마다 새 토큰을 발급하고, 이 토큰은 "교체됨"(rotatedAt)으로 표시한다.
 * - 같은 로그인에서 이어진 토큰은 familyId로 묶는다. 로그아웃과 재사용 감지는 묶음 단위로 지운다.
 * - 교체된 토큰은 유예 시간(기본 1분) 동안만 다시 받아 준다. 동시 재발급과 응답 유실에 대비한 것이다.
 *   유예 시간이 지난 뒤 다시 오면 토큰이 새었다고 보고 묶음 전체를 지운다.
 *
 * 원문은 저장하지 않고 SHA-256 해시만 저장한다. DB가 새어도 토큰을 쓸 수 없다.
 * refresh 토큰은 충분히 긴 난수(UUID)라서 비밀번호처럼 느린 해시(BCrypt)가 필요 없다. 빠른 해시로 바로 조회한다.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken(
    /** 토큰 원문의 SHA-256 (16진수 소문자 64자). hash()로 만든다. */
    @Column(nullable = false, length = 64, unique = true)
    val tokenHash: String,

    @Column(nullable = false)
    val memberId: Long,

    /** 같은 로그인에서 교체로 이어진 토큰 묶음의 id. 로그인할 때 새로 만들고, 교체할 때 이어받는다. */
    @Column(nullable = false, length = 36)
    val familyId: String,

    /** 이 시각이 지나면 재발급 불가 = 재로그인 필요. 교체할 때마다 새 토큰은 다시 30일을 받는다. */
    @Column(nullable = false)
    val expiresAt: LocalDateTime,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {

    /** 이 토큰으로 새 토큰을 받은 시각. null이면 아직 교체되지 않은 최신 토큰이다. */
    @Column
    var rotatedAt: LocalDateTime? = null
        protected set

    val isRotated: Boolean get() = rotatedAt != null

    fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresAt.isBefore(now)

    /** 교체됨으로 표시한다. 이미 교체된 토큰에는 다시 표시하지 않는다 (처음 교체 시각이 유예 시간의 기준이다). */
    fun markRotated(now: LocalDateTime = LocalDateTime.now()) {
        check(rotatedAt == null) { "이미 교체된 토큰입니다" }
        rotatedAt = now
    }

    /** 교체된 지 grace 이내인지. 교체되지 않은 토큰이면 false다. */
    fun isWithinReuseGrace(grace: Duration, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val rotated = rotatedAt ?: return false
        return !now.isAfter(rotated.plus(grace))
    }

    companion object {
        /** 토큰 원문을 저장·조회용 해시로 바꾼다. V5 마이그레이션의 SQL 계산과 같은 결과다. */
        fun hash(rawToken: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
