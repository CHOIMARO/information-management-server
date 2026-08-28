package io.github.qkqnfld.information_management.common.config

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.orm.jpa.SharedEntityManagerCreator

/**
 * Kotlin JDSL 지원 설정.
 * JDSL의 자동구성(kotlinJdslJpqlExecutor)은 EntityManager '빈'을 요구하는데,
 * Spring Boot 4는 EntityManagerFactory만 빈으로 노출하고 EntityManager는 노출하지 않는다.
 * 그래서 트랜잭션 인지형(현재 트랜잭션의 영속성 컨텍스트로 위임하는) 공유 프록시를
 * 직접 빈으로 등록해 준다 — @PersistenceContext가 주입해 주는 것과 같은 종류의 객체다.
 */
@Configuration
class JdslConfig {

    @Bean
    fun sharedEntityManager(entityManagerFactory: EntityManagerFactory): EntityManager {
        return SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
    }
}
