package io.github.qkqnfld.information_management.memo

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * API 통합 테스트: 웹 어댑터 → 애플리케이션 → 영속성 어댑터(인메모리 H2)까지
 * 전체 흐름을 실제 HTTP 계층에서 검증한다.
 * 메모 API는 인증이 필요하므로, 각 테스트 전에 회원가입+로그인으로 토큰을 발급받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemoApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var token: String

    /** 새 회원을 가입시키고 로그인해 토큰을 반환한다 (소유권 테스트에서 두 번째 회원용으로도 사용). */
    private fun registerAndLogin(): String {
        val email = "memo-tester-${System.nanoTime()}@test.com"
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123","nickname":"메모테스터"}"""),
        ).andExpect(status().isCreated)

        val body = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    /** 테스트마다 새 회원으로 로그인해 토큰을 준비한다. */
    @BeforeEach
    fun setUpAuth() {
        token = registerAndLogin()
    }

    /** 요청에 인증 토큰을 싣는다. 기본은 이 테스트의 회원, 지정하면 다른 회원의 토큰. */
    private fun MockHttpServletRequestBuilder.authed(withToken: String = token): MockHttpServletRequestBuilder {
        return this.header("Authorization", "Bearer $withToken")
    }

    /** 응답 본문에서 생성된 메모의 id를 꺼낸다. */
    private fun createMemo(
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        withToken: String = token,
    ): Long {
        val tagsJson = tags.joinToString(",") { "\"$it\"" }
        val body = mockMvc.perform(
            post("/memos")
                .authed(withToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$title","content":"$content","tags":[$tagsJson]}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    @Test
    fun `목록 조회는 페이지 정보와 함께 응답한다`() {
        createMemo(title = "페이징 확인용", content = "내용")

        mockMvc.perform(get("/memos").authed().param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").isNumber)
            .andExpect(jsonPath("$.hasNext").isBoolean)
    }

    @Test
    fun `기본 정렬은 최신순이라 나중에 만든 메모가 목록의 맨 앞에 온다`() {
        createMemo(title = "먼저 만든 메모", content = "내용")
        val latestId = createMemo(title = "나중에 만든 메모", content = "내용")

        mockMvc.perform(get("/memos").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value(latestId))
    }

    @Test
    fun `키워드로 검색하면 제목이나 내용에 포함된 메모만 반환한다`() {
        createMemo(title = "고유키워드XYZ 회의록", content = "내용")
        createMemo(title = "다른 메모", content = "본문에 고유키워드XYZ 포함")
        createMemo(title = "무관한 메모", content = "무관한 내용")

        mockMvc.perform(get("/memos").authed().param("keyword", "고유키워드XYZ"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    @Test
    fun `메모를 생성하면 201과 Location 헤더, 발급된 id를 응답한다`() {
        mockMvc.perform(
            post("/memos")
                .authed()
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
                .authed()
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
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"제목 없음"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `없는 메모를 조회하면 404와 MEMO_NOT_FOUND 코드를 응답한다`() {
        mockMvc.perform(get("/memos/999999").authed())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MEMO_NOT_FOUND"))
    }

    @Test
    fun `id 자리에 문자열을 넣으면 400과 TYPE_MISMATCH 코드를 응답한다`() {
        mockMvc.perform(get("/memos/abc").authed())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
    }

    @Test
    fun `태그와 함께 생성하면 응답에 태그 이름 목록이 포함된다`() {
        mockMvc.perform(
            post("/memos")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"태그 메모","content":"내용","tags":["생성태그A","생성태그B"]}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tags[0]").value("생성태그A"))
            .andExpect(jsonPath("$.tags[1]").value("생성태그B"))
    }

    @Test
    fun `태그로 필터링하면 그 태그가 붙은 메모만 반환한다`() {
        createMemo(title = "필터 대상 1", content = "내용", tags = listOf("필터태그X"))
        createMemo(title = "필터 대상 2", content = "내용", tags = listOf("필터태그X", "필터태그Y"))
        createMemo(title = "필터 비대상", content = "내용", tags = listOf("필터태그Y"))

        mockMvc.perform(get("/memos").authed().param("tag", "필터태그X"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
    }

    @Test
    fun `태그 목록을 조회하면 태그별 메모 수가 함께 온다`() {
        createMemo(title = "카운트 1", content = "내용", tags = listOf("카운트태그Z"))
        createMemo(title = "카운트 2", content = "내용", tags = listOf("카운트태그Z"))

        mockMvc.perform(get("/tags").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == '카운트태그Z')].memoCount").value(2))
    }

    @Test
    fun `수정 시 태그 목록이 통째로 교체된다`() {
        val id = createMemo(title = "교체 전", content = "내용", tags = listOf("교체태그P"))

        mockMvc.perform(
            put("/memos/$id")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"교체 후","content":"내용","tags":["교체태그Q"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tags.length()").value(1))
            .andExpect(jsonPath("$.tags[0]").value("교체태그Q"))
    }

    @Test
    fun `기존 태그 일부를 유지한 채 수정해도 정상 동작한다`() {
        // 회귀 테스트: clear 후 재추가 방식이던 시절, 유지되는 태그의 연결이
        // flush 순서(INSERT 먼저, DELETE 나중) 때문에 유니크 제약에 걸려 500이 났다
        val id = createMemo(title = "유지 수정", content = "내용", tags = listOf("유지태그R", "빠질태그S"))

        mockMvc.perform(
            put("/memos/$id")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"유지 수정","content":"내용","tags":["유지태그R","추가태그T"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tags.length()").value(2))
            .andExpect(jsonPath("$.tags[0]").value("유지태그R"))
            .andExpect(jsonPath("$.tags[1]").value("추가태그T"))
    }

    @Test
    fun `동일한 태그 목록으로 수정해도 정상 동작한다`() {
        val id = createMemo(title = "동일 수정", content = "내용", tags = listOf("동일태그U"))

        mockMvc.perform(
            put("/memos/$id")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"동일 수정 후","content":"내용","tags":["동일태그U"]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tags[0]").value("동일태그U"))
    }

    @Test
    fun `태그를 10개 넘게 보내면 400을 응답한다`() {
        val elevenTags = (1..11).joinToString(",") { "\"넘침$it\"" }
        mockMvc.perform(
            post("/memos")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"태그 과다","content":"내용","tags":[$elevenTags]}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `20자를 넘는 태그 이름은 400을 응답한다`() {
        val longTag = "가".repeat(21)
        mockMvc.perform(
            post("/memos")
                .authed()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"긴 태그","content":"내용","tags":["$longTag"]}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `키워드와 태그를 동시에 지정하면 두 조건을 모두 만족하는 메모만 반환한다`() {
        createMemo(title = "조합 회의록", content = "내용", tags = listOf("조합태그A"))
        createMemo(title = "조합 회의록 둘", content = "내용", tags = listOf("조합태그B"))
        createMemo(title = "조합 장보기", content = "내용", tags = listOf("조합태그A"))

        // keyword=회의록 → 2건, tag=조합태그A → 2건, 둘 다(AND) → 1건
        mockMvc.perform(get("/memos").authed().param("keyword", "회의록").param("tag", "조합태그A"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title").value("조합 회의록"))
    }

    @Test
    fun `목록에는 내 메모만 보인다`() {
        createMemo(title = "내 메모", content = "내용")
        val otherToken = registerAndLogin()
        createMemo(title = "남의 메모 1", content = "내용", withToken = otherToken)
        createMemo(title = "남의 메모 2", content = "내용", withToken = otherToken)

        mockMvc.perform(get("/memos").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].title").value("내 메모"))
    }

    @Test
    fun `남의 메모를 단건 조회하면 404로 존재를 숨긴다`() {
        val myMemoId = createMemo(title = "내 메모", content = "내용")
        val otherToken = registerAndLogin()

        mockMvc.perform(get("/memos/$myMemoId").authed(otherToken))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("MEMO_NOT_FOUND"))
    }

    @Test
    fun `남의 메모는 수정하거나 삭제할 수 없다`() {
        val myMemoId = createMemo(title = "내 메모", content = "내용")
        val otherToken = registerAndLogin()

        mockMvc.perform(
            put("/memos/$myMemoId")
                .authed(otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"탈취 시도","content":"내용"}"""),
        ).andExpect(status().isNotFound)

        mockMvc.perform(delete("/memos/$myMemoId").authed(otherToken))
            .andExpect(status().isNotFound)

        // 주인은 여전히 멀쩡히 조회할 수 있다
        mockMvc.perform(get("/memos/$myMemoId").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("내 메모"))
    }

    @Test
    fun `태그 목록은 내 메모에 붙인 태그만 집계한다`() {
        createMemo(title = "내 메모", content = "내용", tags = listOf("소유태그V"))
        val otherToken = registerAndLogin()
        createMemo(title = "남의 메모 1", content = "내용", tags = listOf("소유태그V"), withToken = otherToken)
        createMemo(title = "남의 메모 2", content = "내용", tags = listOf("소유태그V", "남의태그W"), withToken = otherToken)

        // 같은 태그를 남이 두 번 썼어도 내 카운트는 1이고, 남만 쓴 태그는 목록에 없다
        mockMvc.perform(get("/tags").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.name == '소유태그V')].memoCount").value(1))
            .andExpect(jsonPath("$[?(@.name == '남의태그W')]").isEmpty)
    }

    @Test
    fun `삭제한 메모를 다시 조회하면 404를 응답한다`() {
        val id = createMemo(title = "삭제될 메모", content = "내용")

        mockMvc.perform(delete("/memos/$id").authed())
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/memos/$id").authed())
            .andExpect(status().isNotFound)
    }
}
