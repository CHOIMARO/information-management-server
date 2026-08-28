plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.qkqnfld"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	// DB 스키마 버전 관리 (마이그레이션은 src/main/resources/db/migration). H2 지원은 core에 내장.
	// Boot 4는 기술별 자동구성이 분리되어 flyway-core만으로는 동작하지 않고 이 모듈이 필요하다
	implementation("org.springframework.boot:spring-boot-flyway")
	// Kotlin JDSL: 타입 안전 동적 쿼리 (조건 조합 검색). boot4-support가 Spring Boot 4용 실행기를 제공
	implementation("com.linecorp.kotlin-jdsl:jpql-dsl:3.8.1")
	implementation("com.linecorp.kotlin-jdsl:jpql-render:3.8.1")
	implementation("com.linecorp.kotlin-jdsl:spring-data-jpa-boot4-support:3.8.1")
	// JWT 발급/검증 라이브러리. api만 컴파일 의존, 구현체(impl)와 직렬화(jackson)는 런타임에만 필요
	implementation("io.jsonwebtoken:jjwt-api:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	// Jackson이 Kotlin 문법(생성자 기본값, null 가능성)을 이해하게 하는 모듈. Kotlin+Spring 표준 구성.
	implementation("tools.jackson.module:jackson-module-kotlin")
	runtimeOnly("com.h2database:h2")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")
	testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		// -Xemit-jvm-type-annotations: List<@Size(...) String> 같은 타입 인자 위 어노테이션을
		// 바이트코드에 남긴다. 없으면 Bean Validation이 컨테이너 요소 검증을 못 본다.
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property", "-Xemit-jvm-type-annotations")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
