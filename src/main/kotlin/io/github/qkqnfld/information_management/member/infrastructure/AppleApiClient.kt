package io.github.qkqnfld.information_management.member.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Apple 로그인(Sign in with Apple) 연동.
 *
 * 1. identityToken 검증 (fetchProfile)
 *    카카오·구글과 달리 Apple에는 "이 토큰이 진짜인지" 물어볼 API가 없다.
 *    대신 Apple이 공개한 서명용 공개키 목록(JWKS)으로 서버가 직접 JWT 서명을 검증한다.
 *    Apple 문서가 요구하는 검사: 서명(헤더 kid와 같은 키), iss, aud, exp, nonce.
 *    aud 검사가 핵심이다. aud는 토큰을 받은 앱의 ID(iOS는 번들 ID)다.
 *    이 검사가 없으면 다른 앱이 받은 토큰으로도 우리 서비스에 로그인할 수 있다.
 *
 * 2. authorizationCode 교환과 토큰 취소 (exchangeAuthorizationCode, revoke)
 *    탈퇴할 때 Apple 토큰을 취소해야 한다 (App Store 계정 삭제 정책).
 *    취소에는 Apple refresh 토큰이 필요하고, refresh 토큰은 로그인 때 받은 authorizationCode로만 얻는다.
 *    두 요청 모두 client_secret이 필요하다. client_secret은 Apple 개발자 계정의 비밀키(.p8)로 서명한 JWT다.
 */
