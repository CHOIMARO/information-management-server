package io.github.qkqnfld.information_management.memo.presentation

import io.github.qkqnfld.information_management.memo.infrastructure.TagWithMemoCount

/**
 * 태그 응답(HTTP 계약). 태그 이름과 그 태그가 붙은 메모 수를 담는다.
 */
data class TagResponse(
    val name: String,
    val memoCount: Long,
) {
    companion object {
        fun from(projection: TagWithMemoCount): TagResponse {
            return TagResponse(name = projection.name, memoCount = projection.memoCount)
        }
    }
}
