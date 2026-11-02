package io.github.qkqnfld.information_management.dubbing.presentation

import io.github.qkqnfld.information_management.common.presentation.PageResponse
import io.github.qkqnfld.information_management.dubbing.application.DubbingJobService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 더빙 작업 API (비동기).
 * 1. POST로 작업을 접수하면 바로 202를 받는다.
 * 2. 앱은 GET /dubbing-jobs/{id}로 상태를 확인한다.
 * 3. COMPLETED가 되면 응답의 audioUrl로 파일을 받는다.
 */
@RestController
@RequestMapping("/dubbing-jobs")
class DubbingJobController(
    private val dubbingJobService: DubbingJobService,
) {

    /**
     * 202 Accepted: "요청은 받았고 처리는 나중에 한다"는 뜻이다.
     * Location 헤더에 상태를 확인할 주소를 담아 준다.
     */
    @PostMapping
    fun create(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: CreateDubbingJobRequest,
    ): ResponseEntity<DubbingJobResponse> {
        val result = dubbingJobService.create(memberId, request.text, request.presetVoice, request.customVoiceId)
        return ResponseEntity
            .accepted()
            .location(URI.create("/dubbing-jobs/${result.job.id}"))
            .body(DubbingJobResponse.from(result))
    }

    @GetMapping("/{id}")
    fun findById(@AuthenticationPrincipal memberId: Long, @PathVariable id: Long): DubbingJobResponse {
        return DubbingJobResponse.from(dubbingJobService.findById(memberId, id))
    }

    /** 내 더빙 목록 (기본 20건, 최신순). 예: GET /dubbing-jobs?page=0&size=20 */
    @GetMapping
    fun findAll(
        @AuthenticationPrincipal memberId: Long,
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<DubbingJobResponse> {
        val page = dubbingJobService.findAll(memberId, pageable).map { DubbingJobResponse.from(it) }
        return PageResponse.from(page)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal memberId: Long, @PathVariable id: Long) {
        dubbingJobService.delete(memberId, id)
    }
}
