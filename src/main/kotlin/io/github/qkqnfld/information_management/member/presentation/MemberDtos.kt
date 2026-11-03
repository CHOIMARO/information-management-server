package io.github.qkqnfld.information_management.member.presentation

import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.Member
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 프로필 수정 요청(HTTP 계약). 앞뒤 공백은 서버가 지운다.
 */
data class UpdateProfileRequest(
    @field:NotBlank(message = "닉네임은 비어 있을 수 없습니다")
    @field:Size(max = Member.NICKNAME_MAX_LENGTH, message = "닉네임은 20자 이하여야 합니다")
    val nickname: String,
)

/**
 * 회원 응답(HTTP 계약).
 */
data class MemberResponse(
    val id: Long,
    /** SNS 가입 회원은 이메일 제공에 동의하지 않았을 수 있다 */
    val email: String?,
    val nickname: String,
    /** SNS가 준 프로필 사진 주소. 없으면 앱이 기본 아바타를 보여 준다 */
    val profileImageUrl: String?,
    /** 연결된 로그인 수단 목록: KAKAO / GOOGLE / APPLE (계정 연동으로 여러 개일 수 있다) */
    val providers: List<String>,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(member: Member, providers: List<AuthProvider>): MemberResponse {
            return MemberResponse(
                id = member.id,
                email = member.email,
                nickname = member.nickname,
                profileImageUrl = member.profileImageUrl,
                providers = providers.map { it.name },
                createdAt = member.createdAt,
            )
        }
    }
}
