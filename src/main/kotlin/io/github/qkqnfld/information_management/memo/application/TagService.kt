package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.memo.infrastructure.TagRepository
import io.github.qkqnfld.information_management.memo.infrastructure.TagWithMemoCount
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 태그 조회 로직. 태그 생성은 메모에 붙일 때 MemoService가 담당하므로
 * 여기는 읽기 전용 유스케이스만 있다.
 */
@Service
@Transactional(readOnly = true)
class TagService(
    private val tagRepository: TagRepository,
) {

    /** 내 메모에 붙은 태그들을 각각의 사용 횟수와 함께 이름순으로 조회한다. */
    fun findAllWithMemoCount(memberId: Long): List<TagWithMemoCount> {
        return tagRepository.findAllWithMemoCount(memberId)
    }
}
