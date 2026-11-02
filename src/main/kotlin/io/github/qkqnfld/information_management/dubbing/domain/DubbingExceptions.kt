package io.github.qkqnfld.information_management.dubbing.domain

import io.github.qkqnfld.information_management.common.exception.BadRequestException
import io.github.qkqnfld.information_management.common.exception.ConflictException
import io.github.qkqnfld.information_management.common.exception.NotFoundException
import io.github.qkqnfld.information_management.common.exception.ServiceUnavailableException

/** 더빙 작업이 없거나 남의 작업이다 → 404 (남의 작업도 존재를 숨긴다) */
class DubbingJobNotFoundException(id: Long) :
    NotFoundException(code = "DUBBING_JOB_NOT_FOUND", message = "더빙 작업을 찾을 수 없습니다. id=$id")

/** 내 목소리가 없거나 남의 목소리다 → 404 */
class CustomVoiceNotFoundException(id: Long) :
    NotFoundException(code = "CUSTOM_VOICE_NOT_FOUND", message = "목소리를 찾을 수 없습니다. id=$id")

/** 회원당 1개인 내 목소리를 또 등록하려고 했다 → 409 */
class CustomVoiceLimitExceededException :
    ConflictException(
        code = "CUSTOM_VOICE_LIMIT_EXCEEDED",
        message = "내 목소리는 1개까지 등록할 수 있습니다. 기존 목소리를 삭제한 뒤 다시 등록해 주세요",
    )

/** TTS 업체가 녹음 파일을 거절했다 (형식, 길이, 크기 등) → 400 */
class InvalidVoiceSampleException :
    BadRequestException(
        code = "INVALID_VOICE_SAMPLE",
        message = "녹음 파일을 처리할 수 없습니다. 5~150초 길이의 WAV 또는 MP3 파일인지 확인해 주세요",
    )

/** TTS 업체 쪽 문제로 지금은 처리할 수 없다 → 503 */
class VoiceServiceUnavailableException :
    ServiceUnavailableException(
        code = "VOICE_SERVICE_UNAVAILABLE",
        message = "음성 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요",
    )
