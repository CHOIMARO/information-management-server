package io.github.qkqnfld.information_management.memo.presentation

import io.github.qkqnfld.information_management.memo.application.TagService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 태그 HTTP 엔드포인트. 태그는 memo 도메인의 부속 개념이지만
 * HTTP 리소스로는 /tags라는 독립 경로를 가지므로 컨트롤러를 분리한다.
 */
@RestController
@RequestMapping("/tags")
class TagController(
    private val tagService: TagService,
) {

    @GetMapping
    fun findAll(@AuthenticationPrincipal memberId: Long): List<TagResponse> {
        return tagService.findAllWithMemoCount(memberId).map { TagResponse.from(it) }
    }
}
