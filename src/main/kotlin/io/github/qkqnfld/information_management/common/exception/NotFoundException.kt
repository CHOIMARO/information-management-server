package io.github.qkqnfld.information_management.common.exception

/**
 * "리소스를 찾을 수 없음" 계열 도메인 예외의 공통 부모.
 * 각 도메인은 이를 상속한 자기 예외(예: MemoNotFoundException)를 정의하고,
 * GlobalExceptionHandler는 이 부모 타입 하나만 잡아 404로 변환한다.
 * 덕분에 common이 개별 도메인을 알 필요가 없다.
 */
open class NotFoundException(
    val code: String,
    message: String,
) : RuntimeException(message)
