package io.github.qkqnfld.information_management.member.infrastructure

import io.github.qkqnfld.information_management.member.domain.WithdrawalTask
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface WithdrawalTaskRepository : JpaRepository<WithdrawalTask, Long> {

    fun findAllByMemberId(memberId: Long): List<WithdrawalTask>

    /**
     * Worker가 처리할 다음 작업 하나를 가져오고 그 행을 잠근다 (DubbingJobRepository와 같은 방식).
     * 대상: 다시 시도할 시각이 지난 대기 작업, 또는 처리 도중 서버가 멈춰 PROCESSING에 오래 남은 작업.
     * skip locked: 다른 Worker가 잠근 행은 건너뛰므로 같은 작업을 두 번 처리하지 않는다.
     */
    @Query(
        value = """
            select * from withdrawal_tasks
            where (status = 'PENDING' and next_attempt_at <= :now)
               or (status = 'PROCESSING' and started_at < :staleBefore)
            order by id
            limit 1
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun findNextForProcessing(
        @Param("now") now: LocalDateTime,
        @Param("staleBefore") staleBefore: LocalDateTime,
    ): WithdrawalTask?
}
