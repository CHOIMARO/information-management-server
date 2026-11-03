package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.AppleRevokeToken
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import io.jsonwebtoken.security.Keys
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.web.client.RestClientException
import java.net.URLDecoder
import java.util.Base64
import org.springframework.web.client.RestClient
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Apple identityToken 검증 단위 테스트.
 * 테스트용 RSA 키로 Apple과 같은 형식의 토큰을 직접 만들고,
 * MockRestServiceServer(가짜 HTTP 서버)가 그 공개키 목록(JWKS)을 돌려준다.
 * 그래서 Apple 계정 없이도 서명·iss·aud·exp·nonce 검사를 모두 확인할 수 있다.
 */
class AppleApiClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()

    private val keyA: KeyPair = Jwts.SIG.RS256.keyPair().build()
    private val keyB: KeyPair = Jwts.SIG.RS256.keyPair().build()

    /** Apple 개발자 계정의 .p8 키 대신 쓰는 테스트용 EC 키 (ES256) */
    private val appleSigningKey: KeyPair = Jwts.SIG.ES256.keyPair().build()

    private fun client(
        clientIds: String = APP_ID,
        keyRefreshInterval: Duration = Duration.ofMinutes(1),
        privateKeyPem: String = pem(appleSigningKey),
    ) = AppleApiClient(
        baseUrl = BASE_URL,
        clientIds = clientIds,
        teamId = TEAM_ID,
        keyId = KEY_ID,
        privateKey = privateKeyPem,
        restClientBuilder = builder,
        keyRefreshInterval = keyRefreshInterval,
    )

    /** .p8 파일과 같은 PEM 형식. 환경변수처럼 줄바꿈을 \n 문자로 적는다. */
    private fun pem(keyPair: KeyPair): String =
        "-----BEGIN PRIVATE KEY-----\\n" +
            Base64.getEncoder().encodeToString(keyPair.private.encoded).chunked(64).joinToString("\\n") +
            "\\n-----END PRIVATE KEY-----"

    /** 가짜 Apple 서버가 이 키들을 공개키 목록으로 한 번 돌려주도록 한다. */
    private fun expectKeys(vararg keys: Pair<String, KeyPair>) {
        val jwks = keys.joinToString(",", prefix = """{"keys":[""", postfix = "]}") { (kid, pair) ->
            Jwks.json(Jwks.builder().key(pair.public as RSAPublicKey).id(kid).build())
        }
        server.expect(ExpectedCount.once(), requestTo("$BASE_URL/auth/keys"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(jwks, MediaType.APPLICATION_JSON))
    }

    /** Apple identityToken과 같은 형식의 토큰을 만든다. 각 값을 바꿔 실패 경우를 만든다. */
    private fun token(
        kid: String = "kid-a",
        signingKey: KeyPair = keyA,
        issuer: String = "https://appleid.apple.com",
        audience: String = APP_ID,
        expiresAt: Instant = Instant.now().plus(Duration.ofMinutes(10)),
        nonceClaim: String? = sha256Hex(RAW_NONCE),
    ): String = Jwts.builder()
        .header().keyId(kid).and()
        .issuer(issuer)
        .audience().add(audience).and()
        .subject(APPLE_USER_ID)
        .claim("email", "abc@privaterelay.appleid.com")
        .apply { if (nonceClaim != null) claim("nonce", nonceClaim) }
        .issuedAt(Date.from(Instant.now().minusSeconds(5)))
        .expiration(Date.from(expiresAt))
        .signWith(signingKey.private, Jwts.SIG.RS256)
        .compact()

    @Test
    fun `정상 토큰이면 Apple 사용자 id와 이메일을 돌려준다`() {
        expectKeys("kid-a" to keyA)

        val profile = client().fetchProfile(token(), RAW_NONCE)

        assertEquals(AuthProvider.APPLE, profile.provider)
        assertEquals(APPLE_USER_ID, profile.providerId)
        assertEquals("abc@privaterelay.appleid.com", profile.email)
        assertEquals(null, profile.nickname, "Apple 토큰에는 이름이 없다")
        server.verify()
    }

    @Test
    fun `nonce를 보내지 않으면 nonce 검사는 건너뛴다`() {
        expectKeys("kid-a" to keyA)

        client().fetchProfile(token(nonceClaim = null), null)

        server.verify()
    }

    @Test
    fun `nonce가 다르면 거절한다`() {
        expectKeys("kid-a" to keyA)

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile(token(), "other-nonce") }
    }

    @Test
    fun `aud가 우리 앱 ID가 아니면 거절한다`() {
        expectKeys("kid-a" to keyA)

        assertFailsWith<InvalidSnsTokenException> {
            client().fetchProfile(token(audience = "com.other.app"), RAW_NONCE)
        }
    }

    @Test
    fun `허용한 앱 ID 여러 개 중 하나와 일치하면 통과한다`() {
        expectKeys("kid-a" to keyA)

        client(clientIds = "com.example.ios, $APP_ID").fetchProfile(token(), RAW_NONCE)

        server.verify()
    }

    @Test
    fun `iss가 Apple이 아니면 거절한다`() {
        expectKeys("kid-a" to keyA)

        assertFailsWith<InvalidSnsTokenException> {
            client().fetchProfile(token(issuer = "https://evil.example.com"), RAW_NONCE)
        }
    }

    @Test
    fun `만료된 토큰은 거절한다`() {
        expectKeys("kid-a" to keyA)

        assertFailsWith<InvalidSnsTokenException> {
            client().fetchProfile(token(expiresAt = Instant.now().minus(Duration.ofMinutes(10))), RAW_NONCE)
        }
    }

    @Test
    fun `같은 kid라도 다른 키로 서명한 토큰은 거절한다`() {
        expectKeys("kid-a" to keyA)

        assertFailsWith<InvalidSnsTokenException> {
            client().fetchProfile(token(signingKey = keyB), RAW_NONCE)
        }
    }

    @Test
    fun `RS256이 아닌 알고리즘으로 서명한 토큰은 거절한다`() {
        val hmacToken = Jwts.builder()
            .header().keyId("kid-a").and()
            .issuer("https://appleid.apple.com")
            .audience().add(APP_ID).and()
            .subject(APPLE_USER_ID)
            .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
            .signWith(Keys.hmacShaKeyFor(ByteArray(32) { 7 }), Jwts.SIG.HS256)
            .compact()

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile(hmacToken, null) }
        server.verify() // 알고리즘에서 거절하므로 공개키 목록도 받지 않는다
    }

    @Test
    fun `공개키 목록은 한 번 받아 두고 다시 쓴다`() {
        expectKeys("kid-a" to keyA)
        val client = client()

        client.fetchProfile(token(), RAW_NONCE)
        client.fetchProfile(token(), RAW_NONCE)

        server.verify() // 요청은 한 번뿐이다
    }

    @Test
    fun `모르는 kid가 오면 공개키 목록을 다시 받는다`() {
        // Apple이 키를 교체한 경우: 처음 목록에는 kid-a만, 다시 받은 목록에는 kid-b도 있다
        expectKeys("kid-a" to keyA)
        expectKeys("kid-a" to keyA, "kid-b" to keyB)
        val client = client(keyRefreshInterval = Duration.ZERO)

        client.fetchProfile(token(), RAW_NONCE)
        val profile = client.fetchProfile(token(kid = "kid-b", signingKey = keyB), RAW_NONCE)

        assertEquals(APPLE_USER_ID, profile.providerId)
        server.verify()
    }

    @Test
    fun `다시 받는 간격 안에서는 모르는 kid라도 공개키 목록을 다시 받지 않는다`() {
        expectKeys("kid-a" to keyA)
        val client = client(keyRefreshInterval = Duration.ofMinutes(1))
        client.fetchProfile(token(), RAW_NONCE)

        // 가짜 kid로 Apple 호출을 반복시키는 것을 막는다
        assertFailsWith<InvalidSnsTokenException> {
            client.fetchProfile(token(kid = "kid-unknown", signingKey = keyB), RAW_NONCE)
        }
        server.verify() // 요청은 처음 한 번뿐이다
    }

    @Test
    fun `허용할 앱 ID가 설정되지 않으면 Apple을 호출하지 않고 거절한다`() {
        assertFailsWith<InvalidSnsTokenException> { client(clientIds = "").fetchProfile(token(), RAW_NONCE) }
        server.verify()
    }

    @Test
    fun `정상 토큰이면 토큰을 받은 앱 ID를 함께 돌려준다`() {
        expectKeys("kid-a" to keyA)

        val profile = client(clientIds = "com.other.app, $APP_ID").fetchProfile(token(), RAW_NONCE)

        assertEquals(APP_ID, profile.clientId)
    }

    @Test
    fun `authorizationCode를 Apple refresh 토큰으로 바꾼다`() {
        var form: Map<String, String> = emptyMap()
        server.expect(requestTo("$BASE_URL/auth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect { request -> form = parseForm((request as MockClientHttpRequest).bodyAsString) }
            .andRespond(withSuccess("""{"access_token":"a","refresh_token":"apple-refresh","id_token":"x"}""", MediaType.APPLICATION_JSON))

        val refreshToken = client().exchangeAuthorizationCode("auth-code", APP_ID)

        assertEquals("apple-refresh", refreshToken)
        assertEquals("auth-code", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(APP_ID, form["client_id"])
        assertClientSecret(form.getValue("client_secret"))
        server.verify()
    }

    @Test
    fun `Apple 토큰을 취소할 때 refresh 토큰과 그 토큰을 받은 앱 ID를 보낸다`() {
        var form: Map<String, String> = emptyMap()
        server.expect(requestTo("$BASE_URL/auth/revoke"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request -> form = parseForm((request as MockClientHttpRequest).bodyAsString) }
            .andRespond(withSuccess())

        client().revoke(AppleRevokeToken(clientId = APP_ID, refreshToken = "apple-refresh"))

        assertEquals("apple-refresh", form["token"])
        assertEquals("refresh_token", form["token_type_hint"])
        assertEquals(APP_ID, form["client_id"])
        assertClientSecret(form.getValue("client_secret"))
        server.verify()
    }

    @Test
    fun `Apple이 토큰 취소를 거절하면 예외를 던진다 (탈퇴 Worker가 다시 시도한다)`() {
        server.expect(requestTo("$BASE_URL/auth/revoke")).andRespond(withStatus(HttpStatus.BAD_REQUEST))

        assertFailsWith<RestClientException> { client().revoke(AppleRevokeToken(APP_ID, "apple-refresh")) }
    }

    @Test
    fun `비밀키 설정이 없으면 토큰을 다룰 수 없다`() {
        val client = client(privateKeyPem = "")

        assertEquals(false, client.canManageTokens)
        assertFailsWith<IllegalStateException> { client.exchangeAuthorizationCode("auth-code", APP_ID) }
    }

    @Test
    fun `철회용 토큰은 앱 ID와 refresh 토큰으로 나눠 읽을 수 있다`() {
        val token = AppleRevokeToken(clientId = APP_ID, refreshToken = "r.abc|def")

        assertEquals(token, AppleRevokeToken.decode(token.encode()))
        assertFailsWith<IllegalArgumentException> { AppleRevokeToken.decode("no-separator") }
    }

    /** client_secret이 Apple 규격(ES256, kid, iss=Team ID, sub=앱 ID, aud=Apple)인지 확인한다. */
    private fun assertClientSecret(clientSecret: String) {
        val jws = Jwts.parser().verifyWith(appleSigningKey.public).build().parseSignedClaims(clientSecret)
        assertEquals(KEY_ID, jws.header.keyId)
        assertEquals("ES256", jws.header.algorithm)
        assertEquals(TEAM_ID, jws.payload.issuer)
        assertEquals(APP_ID, jws.payload.subject)
        assertEquals(setOf("https://appleid.apple.com"), jws.payload.audience)
    }

    private fun parseForm(body: String): Map<String, String> = body.split("&").associate {
        val (key, value) = it.split("=", limit = 2)
        URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BASE_URL = "https://appleid.test"
        private const val APP_ID = "io.github.qkqnfld.magiceditor"
        private const val APPLE_USER_ID = "001234.abcdef0123456789abcdef0123456789.0123"
        private const val RAW_NONCE = "raw-nonce-123"
        private const val TEAM_ID = "TEAM123456"
        private const val KEY_ID = "KEY1234567"
    }
}
