package io.github.qkqnfld.information_management.memo.application

import io.github.qkqnfld.information_management.member.application.WithdrawnMemberDataCleaner
import org.springframework.stereotype.Component

/**
 * 탈퇴한 회원의 메모를 지운다. 회원 도메인이 정한 계약을 메모 도메인이 구현한다 (DubbingDataCleaner와 같은 방식).
 * 메모 기능은 제거할 예정이다. 기능을 지울 때 이 클래스도 함께 지운다.
 */
@Component
class MemoDataCleaner(
    private val memoService: MemoService,
) : WithdrawnMemberDataCleaner {

    override val name: String = "memo"

    override fun clean(memberId: Long) {
        memoService.deleteAllOfMember(memberId)
    }
}
