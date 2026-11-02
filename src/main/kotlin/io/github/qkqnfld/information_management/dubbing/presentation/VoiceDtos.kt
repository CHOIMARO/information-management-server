package io.github.qkqnfld.information_management.dubbing.presentation

import io.github.qkqnfld.information_management.dubbing.domain.CustomVoice
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDateTime

/**
 * 내 목소리 등록 요청 (multipart/form-data).
 * 파일과 일반 값을 한 요청으로 받으므로 JSON 본문(@RequestBody)이 아니라 폼 바인딩(@ModelAttribute)으로 받는다.
 * 모든 값을 nullable로 둔다. 값이 빠졌을 때 바인딩 오류가 아니라 검증 오류(fieldErrors)로 알려 주기 위해서다.
 */
data class RegisterVoiceRequest(
    /** 녹음 파일 (WAV 또는 MP3, 5~150초, 25MB 이하) */
    @field:NotNull(message = "녹음 파일이 필요합니다")
    val file: MultipartFile?,

    @field:NotBlank(message = "목소리 이름은 비어 있을 수 없습니다")
    @field:Size(max = 30, message = "목소리 이름은 30자 이하여야 합니다")
    val name: String?,

    /** 목소리 주인 본인이 복제에 동의했는지. true가 아니면 등록하지 않는다. */
    @field:NotNull(message = "목소리 복제 동의가 필요합니다")
    @field:AssertTrue(message = "목소리 복제 동의가 필요합니다")
    val consentAgreed: Boolean?,
) {
    @get:AssertTrue(message = "녹음 파일이 비어 있습니다")
    val isFileNotEmpty: Boolean
        get() = file == null || !file.isEmpty
}

/** 고를 수 있는 목소리 전체: AI 목소리와 내 목소리를 나눠서 준다. */
data class VoicesResponse(
    val presets: List<PresetVoiceResponse>,
    val custom: List<CustomVoiceResponse>,
)

/** AI 목소리. id는 더빙 요청의 presetVoice 값으로 그대로 쓴다. */
data class PresetVoiceResponse(
    val id: String,
    val name: String,
) {
    companion object {
        fun from(voice: PresetVoice): PresetVoiceResponse {
            return PresetVoiceResponse(id = voice.name, name = voice.displayName)
        }
    }
}

/**
 * 내 목소리. id는 더빙 요청의 customVoiceId 값으로 그대로 쓴다.
 * 업체 목소리 id(providerVoiceId)는 의도적으로 넣지 않는다 (CustomVoice 설명 참고).
 */
data class CustomVoiceResponse(
    val id: Long,
    val name: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(voice: CustomVoice): CustomVoiceResponse {
            return CustomVoiceResponse(id = voice.id, name = voice.name, createdAt = voice.createdAt)
        }
    }
}
