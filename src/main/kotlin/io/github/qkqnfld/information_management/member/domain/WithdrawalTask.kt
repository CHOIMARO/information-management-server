package io.github.qkqnfld.information_management.member.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.LocalDateTime

/**
 * 탈퇴 후처리 작업 하나. 회원 탈퇴 요청이 PENDING 상태로 만들고, WithdrawalTaskWorker가 처리한다.
 *
 * 탈퇴 요청은 계정을 지운 뒤 바로 성공을 돌려준다 (PRD 9.17).
 * 외부 호출(카카오·Apple·S3·Typecast)은 실패할 수 있어서, 이 작업으로 남겨 두고 Worker가 재시도한다.
 * 처리가 끝난 행은 "삭제 기록"으로 남긴다. 단, 개인정보인 target은 지운다.
 *
 * 상태 변경은 start / complete / retryLater / fail 메서드로만 한다.
 */
@Entity
@Table(name = "withdrawal_tasks")
class WithdrawalTask(
    /** 탈퇴한 회원 id. 회원 행은 이미 지워졌다. */
    @Column(nullable = false)
    val memberId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val type: WithdrawalTaskType,

    target: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {

    /** 처리 대상. 뜻은 type마다 다르다 (WithdrawalTaskType 참고). 끝난 작업이면 null이다. */
    @Column(length = 1000)
    var target: String? = target
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: WithdrawalTaskStatus = WithdrawalTaskStatus.PENDING
        protected set

    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    /** 이 시각이 지나야 Worker가 이 작업을 가져간다. 실패할수록 간격이 길어진다. */
    @Column(nullable = false)
    var nextAttemptAt: LocalDateTime = createdAt
        protected set

    /** 마지막 실패 사유. 운영 확인용이다. */
    @Column(length = 500)
    var lastError: String? = null
        protected set

    @Column
    var startedAt: LocalDateTime? = null
        protected set

    @Column
    var completedAt: LocalDateTime? = null
        protected set

    /** Worker가 처리를 시작한다. 처리 도중 서버가 멈춰 PROCESSING에 남은 작업도 여기로 온다. */
    fun start(now: LocalDateTime = LocalDateTime.now()) {
        check(status == WithdrawalTaskStatus.PENDING || status == WithdrawalTaskStatus.PROCESSING) {
            "시작할 수 없는 상태입니다: $status"
        }
        status = WithdrawalTaskStatus.PROCESSING
        attempts += 1
        startedAt = now
    }

    /** 처리를 마쳤다. 더는 필요 없는 개인정보(target)를 지운다. */
    fun complete(now: LocalDateTime = LocalDateTime.now()) {
        check(status == WithdrawalTaskStatus.PROCESSING) { "완료할 수 없는 상태입니다: $status" }
        status = WithdrawalTaskStatus.COMPLETED
        target = null
        lastError = null
        completedAt = now
    }

    /**
     * 실패했다. 대기 상태로 되돌리고 다음 시도 시각을 뒤로 미룬다.
     * 간격은 1, 2, 4, 8 ... 분으로 늘리고, 최대 6시간으로 제한한다 (지수 백오프).
     */
    fun retryLater(reason: String, now: LocalDateTime = LocalDateTime.now()) {
        check(status == WithdrawalTaskStatus.PROCESSING) { "재시도할 수 없는 상태입니다: $status" }
        status = WithdrawalTaskStatus.PENDING
        lastError = reason.take(MAX_ERROR_LENGTH)
        nextAttemptAt = now.plus(backoff(attempts))
    }

    /**
     * 재시도 한도를 넘었다. 운영자가 확인할 수 있게 target을 남긴다.
     */
    fun fail(reason: String, now: LocalDateTime = LocalDateTime.now()) {
        check(status == WithdrawalTaskStatus.PENDING || status == WithdrawalTaskStatus.PROCESSING) {
            "실패 처리할 수 없는 상태입니다: $status"
        }
        status = WithdrawalTaskStatus.FAILED
        lastError = reason.take(MAX_ERROR_LENGTH)
        completedAt = now
    }

    fun hasAttemptsLeft(maxAttempts: Int): Boolean = attempts < maxAttempts

    companion object {
        private const val MAX_ERROR_LENGTH = 500
        private val MAX_BACKOFF: Duration = Duration.ofHours(6)

        private fun backoff(attempts: Int): Duration {
            val minutes = 1L shl (attempts - 1).coerceIn(0, 20)
            return Duration.ofMinutes(minutes).coerceAtMost(MAX_BACKOFF)
        }
    }
}

/** 탈퇴 후처리 작업의 종류. target의 뜻이 종류마다 다르다. */
enum class WithdrawalTaskType {
    /** 카카오 연결 끊기. target = 카카오 회원번호 */
    KAKAO_UNLINK,

    /** Apple 토큰 취소. target = 암호화한 철회용 토큰 (AuthCredential.revokeToken) */
    APPLE_REVOKE,

    /** 다른 도메인의 회원 데이터 정리. target = 정리할 도메인 이름 (WithdrawnMemberDataCleaner.name) */
    DATA_CLEANUP,
}

enum class WithdrawalTaskStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}
