package io.github.qkqnfld.information_management.memo.infrastructure

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import io.github.qkqnfld.information_management.memo.domain.Memo
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Spring Data JPA가 구현체를 자동 생성하는 리포지토리.
 * 모든 조회는 소유자(memberId) 범위 안에서만 이뤄진다.
 * KotlinJdslJpqlExecutor: Kotlin JDSL 동적 쿼리 실행 기능을 섞어 넣는다 —
 * 조건 조합 검색은 MemoQueryRepository.kt의 search() 확장 함수 참고.
 */
interface MemoRepository : JpaRepository<Memo, Long>, KotlinJdslJpqlExecutor {

    /** 소유권 확인을 겸하는 단건 조회: 남의 메모면 (있어도) null을 돌려준다. */
    fun findByIdAndMemberId(id: Long, memberId: Long): Memo?
}
