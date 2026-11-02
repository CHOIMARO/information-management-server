package io.github.qkqnfld.information_management.dubbing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 내 목소리: 회원이 녹음 파일로 복제한 목소리. 회원당 1개만 등록할 수 있다.
 * 녹음 원본은 저장하지 않는다 (개인정보 최소 수집). 원본이 필요한 곳은 TTS 업체뿐이다.
 */
@Entity
@Table(name = "custom_voices")
class CustomVoice(
    /** 목소리 주인(회원)의 id. 도메인 경계를 넘는 참조라 객체 대신 id로 둔다. */
    @Column(nullable = false)
    val memberId: Long,

    /** 앱에 보여 줄 이름 (사용자가 정한다) */
    @Column(nullable = false, length = 30)
    val name: String,

    /**
     * TTS 업체가 발급한 목소리 id (Typecast는 uc_로 시작).
     * 앱에는 절대 노출하지 않는다. 업체는 모든 복제 목소리를 우리 계정 소유로 본다.
     * 그래서 앱이 이 값을 직접 보낼 수 있으면 남의 목소리로 합성할 수 있다.
     */
    @Column(nullable = false, length = 100)
    val providerVoiceId: String,

    /** 목소리 주인이 복제에 동의한 시각 (동의 기록) */
    @Column(nullable = false)
    val consentedAt: LocalDateTime,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
