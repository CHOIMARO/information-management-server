package io.github.qkqnfld.information_management.dubbing.infrastructure

import io.github.qkqnfld.information_management.dubbing.domain.CustomVoice
import org.springframework.data.jpa.repository.JpaRepository

interface CustomVoiceRepository : JpaRepository<CustomVoice, Long> {

    /** 소유권 확인을 겸하는 단건 조회: 남의 목소리면 (있어도) null을 돌려준다. */
    fun findByIdAndMemberId(id: Long, memberId: Long): CustomVoice?

    fun findAllByMemberIdOrderByIdAsc(memberId: Long): List<CustomVoice>

    fun existsByMemberId(memberId: Long): Boolean
}
