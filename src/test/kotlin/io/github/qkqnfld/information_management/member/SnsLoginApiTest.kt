package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * SNS 로그인 API 통합 테스트.
 * 카카오/구글 API 클라이언트만 가짜(mock)로 바꾸고 (실제 SNS를 호출할 수 없으므로 —
 * 외부 연동 테스트의 표준 기법), 회원 자동 가입 → 토큰 발급 → 인증까지는 실제 흐름을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SnsLoginApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var kakaoApiClient: KakaoApiClient

    @MockitoBean
    private lateinit var googleApiClient: GoogleApiClient

    private fun uniqueId(): String = "sns-${System.nanoTime()}"

    private fun snsLogin(path: String, snsToken: String) = mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"$snsToken"}"""),
    )

    @Test
    fun `카카오 첫 로그인이면 자동 가입되고 우리 토큰이 발급된다`() {
        val providerId = uniqueId()
        whenever(kakaoApiClient.fetchProfile("kakao-token-1")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, "$providerId@kakao.com", "카카오유저"),
        )

        val body = snsLogin("/auth/login/kakao", "kakao-token-1")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.refreshToken").isString)
            .andReturn().response.contentAsString
        val access = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

        // 발급된 우리 토큰으로 실제 인증이 되는지, 가입 정보가 맞는지 확인
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value("$providerId@kakao.com"))
            .andExpect(jsonPath("$.nickname").value("카카오유저"))
            .andExpect(jsonPath("$.providers[0]").value("KAKAO"))
    }

    @Test
    fun `같은 카카오 계정으로 두 번 로그인해도 회원은 하나만 만들어진다`() {
        val providerId = uniqueId()
        whenever(kakaoApiClient.fetchProfile("kakao-token-2")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, null, "재로그인유저"),
        )

        val firstId = memberIdOf(snsLoginAccess("/auth/login/kakao", "kakao-token-2"))
        val secondId = memberIdOf(snsLoginAccess("/auth/login/kakao", "kakao-token-2"))

        assert(firstId == secondId) { "재로그인 시 새 회원이 만들어지면 안 된다" }
    }

    @Test
    fun `구글 로그인도 같은 흐름으로 동작한다`() {
        val providerId = uniqueId()
        whenever(googleApiClient.fetchProfile("google-id-token")).thenReturn(
            OAuthProfile(AuthProvider.GOOGLE, providerId, "$providerId@gmail.com", "구글유저"),
        )

        val access = snsLoginAccess("/auth/login/google", "google-id-token")
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers[0]").value("GOOGLE"))
    }

    @Test
    fun `SNS 쪽 검증에 실패한 토큰이면 401과 INVALID_SNS_TOKEN 코드를 응답한다`() {
        whenever(kakaoApiClient.fetchProfile("bad-token")).thenThrow(InvalidSnsTokenException())

        snsLogin("/auth/login/kakao", "bad-token")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_SNS_TOKEN"))
    }

    @Test
    fun `이미 자체 가입된 이메일로 SNS 첫 로그인을 하면 409를 응답한다`() {
        val email = "${uniqueId()}@dup.com"
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123","nickname":"자체가입"}"""),
        ).andExpect(status().isCreated)

        whenever(kakaoApiClient.fetchProfile("dup-email-token")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, uniqueId(), email, "중복유저"),
        )

        // 자동 통합은 계정 탈취 경로가 되므로, 기존 방식으로 로그인 후 연동하라는 안내 코드를 준다
        snsLogin("/auth/login/kakao", "dup-email-token")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOCIAL_EMAIL_CONFLICT"))
    }

    @Test
    fun `SNS 가입 계정의 이메일로 비밀번호 로그인을 시도하면 401을 응답한다`() {
        val providerId = uniqueId()
        val email = "$providerId@kakao.com"
        whenever(kakaoApiClient.fetchProfile("kakao-token-3")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, email, "비번없는유저"),
        )
        snsLogin("/auth/login/kakao", "kakao-token-3").andExpect(status().isOk)

        // SNS 계정은 비밀번호가 없으므로(null) 어떤 비밀번호로도 로그인 불가
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    /** SNS 로그인 후 access 토큰을 꺼낸다. */
    private fun snsLoginAccess(path: String, snsToken: String): String {
        val body = snsLogin(path, snsToken).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    /** /members/me로 이 access 토큰 주인의 회원 id를 알아낸다. */
    private fun memberIdOf(access: String): Long {
        val body = mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }
}
