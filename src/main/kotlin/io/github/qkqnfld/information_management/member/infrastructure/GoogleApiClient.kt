package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

/**
 * 구글 idToken 검증.
 *
 * 구글이 공개한 서명용 공개키 목록(JWKS)으로 서버가 직접 서명을 검증한다 (Apple과 같은 방식).
 * 예전에는 tokeninfo 엔드포인트에 물어봤지만, 구글 문서는 그 방식을 "디버깅용, 운영 코드에 부적합"이라고 한다.
 * 구글 문서가 요구하는 검사: 서명, aud, iss, exp.
 *
 * aud는 "이 토큰을 받기로 한 앱"이다. 앱(Android Credential Manager의 setServerClientId,
 * iOS의 GIDServerClientID)이 Web 클라이언트 ID를 넘기면 aud가 그 값이 된다.
 * 그래서 서버는 Android·iOS 클라이언트 ID가 아니라 Web 클라이언트 ID와 비교한다.
 */
@Component
class GoogleApiClient(
    /** 허용할 클라이언트 ID 목록(쉼표 구분). 보통 Web 클라이언트 ID 하나다. 비어 있으면 구글 로그인을 모두 거절한다. */
    @Value("\${google.client-ids:}") clientIds: String,
    @Value("\${google.certs-url:https://www.googleapis.com/oauth2/v3/certs}") certsUrl: String,
    restClientBuilder: RestClient.Builder = defaultRestClientBuilder(),
    /** 모르는 kid가 왔을 때 공개키를 다시 받는 최소 간격 */
    keyRefreshInterval: Duration = Duration.ofMinutes(1),
) {

    private val log = LoggerFactory.getLogger(GoogleApiClient::class.java)

    private val allowedAudiences: Set<String> =
        clientIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private val keyLocator = JwksKeyLocator(restClientBuilder.build(), certsUrl, keyRefreshInterval)

    fun fetchProfile(idToken: String): OAuthProfile {
        if (allowedAudiences.isEmpty()) {
            log.warn("google.client-ids(GOOGLE_CLIENT_IDS)가 비어 있어 구글 로그인을 거절한다")
            throw InvalidSnsTokenException()
        }
        val claims = try {
            Jwts.parser()
                .keyLocator(keyLocator)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build()
                .parseSignedClaims(idToken)
                .payload
        } catch (e: JwtException) {
            throw InvalidSnsTokenException()
        } catch (e: IllegalArgumentException) {
            throw InvalidSnsTokenException()
        } catch (e: RestClientException) {
            log.warn("구글 공개키 목록을 받지 못했다: {}", e.message)
            throw InvalidSnsTokenException()
        }

        // 구글은 iss로 두 가지 형태를 쓴다
        if (claims.issuer !in ISSUERS) throw InvalidSnsTokenException()
        if (claims.expiration == null) throw InvalidSnsTokenException()
        if (claims.audience.orEmpty().none { it in allowedAudiences }) throw InvalidSnsTokenException()
        val subject = claims.subject ?: throw InvalidSnsTokenException()

        return OAuthProfile(
            provider = AuthProvider.GOOGLE,
            providerId = subject,
            // 구글이 확인한 이메일만 쓴다
            email = (claims[EMAIL_CLAIM] as? String)?.takeIf { claims[EMAIL_VERIFIED_CLAIM] == true },
            nickname = claims[NAME_CLAIM] as? String,
            profileImageUrl = claims[PICTURE_CLAIM] as? String,
        )
    }

    companion object {
        private val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
        private const val EMAIL_CLAIM = "email"
        private const val EMAIL_VERIFIED_CLAIM = "email_verified"
        private const val NAME_CLAIM = "name"
        private const val PICTURE_CLAIM = "picture"

        /** 서버와 구글의 시계 차이를 허용하는 범위(초) */
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
