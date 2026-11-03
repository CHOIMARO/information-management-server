package io.github.qkqnfld.information_management.dubbing.domain

/**
 * 더빙 읽기 속도 규칙. 속도는 배율이다: 1.0이 보통, 2.0은 두 배 빠르게.
 * 범위는 Typecast audio_tempo의 범위(0.5~2.0)를 따른다.
 *
 * 앱은 더빙 시트에서 PRESETS의 세 버튼(느리게·보통·빠르게)을 보여 준다 (PRD 9.5).
 * [구간에 맞추기](D12)는 앱이 계산한 배율을 그대로 보낸다. 그래서 요청은 버튼 값이 아닌 숫자 배율을 받는다.
 */
object DubbingSpeed {
    const val MIN = 0.5
    const val MAX = 2.0
    const val DEFAULT = 1.0

    /** 더빙 시트의 속도 버튼. 순서가 앱 화면의 순서다. 배율은 앱 업데이트 없이 여기서 조정한다. */
    val PRESETS: List<Preset> = listOf(
        Preset(name = "느리게", value = 0.8),
        Preset(name = "보통", value = DEFAULT),
        Preset(name = "빠르게", value = 1.2),
    )

    data class Preset(
        val name: String,
        val value: Double,
    )
}
