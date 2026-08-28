package io.github.qkqnfld.information_management.memo.infrastructure

import io.github.qkqnfld.information_management.memo.domain.Tag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 태그 리포지토리.
 */
interface TagRepository : JpaRepository<Tag, Long> {

    /** 이름 목록에 해당하는 태그를 한 번의 IN 쿼리로 조회한다 (find-or-create에 사용). */
    fun findByNameIn(names: Collection<String>): List<Tag>

    /**
     * 내 메모에 붙은 태그들을 각각의 사용 횟수와 함께 조회한다.
     * 태그 엔티티 자체는 전 회원이 공유하지만, 목록과 카운트는 내 메모 기준으로만 계산한다 —
     * 남이 어떤 태그를 쓰는지가 노출되면 안 되기 때문이다.
     * 쿼리 메서드 이름으로 표현하기 어려운 집계(group by)라서 @Query로 JPQL을 직접 작성한다.
     * 반환 타입은 인터페이스 프로젝션: select의 별칭(name, memoCount)과 인터페이스 프로퍼티를 매핑해 준다.
     */
    @Query(
        """
        select t.name as name, count(mt.id) as memoCount
        from MemoTag mt
            join mt.tag t
            join mt.memo m
        where m.memberId = :memberId
        group by t.id, t.name
        order by t.name asc
        """,
    )
    fun findAllWithMemoCount(@Param("memberId") memberId: Long): List<TagWithMemoCount>
}

/** 태그 이름 + 그 태그가 붙은 메모 수 프로젝션. */
interface TagWithMemoCount {
    val name: String
    val memoCount: Long
}
