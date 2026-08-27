package io.github.qkqnfld.information_management.memo.infrastructure

import io.github.qkqnfld.information_management.memo.domain.Memo
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA가 구현체를 자동 생성하는 리포지토리.
 * 복잡한 조회가 생기면 MemoQueryRepository를 별도로 분리한다.
 */
interface MemoRepository : JpaRepository<Memo, Long>
