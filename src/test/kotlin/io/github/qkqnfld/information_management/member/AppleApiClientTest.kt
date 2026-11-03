package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import io.jsonwebtoken.security.Keys
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
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

    private fun client(
        clientIds: String = APP_ID,
        keyRefreshInterval: Duration = Duration.ofMinutes(1),
    ) = AppleApiClient(
        baseUrl = BASE_URL,
        clientIds = clientIds,
        restClientBuilder = builder,
        keyRefreshInterval = keyRefreshInterval,
    )

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

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BASE_URL = "https://appleid.test"
        private const val APP_ID = "io.github.qkqnfld.magiceditor"
        private const val APPLE_USER_ID = "001234.abcdef0123456789abcdef0123456789.0123"
        private const val RAW_NONCE = "raw-nonce-123"
    }
}
