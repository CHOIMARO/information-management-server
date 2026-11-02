package io.github.qkqnfld.information_management.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * AWS S3 클라이언트 Bean 설정.
 *
 * 자격 증명(Access Key)은 코드와 설정 파일에 두지 않는다.
 * AWS SDK의 기본 자격 증명 체인이 실행 환경에서 자동으로 찾는다.
 * - EC2: 인스턴스에 연결된 IAM 역할
 * - 로컬: `aws configure`로 만든 ~/.aws/credentials, 또는 AWS_* 환경변수
 * 생성 시점에는 자격 증명을 읽지 않으므로, 자격 증명이 없는 테스트 환경에서도 Bean은 만들어진다.
 */
@Configuration
class S3Config(
    @Value("\${aws.region}") private val region: String,
) {

    @Bean
    fun s3Client(): S3Client = S3Client.builder()
        .region(Region.of(region))
        .build()

    /** presigned URL 발급기. 서명 계산만 하고 네트워크 호출은 하지 않는다. */
    @Bean
    fun s3Presigner(): S3Presigner = S3Presigner.builder()
        .region(Region.of(region))
        .build()
}
