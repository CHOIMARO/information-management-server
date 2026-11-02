package io.github.qkqnfld.information_management.dubbing.presentation

import io.github.qkqnfld.information_management.dubbing.application.VoiceService
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 목소리 목록 조회와 내 목소리 등록·삭제. */
@RestController
@RequestMapping("/voices")
class VoiceController(
    private val voiceService: VoiceService,
) {

    /** 더빙에 쓸 수 있는 목소리 전체 (AI 목소리 + 내 목소리). */
    @GetMapping
    fun findAll(@AuthenticationPrincipal memberId: Long): VoicesResponse {
        return VoicesResponse(
            presets = PresetVoice.entries.map { PresetVoiceResponse.from(it) },
            custom = voiceService.findMyVoices(memberId).map { CustomVoiceResponse.from(it) },
        )
    }

    /**
     * 녹음 파일로 내 목소리를 등록한다 (회원당 1개).
     * 업체가 복제를 바로 끝내 주므로 동기 방식으로 처리하고 201을 응답한다.
     * 단건 조회 API가 없어서 Location 헤더는 붙이지 않는다.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @AuthenticationPrincipal memberId: Long,
        @Valid @ModelAttribute request: RegisterVoiceRequest,
    ): CustomVoiceResponse {
        // @Valid를 통과했으므로 file과 name은 null이 아니다
        val file = request.file!!
        val voice = voiceService.register(
            memberId = memberId,
            name = request.name!!,
            sample = file.bytes,
            filename = file.originalFilename?.takeIf { it.isNotBlank() } ?: "voice-sample",
            contentType = file.contentType,
        )
        return CustomVoiceResponse.from(voice)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal memberId: Long, @PathVariable id: Long) {
        voiceService.delete(memberId, id)
    }
}
