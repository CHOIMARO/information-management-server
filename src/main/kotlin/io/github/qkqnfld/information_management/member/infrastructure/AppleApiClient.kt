package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.jsonwebtoken.JwsHeader
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.LocatorAdapter
import io.jsonwebtoken.security.Jwks
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.security.Key
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Apple 로그인(Sign in with Apple) identityToken 검증.
 *
 * 카카오·구글과 달리 Apple에는 "이 토큰이 진짜인지" 물어볼 API가 없다.
 * 대신 Apple이 공개한 서명용 공개키 목록(JWKS)으로 서버가 직접 JWT 서명을 검증한다.
 * Apple 문서가 요구하는 검사: 서명(헤더 kid와 같은 키), iss, aud, exp, nonce.
 *
 * aud 검사가 핵심이다. aud는 토큰을 받은 앱의 ID(iOS는 번들 ID)다.
 * 이 검사가 없으면 다른 앱이 받은 토큰으로도 우리 서비스에 로그인할 수 있다.
 */
@Component
class AppleApiClient(
    @Value("\${apple.base-url:https://appleid.apple.com}") baseUrl: String,
    /** 허용할 앱 ID 목록(쉼표 구분). 비어 있으면 Apple 로그인을 모두 거절한다. */
    @Value("\${apple.client-ids:}") clientIds: String,
    restClientBuilder: RestClient.Builder = defaultRestClientBuilder(),
    /** 모르는 kid가 왔을 때 공개키를 다시 받는 최소 간격. 가짜 kid로 Apple 호출을 반복시키는 것을 막는다. */
    private val keyRefreshInterval: Duration = Duration.ofMinutes(1),
) {

    private val log = LoggerFactory.getLogger(AppleApiClient::class.java)

    private val allowedAudiences: Set<String> =
        clientIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val restClient = restClientBuilder.baseUrl(baseUrl).build()

    /** kid → 공개키. Apple은 키를 가끔 교체하므로, 모르는 kid가 오면 목록을 다시 받는다. */
    @Volatile
    private var keys: Map<String, Key> = emptyMap()

    @Volatile
    private var keysFetchedAt: Instant = Instant.EPOCH

    /** 토큰 헤더의 kid로 검증 키를 고른다. RS256 외의 알고리즘은 거절한다. */
    private val keyLocator = object : LocatorAdapter<Key>() {
        override fun locate(header: JwsHeader): Key {
            if (header.algorithm != SIGNATURE_ALGORITHM) throw InvalidSnsTokenException()
            val kid = header.keyId ?: throw InvalidSnsTokenException()
            return keys[kid] ?: refreshKeys()[kid] ?: throw InvalidSnsTokenException()
        }
    }

    /**
     * identityToken을 검증하고 공통 프로필로 바꾼다.
     * @param rawNonce 앱이 만든 원본 nonce. 보내면 토큰의 nonce가 SHA-256(rawNonce)와 같은지 확인한다.
     */
    fun fetchProfile(identityToken: String, rawNonce: String?): OAuthProfile {
        if (allowedAudiences.isEmpty()) {
            log.warn("apple.client-ids(APPLE_CLIENT_IDS)가 비어 있어 Apple 로그인을 거절한다")
            throw InvalidSnsTokenException()
        }
        val claims = try {
            Jwts.parser()
                .keyLocator(keyLocator)
                .requireIssuer(ISSUER)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(identityToken)
                .payload
        } catch (e: JwtException) {
            throw InvalidSnsTokenException()
        } catch (e: IllegalArgumentException) {
            throw InvalidSnsTokenException()
        } catch (e: RestClientException) {
            // 공개키 목록을 받지 못했다. 다른 SNS 클라이언트와 같은 방식으로 처리한다.
            log.warn("Apple 공개키 목록을 받지 못했다: {}", e.message)
            throw InvalidSnsTokenException()
        }

        // exp가 있으면 parser가 만료를 검사한다. exp가 없는 토큰은 영원히 유효하므로 거절한다.
        if (claims.expiration == null) throw InvalidSnsTokenException()
        if (claims.audience.orEmpty().none { it in allowedAudiences }) throw InvalidSnsTokenException()
        if (rawNonce != null && claims[NONCE_CLAIM] != sha256Hex(rawNonce)) throw InvalidSnsTokenException()
        val subject = claims.subject ?: throw InvalidSnsTokenException()

        return OAuthProfile(
            provider = AuthProvider.APPLE,
            providerId = subject,
            // 사용자가 이메일 숨기기를 고르면 Apple의 대리 주소(...@privaterelay.appleid.com)가 온다
            email = claims[EMAIL_CLAIM] as? String,
            // Apple 토큰에는 이름이 없다. 이름은 앱이 첫 로그인 때만 받아서 요청에 담아 보낸다.
            nickname = null,
        )
    }

    /**
     * 공개키 목록을 다시 받는다. 여러 요청이 동시에 와도 한 번만 받도록 잠근다.
     * 마지막으로 받은 지 keyRefreshInterval이 지나지 않았으면 다시 받지 않고 지금 목록을 돌려준다.
     */
    @Synchronized
    private fun refreshKeys(): Map<String, Key> {
        val now = Instant.now()
        if (keys.isNotEmpty() && now.isBefore(keysFetchedAt.plus(keyRefreshInterval))) {
            return keys
        }
        val json = restClient.get().uri(KEYS_PATH).retrieve().body(String::class.java)
            ?: throw InvalidSnsTokenException()
        val jwkSet = Jwks.setParser().build().parse(json)
        // JwkSet은 Map이기도 해서 Kotlin의 jwkSet.keys는 Map의 키 이름 목록이 된다. JWK 목록은 getKeys()로 꺼낸다.
        keys = jwkSet.getKeys().mapNotNull { jwk -> jwk.id?.let { kid -> kid to jwk.toKey() } }.toMap()
        keysFetchedAt = now
        return keys
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val ISSUER = "https://appleid.apple.com"
        private const val KEYS_PATH = "/auth/keys"
        private const val SIGNATURE_ALGORITHM = "RS256"
        private const val NONCE_CLAIM = "nonce"
        private const val EMAIL_CLAIM = "email"

        /** 서버와 Apple의 시계 차이를 허용하는 범위(초) */
        private const val CLOCK_SKEW_SECONDS = 60L

        private fun defaultRestClientBuilder(): RestClient.Builder {
            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(Duration.ofSeconds(5))
            }
            return RestClient.builder().requestFactory(requestFactory)
        }
    }
}
