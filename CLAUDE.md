# information-management

Kotlin + Spring Boot 백엔드 학습 프로젝트. 사용자는 안드로이드 개발자이며 서버 개발을 처음 배우는 중이다.

## 이 프로젝트에서의 역할 (글로벌 CLAUDE.md보다 우선)

- '시니어 백엔드(Spring) 개발자'로서 답변한다. 안드로이드 비유는 개념 이해를 돕는 보조 수단으로만 사용한다.
- 답변은 한국어. 코드만 주지 말고 '왜' 이 방식이 아키텍처 관점에서 좋은지 설명한다.
- 사용자가 학습 중이므로, 새 개념이 등장하면 반드시 짧게라도 설명을 덧붙인다.

## 기술 스택

- Kotlin 2.3 / Spring Boot 4.1 / Java 21 toolchain (foojay resolver가 JDK 자동 프로비저닝)
- 빌드: `./gradlew build`, 실행: `./gradlew bootRun`
- DB: PostgreSQL 17. 로컬 개발은 `docker compose up -d db`(루트 compose.yaml, localhost:5432),
  앱까지 컨테이너로 돌리려면 `docker compose up -d --build` (호스트 포트는 `APP_PORT`로 변경 가능).
  테스트는 Testcontainers가 PostgreSQL 컨테이너를 자동으로 띄운다(jdbc:tc: URL) — **테스트 실행에 Docker 필요**.
  클라우드 DB(RDS) 전환 가이드는 `docs/rds-setup.md`, EC2(서울) 생성·Docker 배포 가이드는 `docs/ec2-setup.md`
  (이미지는 amd64 전용 → t3 계열만, 컨테이너가 IAM 역할을 쓰려면 메타데이터 홉 제한 2).
- 스키마는 Flyway가 관리한다 (`src/main/resources/db/migration/V*.sql`, `ddl-auto=validate`).
  스키마 변경 = 엔티티 수정 + 다음 번호의 새 V파일 추가. 이미 적용된 V파일은 절대 수정하지 않는다
  (체크섬 불일치로 서버가 뜨지 않음). 기존 개발 DB는 baseline-version=4로 베이스라인 처리됨.
