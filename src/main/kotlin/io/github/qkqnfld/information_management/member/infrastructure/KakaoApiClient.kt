package io.github.qkqnfld.information_management.member.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * 카카오 사용자 정보 API 연동.
 * 앱이 카카오 SDK로 받은 access 토큰을 카카오 서버에 직접 물어봐서 검증한다 —
 * 토큰이 진짜라면 카카오만이 그 사용자 정보를 돌려줄 수 있으므로, 이 호출 자체가 검증이다.
 * (user/me 호출에는 서버 쪽 앱 키가 필요 없다)
 */
@Component
class KakaoApiClient {

    private val restClient = RestClient.create("https://kapi.kakao.com")

    fun fetchProfile(kakaoAccessToken: String): OAuthProfile {
        val response = try {
            restClient.get()
                .uri("/v2/user/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $kakaoAccessToken")
                .retrieve()
                .body(KakaoUserResponse::class.java)
        } catch (e: RestClientException) {
            // 카카오가 401/400을 돌려줬다 = 위조·만료된 토큰
            throw InvalidSnsTokenException()
        } ?: throw InvalidSnsTokenException()

        return OAuthProfile(
            provider = AuthProvider.KAKAO,
            providerId = response.id.toString(),
            email = response.kakaoAccount?.email,
            nickname = response.kakaoAccount?.profile?.nickname,
        )
    }
}

/** 카카오 /v2/user/me 응답에서 우리가 쓰는 부분만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoUserResponse(
    val id: Long,
    @JsonProperty("kakao_account")
    val kakaoAccount: KakaoAccount?,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class KakaoAccount(
        val email: String?,
        val profile: KakaoProfile?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class KakaoProfile(
        val nickname: String?,
    )
}
