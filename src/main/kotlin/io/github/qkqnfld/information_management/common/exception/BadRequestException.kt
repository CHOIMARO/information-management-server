package io.github.qkqnfld.information_management.common.exception

/**
 * "요청 내용이 잘못됨" 계열 도메인 예외의 공통 부모 (예: 처리할 수 없는 녹음 파일).
 * Bean Validation으로 미리 거를 수 없고, 처리 도중에야 알 수 있는 입력 오류에 쓴다.
 * GlobalExceptionHandler가 이 부모 타입 하나만 잡아 400으로 변환한다.
 */
open class BadRequestException(
    val code: String,
    message: String,
) : RuntimeException(message)
