package io.github.qkqnfld.information_management.dubbing

import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsErrorType
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsException
import io.github.qkqnfld.information_management.dubbing.infrastructure.TtsVoice
import io.github.qkqnfld.information_management.dubbing.infrastructure.TypecastTtsClient
import org.hamcrest.Matchers.containsString
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Typecast 클라이언트 단위 테스트.
 * 실제 Typecast 대신 MockRestServiceServer(가짜 HTTP 서버)를 RestClient에 연결한다.
 * 그래서 키 없이도 "우리가 보내는 요청 형식"과 "응답·오류 해석"을 검증할 수 있다.
 * 통합 테스트는 TtsClient 자체를 mock으로 바꾸므로, 실제 JSON 직렬화는 이 테스트만 검증한다.
 */
class TypecastTtsClientTest {

    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = TypecastTtsClient(
        baseUrl = BASE_URL,
        apiKey = "test-key",
        model = "ssfm-v31",
        restClientBuilder = builder,
    )

    @Test
    fun `합성 요청은 API 키 헤더와 Typecast 규격의 JSON 본문(속도 포함)을 보내고 오디오 데이터를 돌려준다`() {
        server.expect(requestTo("$BASE_URL/v1/text-to-speech"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-KEY", "test-key"))
            .andExpect(
                content().json(
                    """
                    {
                      "text": "안녕하세요",
                      "voice_id": "tc_69f2e455ea79fd197aa0476f",
                      "model": "ssfm-v31",
                      "output": { "audio_format": "mp3", "audio_tempo": 1.2 }
                    }
                    """,
                    JsonCompareMode.STRICT,
                ),
            )
            .andRespond(withSuccess(byteArrayOf(1, 2, 3), MediaType.parseMediaType("audio/mpeg")))

        val audio = client.synthesize("안녕하세요", TtsVoice.Preset(PresetVoice.SEOHYEON), 1.2)

        assertContentEquals(byteArrayOf(1, 2, 3), audio)
        server.verify()
    }

    @Test
    fun `내 목소리 합성은 업체 목소리 id를 그대로 보낸다`() {
        server.expect(requestTo("$BASE_URL/v1/text-to-speech"))
            .andExpect(content().string(containsString("\"voice_id\":\"uc_mine\"")))
            .andRespond(withSuccess(byteArrayOf(9), MediaType.parseMediaType("audio/mpeg")))

        client.synthesize("테스트", TtsVoice.Custom("uc_mine"), 1.0)

        server.verify()
    }

    @Test
    fun `429와 5xx는 일시적인 오류로 분류한다`() {
        server.expect(requestTo("$BASE_URL/v1/text-to-speech"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val e = assertFailsWith<TtsException> { client.synthesize("테스트", TtsVoice.Preset(PresetVoice.SEOHYEON), 1.0) }

        assertEquals(TtsErrorType.TEMPORARY, e.type)
    }

    @Test
    fun `네트워크 오류는 일시적인 오류로 분류한다`() {
        server.expect(requestTo("$BASE_URL/v1/text-to-speech"))
            .andRespond { throw IOException("connection reset") }

        val e = assertFailsWith<TtsException> { client.synthesize("테스트", TtsVoice.Preset(PresetVoice.SEOHYEON), 1.0) }

        assertEquals(TtsErrorType.TEMPORARY, e.type)
    }

    @Test
    fun `401 같은 그 밖의 4xx는 재시도하지 않는 거절로 분류한다`() {
        server.expect(requestTo("$BASE_URL/v1/text-to-speech"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertFailsWith<TtsException> { client.synthesize("테스트", TtsVoice.Preset(PresetVoice.SEOHYEON), 1.0) }

        assertEquals(TtsErrorType.REJECTED, e.type)
    }

    @Test
    fun `목소리 복제는 multipart로 파일과 이름과 모델을 보내고 업체 목소리 id를 돌려준다`() {
        server.expect(requestTo("$BASE_URL/v1/custom-voices/instant-clone"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-KEY", "test-key"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
            .andExpect(content().string(containsString("name=\"file\"; filename=\"sample.wav\"")))
            .andExpect(content().string(containsString("member-1")))
            .andExpect(content().string(containsString("ssfm-v31")))
            .andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"name":"member-1","model":"ssfm-v31","status":"completed","voice_id":"uc_123"}"""),
            )

        val voiceId = client.cloneVoice("member-1", byteArrayOf(1, 2), "sample.wav", "audio/wav")

        assertEquals("uc_123", voiceId)
        server.verify()
    }

    @Test
    fun `복제 요청이 422로 거절되면 입력 오류로 분류한다`() {
        server.expect(requestTo("$BASE_URL/v1/custom-voices/instant-clone"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT))

        val e = assertFailsWith<TtsException> { client.cloneVoice("member-1", byteArrayOf(1), "a.wav", null) }

        assertEquals(TtsErrorType.INVALID_INPUT, e.type)
    }

    @Test
    fun `목소리 삭제는 DELETE 요청을 보낸다`() {
        server.expect(requestTo("$BASE_URL/v1/custom-voices/uc_123"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        client.deleteVoice("uc_123")

        server.verify()
    }

    @Test
    fun `이미 없는 목소리를 삭제하면 성공으로 처리한다`() {
        server.expect(requestTo("$BASE_URL/v1/custom-voices/uc_gone"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        client.deleteVoice("uc_gone") // 예외가 나지 않아야 한다

        server.verify()
    }

    companion object {
        private const val BASE_URL = "https://api.typecast.test"
    }
}
