package io.github.qkqnfld.information_management.dubbing.application

import io.github.qkqnfld.information_management.member.application.WithdrawnMemberDataCleaner
import org.springframework.stereotype.Component

/**
 * 탈퇴한 회원의 더빙 데이터를 지운다: 더빙 작업과 S3 결과 파일, 내 목소리와 업체 쪽 목소리.
 *
 * 회원 도메인이 정한 계약(WithdrawnMemberDataCleaner)을 더빙 도메인이 구현한다.
 * 그래서 회원 도메인은 더빙 도메인을 모르고, 더빙 쪽만 회원의 application 계약을 안다.
 * 두 Service(DubbingJobService, VoiceService)를 조합하므로 Facade 역할을 한다. Service끼리는 서로 부르지 않는다.
 */
@Component
class DubbingDataCleaner(
    private val dubbingJobService: DubbingJobService,
    private val voiceService: VoiceService,
) : WithdrawnMemberDataCleaner {

    override val name: String = "dubbing"

    override fun clean(memberId: Long) {
        // 작업을 먼저 지운다. 처리 대기 중인 작업이 지운 목소리를 찾다가 실패하는 일을 줄인다.
        dubbingJobService.deleteAllOfMember(memberId)
        voiceService.deleteAllOfMember(memberId)
    }
}
