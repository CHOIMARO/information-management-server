package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.common.security.JwtTokenProvider
import io.github.qkqnfld.information_management.common.security.TokenCipher
import io.github.qkqnfld.information_management.member.domain.AuthCredential
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.CredentialNotFoundException
import io.github.qkqnfld.information_management.member.domain.InvalidRefreshTokenException
import io.github.qkqnfld.information_management.member.domain.LastCredentialException
import io.github.qkqnfld.information_management.member.domain.Member
import io.github.qkqnfld.information_management.member.domain.RefreshToken
import io.github.qkqnfld.information_management.member.domain.SnsAccountAlreadyLinkedException
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.AppleRevokeToken
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.GoogleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import io.github.qkqnfld.information_management.member.infrastructure.MemberRepository
import io.github.qkqnfld.information_management.member.infrastructure.OAuthProfile
import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * 인증 유스케이스: SNS 로그인, 재발급, 로그아웃, 계정 연동.
 * 회원("사람")과 로그인 수단("문")이 분리되어 있어, 어느 문으로 들어와도
 * AuthCredential이 가리키는 회원으로 도착하고 같은 JWT 체계를 쓴다.
 * 이메일·비밀번호 로그인은 없다. 첫 SNS 로그인이 곧 가입이다 (PRD 9.17).
 */
@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberRepository: MemberRepository,
    private val authCredentialRepository: AuthCredentialRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val tokenCipher: TokenCipher,
    private val kakaoApiClient: KakaoApiClient,
    private val googleApiClient: GoogleApiClient,
    private val appleApiClient: AppleApiClient,
    @Value("\${jwt.refresh-expiration-ms}") private val refreshExpirationMs: Long,
) {

    private val log = LoggerFactory.getLogger(AuthService::class.java)

    // ==================== 로그인 ====================

    /** 카카오 로그인: 앱이 받은 카카오 access 토큰을 검증하고 우리 토큰을 발급한다. */
    @Transactional
    fun kakaoLogin(kakaoAccessToken: String): AuthTokens {
        return issueTokens(findOrRegister(kakaoApiClient.fetchProfile(kakaoAccessToken)).memberId)
    }

    /** 구글 로그인: 앱이 받은 idToken을 검증하고 우리 토큰을 발급한다. */
    @Transactional
    fun googleLogin(idToken: String): AuthTokens {
        return issueTokens(findOrRegister(googleApiClient.fetchProfile(idToken)).memberId)
    }

    /**
     * Apple 로그인: 앱이 받은 identityToken을 서버가 직접 검증하고 우리 토큰을 발급한다.
     * Apple 토큰에는 이름이 없으므로, 앱이 첫 로그인 때 받은 이름(nickname)을 함께 받아 가입에 쓴다.
     * 이미 가입된 계정이면 nickname은 쓰지 않는다.
     *
     * @param authorizationCode 앱이 Apple 로그인 결과로 받은 코드. 탈퇴 때 Apple 토큰을 취소하려고 저장한다.
     */
    @Transactional
    fun appleLogin(identityToken: String, rawNonce: String?, nickname: String?, authorizationCode: String?): AuthTokens {
        val profile = appleApiClient.fetchProfile(identityToken, rawNonce)
        val credential = findOrRegister(profile.copy(nickname = nickname))
        authorizationCode?.let { saveAppleRevokeToken(credential, it, profile.clientId) }
        return issueTokens(credential.memberId)
    }

    /**
     * SNS 공통 처리: 이 SNS 계정이 연결된 회원이 있으면 그 수단을 돌려주고 (연동된 계정 포함),
     * 없으면 자동 가입한다 (find-or-create).
     */
    private fun findOrRegister(profile: OAuthProfile): AuthCredential {
        return authCredentialRepository.findByProviderAndProviderId(profile.provider, profile.providerId)
            ?: registerSocialMember(profile)
    }

    /**
     * SNS 첫 로그인 시 자동 가입: 회원과 로그인 수단이 한 트랜잭션에서 함께 만들어진다.
     * 같은 이메일의 다른 계정이 있어도 새 계정을 만든다. 같은 이메일이라도 수단이 다르면 다른 계정이다 (PRD 9.17).
     * 이메일만 보고 합치면 남의 계정이 넘어갈 수 있기 때문이다.
     */
    private fun registerSocialMember(profile: OAuthProfile): AuthCredential {
        val member = memberRepository.save(
            // 이름이 없거나 20자를 넘으면 Member가 기본 이름으로 바꾸거나 자른다
            Member(email = profile.email, nickname = profile.nickname.orEmpty(), profileImageUrl = profile.profileImageUrl),
        )
        return authCredentialRepository.save(
            AuthCredential(memberId = member.id, provider = profile.provider, providerId = profile.providerId),
        )
    }

    /**
     * authorizationCode를 Apple refresh 토큰으로 바꿔 암호화해 저장한다.
     * 실패해도 로그인은 막지 않는다. 앱은 로그인할 때마다 새 코드를 보내므로 다음 로그인 때 다시 저장된다.
     * 저장한 토큰이 없으면 탈퇴할 때 Apple 토큰 취소를 건너뛰고 경고 로그를 남긴다.
     */
    private fun saveAppleRevokeToken(credential: AuthCredential, authorizationCode: String, clientId: String?) {
        if (clientId == null || !tokenCipher.isConfigured || !appleApiClient.canManageTokens) {
            log.warn("Apple 토큰 교환 설정이 없어 철회용 토큰을 저장하지 않는다: memberId={}", credential.memberId)
            return
        }
        try {
            val refreshToken = appleApiClient.exchangeAuthorizationCode(authorizationCode, clientId)
            credential.replaceRevokeToken(tokenCipher.encrypt(AppleRevokeToken(clientId, refreshToken).encode()))
        } catch (e: Exception) {
            log.warn("Apple authorizationCode 교환 실패: memberId={}, reason={}", credential.memberId, e.message)
        }
    }

    // ==================== 계정 연동 ====================

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

    /** 로그인된 회원에게 Apple 로그인 수단을 연결한다. authorizationCode 규칙은 appleLogin과 같다. */
    @Transactional
    fun linkApple(memberId: Long, identityToken: String, rawNonce: String?, authorizationCode: String?) {
        val profile = appleApiClient.fetchProfile(identityToken, rawNonce)
        val credential = link(memberId, profile)
        authorizationCode?.let { saveAppleRevokeToken(credential, it, profile.clientId) }
    }

    private fun link(memberId: Long, profile: OAuthProfile): AuthCredential {
        // 이 SNS 계정이 이미 어딘가(다른 회원 포함)에 연결되어 있으면 거절
        if (authCredentialRepository.findByProviderAndProviderId(profile.provider, profile.providerId) != null) {
            throw SnsAccountAlreadyLinkedException()
        }
        // 이 회원이 같은 provider의 수단을 이미 갖고 있어도 거절 (provider당 1개)
        if (authCredentialRepository.existsByMemberIdAndProvider(memberId, profile.provider)) {
            throw SnsAccountAlreadyLinkedException()
        }
        return authCredentialRepository.save(
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

    // ==================== 재발급 / 로그아웃 ====================

    /**
     * refresh 토큰이 유효하면 새 access 토큰을 발급한다.
     * DB에 없으면(위조·로그아웃됨·탈퇴함) 또는 만료됐으면 401 — 클라이언트는 재로그인으로 보낸다.
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
