package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 카카오 API 클라이언트 단위 테스트. 가짜 HTTP 서버(MockRestServiceServer)로 카카오 응답을 흉내 낸다.
 * 핵심 검사: 토큰의 app_id가 우리 앱 ID인지, 연결 끊기를 어드민 키로 부르는지.
 */
class KakaoApiClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    private fun client(appId: String = APP_ID.toString(), adminKey: String = ADMIN_KEY) = KakaoApiClient(
        appId = appId,
        adminKey = adminKey,
        baseUrl = BASE_URL,
        restClientBuilder = builder,
    )

    private fun expectTokenInfo(appId: Long, userId: Long = USER_ID) {
        server.expect(requestTo("$BASE_URL/v1/user/access_token_info"))
            .andExpect(header("Authorization", "Bearer user-token"))
            .andRespond(withSuccess("""{"id":$userId,"expires_in":7199,"app_id":$appId}""", MediaType.APPLICATION_JSON))
    }

    private fun expectUserMe(isDefaultImage: Boolean = false) {
        server.expect(requestTo("$BASE_URL/v2/user/me"))
            .andRespond(
                withSuccess(
                    """
                    {"id":$USER_ID,"kakao_account":{"email":"user@kakao.com",
                     "profile":{"nickname":"카카오유저","profile_image_url":"https://k.kakaocdn.net/p.jpg","is_default_image":$isDefaultImage}}}
                    """,
                    MediaType.APPLICATION_JSON,
                ),
            )
    }

    @Test
    fun `우리 앱에서 발급된 토큰이면 카카오 사용자 정보를 돌려준다`() {
        expectTokenInfo(appId = APP_ID)
        expectUserMe()

        val profile = client().fetchProfile("user-token")

        assertEquals(AuthProvider.KAKAO, profile.provider)
        assertEquals(USER_ID.toString(), profile.providerId)
        assertEquals("user@kakao.com", profile.email)
        assertEquals("카카오유저", profile.nickname)
        assertEquals("https://k.kakaocdn.net/p.jpg", profile.profileImageUrl)
        server.verify()
    }

    @Test
    fun `기본 프로필 사진이면 사진 주소를 비운다`() {
        expectTokenInfo(appId = APP_ID)
        expectUserMe(isDefaultImage = true)

        assertNull(client().fetchProfile("user-token").profileImageUrl)
    }

    @Test
    fun `다른 앱에서 발급된 토큰이면 거절한다`() {
        expectTokenInfo(appId = 999)

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile("user-token") }
        server.verify()
    }

    @Test
    fun `카카오가 토큰을 거절하면 INVALID_SNS_TOKEN으로 바꾼다`() {
        server.expect(requestTo("$BASE_URL/v1/user/access_token_info"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile("user-token") }
    }

    @Test
    fun `앱 ID가 설정되지 않으면 카카오를 호출하지 않고 거절한다`() {
        assertFailsWith<InvalidSnsTokenException> { client(appId = "").fetchProfile("user-token") }
        server.verify()
    }

    @Test
    fun `연결 끊기는 어드민 키와 회원번호로 요청한다`() {
        server.expect(requestTo("$BASE_URL/v1/user/unlink"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "KakaoAK $ADMIN_KEY"))
            .andExpect(
                content().formData(
                    LinkedMultiValueMap(mapOf("target_id_type" to listOf("user_id"), "target_id" to listOf("$USER_ID"))),
                ),
            )
            .andRespond(withSuccess("""{"id":$USER_ID}""", MediaType.APPLICATION_JSON))

        client().unlink(USER_ID.toString())

        server.verify()
    }

    @Test
    fun `이미 연결이 끊긴 사용자면 성공으로 처리한다`() {
        server.expect(requestTo("$BASE_URL/v1/user/unlink"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"msg":"NotRegisteredUserException","code":-101}"""),
            )

        client().unlink(USER_ID.toString())
    }

    @Test
    fun `연결 끊기가 다른 이유로 실패하면 예외를 던진다 (탈퇴 Worker가 다시 시도한다)`() {
        server.expect(requestTo("$BASE_URL/v1/user/unlink")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertFailsWith<RestClientException> { client().unlink(USER_ID.toString()) }
    }

    @Test
    fun `어드민 키가 없으면 연결을 끊을 수 없다`() {
        assertFailsWith<IllegalStateException> { client(adminKey = "").unlink(USER_ID.toString()) }
    }

    companion object {
        private const val BASE_URL = "https://kapi.kakao.test"
        private const val APP_ID = 123456L
        private const val USER_ID = 4321098765L
        private const val ADMIN_KEY = "test-admin-key"
    }
}
