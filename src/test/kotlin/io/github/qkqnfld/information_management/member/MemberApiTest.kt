package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.RefreshToken
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import io.github.qkqnfld.information_management.member.infrastructure.OAuthProfile
import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 토큰(재발급·로그아웃)과 내 정보·프로필 수정 API의 통합 테스트.
 * 로그인은 카카오 로그인을 쓴다. 카카오 API 클라이언트만 가짜로 바꾸고 나머지는 실제 흐름이다.
 * SNS 로그인 테스트와 같은 mock 구성을 써서 테스트 컨텍스트를 공유한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MemberApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var kakaoApiClient: KakaoApiClient

    @MockitoBean
    private lateinit var googleApiClient: GoogleApiClient

    @MockitoBean
    private lateinit var appleApiClient: AppleApiClient

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    /** 시간이 흐른 상황(유예 시간 경과, 만료 임박)을 만들려고 DB 값을 직접 바꾼다. */
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    /** 새 카카오 사용자로 로그인하고 (accessToken, refreshToken) 쌍을 돌려준다. */
    private fun kakaoLoginTokens(
        nickname: String? = "테스터",
        email: String? = null,
        profileImageUrl: String? = null,
        providerId: String = "member-${System.nanoTime()}",
    ): Pair<String, String> {
        val snsToken = "kakao-$providerId"
        whenever(kakaoApiClient.fetchProfile(snsToken)).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, email, nickname, profileImageUrl),
        )
        val body = mockMvc.perform(
            post("/auth/login/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$snsToken"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val access = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val refresh = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        return access to refresh
    }

    private fun refresh(refreshToken: String): ResultActions = mockMvc.perform(
        post("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"refreshToken":"$refreshToken"}"""),
    )

    /** 재발급에 성공하고 (accessToken, refreshToken) 쌍을 돌려준다. */
    private fun refreshTokens(refreshToken: String): Pair<String, String> {
        val body = refresh(refreshToken).andExpect(status().isOk).andReturn().response.contentAsString
        val access = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val refresh = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        return access to refresh
    }

    /** 이 토큰이 교체된 시각을 과거로 돌린다 (유예 시간이 지난 상황). */
    private fun pretendRotatedMinutesAgo(refreshToken: String, minutes: Long) {
        jdbcTemplate.update(
            "update refresh_tokens set rotated_at = ? where token_hash = ?",
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutes)),
            RefreshToken.hash(refreshToken),
        )
    }

    private fun updateProfile(access: String, json: String): ResultActions = mockMvc.perform(
        patch("/members/me")
            .header("Authorization", "Bearer $access")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json),
    )

    // ==================== 토큰 ====================

    @Test
    fun `refresh 토큰으로 새 access 토큰을 재발급받아 사용할 수 있다`() {
        val (_, refresh) = kakaoLoginTokens(nickname = "재발급테스터")

        val body = refresh(refresh)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andReturn().response.contentAsString
        val newAccess = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

        // 재발급받은 access 토큰이 실제로 동작하는지까지 확인
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $newAccess"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("재발급테스터"))
    }

    // ==================== refresh 토큰 교체 ====================

    @Test
    fun `재발급하면 새 refresh 토큰을 주고, 새 토큰으로 다시 재발급할 수 있다`() {
        val (_, first) = kakaoLoginTokens()

        val (_, second) = refreshTokens(first)
        val (_, third) = refreshTokens(second)

        assertNotEquals(first, second, "재발급하면 refresh 토큰이 바뀐다")
        assertNotEquals(second, third)
    }

    @Test
    fun `재발급한 새 refresh 토큰은 그 시점부터 다시 만료 기간을 받는다`() {
        val (_, old) = kakaoLoginTokens()
        // 만료가 하루 남은 토큰
        jdbcTemplate.update(
            "update refresh_tokens set expires_at = ? where token_hash = ?",
            Timestamp.valueOf(LocalDateTime.now().plusDays(1)),
            RefreshToken.hash(old),
        )

        val (_, new) = refreshTokens(old)

        // 테스트 설정의 refresh 수명은 7일이다
        val newExpiresAt = refreshTokenRepository.findByTokenHash(RefreshToken.hash(new))!!.expiresAt
        assertTrue(newExpiresAt.isAfter(LocalDateTime.now().plusDays(6)), "새 토큰은 만료 기간을 처음부터 다시 받는다")
    }

    @Test
    fun `교체된 이전 토큰도 유예 시간 안에는 다시 쓸 수 있다 (동시 재발급, 응답 유실 대비)`() {
        val (_, old) = kakaoLoginTokens()
        refreshTokens(old)

        // 응답을 못 받은 앱이 이전 토큰으로 다시 시도한다
        val (_, retried) = refreshTokens(old)

        refreshTokens(retried)
    }

    @Test
    fun `유예 시간이 지난 이전 토큰이 오면 401이고 그 로그인의 토큰이 모두 무효가 된다`() {
        val (_, old) = kakaoLoginTokens()
        val (_, current) = refreshTokens(old)
        pretendRotatedMinutesAgo(old, minutes = 2)

        // 이전 토큰 재사용 = 토큰이 새었다는 신호
        refresh(old)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))

        // 같은 로그인에서 이어진 최신 토큰도 무효가 된다 (훔친 사람이 가진 토큰일 수 있다)
        refresh(current).andExpect(status().isUnauthorized)
    }

    @Test
    fun `재사용 감지는 그 로그인의 토큰만 지우고 다른 기기의 로그인은 남긴다`() {
        val providerId = "two-devices-${System.nanoTime()}"
        val (_, phoneOld) = kakaoLoginTokens(providerId = providerId)
        val (_, tablet) = kakaoLoginTokens(providerId = providerId)
        refreshTokens(phoneOld)
        pretendRotatedMinutesAgo(phoneOld, minutes = 2)

        refresh(phoneOld).andExpect(status().isUnauthorized)

        refreshTokens(tablet)
    }

    @Test
    fun `로그아웃하면 유예 시간 중인 이전 토큰으로도 재발급할 수 없다`() {
        val (_, old) = kakaoLoginTokens()
        val (_, current) = refreshTokens(old)

        mockMvc.perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$current"}"""),
        ).andExpect(status().isNoContent)

        refresh(old).andExpect(status().isUnauthorized)
        refresh(current).andExpect(status().isUnauthorized)
    }

    @Test
    fun `DB에는 refresh 토큰 원문이 아니라 해시가 저장된다`() {
        val (_, refresh) = kakaoLoginTokens()

        assertNotNull(refreshTokenRepository.findByTokenHash(RefreshToken.hash(refresh)))
        val rawCount = jdbcTemplate.queryForObject(
            "select count(*) from refresh_tokens where token_hash = ?", Long::class.java, refresh,
        )
        assertEquals(0L, rawCount, "원문으로는 찾을 수 없어야 한다")
    }

    @Test
    fun `위조된 refresh 토큰으로 재발급을 시도하면 401을 응답한다`() {
        refresh("no-such-refresh-token")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `로그아웃하면 그 refresh 토큰으로는 더 이상 재발급할 수 없다`() {
        val (_, refresh) = kakaoLoginTokens()

        mockMvc.perform(
            post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$refresh"}"""),
        ).andExpect(status().isNoContent)

        refresh(refresh)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
    }

    @Test
    fun `토큰 없이 보호된 API에 접근하면 401과 UNAUTHORIZED 코드를 응답한다`() {
        mockMvc.perform(get("/members/me"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
    }

    @Test
    fun `위조된 토큰으로 접근하면 401을 응답한다`() {
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer fake.invalid.token"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `이메일 가입과 이메일 로그인 API는 없다`() {
        // 가입 API는 공개 경로가 아니므로 토큰 없이 부르면 401이다
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"a@test.com","password":"password123","nickname":"가입"}"""),
        ).andExpect(status().isUnauthorized)

        // /auth/**는 공개 경로라서 인증 단계를 지나고, 처리할 API가 없어 404다
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"a@test.com","password":"password123"}"""),
        ).andExpect(status().isNotFound)
    }

    // ==================== 내 정보·프로필 ====================

    @Test
    fun `내 정보에는 SNS가 준 이름, 이메일, 프로필 사진과 로그인 수단이 담긴다`() {
        val (access, _) = kakaoLoginTokens(
            nickname = "내정보테스터",
            email = "me@kakao.com",
            profileImageUrl = "https://k.kakaocdn.net/profile.jpg",
        )

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("내정보테스터"))
            .andExpect(jsonPath("$.email").value("me@kakao.com"))
            .andExpect(jsonPath("$.profileImageUrl").value("https://k.kakaocdn.net/profile.jpg"))
            .andExpect(jsonPath("$.providers[0]").value("KAKAO"))
    }

    @Test
    fun `프로필 수정으로 이름을 바꾸면 앞뒤 공백을 지우고 저장한다`() {
        val (access, _) = kakaoLoginTokens(nickname = "처음이름")

        updateProfile(access, """{"nickname":"  바꾼이름  "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nickname").value("바꾼이름"))

        mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(jsonPath("$.nickname").value("바꾼이름"))
    }

    @Test
    fun `프로필 수정의 이름이 비었거나 20자를 넘으면 400을 응답한다`() {
        val (access, _) = kakaoLoginTokens()

        updateProfile(access, """{"nickname":"   "}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"))

        updateProfile(access, """{"nickname":"${"가".repeat(21)}"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"))
    }
}
