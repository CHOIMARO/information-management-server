package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.memo.domain.Memo
import io.github.qkqnfld.information_management.memo.domain.MemoNotFoundException
import io.github.qkqnfld.information_management.memo.domain.Tag
import io.github.qkqnfld.information_management.memo.infrastructure.MemoRepository
import io.github.qkqnfld.information_management.memo.infrastructure.TagRepository
import io.github.qkqnfld.information_management.memo.infrastructure.search
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 메모 비즈니스 로직. 클래스 기본은 읽기 전용 트랜잭션이고,
 * 쓰기 메서드에만 @Transactional을 다시 선언한다.
 * 모든 유스케이스는 로그인한 회원(memberId)의 메모로 범위가 한정된다.
 */
@Service
@Transactional(readOnly = true)
class MemoService(
    private val memoRepository: MemoRepository,
    private val tagRepository: TagRepository,
) {

    /**
     * 내 메모 목록을 페이징 조회한다. keyword와 tag는 자유롭게 조합할 수 있다 (둘 다 주면 AND).
     * 조건 조합은 동적 쿼리(Kotlin JDSL, MemoQueryRepository.kt)가 실행 시점에 조립한다.
     * @param memberId 로그인한 회원 id (컨트롤러가 토큰에서 꺼내 전달)
     * @param keyword 값이 있으면 제목/내용 포함 검색
     * @param tag 값이 있으면 해당 태그가 붙은 메모만 조회
     * @param pageable 페이지 번호·크기·정렬 정보 (컨트롤러에서 요청 파라미터로 바인딩됨)
     */
    fun findAll(memberId: Long, keyword: String?, tag: String?, pageable: Pageable): Page<Memo> {
        return memoRepository.search(
            memberId = memberId,
            keyword = keyword?.takeIf { it.isNotBlank() },
            tag = tag?.takeIf { it.isNotBlank() },
            pageable = pageable,
        )
    }

    /**
     * 내 메모 단건 조회. 남의 메모는 존재하더라도 404로 응답한다 —
     * 403을 주면 "그 id의 메모가 존재한다"는 사실이 노출되기 때문 (리소스 존재 은닉).
     */
    fun findById(memberId: Long, id: Long): Memo {
        return memoRepository.findByIdAndMemberId(id, memberId) ?: throw MemoNotFoundException(id)
    }

    @Transactional
    fun create(memberId: Long, title: String, content: String, tagNames: List<String> = emptyList()): Memo {
        val memo = Memo(title = title, content = content, memberId = memberId)
        memo.replaceTags(findOrCreateTags(tagNames))
        return memoRepository.save(memo)
    }

    /**
     * 조회한 엔티티의 상태를 바꾸면 트랜잭션 커밋 시점에
     * JPA 더티 체킹(변경 감지)이 UPDATE를 실행한다. save() 호출이 필요 없다.
     * 태그 연결(MemoTag)의 추가/삭제도 cascade + orphanRemoval이 커밋 시점에 반영한다.
     */
    @Transactional
    fun update(memberId: Long, id: Long, title: String, content: String, tagNames: List<String> = emptyList()): Memo {
        val memo = findById(memberId, id)
        memo.update(title, content)
        memo.replaceTags(findOrCreateTags(tagNames))
        return memo
    }

    @Transactional
    fun delete(memberId: Long, id: Long) {
        val memo = findById(memberId, id)
        memoRepository.delete(memo)
    }

    /**
     * 이름 목록으로 태그를 조회하고, 없는 이름은 새로 만들어 함께 반환한다 (find-or-create).
     * 공백을 다듬고 빈 이름과 중복을 제거한 뒤, 요청에 적힌 순서를 유지한다.
     */
    private fun findOrCreateTags(names: List<String>): List<Tag> {
        val normalized = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return emptyList()

        val existingByName = tagRepository.findByNameIn(normalized).associateBy { it.name }
        return normalized.map { name ->
            existingByName[name] ?: tagRepository.save(Tag(name = name))
        }
    }
}
