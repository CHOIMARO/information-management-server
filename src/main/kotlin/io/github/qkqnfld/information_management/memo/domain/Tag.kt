package io.github.qkqnfld.information_management.memo.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 태그. 메모를 분류하는 이름표로, 여러 메모가 같은 태그를 공유한다.
 * 이름은 유일하며(unique), 같은 이름의 태그는 재사용한다 (find-or-create).
 */
@Entity
@Table(name = "tags")
class Tag(
    @Column(nullable = false, length = 20, unique = true)
    val name: String,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
)
