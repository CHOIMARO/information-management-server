package io.github.qkqnfld.information_management.dubbing.infrastructure

import io.github.qkqnfld.information_management.dubbing.domain.PresetVoice

/**
 * TTS 엔진 계약. Service와 Worker는 이 인터페이스에만 의존한다.
 * 업체를 바꿀 때는 구현체만 교체한다 (현재 구현: TypecastTtsClient).
 * 테스트에서는 @MockitoBean으로 가짜 구현을 끼운다.
 */
interface TtsClient {

    /**
     * text를 voice로 읽은 mp3 데이터를 반환한다.
     * @param speed 읽기 속도 배율 (1.0이 보통 속도)
     * @throws TtsException 업체 호출이 실패했을 때
     */
    fun synthesize(text: String, voice: TtsVoice, speed: Double): ByteArray

    /**
     * 녹음 샘플로 목소리를 복제하고, 업체가 발급한 목소리 id를 반환한다.
     * @param name 업체 관리 화면에 보이는 이름
     * @param contentType 녹음 파일의 MIME 타입. 모르면 null
     * @throws TtsException 업체 호출이 실패했을 때
     */
    fun cloneVoice(name: String, sample: ByteArray, filename: String, contentType: String?): String

    /**
     * 업체 쪽 목소리를 삭제해 슬롯을 비운다. 이미 없는 목소리면 성공으로 처리한다.
     * @throws TtsException 업체 호출이 실패했을 때
     */
    fun deleteVoice(providerVoiceId: String)
}

/** 합성에 쓸 목소리. AI 목소리는 우리 식별자로, 내 목소리는 업체 목소리 id로 전달한다. */
sealed interface TtsVoice {
    data class Preset(val voice: PresetVoice) : TtsVoice
    data class Custom(val providerVoiceId: String) : TtsVoice
}

/** TTS 실패의 종류. 호출하는 쪽은 이 값으로 재시도 여부와 응답 코드를 정한다. */
enum class TtsErrorType {
    /** 일시적인 문제 (429, 5xx, 네트워크 오류). 잠시 뒤 다시 시도하면 성공할 수 있다. */
    TEMPORARY,

    /** 입력이 잘못됨 (녹음 파일 형식·길이, 텍스트 등). 같은 입력으로는 다시 시도해도 실패한다. */
    INVALID_INPUT,

    /** 그 밖의 거절 (인증 실패, 크레딧 부족, 슬롯 부족 등). 운영자가 확인해야 한다. */
    REJECTED,
}

class TtsException(
    val type: TtsErrorType,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
