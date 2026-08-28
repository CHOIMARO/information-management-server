package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.memo.domain.Memo
import io.github.qkqnfld.information_management.memo.domain.MemoNotFoundException
import io.github.qkqnfld.information_management.memo.domain.Tag
import io.github.qkqnfld.information_management.memo.infrastructure.MemoRepository
import io.github.qkqnfld.information_management.memo.infrastructure.TagRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * MemoService 단위 테스트.
 * 리포지토리를 mockito로 모킹해서 Spring/DB 없이 서비스 로직만 검증한다.
 */
class MemoServiceTest {

    private val memoRepository: MemoRepository = mock()
    private val tagRepository: TagRepository = mock()
    private val memoService = MemoService(memoRepository, tagRepository)

    @Test
    fun `생성하면 저장소에 저장을 요청하고 저장된 메모를 반환한다`() {
        whenever(memoRepository.save(any<Memo>())).thenAnswer { it.getArgument<Memo>(0) }

        val memo = memoService.create(memberId = 1L, title = "제목", content = "내용")

        assertEquals("제목", memo.title)
        assertEquals("내용", memo.content)
        assertEquals(1L, memo.memberId)
        verify(memoRepository).save(any<Memo>())
    }

    @Test
    fun `태그를 붙여 생성하면 기존 태그는 재사용하고 없는 태그만 새로 만든다`() {
        val existing = Tag(name = "회의", id = 1L)
        whenever(tagRepository.findByNameIn(listOf("회의", "아이디어"))).thenReturn(listOf(existing))
        whenever(tagRepository.save(any<Tag>())).thenAnswer { it.getArgument<Tag>(0) }
        whenever(memoRepository.save(any<Memo>())).thenAnswer { it.getArgument<Memo>(0) }

        val memo = memoService.create(memberId = 1L, title = "제목", content = "내용", tagNames = listOf("회의", "아이디어"))

        assertEquals(listOf("회의", "아이디어"), memo.tags.map { it.name })
        // "아이디어"만 새로 저장됐는지 확인 ("회의"는 기존 것을 재사용)
        verify(tagRepository, times(1)).save(any<Tag>())
    }

    @Test
    fun `수정하면 제목과 내용은 바뀌지만 createdAt은 유지된다`() {
        val existing = Memo(title = "원래 제목", content = "원래 내용", memberId = 1L, id = 1L)
        whenever(memoRepository.findByIdAndMemberId(1L, 1L)).thenReturn(existing)

        val updated = memoService.update(memberId = 1L, id = 1L, title = "새 제목", content = "새 내용")

        assertEquals("새 제목", updated.title)
        assertEquals("새 내용", updated.content)
        assertEquals(existing.createdAt, updated.createdAt)
    }

    @Test
    fun `삭제하면 저장소에 삭제를 요청한다`() {
        val existing = Memo(title = "삭제 대상", content = "내용", memberId = 1L, id = 1L)
        whenever(memoRepository.findByIdAndMemberId(1L, 1L)).thenReturn(existing)

        memoService.delete(memberId = 1L, id = 1L)

        verify(memoRepository).delete(existing)
    }

    @Test
    fun `없는 메모를 조회하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findByIdAndMemberId(999L, 1L)).thenReturn(null)

        assertFailsWith<MemoNotFoundException> {
            memoService.findById(memberId = 1L, id = 999L)
        }
    }

    @Test
    fun `남의 메모를 조회해도 같은 MemoNotFoundException이 발생한다 (존재 은닉)`() {
        // 소유권 검사는 findByIdAndMemberId 쿼리 자체에 들어 있어서,
        // 남의 메모는 "존재하지만 내 것이 아님"이 아니라 "없음"과 동일하게 취급된다.
        whenever(memoRepository.findByIdAndMemberId(10L, 2L)).thenReturn(null)

        assertFailsWith<MemoNotFoundException> {
            memoService.findById(memberId = 2L, id = 10L)
        }
    }

    @Test
    fun `없는 메모를 수정하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findByIdAndMemberId(999L, 1L)).thenReturn(null)

        assertFailsWith<MemoNotFoundException> {
            memoService.update(memberId = 1L, id = 999L, title = "제목", content = "내용")
        }
    }

    @Test
    fun `없는 메모를 삭제하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findByIdAndMemberId(999L, 1L)).thenReturn(null)

        assertFailsWith<MemoNotFoundException> {
            memoService.delete(memberId = 1L, id = 999L)
        }
    }
}
