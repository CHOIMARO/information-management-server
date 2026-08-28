package io.github.qkqnfld.information_management.common.presentation

import org.springframework.data.domain.Page

/**
 * 페이징 목록 응답의 공통 형태(HTTP 계약).
 * Spring의 Page 객체는 프레임워크 내부 구현이라 API로 직접 노출하지 않고,
 * 클라이언트에게 필요한 페이지 정보만 골라서 담는다.
 */
data class PageResponse<T>(
    /** 현재 페이지의 목록 */
    val content: List<T>,
    /** 현재 페이지 번호 (0부터 시작) */
    val page: Int,
    /** 요청한 페이지 크기 */
    val size: Int,
    /** 조건에 맞는 전체 건수 */
    val totalElements: Long,
    /** 전체 페이지 수 */
    val totalPages: Int,
    /** 다음 페이지 존재 여부 (클라이언트의 무한 스크롤 구현에 사용) */
    val hasNext: Boolean,
) {
    companion object {
        // Spring Data의 Page<T>는 T가 null 불가(Any)여야 하므로 같은 제약을 선언한다
        fun <T : Any> from(page: Page<T>): PageResponse<T> {
            return PageResponse(
                content = page.content,
                page = page.number,
                size = page.size,
                totalElements = page.totalElements,
                totalPages = page.totalPages,
                hasNext = page.hasNext(),
            )
        }
    }
}
