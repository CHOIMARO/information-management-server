package io.github.qkqnfld.information_management.dubbing.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/**
 * Typecast TTS API 연동 (https://typecast.ai/docs).
 * 인증은 X-API-KEY 헤더 하나로 한다. 키는 환경변수(TYPECAST_API_KEY)로만 주입한다.
 *
 * @param restClientBuilder 테스트에서 가짜 서버(MockRestServiceServer)를 끼울 수 있게 열어 둔다.
 *   운영에서는 이 값을 주입할 Bean이 없으므로 Kotlin 기본값(타임아웃이 설정된 빌더)이 쓰인다.
 */
@Component
class TypecastTtsClient(
    @Value("\${typecast.base-url}") baseUrl: String,
    @Value("\${typecast.api-key}") apiKey: String,
    @Value("\${typecast.model}") private val model: String,
    restClientBuilder: RestClient.Builder = defaultRestClientBuilder(),
) : TtsClient {

    private val log = LoggerFactory.getLogger(TypecastTtsClient::class.java)

    private val restClient = restClientBuilder
        .baseUrl(baseUrl)
        .defaultHeader(API_KEY_HEADER, apiKey)
        .build()

    override fun synthesize(text: String, voice: TtsVoice, speed: Double): ByteArray {
        val request = TypecastTtsRequest(
            text = text,
            voiceId = providerVoiceIdOf(voice),
            model = model,
            output = TypecastTtsRequest.Output(audioFormat = "mp3", audioTempo = speed),
        )
        return call("text-to-speech") {
            restClient.post()
                .uri("/v1/text-to-speech")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ByteArray::class.java)
        }?.takeIf { it.isNotEmpty() }
            ?: throw TtsException(TtsErrorType.TEMPORARY, "Typecast가 빈 오디오를 반환했습니다")
    }

    override fun cloneVoice(name: String, sample: ByteArray, filename: String, contentType: String?): String {
        // multipart/form-data: 파일과 일반 값을 한 요청에 담는다 (필드 이름은 Typecast 규격)
        val form = MultipartBodyBuilder().apply {
            part("file", sample).filename(filename).contentType(parseMediaType(contentType))
            part("name", name)
            part("model", model)
        }.build()

        val response = call("instant-clone") {
            restClient.post()
                .uri("/v1/custom-voices/instant-clone")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(TypecastCloneResponse::class.java)
        } ?: throw TtsException(TtsErrorType.TEMPORARY, "Typecast가 빈 복제 응답을 반환했습니다")

        if (response.status == "failed") {
            throw TtsException(TtsErrorType.INVALID_INPUT, "Typecast가 목소리 복제에 실패했습니다")
        }
        return response.voiceId
    }

    override fun deleteVoice(providerVoiceId: String) {
        call("delete-custom-voice") {
            try {
                restClient.delete()
                    .uri("/v1/custom-voices/{voiceId}", providerVoiceId)
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: HttpClientErrorException.NotFound) {
                // 이미 지워진 목소리: 목표 상태(없음)와 같으므로 성공으로 본다 (멱등 삭제)
            }
        }
    }

    /**
     * AI 목소리 식별자를 Typecast 목소리 id로 바꾼다.
     * when이 enum 전체를 다루므로, PresetVoice에 값을 추가하고 여기를 빠뜨리면 컴파일 오류가 난다.
     * id는 목소리 목록 API(GET /v3/voices?model=ssfm-v31)에서 확인한 값을 쓴다 (2026-11-02 확인).
     * 공식 문서의 예시 id는 실제 id가 아니다.
     */
    private fun providerVoiceIdOf(voice: TtsVoice): String = when (voice) {
        is TtsVoice.Custom -> voice.providerVoiceId
        is TtsVoice.Preset -> when (voice.voice) {
            PresetVoice.SEOHYEON -> "tc_69f2e455ea79fd197aa0476f"
            PresetVoice.SEOYEON -> "tc_637cbfeef7f00f21e8a1c6c8"
            PresetVoice.JINWOO -> "tc_632293f759d649937b97f323"
            PresetVoice.TAEWOO -> "tc_6800a387534948f191cc952b"
        }
    }

    /**
     * 실제 호출을 감싸 Spring 예외를 TtsException으로 바꾼다.
     * Typecast 문서는 상태 코드별 의미를 밝히지 않는다. 그래서 HTTP 표준 의미로 분류한다.
     */
    private fun <T> call(operation: String, block: () -> T): T {
        try {
            return block()
        } catch (e: RestClientResponseException) {
            val status = e.statusCode.value()
            log.warn("Typecast {} 실패: status={}, body={}", operation, status, e.responseBodyAsString.take(500))
            throw TtsException(classify(status), "Typecast $operation 실패 (HTTP $status)", e)
        } catch (e: ResourceAccessException) {
            log.warn("Typecast {} 연결 실패: {}", operation, e.message)
            throw TtsException(TtsErrorType.TEMPORARY, "Typecast $operation 연결 실패", e)
        }
    }

    private fun classify(status: Int): TtsErrorType = when {
        status == 429 || status >= 500 -> TtsErrorType.TEMPORARY
        status == 400 || status == 413 || status == 422 -> TtsErrorType.INVALID_INPUT
        else -> TtsErrorType.REJECTED // 401(키), 402(크레딧), 403(권한·슬롯), 404 등
    }

    private fun parseMediaType(contentType: String?): MediaType = try {
        contentType?.let(MediaType::parseMediaType) ?: MediaType.APPLICATION_OCTET_STREAM
    } catch (e: InvalidMediaTypeException) {
        MediaType.APPLICATION_OCTET_STREAM
    }

    companion object {
        private const val API_KEY_HEADER = "X-API-KEY"

        /**
         * 연결은 5초, 응답은 60초까지 기다린다.
         * 긴 문장(최대 2,000자)은 합성에 수십 초가 걸릴 수 있어 응답 제한을 넉넉히 둔다.
         * 타임아웃이 없으면 업체가 응답하지 않을 때 Worker가 영원히 멈춘다.
         */
        private fun defaultRestClientBuilder(): RestClient.Builder {
            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
                setReadTimeout(Duration.ofSeconds(60))
            }
            return RestClient.builder().requestFactory(requestFactory)
        }
    }
}

/** Typecast 음성 합성 요청 본문. 필드 이름은 Typecast API 규격(snake_case)을 따른다. */
internal data class TypecastTtsRequest(
    val text: String,
    @JsonProperty("voice_id")
    val voiceId: String,
    val model: String,
    val output: Output,
) {
    data class Output(
        @JsonProperty("audio_format")
        val audioFormat: String,
        /** 읽기 속도 배율 (0.5~2.0, 기본 1.0) */
        @JsonProperty("audio_tempo")
        val audioTempo: Double,
    )
}

/** Typecast 목소리 복제 응답에서 우리가 쓰는 부분만 매핑한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class TypecastCloneResponse(
    @JsonProperty("voice_id")
    val voiceId: String,
    /** pending | training | completed | failed (Instant Clone은 보통 바로 completed) */
    val status: String?,
)
