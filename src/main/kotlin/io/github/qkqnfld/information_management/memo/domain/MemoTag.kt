package io.github.qkqnfld.information_management.memo.domain

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 메모↔태그 다대다(N:M) 관계의 중간 엔티티.
 * @ManyToMany로 숨기지 않고 엔티티로 명시해서, 나중에 컬럼(붙인 순서, 붙인 시각 등)을
 * 추가할 수 있게 한다. (memo_id, tag_id) 조합은 유일하다 — 같은 태그를 두 번 못 붙인다.
 *
 * fetch = LAZY: 관계 상대를 실제로 사용할 때까지 조회를 미룬다.
 * @ManyToOne의 기본값은 EAGER(즉시 로딩)라서, 명시하지 않으면 의도치 않은 조회가 연쇄된다.
 */
@Entity
@Table(
    name = "memo_tags",
    uniqueConstraints = [UniqueConstraint(columnNames = ["memo_id", "tag_id"])],
)
class MemoTag(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "memo_id")
    val memo: Memo,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id")
    val tag: Tag,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
