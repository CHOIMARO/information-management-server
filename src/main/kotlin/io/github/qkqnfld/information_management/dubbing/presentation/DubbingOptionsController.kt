package io.github.qkqnfld.information_management.dubbing.presentation

import io.github.qkqnfld.information_management.dubbing.domain.DubbingJob
import io.github.qkqnfld.information_management.dubbing.domain.DubbingSpeed
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 더빙 시트가 쓰는 선택지(목소리, 속도, 글자 수 한도). 로그인 없이 부를 수 있다.
 *
 * 왜 공개 API를 따로 두나: 더빙 시트는 로그인하지 않아도 열리고, 목소리 목록과 속도를 보여 준다 (PRD 9.5, D68).
 * GET /voices는 내 목소리(개인 데이터)도 주기 때문에 로그인이 필요하다.
 * 공개 정보와 개인 정보를 다른 API로 나누면 "토큰이 있으면 더 준다" 같은 조건이 없어 규칙이 단순하다.
 *
 * 모든 사용자에게 같은 값이라서, 앱이 1시간 동안 응답을 다시 써도 되도록 캐시 헤더를 붙인다.
 * 목록은 서버 코드(PresetVoice, DubbingSpeed)에서 만든다. 앱 업데이트 없이 서버 배포로 바꿀 수 있다.
 */
@RestController
class DubbingOptionsController {

    @GetMapping("/dubbing-options")
    fun options(): ResponseEntity<DubbingOptionsResponse> {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(CACHE_DURATION).cachePublic())
            .body(OPTIONS)
    }

    companion object {
        private val CACHE_DURATION: Duration = Duration.ofHours(1)

        /** 값이 서버 실행 중에 바뀌지 않으므로 한 번만 만든다. */
        private val OPTIONS = DubbingOptionsResponse(
            voices = PresetVoice.entries.map { PresetVoiceResponse.from(it) },
            speed = SpeedOptionsResponse(
                min = DubbingSpeed.MIN,
                max = DubbingSpeed.MAX,
                default = DubbingSpeed.DEFAULT,
                presets = DubbingSpeed.PRESETS.map { SpeedPresetResponse(name = it.name, value = it.value) },
            ),
            maxTextLength = DubbingJob.MAX_TEXT_LENGTH,
        )
    }
}

/** 더빙 시트의 선택지 전체 */
data class DubbingOptionsResponse(
    /** AI 목소리 목록. 순서대로 보여 준다. 내 목소리는 GET /voices(로그인 필요)로 받는다 */
    val voices: List<PresetVoiceResponse>,
    val speed: SpeedOptionsResponse,
    /** 한 번에 더빙할 수 있는 최대 글자 수. 넘으면 입력을 막는다 */
    val maxTextLength: Int,
)

/** 속도 선택지. 더빙 요청의 speed에는 min~max 사이의 배율을 보낸다 */
data class SpeedOptionsResponse(
    val min: Double,
    val max: Double,
    /** 기본 배율 (보통) */
    val default: Double,
    /** 더빙 시트의 속도 버튼. 순서대로 보여 준다 */
    val presets: List<SpeedPresetResponse>,
)

data class SpeedPresetResponse(
    /** 버튼에 보여 줄 이름 (예: 느리게) */
    val name: String,
    /** 더빙 요청의 speed에 그대로 보낼 배율 */
    val value: Double,
)
