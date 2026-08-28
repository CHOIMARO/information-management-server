package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import io.github.qkqnfld.information_management.member.infrastructure.OAuthProfile
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 계정 연동 API 통합 테스트.
 * 핵심 검증: 연동의 근거는 "인증된 세션 + SNS 토큰 검증"이며 이메일 일치가 아니라는 것,
 * 그리고 연동/해제의 보안 규칙(중복 연결 금지, 마지막 수단 해제 금지)이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountLinkApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var kakaoApiClient: KakaoApiClient

    @MockitoBean
    private lateinit var googleApiClient: GoogleApiClient

    private fun uniqueId(): String = "link-${System.nanoTime()}"

    /** 자체 회원가입 + 로그인 → access 토큰 */
    private fun signupAndLogin(email: String): String {
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123","nickname":"연동테스터"}"""),
        ).andExpect(status().isCreated)
        val body = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private fun linkKakao(access: String, snsToken: String) = mockMvc.perform(
        post("/members/me/links/kakao")
            .header("Authorization", "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"$snsToken"}"""),
    )

    private fun memberIdOf(access: String): Long {
        val body = mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    @Test
    fun `이메일이 달라도 카카오 계정을 연동할 수 있고, 이후 카카오 로그인은 같은 계정에 도착한다`() {
        val providerId = uniqueId()
        // 회원 이메일(gmail)과 카카오 이메일(naver)이 다르다 — 연동은 이메일과 무관해야 한다
        val access = signupAndLogin("$providerId@gmail.com")
        whenever(kakaoApiClient.fetchProfile("link-token-1")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, "$providerId@naver.com", "카카오유저"),
        )

        linkKakao(access, "link-token-1").andExpect(status().isNoContent)

        // 연결된 수단 목록에 KAKAO가 추가됐다
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[?(@ == 'KAKAO')]").exists())

        // 이제 카카오로 로그인해도 새 계정이 아니라 기존 계정에 도착한다
        val kakaoLoginBody = mockMvc.perform(
            post("/auth/login/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"link-token-1"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val kakaoAccess = Regex("\"accessToken\":\"([^\"]+)\"").find(kakaoLoginBody)!!.groupValues[1]

        assertEquals(memberIdOf(access), memberIdOf(kakaoAccess), "연동 후 카카오 로그인은 같은 회원이어야 한다")
    }

    @Test
    fun `다른 회원에 이미 연결된 SNS 계정은 연동할 수 없다`() {
        val providerId = uniqueId()
        whenever(kakaoApiClient.fetchProfile("stolen-token")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, null, "선점유저"),
        )
        // 첫 회원이 SNS 로그인으로 이 카카오 계정을 선점
        mockMvc.perform(
            post("/auth/login/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"stolen-token"}"""),
        ).andExpect(status().isOk)

        // 두 번째 회원이 같은 카카오 계정을 연동 시도
        val otherAccess = signupAndLogin("${uniqueId()}@test.com")
        linkKakao(otherAccess, "stolen-token")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SNS_ALREADY_LINKED"))
    }

    @Test
    fun `같은 provider의 수단을 두 번 연동하면 409를 응답한다`() {
        val access = signupAndLogin("${uniqueId()}@test.com")
        whenever(kakaoApiClient.fetchProfile("dup-link-1")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, uniqueId(), null, null),
        )
        whenever(kakaoApiClient.fetchProfile("dup-link-2")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, uniqueId(), null, null),
        )

        linkKakao(access, "dup-link-1").andExpect(status().isNoContent)
        linkKakao(access, "dup-link-2")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SNS_ALREADY_LINKED"))
    }

    @Test
    fun `연동을 해제하면 수단 목록에서 사라진다`() {
        val access = signupAndLogin("${uniqueId()}@test.com")
        whenever(kakaoApiClient.fetchProfile("unlink-token")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, uniqueId(), null, null),
        )
        linkKakao(access, "unlink-token").andExpect(status().isNoContent)

        mockMvc.perform(delete("/members/me/links/KAKAO").header("Authorization", "Bearer $access"))
            .andExpect(status().isNoContent)

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.providers.length()").value(1))
            .andExpect(jsonPath("$.providers[0]").value("LOCAL"))
    }

    @Test
    fun `마지막 남은 로그인 수단은 해제할 수 없다`() {
        // LOCAL 수단 하나뿐인 회원이 그것을 해제하려 하면 계정에 잠겨버린다 → 409
        val access = signupAndLogin("${uniqueId()}@test.com")

        mockMvc.perform(delete("/members/me/links/LOCAL").header("Authorization", "Bearer $access"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LAST_LOGIN_METHOD"))
    }

    @Test
    fun `연결되지 않은 수단을 해제하면 404를 응답한다`() {
        val access = signupAndLogin("${uniqueId()}@test.com")

        mockMvc.perform(delete("/members/me/links/GOOGLE").header("Authorization", "Bearer $access"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CREDENTIAL_NOT_FOUND"))
    }
}
