package io.github.qkqnfld.information_management.common.presentation

/**
 * 모든 에러 응답의 통일된 형식(API 계약).
 * code는 클라이언트가 분기할 수 있는 기계용 식별자, message는 사람용 설명이다.
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: List<FieldErrorDetail> = emptyList(),
)

/** 검증에 실패한 필드와 그 이유. */
data class FieldErrorDetail(
    val field: String,
    val reason: String,
)
