package io.github.qkqnfld.information_management.memo.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 메모 도메인 모델이자 memos 테이블과 매핑되는 JPA 엔티티.
 * 실용형 레이어드에서는 도메인 모델과 영속 모델을 통합한다.
 * 상태 변경은 외부 세터 대신 의도가 드러나는 메서드(update)로만 허용한다.
 */
@Entity
@Table(name = "memos")
class Memo(
    title: String,
    content: String,

    /**
     * 작성자(회원)의 id. 도메인 경계를 넘는 참조는 객체 연관관계가 아니라
     * id(Long)로만 한다는 규칙에 따라 Member 엔티티를 직접 참조하지 않는다.
     */
    @Column(nullable = false)
    val memberId: Long,

    /** id 발급은 DB의 auto-increment에 위임한다. 0이면 저장 전 상태. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Column(nullable = false, length = 200)
    var title: String = title
        protected set

    @Column(nullable = false, length = 4000)
    var content: String = content
        protected set

    /**
     * 이 메모에 붙은 태그 연결 목록.
     * cascade = ALL: 메모를 저장/삭제하면 연결(MemoTag)도 함께 저장/삭제된다.
     * orphanRemoval = true: 컬렉션에서 빠진 연결은 DB에서도 삭제된다.
     * 생명주기를 메모가 관리하므로 MemoTag용 리포지토리가 따로 필요 없다.
     */
    @OneToMany(mappedBy = "memo", cascade = [CascadeType.ALL], orphanRemoval = true)
    protected val memoTags: MutableList<MemoTag> = mutableListOf()

    /** 이 메모에 붙은 태그들. 연결 엔티티(MemoTag)는 외부에 숨기고 태그만 노출한다. */
    val tags: List<Tag>
        get() = memoTags.map { it.tag }

    /** 제목과 내용을 수정한다. createdAt은 최초 생성 시점을 유지한다. */
    fun update(title: String, content: String) {
        this.title = title
        this.content = content
    }

    /**
     * 태그 목록을 교체한다. 빠진 연결만 제거(orphanRemoval)하고 새 연결만 추가(cascade)한다.
     * 전체 clear 후 재추가하는 방식은 쓰면 안 된다 — Hibernate가 flush 시점에 INSERT를
     * DELETE보다 먼저 실행하므로, 유지되는 태그의 연결이 "기존 행이 살아 있는 채 재삽입"되어
     * (memo_id, tag_id) 유니크 제약에 걸린다. 차집합 방식은 유지되는 연결을 건드리지 않아
     * 이 문제가 원천적으로 없고, 불필요한 DELETE/INSERT도 발생하지 않는다.
     */
    fun replaceTags(tags: List<Tag>) {
        val newTagIds = tags.map { it.id }.toSet()
        memoTags.removeIf { it.tag.id !in newTagIds }

        val currentTagIds = memoTags.map { it.tag.id }.toSet()
        tags.filter { it.id !in currentTagIds }
            .forEach { memoTags.add(MemoTag(memo = this, tag = it)) }
    }
}
