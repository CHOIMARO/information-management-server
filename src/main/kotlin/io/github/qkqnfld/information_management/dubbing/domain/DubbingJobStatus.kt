package io.github.qkqnfld.information_management.dubbing.domain

/**
 * 더빙 작업의 처리 상태.
 * 기본 흐름은 PENDING → PROCESSING → COMPLETED이다.
 * 일시적인 오류가 나면 PROCESSING → PENDING으로 돌아가 다시 시도하고, 한도를 넘으면 FAILED가 된다.
 */
enum class DubbingJobStatus {
    /** 처리 대기 (새 작업, 또는 일시적인 오류 뒤 재시도 대기) */
    PENDING,

    /** Worker가 처리 중 */
    PROCESSING,

    /** 완료. 결과 파일이 S3에 있다 */
    COMPLETED,

    /** 실패. 다시 시도하지 않는다 */
    FAILED,
}
