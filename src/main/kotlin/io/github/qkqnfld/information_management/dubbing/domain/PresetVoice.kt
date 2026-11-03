package io.github.qkqnfld.information_management.dubbing.domain

/**
 * 앱에서 고를 수 있는 AI 목소리 목록 (PRD 9.5, D11).
 * 여기에는 우리 서버의 식별자(enum 이름)와 화면 표시 값만 둔다.
 * TTS 업체의 목소리 id는 infrastructure(TypecastTtsClient)가 매핑한다.
 * 그래서 업체를 바꿔도 이 enum과 앱 API는 바뀌지 않는다.
 *
 * 주의:
 * - enum 이름은 앱이 내장 샘플 파일을 찾는 키이고, DB(dubbing_jobs.preset_voice)에 저장된다. 이름을 바꾸지 않는다.
 * - 목소리를 지우려면 그 목소리를 쓴 작업 행을 먼저 처리한다. 남은 행은 enum으로 바꿀 수 없어 조회가 실패한다.
 * - 선언 순서가 앱 목록의 순서다.
 */
enum class PresetVoice(
    /** 앱 화면에 보여 줄 이름 */
    val displayName: String,
    /** 앱 화면에 보여 줄 한 줄 설명 */
    val description: String,
) {
    SEOHYEON("서현", "아나운서·대화에 어울리는 여성 목소리"),
    SEOYEON("서연", "설명·강의에 어울리는 여성 목소리"),
    JINWOO("진우", "광고·다큐에 어울리는 남성 목소리"),
    TAEWOO("태우", "설명·강의에 어울리는 남성 목소리"),
}
