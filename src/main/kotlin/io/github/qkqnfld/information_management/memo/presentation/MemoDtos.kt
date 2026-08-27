package io.github.qkqnfld.information_management.memo.presentation

import io.github.qkqnfld.information_management.memo.domain.Memo
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 메모 생성/수정 요청 본문(HTTP 계약).
 * id와 createdAt은 서버가 정하므로 받지 않는다.
 * 검증 규칙 위반 시 컨트롤러 진입 전에 400으로 거절된다.
 */
data class MemoRequest(
    @field:NotBlank(message = "제목은 비어 있을 수 없습니다")
    @field:Size(max = 200, message = "제목은 200자 이하여야 합니다")
    val title: String,

    @field:Size(max = 4000, message = "내용은 4000자 이하여야 합니다")
    val content: String,
)

/**
 * 메모 응답(HTTP 계약). 엔티티를 API로 직접 노출하지 않기 위한 변환 계층.
 * 엔티티가 바뀌어도 API 스펙은 여기서 독립적으로 관리한다.
 */
data class MemoResponse(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(memo: Memo): MemoResponse {
            return MemoResponse(
                id = memo.id,
                title = memo.title,
                content = memo.content,
                createdAt = memo.createdAt,
            )
        }
    }
}
