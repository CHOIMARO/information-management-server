package io.github.qkqnfld.information_management.common.exception

/**
 * "인증 실패" 계열 도메인 예외의 공통 부모 (예: 잘못된 로그인 정보).
 * GlobalExceptionHandler가 이 부모 타입 하나만 잡아 401로 변환한다.
 */
open class UnauthorizedException(
    val code: String,
    message: String,
) : RuntimeException(message)