@Component
class AppleApiClient(
    @Value("\${apple.base-url:https://appleid.apple.com}") baseUrl: String,
    /** 허용할 앱 ID 목록(쉼표 구분). 비어 있으면 Apple 로그인을 모두 거절한다. */
    @Value("\${apple.client-ids:}") clientIds: String,
    /** Apple 개발자 계정의 Team ID (10자). client_secret의 iss가 된다. */
    @Value("\${apple.team-id:}") private val teamId: String = "",
    /** .p8 비밀키의 Key ID (10자). client_secret 헤더의 kid가 된다. */
    @Value("\${apple.key-id:}") private val keyId: String = "",
    /** .p8 파일 내용 (PEM). 환경변수에서는 줄바꿈을 \n으로 적어도 된다. */
    @Value("\${apple.private-key:}") privateKey: String = "",
    restClientBuilder: RestClient.Builder = defaultRestClientBuilder(),
    /** 모르는 kid가 왔을 때 공개키를 다시 받는 최소 간격. 가짜 kid로 Apple 호출을 반복시키는 것을 막는다. */
    keyRefreshInterval: Duration = Duration.ofMinutes(1),
) {

    private val log = LoggerFactory.getLogger(AppleApiClient::class.java)

    private val allowedAudiences: Set<String> =
        clientIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val restClient = restClientBuilder.baseUrl(baseUrl).build()

    private val keyLocator = JwksKeyLocator(restClient, KEYS_PATH, keyRefreshInterval)

    private val signingKey: PrivateKey? = privateKey.takeIf { it.isNotBlank() }?.let { parsePrivateKey(it) }

    /** 토큰 교환·취소에 필요한 값(Team ID, Key ID, 비밀키)이 모두 있는지 */
    val canManageTokens: Boolean
        get() = teamId.isNotBlank() && keyId.isNotBlank() && signingKey != null

    // ==================== identityToken 검증 ====================

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
        val clientId = claims.audience.orEmpty().firstOrNull { it in allowedAudiences }
            ?: throw InvalidSnsTokenException()
        if (rawNonce != null && claims[NONCE_CLAIM] != sha256Hex(rawNonce)) throw InvalidSnsTokenException()
        val subject = claims.subject ?: throw InvalidSnsTokenException()

        return OAuthProfile(
            provider = AuthProvider.APPLE,
            providerId = subject,
            // 사용자가 이메일 숨기기를 고르면 Apple의 대리 주소(...@privaterelay.appleid.com)가 온다
            email = claims[EMAIL_CLAIM] as? String,
            // Apple 토큰에는 이름이 없다. 이름은 앱이 첫 로그인 때만 받아서 요청에 담아 보낸다.
            nickname = null,
            clientId = clientId,
        )
    }

    // ==================== 토큰 교환과 취소 ====================

    /**
     * 앱이 받은 authorizationCode를 Apple refresh 토큰으로 바꾼다.
     * code는 5분 동안 한 번만 쓸 수 있다. 그래서 로그인 요청을 받은 즉시 교환한다.
     *
     * @param clientId 토큰을 받은 앱의 ID (fetchProfile이 돌려준 clientId)
     * @throws IllegalStateException 교환에 필요한 설정이 없을 때
     * @throws RestClientException Apple이 거절했거나 응답하지 않았을 때
     */
    fun exchangeAuthorizationCode(authorizationCode: String, clientId: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", clientId)
            add("client_secret", createClientSecret(clientId))
            add("code", authorizationCode)
            add("grant_type", "authorization_code")
        }
        val response = restClient.post()
            .uri(TOKEN_PATH)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(AppleTokenResponse::class.java)
        return response?.refreshToken ?: throw IllegalStateException("Apple 응답에 refresh_token이 없습니다")
    }

    /**
     * Apple 토큰을 취소한다. 사용자의 Apple ID 설정에서 이 앱의 연결이 사라진다.
     * 이미 취소된 토큰이어도 Apple은 성공(200)을 돌려준다. 그래서 다시 시도해도 안전하다.
     *
     * @throws IllegalStateException 취소에 필요한 설정이 없을 때
     * @throws RestClientException Apple이 거절했거나 응답하지 않았을 때
     */
    fun revoke(token: AppleRevokeToken) {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("client_id", token.clientId)
            add("client_secret", createClientSecret(token.clientId))
            add("token", token.refreshToken)
            add("token_type_hint", "refresh_token")
        }
        restClient.post()
            .uri(REVOKE_PATH)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .toBodilessEntity()
    }

    /**
     * client_secret JWT를 만든다 (ES256, 유효 5분).
     * Apple은 최대 6개월까지 허용하지만, 요청마다 짧게 만들면 새어도 피해가 작다.
     */
    private fun createClientSecret(clientId: String): String {
        check(canManageTokens) { "apple.team-id, apple.key-id, apple.private-key가 모두 필요합니다" }
        val key = checkNotNull(signingKey)
        val now = Instant.now()
        return Jwts.builder()
            .header().keyId(keyId).and()
            .issuer(teamId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(CLIENT_SECRET_LIFETIME)))
            .audience().add(ISSUER).and()
            .subject(clientId)
            .signWith(key, Jwts.SIG.ES256)
            .compact()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val ISSUER = "https://appleid.apple.com"
        private const val KEYS_PATH = "/auth/keys"
        private const val TOKEN_PATH = "/auth/token"
        private const val REVOKE_PATH = "/auth/revoke"
        private const val NONCE_CLAIM = "nonce"
        private const val EMAIL_CLAIM = "email"
        private val CLIENT_SECRET_LIFETIME: Duration = Duration.ofMinutes(5)

        /** 서버와 Apple의 시계 차이를 허용하는 범위(초) */
        private const val CLOCK_SKEW_SECONDS = 60L

        /** .p8 파일(PKCS#8 PEM)의 EC 비밀키를 읽는다. */
        private fun parsePrivateKey(pem: String): PrivateKey {
            val base64 = pem.replace("\\n", "\n")
                .lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
                .filterNot { it.isWhitespace() }
            val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
            return KeyFactory.getInstance("EC").generatePrivate(spec)
        }

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

/**
 * Apple 토큰 취소에 필요한 값 한 벌. 암호화해서 AuthCredential.revokeToken에 저장한다.
 * 취소 요청의 client_id는 토큰을 받은 앱과 같아야 해서 refresh 토큰과 함께 보관한다.
 */
data class AppleRevokeToken(
    val clientId: String,
    val refreshToken: String,
) {
    /** 저장용 문자열. 앱 ID에는 '|'가 들어가지 않으므로 구분자로 쓴다. */
    fun encode(): String = "$clientId$SEPARATOR$refreshToken"

    companion object {
        private const val SEPARATOR = "|"

        fun decode(value: String): AppleRevokeToken {
            val clientId = value.substringBefore(SEPARATOR)
            val refreshToken = value.substringAfter(SEPARATOR, missingDelimiterValue = "")
            require(clientId.isNotEmpty() && refreshToken.isNotEmpty()) { "Apple 철회용 토큰 형식이 올바르지 않습니다" }
            return AppleRevokeToken(clientId, refreshToken)
        }
    }
}

/** Apple 토큰 교환 응답에서 우리가 쓰는 부분만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class AppleTokenResponse(
    @JsonProperty("refresh_token")
    val refreshToken: String?,
)
