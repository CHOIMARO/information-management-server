package io.github.qkqnfld.information_management.member.application

/**
 * 탈퇴한 회원의 데이터를 지우는 쪽이 구현하는 계약. 다른 도메인(더빙, 메모 등)이 구현한다.
 *
 * 왜 인터페이스인가: 회원 도메인이 더빙 도메인의 서비스를 직접 부르면, 회원이 더빙을 알게 된다.
 * 도메인이 늘어날 때마다 회원 코드를 고쳐야 한다. 그래서 회원 도메인은 이 계약만 정하고,
 * 각 도메인이 자기 데이터를 지우는 구현을 Spring 빈으로 등록한다 (의존성 역전).
 * 의존 방향: 더빙 → 회원(이 인터페이스). 회원은 더빙을 모른다.
 *
 * 실행: 탈퇴하면 구현체마다 WithdrawalTask(DATA_CLEANUP)가 하나씩 만들어지고, Worker가 clean을 부른다.
 * 실패하면 Worker가 간격을 늘려 가며 다시 부른다. 그래서 clean은 여러 번 불려도 안전해야 한다 (멱등).
 */
interface WithdrawnMemberDataCleaner {

    /** 작업 기록에 남는 이름. 구현체마다 달라야 하고, 한 번 정하면 바꾸지 않는다 (남은 작업이 이 이름으로 구현체를 찾는다). */
    val name: String

    /**
     * 이 회원의 데이터를 모두 지운다. 외부 서비스(S3, TTS 업체)에 맡긴 데이터도 지운다.
     * @throws Exception 일부를 지우지 못했을 때. Worker가 나중에 다시 부른다.
     */
    fun clean(memberId: Long)
}
