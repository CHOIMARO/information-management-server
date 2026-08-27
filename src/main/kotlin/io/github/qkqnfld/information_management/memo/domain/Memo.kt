package io.github.qkqnfld.information_management.memo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
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

    /** 제목과 내용을 수정한다. createdAt은 최초 생성 시점을 유지한다. */
    fun update(title: String, content: String) {
        this.title = title
        this.content = content
    }
}
