package io.github.qkqnfld.information_management.memo.presentation

import io.github.qkqnfld.information_management.common.presentation.PageResponse
import io.github.qkqnfld.information_management.memo.application.MemoService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 메모 HTTP 엔드포인트. 요청 해석과 응답 변환만 담당하고 로직은 Service에 위임한다.
 * 실패는 예외로 올라가 GlobalExceptionHandler가 변환한다.
 */
@RestController
@RequestMapping("/memos")
class MemoController(
    private val memoService: MemoService,
) {

    /**
     * 메모 목록을 페이징 조회한다. keyword와 tag는 자유롭게 조합 가능하다 (AND).
     * 예: GET /memos?page=0&size=20&keyword=회의&tag=프로젝트&sort=id,asc
     * Pageable은 page/size/sort 요청 파라미터를 Spring이 자동으로 바인딩해 준다.
     * 파라미터가 없으면 @PageableDefault의 기본값(20건, 최신순)이 적용된다.
     */
    @GetMapping
    fun findAll(
        @AuthenticationPrincipal memberId: Long,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) tag: String?,
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<MemoResponse> {
        val page = memoService.findAll(memberId, keyword, tag, pageable).map { MemoResponse.from(it) }
        return PageResponse.from(page)
    }

    @GetMapping("/{id}")
    fun findById(@AuthenticationPrincipal memberId: Long, @PathVariable id: Long): MemoResponse {
        return MemoResponse.from(memoService.findById(memberId, id))
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: MemoRequest,
    ): ResponseEntity<MemoResponse> {
        val memo = memoService.create(memberId, request.title, request.content, request.tags)
        return ResponseEntity
            .created(URI.create("/memos/${memo.id}"))
            .body(MemoResponse.from(memo))
    }

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: MemoRequest,
    ): MemoResponse {
        return MemoResponse.from(memoService.update(memberId, id, request.title, request.content, request.tags))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal memberId: Long, @PathVariable id: Long) {
        memoService.delete(memberId, id)
    }
}
