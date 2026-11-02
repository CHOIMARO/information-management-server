package io.github.qkqnfld.information_management.common.exception

/**
 * "외부 서비스 장애로 지금은 처리할 수 없음" 계열 도메인 예외의 공통 부모 (예: TTS 업체 장애).
 * 클라이언트는 잠시 뒤 같은 요청을 다시 보내면 된다.
 * GlobalExceptionHandler가 이 부모 타입 하나만 잡아 503으로 변환한다.
 */
open class ServiceUnavailableException(
    val code: String,
    message: String,
) : RuntimeException(message)
