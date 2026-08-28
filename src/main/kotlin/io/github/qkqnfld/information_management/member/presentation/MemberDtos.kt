package io.github.qkqnfld.information_management.member.presentation

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.Member
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 회원가입 요청(HTTP 계약).
 */
data class SignupRequest(
    @field:NotBlank(message = "이메일은 비어 있을 수 없습니다")
    @field:Email(message = "이메일 형식이 올바르지 않습니다")
    @field:Size(max = 100, message = "이메일은 100자 이하여야 합니다")
    val email: String,

    @field:NotBlank(message = "비밀번호는 비어 있을 수 없습니다")
    @field:Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다")
    val password: String,

    @field:NotBlank(message = "닉네임은 비어 있을 수 없습니다")
    @field:Size(max = 20, message = "닉네임은 20자 이하여야 합니다")
    val nickname: String,
)

/**
 * 회원 응답(HTTP 계약). 비밀번호(해시일지라도)는 절대 응답에 담지 않는다.
 */
data class MemberResponse(
    val id: Long,
    /** SNS 가입 회원은 이메일 제공에 동의하지 않았을 수 있다 */
    val email: String?,
    val nickname: String,
    /** 연결된 로그인 수단 목록: LOCAL / KAKAO / GOOGLE (계정 연동으로 여러 개일 수 있다) */
    val providers: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member, providers: List<AuthProvider>): MemberResponse {
            return MemberResponse(
                id = member.id,
                email = member.email,
                nickname = member.nickname,
                providers = providers.map { it.name },
                createdAt = member.createdAt,
            )
        }
    }
}
