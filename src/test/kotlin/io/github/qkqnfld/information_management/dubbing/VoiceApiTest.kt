package io.github.qkqnfld.information_management.dubbing

import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsErrorType
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsException
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * 목소리 API 통합 테스트.
 * TTS 업체(TtsClient)와 S3(AudioStorage)만 가짜로 바꾸고, 나머지는 실제 흐름(DB 포함)으로 검증한다.
 * DubbingJobApiTest와 같은 mock 구성을 쓴다. 그래서 Spring이 테스트 컨텍스트를 공유해 테스트가 빨라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var ttsClient: TtsClient

    @MockitoBean
    private lateinit var audioStorage: AudioStorage

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        token = registerAndLogin()
        whenever(ttsClient.cloneVoice(any(), any(), any(), anyOrNull())).thenReturn("uc_test_voice")
    }

    @Test
    fun `목록에는 AI 목소리가 있고 내 목소리는 처음에 비어 있다`() {
        mockMvc.perform(get("/voices").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.presets[0].id").value("DYLAN"))
            .andExpect(jsonPath("$.presets[0].name").value("딜런"))
            .andExpect(jsonPath("$.custom.length()").value(0))
    }

    @Test
    fun `녹음 파일로 내 목소리를 등록하면 201이고 목록에 보인다`() {
        registerVoice()
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.name").value("내 목소리"))
            // 업체 목소리 id는 앱에 노출하지 않는다
            .andExpect(jsonPath("$.providerVoiceId").doesNotExist())

        // 업체 쪽 이름에는 사용자가 정한 이름 대신 회원 id만 보낸다
        verify(ttsClient).cloneVoice(argThat { startsWith("member-") }, any(), eq("sample.wav"), eq("audio/wav"))

        mockMvc.perform(get("/voices").authed())
            .andExpect(jsonPath("$.custom.length()").value(1))
            .andExpect(jsonPath("$.custom[0].name").value("내 목소리"))
    }

    @Test
    fun `복제에 동의하지 않으면 400이고 업체를 호출하지 않는다`() {
        registerVoice(consent = "false")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("consentAgreed"))

        verifyNoInteractions(ttsClient)
    }

    @Test
    fun `녹음 파일이 없으면 400을 응답한다`() {
        mockMvc.perform(
            multipart("/voices")
                .param("name", "내 목소리")
                .param("consentAgreed", "true")
                .authed(),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("file"))
    }

    @Test
    fun `빈 녹음 파일이면 400을 응답한다`() {
        registerVoice(content = ByteArray(0))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("fileNotEmpty"))
    }

    @Test
    fun `내 목소리는 회원당 1개라서 두 번째 등록은 409를 응답한다`() {
        registerVoice().andExpect(status().isCreated)

        registerVoice()
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CUSTOM_VOICE_LIMIT_EXCEEDED"))
    }

    @Test
    fun `업체가 녹음 파일을 거절하면 400 INVALID_VOICE_SAMPLE을 응답한다`() {
        whenever(ttsClient.cloneVoice(any(), any(), any(), anyOrNull()))
            .thenThrow(TtsException(TtsErrorType.INVALID_INPUT, "bad audio"))

        registerVoice()
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_VOICE_SAMPLE"))
    }

    @Test
    fun `업체 장애면 503 VOICE_SERVICE_UNAVAILABLE을 응답하고 아무것도 저장하지 않는다`() {
        whenever(ttsClient.cloneVoice(any(), any(), any(), anyOrNull()))
            .thenThrow(TtsException(TtsErrorType.TEMPORARY, "timeout"))

        registerVoice()
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("VOICE_SERVICE_UNAVAILABLE"))

        mockMvc.perform(get("/voices").authed())
            .andExpect(jsonPath("$.custom.length()").value(0))
    }

    @Test
    fun `내 목소리를 삭제하면 업체 쪽 목소리도 지우고 다시 등록할 수 있다`() {
        val voiceId = registerVoiceId()

        mockMvc.perform(delete("/voices/$voiceId").authed())
            .andExpect(status().isNoContent)

        verify(ttsClient).deleteVoice("uc_test_voice")
        mockMvc.perform(get("/voices").authed())
            .andExpect(jsonPath("$.custom.length()").value(0))
        registerVoice().andExpect(status().isCreated)
    }

    @Test
    fun `남의 목소리는 삭제할 수 없고 404를 응답한다`() {
        val othersVoiceId = registerVoiceId(withToken = registerAndLogin())

        mockMvc.perform(delete("/voices/$othersVoiceId").authed())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CUSTOM_VOICE_NOT_FOUND"))
    }

    @Test
    fun `토큰 없이 목록을 요청하면 401을 응답한다`() {
        mockMvc.perform(get("/voices"))
            .andExpect(status().isUnauthorized)
    }

    // ==================== 도우미 ====================

    private fun registerVoice(
        withToken: String = token,
        consent: String = "true",
        content: ByteArray = byteArrayOf(1, 2, 3),
    ): ResultActions {
        return mockMvc.perform(
            multipart("/voices")
                .file(MockMultipartFile("file", "sample.wav", "audio/wav", content))
                .param("name", "내 목소리")
                .param("consentAgreed", consent)
                .authed(withToken),
        )
    }

    private fun registerVoiceId(withToken: String = token): Long {
        val body = registerVoice(withToken).andExpect(status().isCreated).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    private fun registerAndLogin(): String {
        val email = "voice-tester-${System.nanoTime()}@test.com"
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123","nickname":"목소리테스터"}"""),
        ).andExpect(status().isCreated)

        val body = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    /**
     * 요청에 인증 토큰을 싣는다. Spring 7에서는 일반 요청 빌더와 multipart 요청 빌더의
     * 공통 부모가 AbstractMockHttpServletRequestBuilder라서, 그 타입에 확장 함수를 건다.
     */
    private fun <B : AbstractMockHttpServletRequestBuilder<B>> B.authed(withToken: String = token): B {
        return this.header("Authorization", "Bearer $withToken")
    }
}
