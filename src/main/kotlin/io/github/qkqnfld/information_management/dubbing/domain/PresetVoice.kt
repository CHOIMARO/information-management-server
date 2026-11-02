package io.github.qkqnfld.information_management.dubbing.domain

/**
 * 앱에서 고를 수 있는 AI 목소리 목록.
 * 여기에는 우리 서버의 식별자(enum 이름)와 화면 표시 이름만 둔다.
 * TTS 업체의 목소리 id는 infrastructure(TypecastTtsClient)가 매핑한다.
 * 그래서 업체를 바꿔도 이 enum과 앱 API는 바뀌지 않는다.
 *
 * 주의: 현재 목록은 동작 확인용 임시 목록이다 (DYLAN은 영어 이름의 목소리).
 * 한국어 목소리를 골라 교체한다 (docs/dubbing-todo.md 참고).
 */
enum class PresetVoice(
    /** 앱 화면에 보여 줄 이름 */
    val displayName: String,
) {
    DYLAN("딜런"),
}
