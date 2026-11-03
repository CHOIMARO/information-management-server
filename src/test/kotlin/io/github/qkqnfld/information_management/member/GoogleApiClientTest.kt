package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.KeyPair
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 구글 idToken 검증 단위 테스트.
 * 테스트용 RSA 키로 구글과 같은 형식의 토큰을 만들고, 가짜 HTTP 서버가 그 공개키 목록을 돌려준다.
 * 핵심 검사: aud가 우리 Web 클라이언트 ID인지, iss가 구글인지.
 */
class GoogleApiClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val key: KeyPair = Jwts.SIG.RS256.keyPair().build()

    private fun client(clientIds: String = WEB_CLIENT_ID) = GoogleApiClient(
        clientIds = clientIds,
        certsUrl = CERTS_URL,
        restClientBuilder = builder,
    )

    private fun expectKeys() {
        val jwks = """{"keys":[${Jwks.json(Jwks.builder().key(key.public as RSAPublicKey).id(KID).build())}]}"""
        server.expect(ExpectedCount.once(), requestTo(CERTS_URL))
            .andRespond(withSuccess(jwks, MediaType.APPLICATION_JSON))
    }

    private fun idToken(
        audience: String = WEB_CLIENT_ID,
        issuer: String = "https://accounts.google.com",
        emailVerified: Boolean = true,
    ): String = Jwts.builder()
        .header().keyId(KID).and()
        .issuer(issuer)
        .audience().add(audience).and()
        .subject(GOOGLE_USER_ID)
        .claim("azp", "android-client-id")
        .claim("email", "user@gmail.com")
        .claim("email_verified", emailVerified)
        .claim("name", "구글 사용자")
        .claim("picture", "https://lh3.googleusercontent.com/a/photo")
        .issuedAt(Date.from(Instant.now().minusSeconds(5)))
        .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
        .signWith(key.private, Jwts.SIG.RS256)
        .compact()

    @Test
    fun `aud가 Web 클라이언트 ID인 정상 토큰이면 구글 사용자 정보를 돌려준다`() {
        expectKeys()

        val profile = client().fetchProfile(idToken())

        assertEquals(AuthProvider.GOOGLE, profile.provider)
        assertEquals(GOOGLE_USER_ID, profile.providerId)
        assertEquals("user@gmail.com", profile.email)
        assertEquals("구글 사용자", profile.nickname)
        assertEquals("https://lh3.googleusercontent.com/a/photo", profile.profileImageUrl)
    }

    @Test
    fun `iss가 https 없는 accounts_google_com이어도 통과한다`() {
        expectKeys()

        client().fetchProfile(idToken(issuer = "accounts.google.com"))
    }

    @Test
    fun `aud가 우리 클라이언트 ID가 아니면 거절한다 (다른 앱이 받은 토큰)`() {
        expectKeys()

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile(idToken(audience = "other-app.apps.googleusercontent.com")) }
    }

    @Test
    fun `iss가 구글이 아니면 거절한다`() {
        expectKeys()

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile(idToken(issuer = "https://evil.example.com")) }
    }

    @Test
    fun `구글이 확인하지 않은 이메일은 쓰지 않는다`() {
        expectKeys()

        assertNull(client().fetchProfile(idToken(emailVerified = false)).email)
    }

    @Test
    fun `서명이 위조된 토큰은 거절한다`() {
        expectKeys()
        val forged = idToken().dropLast(4) + "AAAA"

        assertFailsWith<InvalidSnsTokenException> { client().fetchProfile(forged) }
    }

    @Test
    fun `허용할 클라이언트 ID가 설정되지 않으면 구글을 호출하지 않고 거절한다`() {
        assertFailsWith<InvalidSnsTokenException> { client(clientIds = "").fetchProfile(idToken()) }
        server.verify()
    }

    companion object {
        private const val CERTS_URL = "https://www.googleapis.test/oauth2/v3/certs"
        private const val WEB_CLIENT_ID = "1234-web.apps.googleusercontent.com"
        private const val GOOGLE_USER_ID = "110169484474386276334"
        private const val KID = "google-kid"
    }
}
