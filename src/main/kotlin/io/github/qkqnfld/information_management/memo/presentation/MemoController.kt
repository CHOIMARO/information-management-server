package io.github.qkqnfld.information_management.memo.presentation

import io.github.qkqnfld.information_management.memo.application.MemoService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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

    @GetMapping
    fun findAll(): List<MemoResponse> {
        return memoService.findAll().map { MemoResponse.from(it) }
    }

    @GetMapping("/{id}")
    fun findById(@PathVariable id: Long): MemoResponse {
        return MemoResponse.from(memoService.findById(id))
    }

    @PostMapping
    fun create(@Valid @RequestBody request: MemoRequest): ResponseEntity<MemoResponse> {
        val memo = memoService.create(request.title, request.content)
        return ResponseEntity
            .created(URI.create("/memos/${memo.id}"))
            .body(MemoResponse.from(memo))
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: MemoRequest): MemoResponse {
        return MemoResponse.from(memoService.update(id, request.title, request.content))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        memoService.delete(id)
    }
}
