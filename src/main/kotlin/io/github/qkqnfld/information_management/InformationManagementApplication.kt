package io.github.qkqnfld.information_management

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * @EnableScheduling: @Scheduled가 붙은 메서드들을 주기 실행하는 스케줄러를 활성화한다.
 * (예: RefreshTokenCleanupScheduler의 만료 토큰 정리)
 */
@SpringBootApplication
@EnableScheduling
class InformationManagementApplication

fun main(args: Array<String>) {
	runApplication<InformationManagementApplication>(*args)
}
