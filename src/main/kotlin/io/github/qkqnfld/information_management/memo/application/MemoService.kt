package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.memo.domain.Memo
import io.github.qkqnfld.information_management.memo.domain.MemoNotFoundException
import io.github.qkqnfld.information_management.memo.infrastructure.MemoRepository
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 메모 비즈니스 로직. 클래스 기본은 읽기 전용 트랜잭션이고,
 * 쓰기 메서드에만 @Transactional을 다시 선언한다.
 */
@Service
@Transactional(readOnly = true)
class MemoService(
    private val memoRepository: MemoRepository,
) {

    fun findAll(): List<Memo> {
        return memoRepository.findAll(Sort.by("id"))
    }

    fun findById(id: Long): Memo {
        return memoRepository.findByIdOrNull(id) ?: throw MemoNotFoundException(id)
    }

    @Transactional
    fun create(title: String, content: String): Memo {
        return memoRepository.save(Memo(title = title, content = content))
    }

    /**
     * 조회한 엔티티의 상태를 바꾸면 트랜잭션 커밋 시점에
     * JPA 더티 체킹(변경 감지)이 UPDATE를 실행한다. save() 호출이 필요 없다.
     */
    @Transactional
    fun update(id: Long, title: String, content: String): Memo {
        val memo = findById(id)
        memo.update(title, content)
        return memo
    }

    @Transactional
    fun delete(id: Long) {
        val memo = findById(id)
        memoRepository.delete(memo)
    }
}
