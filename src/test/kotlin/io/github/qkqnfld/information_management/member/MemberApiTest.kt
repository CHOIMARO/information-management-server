package io.github.qkqnfld.information_management.member

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * 회원가입/로그인/인증 흐름의 API 통합 테스트.
 * 실제 시큐리티 필터 체인을 통과하므로 인증 정책까지 함께 검증된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    /** 테스트 간 데이터가 공유되므로 매번 유일한 이메일을 만든다. */
    private fun uniqueEmail(): String = "user${System.nanoTime()}@test.com"

    private fun signup(email: String, password: String = "password123", nickname: String = "테스터"): ResultActions {
        return mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password","nickname":"$nickname"}"""),
        )
    }

    private fun login(email: String, password: String = "password123"): ResultActions {
        return mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        )
    }

    @Test
    fun `회원가입하면 201과 회원 정보를 응답하고 비밀번호는 응답에 없다`() {
        val email = uniqueEmail()

        signup(email)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.nickname").value("테스터"))
            .andExpect(jsonPath("$.password").doesNotExist())
    }

    @Test
    fun `이미 가입된 이메일로 가입하면 409와 DUPLICATE_EMAIL 코드를 응답한다`() {
        val email = uniqueEmail()
        signup(email).andExpect(status().isCreated)

        signup(email)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
    }

    @Test
    fun `비밀번호가 8자 미만이면 400을 응답한다`() {
        signup(uniqueEmail(), password = "short")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
    }

    /** 로그인 후 (accessToken, refreshToken) 쌍을 꺼낸다. */
    private fun loginTokens(email: String): Pair<String, String> {
        val body = login(email).andExpect(status().isOk).andReturn().response.contentAsString
        val access = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val refresh = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        return access to refresh
    }

    @Test
    fun `올바른 자격 증명으로 로그인하면 access와 refresh 토큰을 모두 발급한다`() {
        val email = uniqueEmail()
        signup(email).andExpect(status().isCreated)

        login(email)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.refreshToken").isString)
    }

    @Test
    fun `refresh 토큰으로 새 access 토큰을 재발급받아 사용할 수 있다`() {
        val email = uniqueEmail()
        signup(email).andExpect(status().isCreated)
        val (_, refresh) = loginTokens(email)

        val body = mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refresh"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andReturn().response.contentAsString
        val newAccess = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

        // 재발급받은 access 토큰이 실제로 동작하는지까지 확인
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $newAccess"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
    }

    @Test
    fun `위조된 refresh 토큰으로 재발급을 시도하면 401을 응답한다`() {
        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"no-such-refresh-token"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `로그아웃하면 그 refresh 토큰으로는 더 이상 재발급할 수 없다`() {
        val email = uniqueEmail()
        signup(email).andExpect(status().isCreated)
        val (_, refresh) = loginTokens(email)

        mockMvc.perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refresh"}"""),
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refresh"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `틀린 비밀번호로 로그인하면 401과 INVALID_CREDENTIALS 코드를 응답한다`() {
        val email = uniqueEmail()
        signup(email).andExpect(status().isCreated)

        login(email, password = "wrong-password")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `가입되지 않은 이메일로 로그인해도 같은 401 코드를 응답한다 (계정 열거 방지)`() {
        login("no-such-user@test.com")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    fun `토큰 없이 보호된 API에 접근하면 401과 UNAUTHORIZED 코드를 응답한다`() {
        mockMvc.perform(get("/memos"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `위조된 토큰으로 접근하면 401을 응답한다`() {
        mockMvc.perform(get("/memos").header("Authorization", "Bearer fake.invalid.token"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `유효한 토큰으로 내 정보를 조회하면 가입한 정보가 온다`() {
        val email = uniqueEmail()
        signup(email, nickname = "내정보테스터").andExpect(status().isCreated)
        val body = login(email).andReturn().response.contentAsString
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.nickname").value("내정보테스터"))
    }
}
