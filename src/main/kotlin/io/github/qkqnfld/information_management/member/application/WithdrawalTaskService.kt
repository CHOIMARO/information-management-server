package io.github.qkqnfld.information_management.member.application

import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskStatus
import io.github.qkqnfld.information_management.member.domain.WithdrawalTaskType
import io.github.qkqnfld.information_management.member.infrastructure.WithdrawalTaskRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 탈퇴 후처리 작업의 상태 변경 (트랜잭션 경계). DubbingJobService의 Worker 전용 메서드와 같은 구조다.
 * 실제 외부 호출은 WithdrawalTaskWorker가 트랜잭션 밖에서 한다.
 */
@Service
@Transactional(readOnly = true)
class WithdrawalTaskService(
    private val withdrawalTaskRepository: WithdrawalTaskRepository,
    @Value("\${withdrawal.worker.max-attempts:10}") private val maxAttempts: Int,
    @Value("\${withdrawal.worker.stale-after-minutes:10}") private val staleAfterMinutes: Long,
) {

    /**
     * 처리할 작업 하나를 가져와 PROCESSING으로 바꾼다. 처리할 작업이 없으면 null을 반환한다.
     * 엔티티 대신 값 사본을 반환한다. Worker는 트랜잭션 밖에서 일하기 때문이다.
     */
    @Transactional
    fun claimNext(now: LocalDateTime = LocalDateTime.now()): WithdrawalWork? {
        val task = withdrawalTaskRepository.findNextForProcessing(now, now.minusMinutes(staleAfterMinutes))
            ?: return null
        // PROCESSING에 멈춰 있던 작업이 이미 한도만큼 시도했다면 더는 시도하지 않는다
        if (!task.hasAttemptsLeft(maxAttempts)) {
            task.fail(task.lastError ?: "재시도 한도를 넘었습니다", now)
            return null
        }
        val target = task.target ?: run {
            task.fail("처리 대상이 없습니다", now)
            return null
        }
        task.start(now)
        return WithdrawalWork(taskId = task.id, memberId = task.memberId, type = task.type, target = target)
    }

    @Transactional
    fun complete(taskId: Long) {
        val task = withdrawalTaskRepository.findByIdOrNull(taskId) ?: return
        if (task.status == WithdrawalTaskStatus.PROCESSING) task.complete()
    }

    /** 실패를 기록한다. 재시도 횟수가 남았으면 간격을 두고 다시 시도하고, 아니면 FAILED로 끝낸다. */
    @Transactional
    fun recordFailure(taskId: Long, reason: String) {
        val task = withdrawalTaskRepository.findByIdOrNull(taskId) ?: return
        if (task.status != WithdrawalTaskStatus.PROCESSING) return
        if (task.hasAttemptsLeft(maxAttempts)) task.retryLater(reason) else task.fail(reason)
    }
}

/** Worker가 트랜잭션 밖에서 쓰는 작업 정보 사본. */
data class WithdrawalWork(
    val taskId: Long,
    val memberId: Long,
    val type: WithdrawalTaskType,
    val target: String,
)
