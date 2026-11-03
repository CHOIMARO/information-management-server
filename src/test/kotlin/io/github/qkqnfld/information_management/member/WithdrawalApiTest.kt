package io.github.qkqnfld.information_management.member

import io.github.qkqnfld.information_management.dubbing.domain.CustomVoice
import io.github.qkqnfld.information_management.dubbing.domain.DubbingJob
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.CustomVoiceRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.DubbingJobRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import io.github.qkqnfld.information_management.member.application.WithdrawalTaskWorker
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskStatus
import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskType
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.AppleRevokeToken
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import io.github.qkqnfld.information_management.member.infrastructure.OAuthProfile
import io.github.qkqnfld.information_management.member.infrastructure.WithdrawalTaskRepository
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClientException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 회원 탈퇴 API와 탈퇴 후처리 Worker의 통합 테스트.
 * SNS 클라이언트, TTS 업체, S3만 가짜로 바꾼다.
 * Worker는 자동 실행을 막아 두고(test application.properties), processNext()를 직접 불러 처리 시점을 통제한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WithdrawalApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var worker: WithdrawalTaskWorker

    @Autowired
    private lateinit var withdrawalTaskRepository: WithdrawalTaskRepository

    @Autowired
    private lateinit var authCredentialRepository: AuthCredentialRepository

    @Autowired
    private lateinit var dubbingJobRepository: DubbingJobRepository

    @Autowired
    private lateinit var customVoiceRepository: CustomVoiceRepository

    @MockitoBean
    private lateinit var kakaoApiClient: KakaoApiClient

    @MockitoBean
    private lateinit var googleApiClient: GoogleApiClient

    @MockitoBean
    private lateinit var appleApiClient: AppleApiClient

    @MockitoBean
    private lateinit var ttsClient: TtsClient

    @MockitoBean
    private lateinit var audioStorage: AudioStorage

    /** Worker는 회원과 상관없이 가장 오래된 작업부터 꺼낸다. 이전 테스트의 작업이 섞이지 않게 비운다. */
    @BeforeEach
    fun setUp() {
        withdrawalTaskRepository.deleteAll()
    }

    // ==================== 탈퇴 요청 ====================

    @Test
    fun `탈퇴하면 204를 응답하고 남은 access 토큰과 refresh 토큰은 바로 쓸 수 없다`() {
        val login = kakaoLogin()

        withdraw(login.access).andExpect(status().isNoContent)

        // access 토큰은 수명이 남았어도 회원이 없어서 거절된다 (계정 잠금)
        mockMvc.perform(get("/members/me").header("Authorization", "Bearer ${login.access}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${login.refresh}"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `탈퇴한 뒤 같은 카카오 계정으로 로그인하면 새 계정으로 가입된다`() {
        val login = kakaoLogin()
        val oldMemberId = memberIdOf(login.access)

        withdraw(login.access).andExpect(status().isNoContent)
        val again = loginWith(login.snsToken)

        assertNotEquals(oldMemberId, memberIdOf(again), "탈퇴 후 다시 가입하면 새 계정이어야 한다")
    }

    @Test
    fun `탈퇴하면 카카오 연결 끊기와 도메인별 데이터 정리 작업이 등록된다`() {
        val login = kakaoLogin()
        val memberId = memberIdOf(login.access)

        withdraw(login.access).andExpect(status().isNoContent)

        val tasks = withdrawalTaskRepository.findAllByMemberId(memberId)
        assertEquals(
            setOf(
                WithdrawalTaskType.KAKAO_UNLINK to login.providerId,
                WithdrawalTaskType.DATA_CLEANUP to "dubbing",
                WithdrawalTaskType.DATA_CLEANUP to "memo",
            ),
            tasks.map { it.type to it.target }.toSet(),
        )
        assertTrue(tasks.all { it.status == WithdrawalTaskStatus.PENDING })
        assertTrue(authCredentialRepository.findByMemberId(memberId).isEmpty(), "로그인 수단은 바로 지운다")
    }

    // ==================== 후처리 Worker ====================

    @Test
    fun `Worker가 카카오 연결을 끊고 끝난 작업에서는 카카오 회원번호를 지운다`() {
        val login = kakaoLogin()
        val memberId = memberIdOf(login.access)
        withdraw(login.access).andExpect(status().isNoContent)

        processAll()

        verify(kakaoApiClient).unlink(login.providerId)
        val tasks = withdrawalTaskRepository.findAllByMemberId(memberId)
        assertTrue(tasks.all { it.status == WithdrawalTaskStatus.COMPLETED })
        assertTrue(tasks.all { it.target == null }, "끝난 작업에는 개인정보를 남기지 않는다")
    }

    @Test
    fun `Worker가 더빙 작업과 S3 파일, 내 목소리와 업체 목소리를 지운다`() {
        val login = kakaoLogin()
        val memberId = memberIdOf(login.access)
        val audioKey = "dubbings/$memberId/1.mp3"
        dubbingJobRepository.save(
            DubbingJob(memberId = memberId, text = "탈퇴 전 더빙", presetVoice = PresetVoice.SEOHYEON).apply {
                start()
                complete(audioKey)
            },
        )
        customVoiceRepository.save(
            CustomVoice(memberId = memberId, name = "내 목소리", providerVoiceId = "uc_withdraw", consentedAt = LocalDateTime.now()),
        )

        withdraw(login.access).andExpect(status().isNoContent)
        processAll()

        verify(audioStorage).delete(audioKey)
        verify(ttsClient).deleteVoice("uc_withdraw")
        assertTrue(dubbingJobRepository.findAllByMemberId(memberId).isEmpty())
        assertTrue(customVoiceRepository.findAllByMemberIdOrderByIdAsc(memberId).isEmpty())
    }

    @Test
    fun `Apple 로그인 때 받은 토큰으로 탈퇴할 때 Apple 토큰을 취소한다`() {
        val providerId = "apple-${System.nanoTime()}"
        whenever(appleApiClient.fetchProfile("apple-identity-token", null)).thenReturn(
            OAuthProfile(AuthProvider.APPLE, providerId, null, null, clientId = "com.test.app"),
        )
        whenever(appleApiClient.canManageTokens).thenReturn(true)
        whenever(appleApiClient.exchangeAuthorizationCode("auth-code", "com.test.app")).thenReturn("apple-refresh-token")
        val body = mockMvc.perform(
            post("/auth/login/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"apple-identity-token","authorizationCode":"auth-code"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val access = accessTokenOf(body)

        withdraw(access).andExpect(status().isNoContent)
        processAll()

        verify(appleApiClient).revoke(AppleRevokeToken(clientId = "com.test.app", refreshToken = "apple-refresh-token"))
    }

    @Test
    fun `카카오 연결 끊기가 실패하면 작업을 대기 상태로 되돌리고 나중에 다시 시도한다`() {
        val login = kakaoLogin()
        val memberId = memberIdOf(login.access)
        doThrow(RestClientException("카카오 장애")).whenever(kakaoApiClient).unlink(login.providerId)
        withdraw(login.access).andExpect(status().isNoContent)

        processAll()

        val kakaoTask = withdrawalTaskRepository.findAllByMemberId(memberId).single { it.type == WithdrawalTaskType.KAKAO_UNLINK }
        assertEquals(WithdrawalTaskStatus.PENDING, kakaoTask.status)
        assertEquals(1, kakaoTask.attempts)
        assertEquals("카카오 장애", kakaoTask.lastError)
        assertTrue(kakaoTask.nextAttemptAt.isAfter(LocalDateTime.now()), "다음 시도는 간격을 두고 한다")
        assertEquals(login.providerId, kakaoTask.target, "다시 시도할 수 있게 대상을 남긴다")
        assertNull(kakaoTask.completedAt)
    }

    // ==================== 도우미 ====================

    private data class KakaoLogin(val snsToken: String, val providerId: String, val access: String, val refresh: String)

    private fun kakaoLogin(): KakaoLogin {
        val providerId = "withdraw-${System.nanoTime()}"
        val snsToken = "kakao-$providerId"
        whenever(kakaoApiClient.fetchProfile(snsToken)).thenReturn(
            OAuthProfile(AuthProvider.KAKAO, providerId, null, "탈퇴테스터"),
        )
        val body = mockMvc.perform(
            post("/auth/login/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$snsToken"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val refresh = Regex("\"refreshToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        return KakaoLogin(snsToken, providerId, accessTokenOf(body), refresh)
    }

    private fun loginWith(snsToken: String): String {
        val body = mockMvc.perform(
            post("/auth/login/kakao")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"$snsToken"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return accessTokenOf(body)
    }

    private fun withdraw(access: String) = mockMvc.perform(
        delete("/members/me").header("Authorization", "Bearer $access"),
    )

    /** 지금 처리할 수 있는 작업을 모두 처리한다. 실패한 작업은 다음 시도 시각이 미래라서 다시 꺼내지 않는다. */
    private fun processAll() {
        repeat(withdrawalTaskRepository.count().toInt()) { worker.processNext() }
    }

    private fun accessTokenOf(body: String): String = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]

    private fun memberIdOf(access: String): Long {
        val body = mockMvc.perform(get("/members/me").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }
}
