package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.common.security.TokenCipher
import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskType
import io.github.qkqnfld.information_management.member.infrastructure.AppleApiClient
import io.github.qkqnfld.information_management.member.infrastructure.AppleRevokeToken
import io.github.qkqnfld.information_management.member.infrastructure.KakaoApiClient
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 탈퇴 후처리 작업 처리기. DB를 큐로 쓴다 (DubbingJobWorker와 같은 구조).
 *
 * 처리 순서와 트랜잭션:
 * 1. 작업 가져오기 (짧은 트랜잭션, WithdrawalTaskService.claimNext)
 * 2. 카카오·Apple·다른 도메인 정리 호출 (트랜잭션 밖)
 * 3. 결과 기록 (짧은 트랜잭션, complete 또는 recordFailure)
 * 외부 호출을 트랜잭션 밖에서 하는 이유: 외부 서비스를 기다리는 동안 DB 커넥션을 잡지 않으려는 것이다.
 *
 * 실패하면 1, 2, 4, 8 ... 분 간격으로 다시 시도하고, 한도(기본 10번)를 넘으면 FAILED로 남긴다.
 * FAILED 작업은 운영자가 로그와 withdrawal_tasks 테이블로 확인한다.
 */
@Component
class WithdrawalTaskWorker(
    private val withdrawalTaskService: WithdrawalTaskService,
    private val kakaoApiClient: KakaoApiClient,
    private val appleApiClient: AppleApiClient,
    private val tokenCipher: TokenCipher,
    dataCleaners: List<WithdrawnMemberDataCleaner>,
) {

    private val log = LoggerFactory.getLogger(WithdrawalTaskWorker::class.java)

    private val cleanersByName: Map<String, WithdrawnMemberDataCleaner> = dataCleaners.associateBy { it.name }

    init {
        require(cleanersByName.size == dataCleaners.size) { "WithdrawnMemberDataCleaner의 name이 겹칩니다" }
    }

    /**
     * 처리할 작업을 하나 꺼내 처리한다.
     * 테스트는 initial-delay를 길게 잡아 자동 실행을 막고, 이 메서드를 직접 부른다.
     */
    @Scheduled(
        fixedDelayString = "\${withdrawal.worker.fixed-delay-ms:5000}",
        initialDelayString = "\${withdrawal.worker.initial-delay-ms:10000}",
    )
    fun processNext() {
        val work = withdrawalTaskService.claimNext() ?: return
        try {
            execute(work)
            withdrawalTaskService.complete(work.taskId)
        } catch (e: Exception) {
            log.warn("탈퇴 후처리 실패: taskId={}, type={}, memberId={}", work.taskId, work.type, work.memberId, e)
            withdrawalTaskService.recordFailure(work.taskId, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun execute(work: WithdrawalWork) {
        when (work.type) {
            WithdrawalTaskType.KAKAO_UNLINK -> kakaoApiClient.unlink(work.target)
            WithdrawalTaskType.APPLE_REVOKE -> appleApiClient.revoke(AppleRevokeToken.decode(tokenCipher.decrypt(work.target)))
            WithdrawalTaskType.DATA_CLEANUP -> {
                val cleaner = cleanersByName[work.target]
                    ?: throw IllegalStateException("데이터 정리 구현체를 찾을 수 없습니다: ${work.target}")
                cleaner.clean(work.memberId)
            }
        }
    }
}
