package io.github.qkqnfld.information_management.dubbing.infrastructure

import io.github.qkqnfld.information_management.dubbing.domain.DubbingJob
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface DubbingJobRepository : JpaRepository<DubbingJob, Long> {

    /** 소유권 확인을 겸하는 단건 조회: 남의 작업이면 (있어도) null을 돌려준다. */
    fun findByIdAndMemberId(id: Long, memberId: Long): DubbingJob?

    fun findAllByMemberId(memberId: Long, pageable: Pageable): Page<DubbingJob>

    /**
     * Worker가 처리할 다음 작업 하나를 가져오고 그 행을 잠근다.
     * 대상: 대기 중(PENDING)인 작업, 또는 처리 도중 서버가 멈춰 PROCESSING에 오래 남은 작업.
     *
     * - for update: 트랜잭션이 끝날 때까지 이 행을 다른 트랜잭션이 바꾸지 못하게 잠근다.
     * - skip locked: 이미 잠긴 행은 기다리지 않고 건너뛴다.
     *   서버를 여러 대로 늘려도 Worker들이 같은 작업을 중복 처리하지 않는다.
     * JPQL에는 skip locked 문법이 없어 PostgreSQL 네이티브 쿼리로 쓴다.
     */
    @Query(
        value = """
            select * from dubbing_jobs
            where status = 'PENDING'
               or (status = 'PROCESSING' and started_at < :staleBefore)
            order by id
            limit 1
            for update skip locked
        """,
        nativeQuery = true,
    )
    fun findNextForProcessing(@Param("staleBefore") staleBefore: LocalDateTime): DubbingJob?
}
