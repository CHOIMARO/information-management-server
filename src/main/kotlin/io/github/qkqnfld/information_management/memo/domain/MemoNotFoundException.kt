package io.github.qkqnfld.information_management.memo.domain

import io.github.qkqnfld.information_management.common.exception.NotFoundException

/** 요청한 id의 메모가 존재하지 않을 때 던지는 도메인 예외. */
class MemoNotFoundException(memoId: Long) :
    NotFoundException(code = "MEMO_NOT_FOUND", message = "메모를 찾을 수 없습니다. id=$memoId")
