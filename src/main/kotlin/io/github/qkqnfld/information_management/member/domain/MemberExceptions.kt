package io.github.qkqnfld.information_management.member.domain

import io.github.qkqnfld.information_management.common.exception.ConflictException
import io.github.qkqnfld.information_management.common.exception.NotFoundException
import io.github.qkqnfld.information_management.common.exception.UnauthorizedException

/**
 * refresh 토큰이 없거나(위조/로그아웃됨) 만료됨 → 401.
 * 클라이언트는 이 응답을 받으면 재로그인으로 보내야 한다.
 */
class InvalidRefreshTokenException :
    UnauthorizedException(code = "INVALID_REFRESH_TOKEN", message = "유효하지 않거나 만료된 토큰입니다. 다시 로그인해 주세요")

/** SNS 쪽 검증에 실패한 토큰(위조·만료·권한 없음) → 401 */
class InvalidSnsTokenException :
    UnauthorizedException(code = "INVALID_SNS_TOKEN", message = "유효하지 않은 SNS 토큰입니다")

/** 이미 (다른 계정 또는 이 계정에) 연결되어 있는 SNS 계정을 다시 연결 시도 → 409 */
class SnsAccountAlreadyLinkedException :
    ConflictException(code = "SNS_ALREADY_LINKED", message = "이미 연결된 SNS 계정입니다")

/** 마지막 남은 로그인 수단의 해제 시도 → 409 (스스로 계정에 잠기는 것 방지) */
class LastCredentialException :
    ConflictException(code = "LAST_LOGIN_METHOD", message = "마지막 로그인 수단은 해제할 수 없습니다")

/** 연결되어 있지 않은 로그인 수단의 해제 시도 → 404 */
class CredentialNotFoundException :
    NotFoundException(code = "CREDENTIAL_NOT_FOUND", message = "연결되지 않은 로그인 수단입니다")

/** 회원을 찾을 수 없음 → 404 */
class MemberNotFoundException(id: Long) :
    NotFoundException(code = "MEMBER_NOT_FOUND", message = "회원을 찾을 수 없습니다 (id: $id)")
