package io.github.qkqnfld.information_management.member.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 구글 idToken 검증.
 * 구글 로그인은 access 토큰이 아니라 idToken(JWT)을 주는데, 구글의 tokeninfo 엔드포인트에
 * 물어보면 서명·만료를 구글이 검증해 준다 (무효면 400).
 * 운영에서 트래픽이 커지면 구글 공개키로 서버가 직접 서명을 검증하는 방식(google-api-client
 * 라이브러리)으로 바꾸고, 등록된 앱의 client id와 aud 클레임이 일치하는지도 검증해야 한다 —
 * 구글 클라우드 콘솔에 앱을 등록하는 시점에 함께 다룬다.
 */
@Component
class GoogleApiClient {

    private val restClient = RestClient.create("https://oauth2.googleapis.com")

    fun fetchProfile(idToken: String): OAuthProfile {
        val response = try {
            restClient.get()
                .uri("/tokeninfo?id_token={token}", idToken)
                .retrieve()
                .body(GoogleTokenInfoResponse::class.java)
        } catch (e: RestClientException) {
            throw InvalidSnsTokenException()
        } ?: throw InvalidSnsTokenException()

        return OAuthProfile(
            provider = AuthProvider.GOOGLE,
            providerId = response.sub,
            email = response.email,
            nickname = response.name,
        )
    }
}

/** 구글 tokeninfo 응답에서 우리가 쓰는 부분만 매핑한다. sub가 구글 쪽 사용자 고유 id다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GoogleTokenInfoResponse(
    val sub: String,
    val email: String?,
    val name: String?,
)
