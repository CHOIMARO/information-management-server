package io.github.qkqnfld.information_management.memo

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * API 통합 테스트: 웹 어댑터 → 애플리케이션 → 영속성 어댑터(인메모리 H2)까지
 * 전체 흐름을 실제 HTTP 계층에서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    /** 응답 본문에서 생성된 메모의 id를 꺼낸다. */
    private fun createMemo(title: String, content: String): Long {
        val body = mockMvc.perform(
            post("/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$title","content":"$content"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    @Test
    fun `메모를 생성하면 201과 Location 헤더, 발급된 id를 응답한다`() {
        mockMvc.perform(
            post("/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"통합 테스트","content":"내용"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.title").value("통합 테스트"))
    }

    @Test
    fun `빈 제목으로 생성하면 400과 필드 에러를 응답한다`() {
        mockMvc.perform(
            post("/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"","content":"내용"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
    }

    @Test
    fun `필수 필드가 누락된 본문은 400을 응답한다`() {
        mockMvc.perform(
            post("/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"제목 없음"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `없는 메모를 조회하면 404와 MEMO_NOT_FOUND 코드를 응답한다`() {
        mockMvc.perform(get("/memos/999999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MEMO_NOT_FOUND"))
    }

    @Test
    fun `id 자리에 문자열을 넣으면 400과 TYPE_MISMATCH 코드를 응답한다`() {
        mockMvc.perform(get("/memos/abc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
    }

    @Test
    fun `삭제한 메모를 다시 조회하면 404를 응답한다`() {
        val id = createMemo(title = "삭제될 메모", content = "내용")

        mockMvc.perform(delete("/memos/$id"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/memos/$id"))
            .andExpect(status().isNotFound)
    }
}
