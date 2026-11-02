package io.github.qkqnfld.information_management.dubbing

import io.github.qkqnfld.information_management.dubbing.application.DubbingJobWorker
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import io.github.qkqnfld.information_management.dubbing.infrastructure.AudioStorage
import io.github.qkqnfld.information_management.dubbing.infrastructure.DubbingJobRepository
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsClient
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsErrorType
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsException
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsVoice
import org.hamcrest.Matchers.endsWith
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * 더빙 작업 API 통합 테스트.
 * TTS 업체(TtsClient)와 S3(AudioStorage)만 가짜로 바꾼다.
 * Worker는 자동 실행을 막아 두고(test application.properties), processNext()를 직접 불러 처리 시점을 통제한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DubbingJobApiTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var worker: DubbingJobWorker

    @Autowired
    private lateinit var dubbingJobRepository: DubbingJobRepository

    @MockitoBean
    private lateinit var ttsClient: TtsClient

    @MockitoBean
    private lateinit var audioStorage: AudioStorage

    private lateinit var token: String

    /**
     * Worker는 회원과 상관없이 가장 오래된 작업부터 꺼낸다.
     * 이전 테스트가 남긴 작업이 먼저 꺼내지지 않게 테스트마다 작업 테이블을 비운다.
     */
    @BeforeEach
    fun setUp() {
        dubbingJobRepository.deleteAll()
        token = registerAndLogin()
        whenever(ttsClient.synthesize(any(), any())).thenReturn(byteArrayOf(1, 2, 3))
        whenever(audioStorage.createDownloadUrl(any())).thenReturn("https://s3.test/presigned-url")
    }

    @Test
    fun `AI 목소리로 더빙을 요청하면 202와 PENDING 상태, 상태 조회 주소를 응답한다`() {
        createJob("""{"text":"안녕하세요","presetVoice":"DYLAN"}""")
            .andExpect(status().isAccepted)
            .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/dubbing-jobs/")))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.presetVoice").value("DYLAN"))
            .andExpect(jsonPath("$.audioUrl").doesNotExist())
    }

    @Test
    fun `Worker가 처리하면 COMPLETED가 되고 다운로드 URL이 생긴다`() {
        val jobId = createJobId("""{"text":"안녕하세요","presetVoice":"DYLAN"}""")

        worker.processNext()

        verify(ttsClient).synthesize("안녕하세요", TtsVoice.Preset(PresetVoice.DYLAN))
        verify(audioStorage).upload(argThat { startsWith("dubbings/") && endsWith("/$jobId.mp3") }, any(), eq("audio/mpeg"))
        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.audioUrl").value("https://s3.test/presigned-url"))
            .andExpect(jsonPath("$.completedAt").isNotEmpty)
    }

    @Test
    fun `내 목소리로 요청하면 업체 목소리 id로 합성한다`() {
        val voiceId = registerVoice("uc_my_voice")
        val jobId = createJobId("""{"text":"내 목소리 테스트","customVoiceId":$voiceId}""")

        worker.processNext()

        verify(ttsClient).synthesize("내 목소리 테스트", TtsVoice.Custom("uc_my_voice"))
        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.customVoiceId").value(voiceId))
    }

    @Test
    fun `남의 목소리로는 더빙할 수 없고 404를 응답한다`() {
        val othersVoiceId = registerVoice("uc_others", withToken = registerAndLogin())

        createJob("""{"text":"훔친 목소리","customVoiceId":$othersVoiceId}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CUSTOM_VOICE_NOT_FOUND"))
    }

    @Test
    fun `목소리를 둘 다 지정하거나 하나도 지정하지 않으면 400을 응답한다`() {
        createJob("""{"text":"둘 다","presetVoice":"DYLAN","customVoiceId":1}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("voiceSelectedOnce"))

        createJob("""{"text":"하나도 없음"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("voiceSelectedOnce"))
    }

    @Test
    fun `텍스트가 비었거나 2000자를 넘으면 400을 응답한다`() {
        createJob("""{"text":"  ","presetVoice":"DYLAN"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("text"))

        createJob("""{"text":"${"가".repeat(2001)}","presetVoice":"DYLAN"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors[0].field").value("text"))
    }

    @Test
    fun `일시적인 오류는 다시 시도하고 3번 실패하면 FAILED가 된다`() {
        whenever(ttsClient.synthesize(any(), any())).thenThrow(TtsException(TtsErrorType.TEMPORARY, "429"))
        val jobId = createJobId("""{"text":"재시도","presetVoice":"DYLAN"}""")

        worker.processNext()
        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("PENDING"))

        worker.processNext()
        worker.processNext()

        verify(ttsClient, times(3)).synthesize(any(), any())
        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.audioUrl").doesNotExist())
    }

    @Test
    fun `입력 오류는 다시 시도하지 않고 바로 FAILED가 된다`() {
        whenever(ttsClient.synthesize(any(), any())).thenThrow(TtsException(TtsErrorType.INVALID_INPUT, "422"))
        val jobId = createJobId("""{"text":"입력 오류","presetVoice":"DYLAN"}""")

        worker.processNext()

        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("FAILED"))
    }

    @Test
    fun `S3 업로드가 실패하면 다시 시도한다`() {
        whenever(audioStorage.upload(any(), any(), any())).thenThrow(RuntimeException("S3 down"))
        val jobId = createJobId("""{"text":"업로드 실패","presetVoice":"DYLAN"}""")

        worker.processNext()

        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `처리 전에 내 목소리를 삭제하면 그 작업은 FAILED가 된다`() {
        val voiceId = registerVoice("uc_to_delete")
        val jobId = createJobId("""{"text":"삭제될 목소리","customVoiceId":$voiceId}""")
        mockMvc.perform(delete("/voices/$voiceId").authed()).andExpect(status().isNoContent)

        worker.processNext()

        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.customVoiceId").doesNotExist())
    }

    @Test
    fun `남의 작업은 조회할 수 없고 404를 응답한다`() {
        val othersJobId = createJobId("""{"text":"남의 작업","presetVoice":"DYLAN"}""", withToken = registerAndLogin())

        mockMvc.perform(get("/dubbing-jobs/$othersJobId").authed())
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("DUBBING_JOB_NOT_FOUND"))
    }

    @Test
    fun `목록에는 내 작업만 최신순으로 보인다`() {
        createJobId("""{"text":"첫 번째","presetVoice":"DYLAN"}""")
        createJobId("""{"text":"두 번째","presetVoice":"DYLAN"}""")
        createJobId("""{"text":"남의 작업","presetVoice":"DYLAN"}""", withToken = registerAndLogin())

        mockMvc.perform(get("/dubbing-jobs").authed())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].text").value("두 번째"))
            .andExpect(jsonPath("$.content[1].text").value("첫 번째"))
    }

    @Test
    fun `작업을 삭제하면 S3 파일도 지우고 다시 조회하면 404다`() {
        val jobId = createJobId("""{"text":"삭제 테스트","presetVoice":"DYLAN"}""")
        worker.processNext()

        mockMvc.perform(delete("/dubbing-jobs/$jobId").authed())
            .andExpect(status().isNoContent)

        verify(audioStorage).delete(argThat { endsWith("/$jobId.mp3") })
        mockMvc.perform(get("/dubbing-jobs/$jobId").authed())
            .andExpect(status().isNotFound)
    }

    // ==================== 도우미 ====================

    private fun createJob(json: String, withToken: String = token): ResultActions {
        return mockMvc.perform(
            post("/dubbing-jobs")
                .authed(withToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )
    }

    private fun createJobId(json: String, withToken: String = token): Long {
        val body = createJob(json, withToken).andExpect(status().isAccepted).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    /** 업체가 providerVoiceId를 발급한다고 가정하고 내 목소리를 등록한 뒤, 우리 쪽 목소리 id를 반환한다. */
    private fun registerVoice(providerVoiceId: String, withToken: String = token): Long {
        whenever(ttsClient.cloneVoice(any(), any(), any(), anyOrNull())).thenReturn(providerVoiceId)
        val body = mockMvc.perform(
            multipart("/voices")
                .file(MockMultipartFile("file", "sample.wav", "audio/wav", byteArrayOf(1, 2, 3)))
                .param("name", "내 목소리")
                .param("consentAgreed", "true")
                .authed(withToken),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Regex("\"id\":(\\d+)").find(body)!!.groupValues[1].toLong()
    }

    private fun registerAndLogin(): String {
        val email = "dubbing-tester-${System.nanoTime()}@test.com"
        mockMvc.perform(
            post("/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password123","nickname":"더빙테스터"}"""),
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
