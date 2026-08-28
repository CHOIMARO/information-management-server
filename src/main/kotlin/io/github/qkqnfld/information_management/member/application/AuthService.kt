package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.common.security.JwtTokenProvider
import io.github.qkqnfld.information_management.member.domain.AuthCredential
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.CredentialNotFoundException
import io.github.qkqnfld.information_management.member.domain.InvalidCredentialsException
import io.github.qkqnfld.information_management.member.domain.InvalidRefreshTokenException
import io.github.qkqnfld.information_management.member.domain.LastCredentialException
import io.github.qkqnfld.information_management.member.domain.Member
import io.github.qkqnfld.information_management.member.domain.RefreshToken
import io.github.qkqnfld.information_management.member.domain.SnsAccountAlreadyLinkedException
import io.github.qkqnfld.information_management.member.domain.SocialEmailConflictException
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import io.github.qkqnfld.information_management.member.infrastructure.MemberRepository
import io.github.qkqnfld.information_management.member.infrastructure.OAuthProfile
import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/** 로그인/재발급이 발급하는 토큰 한 쌍. */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

/**
 * 인증 유스케이스: 로그인(자체/SNS), 재발급, 로그아웃, 계정 연동.
 * 회원("사람")과 로그인 수단("문")이 분리되어 있어, 어느 문으로 들어와도
 * AuthCredential이 가리키는 회원으로 도착하고 같은 JWT 체계를 쓴다.
 */