- 인증: JWT access 토큰 1시간(무상태) + refresh 토큰 30일(DB 저장, RefreshToken 엔티티). 설정은 `common/security/`.
  로그아웃 = refresh 삭제, 재발급 = POST /auth/refresh. permitAll: /auth/**, GET /dubbing-options.
  나머지는 `Authorization: Bearer <access>` 필수. JwtAuthenticationFilter는 토큰의 회원이 아직 있는지도
  확인한다(ActiveMemberChecker, 탈퇴 직후 남은 access 토큰 거절).
  만료 refresh는 매일 04시 @Scheduled 배치(RefreshTokenCleanupScheduler)가 정리한다.
- 로그인은 SNS(카카오·구글·Apple)뿐이고 이메일 가입·로그인은 없다. 첫 SNS 로그인이 곧 가입(find-or-create)이다.
  카카오: access 토큰으로 user/me 조회 + access_token_info의 app_id가 KAKAO_APP_ID인지 확인.
  구글·Apple: 공개키(JWKS, JwksKeyLocator)로 서버가 직접 서명 검증 + aud 확인.
  구글 aud = GOOGLE_CLIENT_IDS(Web 클라이언트 ID, 앱이 serverClientId로 넘김), Apple aud = APPLE_CLIENT_IDS(번들 ID).
  설정값이 비면 그 SNS 로그인은 전부 거절한다. Apple은 authorizationCode를 refresh 토큰으로 바꿔
  AuthCredential.revokeToken에 암호화(TokenCipher, TOKEN_ENCRYPTION_KEY)해 저장한다 (탈퇴 때 취소용).
  테스트는 SNS 클라이언트를 @MockitoBean으로 대체한다 (클라이언트 자체는 MockRestServiceServer로 단위 테스트).
- 계정: 회원(Member="사람")과 로그인 수단(AuthCredential="문", 1:N)이 분리되어 있다.
  같은 이메일이라도 수단이 다르면 다른 계정이다 (PRD 9.17, 이메일 유니크 아님, 이메일로 자동 통합 금지).
  연동 API(/members/me/links/*)는 있지만 PRD상 계정 합치기는 2차라 앱은 아직 쓰지 않는다.
  프로필 수정 = PATCH /members/me(이름). 탈퇴 = DELETE /members/me: 한 트랜잭션에서 계정을 지우고
  withdrawal_tasks에 후처리(카카오 연결 끊기, Apple 토큰 취소, 도메인별 데이터 정리)를 등록하면
  WithdrawalTaskWorker(@Scheduled, DB 큐, 지수 백오프 재시도)가 처리한다. 다른 도메인은
  member의 WithdrawnMemberDataCleaner를 구현해 자기 데이터를 지운다 (DubbingDataCleaner, MemoDataCleaner).
- 메모/태그는 소유자(Memo.memberId, id 참조) 기반: 모든 조회/수정/삭제는 로그인 회원 범위로 한정되고,
  남의 메모 접근은 404로 존재를 숨긴다 (소유권 검사는 findByIdAndMemberId 쿼리에 포함).
  통합 테스트는 @BeforeEach에서 TestMembers(src/test …/support)로 회원을 만들고 토큰을 얻는다. 테스트용 application.properties는
  main을 '대체'하므로 main에 필수 속성을 추가하면 테스트 쪽에도 같이 추가해야 한다.
- 더빙(dubbing 도메인): Typecast TTS(TtsClient 인터페이스 → TypecastTtsClient)로 AI 목소리(PresetVoice, 앱 목록 순서 = 선언 순서)와
  내 목소리(CustomVoice, 회원당 1개, 복제 동의 필수, 업체 voice_id는 앱에 노출 금지)를 합성한다.
  POST /dubbing-jobs는 202로 접수만 하고, DubbingJobWorker(@Scheduled, DB를 큐로 사용, for update skip locked)가
  트랜잭션 밖에서 합성 → S3 업로드 → 조회 시 presigned URL. 속도는 배율(speed 0.5~2.0, Typecast audio_tempo),
  글자 수는 200자. 더빙 시트 선택지(목소리·속도 버튼·글자 수)는 공개 API GET /dubbing-options가 준다.
  키는 환경변수(TYPECAST_API_KEY, AWS_S3_BUCKET),
  AWS 권한은 IAM 역할/기본 자격 증명 체인. 테스트는 TtsClient·AudioStorage를 @MockitoBean으로 대체하고
  Worker는 processNext()를 직접 호출한다. 설정 가이드 `docs/s3-setup.md`, 남은 작업 `docs/dubbing-todo.md`.

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
- 서버 검증 시 사용자가 IntelliJ에서 8080으로 서버를 띄워둔 경우가 많다. Claude가 검증용 서버를 띄울 때는 `./gradlew bootRun --args='--server.port=8081'`(compose의 db가 떠 있어야 함) 또는 `APP_PORT=8082 docker compose up -d`를 사용하고, 검증 후 반드시 종료한다.
- API 수동 테스트 요청 모음: 프로젝트 루트 `memos.http`
- 앱(CMP: Android·iOS) 연동 가이드: `docs/app-api-guide.md`(동작 규칙) + `docs/openapi.json`(형식). API를 바꾸면 둘 다 갱신한다.
- API 문서: springdoc이 컨트롤러·DTO에서 자동 생성한다. 로컬 `http://localhost:8080/swagger-ui.html`.
  설정은 `common/config/OpenApiConfig.kt` (공개 API 판정은 SecurityConfig의 permitAll과 맞춘다).
  메모 API는 문서에서 제외(`springdoc.paths-to-exclude`). 출시 전까지 EC2는 개발 서버라 Swagger를 켜 두고,
  운영 전환 시 환경변수로 끈다 (`docs/dubbing-todo.md` 0번). 검증은 `OpenApiDocsTest`.
