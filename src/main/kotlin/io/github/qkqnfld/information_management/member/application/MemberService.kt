package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.common.security.ActiveMemberChecker
import io.github.qkqnfld.information_management.member.domain.AuthProvider
import io.github.qkqnfld.information_management.member.domain.Member
import io.github.qkqnfld.information_management.member.domain.MemberNotFoundException
import io.github.qkqnfld.information_management.member.domain.WithdrawalTask
import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskType
import io.github.qkqnfld.information_management.member.infrastructure.AuthCredentialRepository
import io.github.qkqnfld.information_management.member.infrastructure.MemberRepository
import io.github.qkqnfld.information_management.member.infrastructure.RefreshTokenRepository
import io.github.qkqnfld.information_management.member.infrastructure.WithdrawalTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 회원 비즈니스 로직: 내 정보 조회, 프로필 수정, 탈퇴.
 * 가입은 첫 SNS 로그인 때 AuthService가 한다.
 */
@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberRepository,
    private val authCredentialRepository: AuthCredentialRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val withdrawalTaskRepository: WithdrawalTaskRepository,
    /** 다른 도메인이 등록한 데이터 정리 구현체 목록. 여기서는 이름만 쓰고, 실행은 Worker가 한다. */
    private val dataCleaners: List<WithdrawnMemberDataCleaner>,
) : ActiveMemberChecker {

    private val log = LoggerFactory.getLogger(MemberService::class.java)

    // ==================== 조회 ====================

    fun findById(id: Long): Member {
        return memberRepository.findByIdOrNull(id) ?: throw MemberNotFoundException(id)
    }

    /** 이 회원에게 연결된 로그인 수단 목록 (계정 관리 화면의 "카카오로 로그인됨" 데이터). */
    fun findProviders(memberId: Long): List<AuthProvider> {
        return authCredentialRepository.findByMemberId(memberId).map { it.provider }
    }

    /** JwtAuthenticationFilter가 요청마다 부른다. 기본 키 조회 한 번이라 비용이 작다. */
    override fun isActive(memberId: Long): Boolean {
        return memberRepository.existsById(memberId)
    }

    // ==================== 프로필 수정 ====================

    /**
     * 이름을 바꾼다 (마이 > [프로필 수정]).
     * 엔티티의 의도 메서드로 값을 바꾸고 save를 다시 부르지 않는다. 트랜잭션이 끝날 때 더티 체킹이 UPDATE한다.
     */
    @Transactional
    fun updateNickname(memberId: Long, nickname: String): Member {
        val member = findById(memberId)
        member.changeNickname(nickname)
        return member
    }

    // ==================== 탈퇴 ====================

    /**
     * 회원 탈퇴 (PRD 9.17 S16).
     *
     * 이 트랜잭션에서 하는 일 (계정 잠금):
     * 1. 외부 정리 작업을 등록한다 (카카오 연결 끊기, Apple 토큰 취소, 다른 도메인 데이터 정리).
     * 2. refresh 토큰, 로그인 수단, 회원을 지운다.
     * 이 트랜잭션이 커밋되면 같은 SNS 계정으로 로그인해도 새 계정이 만들어지고,
     * 남은 access 토큰도 JwtAuthenticationFilter(ActiveMemberChecker)에서 거절된다.
     *
     * 외부 호출은 여기서 하지 않는다. 사용자를 기다리게 하지 않고, 실패하면 Worker가 다시 시도한다.
     * 작업 등록과 계정 삭제가 한 트랜잭션이라, 둘 중 하나만 일어나는 일은 없다.
     */
    @Transactional
    fun withdraw(memberId: Long) {
        val member = findById(memberId)
        val credentials = authCredentialRepository.findByMemberId(memberId)

        val tasks = buildList {
            credentials.forEach { credential ->
                when (credential.provider) {
                    AuthProvider.KAKAO -> add(WithdrawalTask(memberId, WithdrawalTaskType.KAKAO_UNLINK, credential.providerId))
                    AuthProvider.APPLE -> credential.revokeToken
                        ?.let { add(WithdrawalTask(memberId, WithdrawalTaskType.APPLE_REVOKE, it)) }
                        ?: log.warn("Apple 철회용 토큰이 없어 Apple 토큰 취소를 건너뛴다: memberId={}", memberId)
                    // 구글: 서버는 idToken만 받아 취소할 토큰이 없다. 앱이 기기의 로그인 상태를 지운다.
                    AuthProvider.GOOGLE -> Unit
                }
            }
            dataCleaners.forEach { add(WithdrawalTask(memberId, WithdrawalTaskType.DATA_CLEANUP, it.name)) }
        }
        withdrawalTaskRepository.saveAll(tasks)

        refreshTokenRepository.deleteByMemberId(memberId)
        authCredentialRepository.deleteAll(credentials)
        memberRepository.delete(member)
        log.info("회원 탈퇴: memberId={}, 후처리 작업 {}건 등록", memberId, tasks.size)
    }
}
