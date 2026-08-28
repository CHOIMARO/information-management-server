package io.github.qkqnfld.information_management.common.presentation

import io.github.qkqnfld.information_management.common.exception.ConflictException
import io.github.qkqnfld.information_management.common.exception.NotFoundException
import io.github.qkqnfld.information_management.common.exception.UnauthorizedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 전역 예외 처리기: 애플리케이션 어디서 예외가 터지든
 * 여기서 한 번에 ErrorResponse 형식과 올바른 상태 코드로 변환한다.
 * 모든 도메인이 공유하는 횡단 관심사이므로 common에 위치한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** 리소스를 찾을 수 없음 (도메인 공통 부모 예외) → 404 */
    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(e: NotFoundException): ErrorResponse {
        return ErrorResponse(code = e.code, message = e.message ?: "리소스를 찾을 수 없습니다")
    }

    /** 인증 실패 (도메인 공통 부모 예외, 예: 잘못된 로그인 정보) → 401 */
    @ExceptionHandler(UnauthorizedException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleUnauthorized(e: UnauthorizedException): ErrorResponse {
        return ErrorResponse(code = e.code, message = e.message ?: "인증에 실패했습니다")
    }

    /** 현재 상태와 충돌 (도메인 공통 부모 예외, 예: 이메일 중복) → 409 */
    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(e: ConflictException): ErrorResponse {
        return ErrorResponse(code = e.code, message = e.message ?: "요청이 현재 상태와 충돌합니다")
    }

    /** @Valid 검증 실패 → 400 + 어떤 필드가 왜 틀렸는지 */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidation(e: MethodArgumentNotValidException): ErrorResponse {
        val fieldErrors = e.bindingResult.fieldErrors.map {
            FieldErrorDetail(field = it.field, reason = it.defaultMessage ?: "올바르지 않은 값입니다")
        }
        return ErrorResponse(code = "INVALID_REQUEST", message = "요청 값이 올바르지 않습니다", fieldErrors = fieldErrors)
    }

    /** 본문 JSON 파싱 실패, 필수 필드 누락 → 400 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ErrorResponse {
        return ErrorResponse(code = "MALFORMED_BODY", message = "요청 본문을 읽을 수 없습니다. JSON 형식과 필수 필드를 확인하세요")
    }

    /** 경로/쿼리 파라미터 타입 불일치 (예: id 자리에 문자열) → 400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ErrorResponse {
        return ErrorResponse(code = "TYPE_MISMATCH", message = "파라미터 '${e.name}'의 형식이 올바르지 않습니다")
    }

    /** 존재하지 않는 경로 → 404 (catch-all이 500으로 삼키지 않도록 명시) */
    @ExceptionHandler(NoResourceFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNoResource(e: NoResourceFoundException): ErrorResponse {
        return ErrorResponse(code = "NOT_FOUND", message = "존재하지 않는 경로입니다")
    }

    /** 지원하지 않는 HTTP 메서드 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ErrorResponse {
        return ErrorResponse(code = "METHOD_NOT_ALLOWED", message = "지원하지 않는 HTTP 메서드입니다")
    }

    /** 그 외 모든 예외 → 500. 내부 사정은 로그에만 남기고 클라이언트에는 노출하지 않는다 */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleUnexpected(e: Exception): ErrorResponse {
        log.error("처리되지 않은 예외 발생", e)
        return ErrorResponse(code = "INTERNAL_ERROR", message = "서버 내부 오류가 발생했습니다")
    }
}