@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberRepository: MemberRepository,
    private val authCredentialRepository: AuthCredentialRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val kakaoApiClient: KakaoApiClient,
    private val googleApiClient: GoogleApiClient,
    @Value("\${jwt.refresh-expiration-ms}") private val refreshExpirationMs: Long,
) {

    // ============ 로그인 ============

    /**
     * 자체(이메일/비밀번호) 로그인. LOCAL 수단이 연결된 계정만 가능하다.
     * matches(): 원문을 다시 해싱해 저장된 해시와 비교한다 (해시는 복호화가 불가능하므로).
     */
    @Transactional
    fun login(email: String, rawPassword: String): AuthTokens {
        val member = memberRepository.findByEmail(email) ?: throw InvalidCredentialsException()
        val credential = authCredentialRepository.findByMemberIdAndProvider(member.id, AuthProvider.LOCAL)
            ?: throw InvalidCredentialsException() // SNS로만 가입된 계정 — 비밀번호 로그인 불가
        val hashedPassword = credential.password ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(rawPassword, hashedPassword)) {
            throw InvalidCredentialsException()
        }
        return issueTokens(member.id)
    }

    /** 카카오 로그인: 앱이 받은 카카오 access 토큰을 검증하고 우리 토큰을 발급한다. */
    @Transactional
    fun kakaoLogin(kakaoAccessToken: String): AuthTokens {
        return socialLogin(kakaoApiClient.fetchProfile(kakaoAccessToken))
    }

    /** 구글 로그인: 앱이 받은 idToken을 검증하고 우리 토큰을 발급한다. */
    @Transactional
    fun googleLogin(idToken: String): AuthTokens {
        return socialLogin(googleApiClient.fetchProfile(idToken))
    }

    /**
     * SNS 공통 처리: 이 SNS 계정이 연결된 회원이 있으면 그 회원으로 로그인 (연동된 계정 포함),
     * 없으면 자동 가입한다 (find-or-create).
     */
    private fun socialLogin(profile: OAuthProfile): AuthTokens {
        val credential = authCredentialRepository.findByProviderAndProviderId(profile.provider, profile.providerId)
        val memberId = credential?.memberId ?: registerSocialMember(profile).id
        return issueTokens(memberId)
    }

    /**
     * SNS 첫 로그인 시 자동 가입: 회원과 로그인 수단이 한 트랜잭션에서 함께 만들어진다.
     * 같은 이메일의 기존 계정이 있으면 자동 통합하지 않는다 — 이메일 일치만으로 합치면
     * 계정 탈취 경로가 되므로, 기존 방식으로 로그인 후 명시적으로 연동하도록 안내한다.
     */
    private fun registerSocialMember(profile: OAuthProfile): Member {
        if (profile.email != null && memberRepository.existsByEmail(profile.email)) {
            throw SocialEmailConflictException()
        }
        val member = memberRepository.save(
            Member(email = profile.email, nickname = profile.nickname ?: "사용자"),
        )
        authCredentialRepository.save(
            AuthCredential(memberId = member.id, provider = profile.provider, providerId = profile.providerId),
        )
        return member
    }

    // ============ 계정 연동 ============

    /**
     * 로그인된 회원에게 카카오 로그인 수단을 연결한다.
     * 근거는 "현재 세션(기존 계정 주인 증명) + SNS 토큰 검증(SNS 계정 주인 증명)"이며,
     * 이메일이 같은지는 보지 않는다 — 이메일은 연동의 근거가 아니다.
     */
    @Transactional
    fun linkKakao(memberId: Long, kakaoAccessToken: String) {
        link(memberId, kakaoApiClient.fetchProfile(kakaoAccessToken))
    }

    /** 로그인된 회원에게 구글 로그인 수단을 연결한다. */
    @Transactional
    fun linkGoogle(memberId: Long, idToken: String) {
        link(memberId, googleApiClient.fetchProfile(idToken))
    }

    private fun link(memberId: Long, profile: OAuthProfile) {
        // 이 SNS 계정이 이미 어딘가(다른 회원 포함)에 연결되어 있으면 거절
        if (authCredentialRepository.findByProviderAndProviderId(profile.provider, profile.providerId) != null) {
            throw SnsAccountAlreadyLinkedException()
        }
        // 이 회원이 같은 provider의 수단을 이미 갖고 있어도 거절 (provider당 1개)
        if (authCredentialRepository.existsByMemberIdAndProvider(memberId, profile.provider)) {
            throw SnsAccountAlreadyLinkedException()
        }
        authCredentialRepository.save(
            AuthCredential(memberId = memberId, provider = profile.provider, providerId = profile.providerId),
        )
    }

    /**
     * 로그인 수단 연결 해제. 마지막 남은 수단은 해제할 수 없다 —
     * 허용하면 어떤 방법으로도 로그인할 수 없는 계정이 되어버린다.
     */
    @Transactional
    fun unlink(memberId: Long, provider: AuthProvider) {
        val credential = authCredentialRepository.findByMemberIdAndProvider(memberId, provider)
            ?: throw CredentialNotFoundException()
        if (authCredentialRepository.countByMemberId(memberId) <= 1) {
            throw LastCredentialException()
        }
        authCredentialRepository.delete(credential)
    }

    // ============ 재발급 / 로그아웃 ============

    /**
     * refresh 토큰이 유효하면 새 access 토큰을 발급한다.
     * DB에 없으면(위조·로그아웃됨) 또는 만료됐으면 401 — 클라이언트는 재로그인으로 보낸다.
     * 만료된 행은 여기서 지우지 않고(롤백 문제) 매일 새벽 배치가 정리한다.
     */
    fun refresh(refreshToken: String): AuthTokens {
        val stored = refreshTokenRepository.findByToken(refreshToken) ?: throw InvalidRefreshTokenException()
        if (stored.isExpired()) {
            throw InvalidRefreshTokenException()
        }
        return AuthTokens(
            accessToken = jwtTokenProvider.createToken(stored.memberId),
            refreshToken = refreshToken,
        )
    }

    /**
     * 로그아웃: refresh 토큰을 삭제해 재발급 경로를 끊는다.
     * 이미 발급된 access 토큰은 남은 수명(최대 1시간)까지만 유효하다.
     */
    @Transactional
    fun logout(refreshToken: String) {
        refreshTokenRepository.deleteByToken(refreshToken)
    }

    /** access + refresh 발급. refresh는 예측 불가능한 난수(UUID v4)로 만들어 DB에 저장한다. */
    private fun issueTokens(memberId: Long): AuthTokens {
        val refreshToken = UUID.randomUUID().toString()
        refreshTokenRepository.save(
            RefreshToken(
                token = refreshToken,
                memberId = memberId,
                expiresAt = LocalDateTime.now().plus(Duration.ofMillis(refreshExpirationMs)),
            ),
        )
        return AuthTokens(
            accessToken = jwtTokenProvider.createToken(memberId),
            refreshToken = refreshToken,
        )
    }
}
