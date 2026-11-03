package io.github.qkqnfld.information_management.dubbing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 더빙 작업. 앱 요청으로 PENDING 상태로 만들어지고, Worker가 처리해 상태를 바꾼다.
 * 상태 변경은 세터가 아니라 start / complete / retryLater / fail 메서드로만 한다.
 * 각 메서드는 허용되지 않는 상태에서 호출되면 예외를 던져 잘못된 전이를 막는다.
 */
@Entity
@Table(name = "dubbing_jobs")
class DubbingJob(
    @Column(nullable = false)
    val memberId: Long,

    /** 읽을 텍스트. 요청은 MAX_TEXT_LENGTH까지 받는다 (컬럼은 Typecast 한도인 2,000자). */
    @Column(nullable = false, length = 2000)
    val text: String,

    /** AI 목소리로 만들 때 사용한다. customVoiceId와 둘 중 하나에만 값이 있다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    val presetVoice: PresetVoice? = null,

    /**
     * 내 목소리로 만들 때 사용한다 (CustomVoice.id).
     * 작업은 목소리보다 오래 남을 수 있어 객체 연관관계 대신 id로 둔다.
     * 목소리가 삭제되면 DB가 이 값을 null로 바꾼다 (on delete set null).
     */
    @Column
    val customVoiceId: Long? = null,

    /** 읽기 속도 배율 (DubbingSpeed 참고). 1.0이 보통 속도다. */
    @Column(nullable = false)
    val speed: Double = DubbingSpeed.DEFAULT,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    init {
        require((presetVoice == null) != (customVoiceId == null)) {
            "presetVoice와 customVoiceId 중 정확히 하나만 지정해야 합니다"
        }
        require(speed in DubbingSpeed.MIN..DubbingSpeed.MAX) { "속도는 ${DubbingSpeed.MIN}~${DubbingSpeed.MAX} 사이여야 합니다" }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DubbingJobStatus = DubbingJobStatus.PENDING
        protected set

    /** 처리를 시작한 횟수. 재시도 한도를 판단할 때 쓴다. */
    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    /** 결과 mp3의 S3 객체 키. COMPLETED일 때만 값이 있다. */
    @Column(length = 200)
    var audioKey: String? = null
        protected set

    /** 마지막 실패 사유. 운영 확인용이며 앱에는 노출하지 않는다. */
    @Column(length = 500)
    var failureReason: String? = null
        protected set

    @Column
    var startedAt: LocalDateTime? = null
        protected set

    @Column
    var completedAt: LocalDateTime? = null
        protected set

    /**
     * Worker가 처리를 시작한다.
     * 서버가 처리 도중 멈춰서 PROCESSING에 남은 작업을 다시 가져온 경우도 여기로 온다.
     */
    fun start(now: LocalDateTime = LocalDateTime.now()) {
        check(status == DubbingJobStatus.PENDING || status == DubbingJobStatus.PROCESSING) {
            "시작할 수 없는 상태입니다: $status"
        }
        status = DubbingJobStatus.PROCESSING
        attempts += 1
        startedAt = now
    }

    /** 결과 파일 업로드까지 끝났다. */
    fun complete(audioKey: String, now: LocalDateTime = LocalDateTime.now()) {
        check(status == DubbingJobStatus.PROCESSING) { "완료할 수 없는 상태입니다: $status" }
        status = DubbingJobStatus.COMPLETED
        this.audioKey = audioKey
        failureReason = null
        completedAt = now
    }

    /** 일시적인 오류가 났다. 대기 상태로 되돌려 다음 차례에 다시 시도한다. */
    fun retryLater(reason: String) {
        check(status == DubbingJobStatus.PROCESSING) { "재시도할 수 없는 상태입니다: $status" }
        status = DubbingJobStatus.PENDING
        failureReason = reason.take(MAX_REASON_LENGTH)
    }

    /** 다시 시도해도 소용없는 실패다. */
    fun fail(reason: String, now: LocalDateTime = LocalDateTime.now()) {
        check(status == DubbingJobStatus.PENDING || status == DubbingJobStatus.PROCESSING) {
            "실패 처리할 수 없는 상태입니다: $status"
        }
        status = DubbingJobStatus.FAILED
        failureReason = reason.take(MAX_REASON_LENGTH)
        completedAt = now
    }

    fun hasAttemptsLeft(maxAttempts: Int): Boolean = attempts < maxAttempts

    companion object {
        /** 한 번에 더빙할 수 있는 최대 글자 수 (PRD 9.5). Typecast는 글자 수로 과금하므로 크레딧도 보호한다. */
        const val MAX_TEXT_LENGTH = 200

        private const val MAX_REASON_LENGTH = 500
    }
}
