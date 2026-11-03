package io.github.qkqnfld.information_management.member.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.InvalidSnsTokenException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.time.Duration

/**
 * 카카오 API 연동.
 *
 * 1. 로그인 토큰 검증 (fetchProfile)
 *    앱이 카카오 SDK로 받은 access 토큰을 카카오 서버에 직접 물어봐서 검증한다.
 *    토큰이 진짜라면 카카오만이 그 사용자 정보를 돌려줄 수 있으므로, 이 호출 자체가 검증이다.
 *    추가로 토큰 정보 API의 app_id가 우리 앱 ID와 같은지 확인한다.
 *    이 검사가 없으면 다른 앱이 받은 카카오 토큰으로도 우리 서비스에 로그인할 수 있다.
 *
 * 2. 연결 끊기 (unlink)
 *    탈퇴할 때 서버가 어드민 키로 카카오 연결을 끊는다. 이때는 사용자의 토큰이 없어도 된다.
 *    어드민 키는 모든 사용자를 다룰 수 있는 키라서 서버에만 둔다 (카카오 문서 요구 사항).
 */
@Component
class KakaoApiClient(
    /** 카카오 앱 ID (숫자). 내 애플리케이션 > 앱 설정 > 요약 정보에 있다. 앱 키가 아니다. 비어 있으면 카카오 로그인을 모두 거절한다. */
    @Value("\${kakao.app-id:}") appId: String,
    /** 어드민 키. 연결 끊기에만 쓴다. */
    @Value("\${kakao.admin-key:}") private val adminKey: String = "",
    @Value("\${kakao.base-url:https://kapi.kakao.com}") baseUrl: String = "https://kapi.kakao.com",
    restClientBuilder: RestClient.Builder = defaultRestClientBuilder(),
) {

    private val log = LoggerFactory.getLogger(KakaoApiClient::class.java)

    private val appId: Long? = appId.trim().toLongOrNull()

    private val restClient = restClientBuilder.baseUrl(baseUrl).build()

    fun fetchProfile(kakaoAccessToken: String): OAuthProfile {
        val expectedAppId = appId ?: run {
            log.warn("kakao.app-id(KAKAO_APP_ID)가 비어 있거나 숫자가 아니어서 카카오 로그인을 거절한다")
            throw InvalidSnsTokenException()
        }

        val tokenInfo = get("/v1/user/access_token_info", kakaoAccessToken, KakaoTokenInfoResponse::class.java)
        if (tokenInfo.appId != expectedAppId) throw InvalidSnsTokenException()

        val user = get("/v2/user/me", kakaoAccessToken, KakaoUserResponse::class.java)
        if (user.id != tokenInfo.id) throw InvalidSnsTokenException()

        val profile = user.kakaoAccount?.profile
        return OAuthProfile(
            provider = AuthProvider.KAKAO,
            providerId = user.id.toString(),
            email = user.kakaoAccount?.email,
            nickname = profile?.nickname,
            // 기본 프로필 사진이면 카카오가 기본 이미지 주소를 준다. 앱이 기본 아바타를 쓰도록 비운다.
            profileImageUrl = profile?.profileImageUrl?.takeUnless { profile.isDefaultImage == true },
        )
    }

    /**
     * 카카오 연결을 끊는다. 이미 끊긴 사용자면 성공으로 처리한다 (다시 시도해도 안전하게).
     *
     * @param kakaoUserId 카카오 회원번호 (AuthCredential.providerId)
     * @throws IllegalStateException 어드민 키가 없을 때
     * @throws RestClientException 카카오가 거절했거나 응답하지 않았을 때
     */
    fun unlink(kakaoUserId: String) {
        check(adminKey.isNotBlank()) { "kakao.admin-key(KAKAO_ADMIN_KEY)가 비어 있어 카카오 연결을 끊을 수 없습니다" }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("target_id_type", "user_id")
            add("target_id", kakaoUserId)
        }
        try {
            restClient.post()
                .uri("/v1/user/unlink")
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK $adminKey")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            // -101: 이 앱에 연결되지 않은 사용자. 이미 끊겼으므로 목적을 이뤘다.
            if (e.responseBodyAsString.contains(NOT_REGISTERED_USER_CODE)) return
            throw e
        }
    }

    private fun <T : Any> get(path: String, kakaoAccessToken: String, type: Class<T>): T {
        return try {
            restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $kakaoAccessToken")
                .retrieve()
                .body(type)
        } catch (e: RestClientException) {
            // 카카오가 401/400을 돌려줬다 = 위조·만료된 토큰
            throw InvalidSnsTokenException()
        } ?: throw InvalidSnsTokenException()
    }

    companion object {
        private const val NOT_REGISTERED_USER_CODE = "\"code\":-101"

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

/** 카카오 /v1/user/access_token_info 응답. app_id는 토큰이 발급된 앱의 ID다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KakaoTokenInfoResponse(
    /** 카카오 회원번호 */
    val id: Long,
    @JsonProperty("app_id")
    val appId: Long,
)

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
        @JsonProperty("profile_image_url")
        val profileImageUrl: String? = null,
        @JsonProperty("is_default_image")
        val isDefaultImage: Boolean? = null,
    )
}
