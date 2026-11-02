package io.github.qkqnfld.information_management.dubbing.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Duration

/**
 * 더빙 결과 오디오를 S3에 저장한다.
 * 버킷은 비공개다. 앱은 presigned URL(서명된 임시 URL)로만 파일을 받는다.
 * 그래서 파일이 t3.small을 거치지 않고, URL이 새도 몇 분 뒤에는 쓸 수 없다.
 */
@Component
class AudioStorage(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    @Value("\${aws.s3.bucket}") private val bucket: String,
    @Value("\${dubbing.audio-url-expiration-minutes:10}") private val urlExpirationMinutes: Long,
) {

    fun upload(key: String, content: ByteArray, contentType: String) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .build()
        s3Client.putObject(request, RequestBody.fromBytes(content))
    }

    /** S3 삭제는 멱등이다. 없는 키를 지워도 오류가 나지 않는다. */
    fun delete(key: String) {
        val request = DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()
        s3Client.deleteObject(request)
    }

    /**
     * 다운로드용 presigned URL을 만든다.
     * 서명은 서버 안에서 계산만 하고 S3를 호출하지 않으므로 조회할 때마다 만들어도 비용이 거의 없다.
     */
    fun createDownloadUrl(key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(urlExpirationMinutes))
            .getObjectRequest(getObjectRequest)
            .build()
        return s3Presigner.presignGetObject(presignRequest).url().toString()
    }
}
