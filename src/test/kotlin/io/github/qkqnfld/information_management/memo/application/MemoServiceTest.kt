package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.memo.domain.Memo
import io.github.qkqnfld.information_management.memo.domain.MemoNotFoundException
import io.github.qkqnfld.information_management.memo.infrastructure.MemoRepository
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * MemoService 단위 테스트.
 * 리포지토리를 mockito로 모킹해서 Spring/DB 없이 서비스 로직만 검증한다.
 */
class MemoServiceTest {

    private val memoRepository: MemoRepository = mock()
    private val memoService = MemoService(memoRepository)

    @Test
    fun `생성하면 저장소에 저장을 요청하고 저장된 메모를 반환한다`() {
        whenever(memoRepository.save(any<Memo>())).thenAnswer { it.getArgument<Memo>(0) }

        val memo = memoService.create(title = "제목", content = "내용")

        assertEquals("제목", memo.title)
        assertEquals("내용", memo.content)
        verify(memoRepository).save(any<Memo>())
    }

    @Test
    fun `수정하면 제목과 내용은 바뀌지만 createdAt은 유지된다`() {
        val existing = Memo(title = "원래 제목", content = "원래 내용", id = 1L)
        whenever(memoRepository.findById(1L)).thenReturn(Optional.of(existing))

        val updated = memoService.update(1L, title = "새 제목", content = "새 내용")

        assertEquals("새 제목", updated.title)
        assertEquals("새 내용", updated.content)
        assertEquals(existing.createdAt, updated.createdAt)
    }

    @Test
    fun `삭제하면 저장소에 삭제를 요청한다`() {
        val existing = Memo(title = "삭제 대상", content = "내용", id = 1L)
        whenever(memoRepository.findById(1L)).thenReturn(Optional.of(existing))

        memoService.delete(1L)

        verify(memoRepository).delete(existing)
    }

    @Test
    fun `없는 메모를 조회하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findById(999L)).thenReturn(Optional.empty())

        assertFailsWith<MemoNotFoundException> {
            memoService.findById(999L)
        }
    }

    @Test
    fun `없는 메모를 수정하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findById(999L)).thenReturn(Optional.empty())

        assertFailsWith<MemoNotFoundException> {
            memoService.update(999L, title = "제목", content = "내용")
        }
    }

    @Test
    fun `없는 메모를 삭제하면 MemoNotFoundException이 발생한다`() {
        whenever(memoRepository.findById(999L)).thenReturn(Optional.empty())

        assertFailsWith<MemoNotFoundException> {
            memoService.delete(999L)
        }
    }
}
