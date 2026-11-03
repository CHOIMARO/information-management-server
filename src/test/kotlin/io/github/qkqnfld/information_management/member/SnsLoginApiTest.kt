package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
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
import kotlin.test.assertEquals

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

    @MockitoBean
    private lateinit var appleApiClient: AppleApiClient

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
    fun `같은 이메일이라도 다른 SNS로 처음 로그인하면 다른 계정으로 가입된다`() {
        // PRD 9.17: 계정은 로그인 수단과 고유 ID로 구분한다. 이메일만 보고 합치면 남의 계정이 넘어갈 수 있다
        val email = "${uniqueId()}@same.com"
        whenever(kakaoApiClient.fetchProfile("same-email-kakao")).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, uniqueId(), email, "카카오쪽"),
        )
        whenever(googleApiClient.fetchProfile("same-email-google")).thenReturn(
            OAuthProfile(AuthProvider.GOOGLE, uniqueId(), email, "구글쪽"),
        )

        val kakaoMemberId = memberIdOf(snsLoginAccess("/auth/login/kakao", "same-email-kakao"))
        val googleMemberId = memberIdOf(snsLoginAccess("/auth/login/google", "same-email-google"))

        assert(kakaoMemberId != googleMemberId) { "이메일이 같아도 자동으로 합치면 안 된다" }
    }

    @Test
    fun `SNS 이름이 20자를 넘으면 20자로 잘라서 가입한다`() {
        whenever(googleApiClient.fetchProfile("long-name-token")).thenReturn(
            OAuthProfile(AuthProvider.GOOGLE, uniqueId(), null, "Christopher Alexander Montgomery"),
        )

        val access = snsLoginAccess("/auth/login/google", "long-name-token")

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.nickname").value("Christopher Alexande"))
    }

    @Test
    fun `SNS가 프로필 사진을 주면 내 정보에 담는다`() {
        whenever(googleApiClient.fetchProfile("picture-token")).thenReturn(
            OAuthProfile(AuthProvider.GOOGLE, uniqueId(), null, "사진유저", "https://lh3.googleusercontent.com/a/photo"),
        )

        val access = snsLoginAccess("/auth/login/google", "picture-token")

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.profileImageUrl").value("https://lh3.googleusercontent.com/a/photo"))
    }

    @Test
    fun `Apple 첫 로그인이면 앱이 보낸 이름으로 가입된다`() {
        val providerId = uniqueId()
        whenever(appleApiClient.fetchProfile("apple-token-1", "nonce-1")).thenReturn(
            OAuthProfile(AuthProvider.APPLE, providerId, "$providerId@privaterelay.appleid.com", null),
        )

        val access = appleLoginAccess("""{"token":"apple-token-1","nonce":"nonce-1","nickname":"애플유저"}""")

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("애플유저"))
            .andExpect(jsonPath("$.email").value("$providerId@privaterelay.appleid.com"))
            .andExpect(jsonPath("$.providers[0]").value("APPLE"))
    }

    @Test
    fun `Apple 재로그인은 이름 없이도 같은 회원에 도착하고 처음 이름이 유지된다`() {
        val providerId = uniqueId()
        // Apple은 이름을 첫 로그인 때만 앱에 준다. 두 번째부터는 이름이 없다.
        whenever(appleApiClient.fetchProfile("apple-token-2", null)).thenReturn(
            OAuthProfile(AuthProvider.APPLE, providerId, null, null),
        )

        val first = appleLoginAccess("""{"token":"apple-token-2","nickname":"처음이름"}""")
        val second = appleLoginAccess("""{"token":"apple-token-2"}""")

        assertEquals(memberIdOf(first), memberIdOf(second), "재로그인 시 새 회원이 만들어지면 안 된다")
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $second"))
            .andExpect(jsonPath("$.nickname").value("처음이름"))
    }

    @Test
    fun `Apple 첫 로그인에 이름이 없으면 기본 이름으로 가입된다`() {
        whenever(appleApiClient.fetchProfile("apple-token-3", null)).thenReturn(
            OAuthProfile(AuthProvider.APPLE, uniqueId(), null, null),
        )

        val access = appleLoginAccess("""{"token":"apple-token-3","nickname":"  "}""")

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.nickname").value("magic 사용자"))
    }

    @Test
    fun `Apple 토큰 검증에 실패하면 401과 INVALID_SNS_TOKEN 코드를 응답한다`() {
        whenever(appleApiClient.fetchProfile("bad-apple-token", null)).thenThrow(InvalidSnsTokenException())

        appleLogin("""{"token":"bad-apple-token"}""")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_SNS_TOKEN"))
    }

    @Test
    fun `Apple 로그인 이름이 100자를 넘으면 400을 응답한다`() {
        appleLogin("""{"token":"apple-token-4","nickname":"${"가".repeat(101)}"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"))
    }

    private fun appleLogin(json: String) = mockMvc.perform(
        post("/auth/login/apple").contentType(MediaType.APPLICATION_JSON).content(json),
    )

    private fun appleLoginAccess(json: String): String {
        val body = appleLogin(json).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
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
