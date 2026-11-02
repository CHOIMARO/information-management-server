package io.github.qkqnfld.information_management.dubbing.presentation

import io.github.qkqnfld.information_management.dubbing.application.DubbingJobResult
import io.github.qkqnfld.information_management.dubbing.domain.DubbingJobStatus
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 더빙 요청 본문.
 * 목소리는 presetVoice(AI 목소리)와 customVoiceId(내 목소리) 중 정확히 하나만 지정한다.
 */
data class CreateDubbingJobRequest(
    @field:NotBlank(message = "텍스트는 비어 있을 수 없습니다")
    @field:Size(max = 2000, message = "텍스트는 2000자 이하여야 합니다")
    val text: String,

    /** GET /voices의 presets[].id 값 */
    val presetVoice: PresetVoice? = null,

    /** GET /voices의 custom[].id 값 */
    val customVoiceId: Long? = null,
) {
    @get:AssertTrue(message = "presetVoice와 customVoiceId 중 정확히 하나를 지정해야 합니다")
    val isVoiceSelectedOnce: Boolean
        get() = (presetVoice == null) != (customVoiceId == null)
}

/** 더빙 작업 응답. 실패 사유 같은 내부 정보는 넣지 않는다. */
data class DubbingJobResponse(
    val id: Long,
    val status: DubbingJobStatus,
    val text: String,
    val presetVoice: PresetVoice?,
    /** 내 목소리 id. 그 목소리가 삭제되었으면 null이다. */
    val customVoiceId: Long?,
    /**
     * 결과 파일 다운로드 URL (COMPLETED일 때만 값이 있다).
     * 몇 분 뒤 만료된다. 만료되면 이 작업을 다시 조회해 새 URL을 받는다.
     */
    val audioUrl: String?,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?,
) {
    companion object {
        fun from(result: DubbingJobResult): DubbingJobResponse {
            val job = result.job
            return DubbingJobResponse(
                id = job.id,
                status = job.status,
                text = job.text,
                presetVoice = job.presetVoice,
                customVoiceId = job.customVoiceId,
                audioUrl = result.audioUrl,
                createdAt = job.createdAt,
                completedAt = job.completedAt,
            )
        }
    }
}
