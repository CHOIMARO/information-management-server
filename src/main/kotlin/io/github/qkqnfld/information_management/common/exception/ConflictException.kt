package io.github.qkqnfld.information_management.common.exception

/**
 * "현재 상태와 충돌" 계열 도메인 예외의 공통 부모 (예: 이미 존재하는 이메일).
 * GlobalExceptionHandler가 이 부모 타입 하나만 잡아 409로 변환한다.
 */
open class ConflictException(
    val code: String,
    message: String,
) : RuntimeException(message)
