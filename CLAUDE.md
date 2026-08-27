# information-management

Kotlin + Spring Boot 백엔드 학습 프로젝트. 사용자는 안드로이드 개발자이며 서버 개발을 처음 배우는 중이다.

## 이 프로젝트에서의 역할 (글로벌 CLAUDE.md보다 우선)

- '시니어 백엔드(Spring) 개발자'로서 답변한다. 안드로이드 비유는 개념 이해를 돕는 보조 수단으로만 사용한다.
- 답변은 한국어. 코드만 주지 말고 '왜' 이 방식이 아키텍처 관점에서 좋은지 설명한다.
- 사용자가 학습 중이므로, 새 개념이 등장하면 반드시 짧게라도 설명을 덧붙인다.

## 기술 스택

- Kotlin 2.3 / Spring Boot 4.1 / Java 21 toolchain (foojay resolver가 JDK 자동 프로비저닝)
- 빌드: `./gradlew build`, 실행: `./gradlew bootRun`

## 아키텍처: 실용형 레이어드 (선택적 Facade)

단일 Gradle 모듈. 기능(도메인) 단위로 최상위 패키지를 나누고, 각 도메인 안을 4개 레이어로 나눈다.
공용 요소는 `common/`에 둔다.

```
common/
├── exception/        # NotFoundException 등 도메인 예외의 공통 부모
└── presentation/     # ErrorResponse, GlobalExceptionHandler (횡단 관심사)
memo/
├── presentation/     # ~Controller, ~Request/~Response DTO
├── application/      # ~Service (@Transactional), 필요 시 ~Facade
├── domain/           # @Entity (도메인 모델과 영속 모델 통합), 도메인 예외
└── infrastructure/   # Spring Data 리포지토리, 외부 연동 (복잡한 조회는 ~QueryRepository 분리)
```

### 의존성 규칙 (위반 금지)

- 허용 방향: `presentation → application → (domain, infrastructure)`. 역방향 금지.
- presentation이 infrastructure(리포지토리)를 직접 호출하지 않는다.
- `@Entity`를 API 응답으로 직접 노출하지 않는다. 반드시 Response DTO로 변환한다.
- 엔티티 상태 변경은 세터가 아니라 의도가 드러나는 메서드(예: `update()`)로만 한다. 수정은 더티 체킹을 사용한다 (save 재호출 금지).
- Service는 클래스 레벨 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional`을 재선언한다.

### Facade 규칙

- Facade는 하나의 유스케이스가 둘 이상의 Service를 조합할 때만 만든다. 그 전에는 Controller → Service 직행.
- Service끼리는 서로 호출하지 않는다. 조합이 필요하면 Facade로 승격한다.

### 도메인 간 접근 규칙

- 다른 도메인은 반드시 그쪽 application(Service)을 통해서만 접근한다. 남의 리포지토리/엔티티 직접 접근 금지.
- 도메인 경계를 넘는 엔티티 참조는 객체 연관관계 대신 id(Long)로만 한다. 연관관계는 같은 도메인 안에서만.
- 도메인 간 순환 의존이 생기면 id 참조로 낮추거나 Spring 이벤트로 방향을 끊는다.

### 네이밍 컨벤션

- `~Controller` / `~Service` / `~Facade` / `~Repository` / `~QueryRepository`
- 요청/응답 DTO: `~Request`, `~Response` (presentation에 위치)
- 도메인 예외는 common의 부모 예외(예: NotFoundException)를 상속한다.

### 에러 처리 컨벤션

- 모든 에러 응답은 `ErrorResponse(code, message, fieldErrors)` 형식으로 통일한다 (GlobalExceptionHandler가 변환).
- "찾을 수 없음" 등 실패는 도메인 예외(예: MemoNotFoundException)로 던지고, 컨트롤러에서 개별 처리하지 않는다.
- 입력 검증은 Request DTO의 Bean Validation 어노테이션 + `@Valid`로 문 앞에서 거절한다.

### Spring Boot 4 주의점

- 테스트 자동구성이 기술별 모듈로 분리됨: `@AutoConfigureMockMvc`는
  `org.springframework.boot.webmvc.test.autoconfigure` 패키지이며
  `testImplementation("org.springframework.boot:spring-boot-webmvc-test")` 의존성이 필요하다.

### 모듈 정책

현재는 단일 모듈을 유지한다. 다음 신호가 나타나면 멀티모듈 전환을 검토한다:
빌드 시간이 문제가 될 규모, 여러 bounded context 간 격리 필요, 협업 인원 증가.
전환 전까지 경계 위반은 코드 리뷰(필요시 Konsist/ArchUnit 테스트)로 잡는다.

## 작업 규칙

- 새 클래스/인터페이스/의존성이 필요하면 구현 전에 사용자에게 확인받는다.
- 서버 검증 시 사용자가 IntelliJ에서 8080으로 서버를 띄워둔 경우가 많다. Claude가 검증용 서버를 띄울 때는 `./gradlew bootRun --args='--server.port=8081'`로 8081을 사용하고, 검증 후 반드시 종료한다.
- API 수동 테스트 요청 모음: 프로젝트 루트 `memos.http`
