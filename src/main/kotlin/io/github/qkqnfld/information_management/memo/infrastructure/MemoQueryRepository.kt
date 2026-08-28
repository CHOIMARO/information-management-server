package io.github.qkqnfld.information_management.memo.infrastructure

import io.github.qkqnfld.information_management.memo.domain.Memo
import io.github.qkqnfld.information_management.memo.domain.MemoTag
import io.github.qkqnfld.information_management.memo.domain.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

/**
 * 메모 동적 검색 쿼리 (~QueryRepository 분리 컨벤션의 Kotlin식 구현 — 클래스 대신 확장 함수 파일).
 *
 * 정적 쿼리(쿼리 메서드/@Query)는 조건 조합마다 메서드가 하나씩 필요해서
 * keyword × tag 조합부터 이미 감당이 안 됐다. 동적 쿼리는 들어온 조건만 골라
 * WHERE 절을 실행 시점에 조립하므로 메서드 하나로 모든 조합을 처리한다.
 *
 * Kotlin JDSL 규약: whereAnd()에 null인 조건을 넘기면 그 조건은 조용히 빠진다 —
 * "조건이 없으면 거른다"가 코드 구조 자체로 표현된다.
 */
fun MemoRepository.search(
    memberId: Long,
    keyword: String?,
    tag: String?,
    pageable: Pageable,
): Page<Memo> {
    val page = findPage(pageable) {
        select(
            entity(Memo::class),
        ).from(
            entity(Memo::class),
        ).whereAnd(
            // 항상 적용: 소유자 범위
            path(Memo::memberId).eq(memberId),
            // keyword가 있을 때만: 제목 또는 내용 포함
            keyword?.let {
                or(
                    path(Memo::title).like("%$it%"),
                    path(Memo::content).like("%$it%"),
                )
            },
            // tag가 있을 때만: 해당 이름의 태그가 붙어 있는 메모 (상관 서브쿼리)
            tag?.let {
                exists(
                    select(
                        path(MemoTag::id),
                    ).from(
                        entity(MemoTag::class),
                    ).whereAnd(
                        path(MemoTag::memo)(Memo::id).eq(path(Memo::id)),
                        path(MemoTag::tag)(Tag::name).eq(it),
                    ).asSubquery(),
                )
            },
        )
    }
    // findPage는 프로젝션 특성상 Page<Memo?>를 돌려주지만, 엔티티 조회 결과에 null은 없다
    return page.map { requireNotNull(it) }
}
