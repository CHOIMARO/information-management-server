# 앱 연동 API 가이드 (Android·iOS, Compose Multiplatform)

앱이 쓰는 서버 API의 요청·응답 형식과 동작 규칙을 정리한다.
앱은 Compose Multiplatform(CMP)이다. 서버 호출과 토큰 처리는 공통 코드에서 하고, 로그인 SDK·음성 재생·HTTP 허용 설정만 플랫폼별로 나뉜다 (4절).

- **형식(필드, 타입, 필수 여부)**: `docs/openapi.json`이 정확한 기준이다. 서버 코드에서 자동으로 만든 문서다.
- **동작 규칙(토큰 재발급, 상태 조회 간격, URL 만료 등)**: OpenAPI에 담기지 않으므로 이 문서에 적는다.
- 화면으로 보기: `http://54.117.33.15/swagger-ui.html` (개발 서버) 또는 `http://localhost:8080/swagger-ui.html` (로컬 서버)

## 0. 기본 규칙

| 항목 | 값 |
|---|---|
| 서버 주소 | debug 빌드는 개발 서버 `http://54.117.33.15` (회사·집 와이파이에서만 접속된다). 로컬 서버 주소는 4-3을 본다. |
| 본문 형식 | JSON (`Content-Type: application/json`), UTF-8. 내 목소리 등록만 `multipart/form-data` |
| 인증 | `Authorization: Bearer <accessToken>` (공개 API 제외) |
| id | 모두 Long |
| 날짜·시간 | `"2026-11-02T15:40:43.105877"` 형식. 시간대 표시가 없고 **한국 시간(Asia/Seoul)** 이다. 소수점 자릿수는 일정하지 않다. kotlinx-datetime의 `LocalDateTime`으로 파싱한다. |
| 값이 없는 필드 | 생략하지 않고 `null`로 온다 |

공개 API(토큰 없이 호출)는 `POST /members`와 `/auth/**`뿐이다. 나머지는 모두 토큰이 필요하다.

## 1. 오류 응답

모든 오류는 같은 형식이다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 값이 올바르지 않습니다",
  "fieldErrors": [
    { "field": "password", "reason": "비밀번호는 8자 이상 64자 이하여야 합니다" }
  ]
}
```

- 앱은 **HTTP 상태 코드와 `code`** 로 분기한다. `message`는 바뀔 수 있으므로 분기에 쓰지 않는다.
- API별 도메인 오류의 `message`는 사용자 안내용 한국어 문장이다. 화면에 그대로 보여 줘도 된다.
- `fieldErrors`는 입력 검증 실패(`INVALID_REQUEST`)일 때만 채워진다. 그 외에는 빈 배열이다.

공통 오류 코드:

| 상태 | code | 뜻 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 입력 검증 실패. `fieldErrors`에 필드별 이유가 있다. |
| 400 | `MALFORMED_BODY` | JSON 형식 오류, 필수 필드 누락, enum에 없는 값 |
| 400 | `TYPE_MISMATCH` | 경로·쿼리 파라미터 형식 오류 (예: id 자리에 문자) |
| 401 | `UNAUTHORIZED` | 토큰이 없거나, 만료됐거나, 위조됐다. **세 경우를 구분하지 않는다.** |
| 404 | `NOT_FOUND` | 없는 경로 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 |
| 413 | `FILE_TOO_LARGE` | 업로드 파일이 25MB를 넘는다 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 Content-Type |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

## 2. 인증

### 2-1. 회원 가입 `POST /members` (공개)

```json
// 요청
{ "email": "user@example.com", "password": "password123", "nickname": "마로" }

// 응답 201
{ "id": 1, "email": "user@example.com", "nickname": "마로", "providers": ["LOCAL"], "createdAt": "2026-11-02T15:40:43.105877" }
```

| 필드 | 규칙 |
|---|---|
| `email` | 이메일 형식, 100자 이하 |
| `password` | 8~64자 |
| `nickname` | 비어 있지 않음, 20자 이하 |

오류: 400 `INVALID_REQUEST`, 409 `DUPLICATE_EMAIL`

가입은 토큰을 주지 않는다. 가입 후 바로 로그인 API를 호출한다.

### 2-2. 로그인 `POST /auth/login` (공개)

```json
// 요청
{ "email": "user@example.com", "password": "password123" }

// 응답 200
{ "accessToken": "eyJhbGciOi...", "refreshToken": "0b6f1c3e-..." }
```

오류: 401 `INVALID_CREDENTIALS` (이메일이나 비밀번호가 틀림)

### 2-3. 토큰 재발급 `POST /auth/refresh` (공개)

```json
// 요청
{ "refreshToken": "0b6f1c3e-..." }

// 응답 200: accessToken은 새 값, refreshToken은 요청과 같은 값
{ "accessToken": "eyJhbGciOi...(새 값)", "refreshToken": "0b6f1c3e-..." }
```

오류: 401 `INVALID_REFRESH_TOKEN` (만료, 로그아웃됨, 위조) → 로그인 화면으로 보낸다.

### 2-4. 로그아웃 `POST /auth/logout` (공개)

```json
// 요청
{ "refreshToken": "0b6f1c3e-..." }
// 응답 204 (본문 없음)
```

서버는 refresh 토큰을 지운다. 이미 발급된 access 토큰은 남은 수명 동안 유효하므로, **앱도 저장한 두 토큰을 모두 지운다.**

### 2-5. 내 정보 `GET /members/me`

```json
// 응답 200
{ "id": 1, "email": "user@example.com", "nickname": "마로", "providers": ["LOCAL"], "createdAt": "2026-11-02T15:40:43.105877" }
```

- `providers`: 이 회원에 연결된 로그인 수단. `LOCAL`(이메일), `KAKAO`, `GOOGLE`
- `email`: SNS로만 가입한 회원은 `null`일 수 있다.

### 2-6. 토큰 처리 규칙 (앱 구현 필수)

| 토큰 | 수명 | 비고 |
|---|---|---|
| access | 1시간 | 모든 보호 API 요청에 붙인다. |
| refresh | 로그인 시점부터 30일 | 재발급해도 **연장되지 않는다.** 30일이 지나면 다시 로그인해야 한다. |

1. 보호 API가 401을 돌려주면 access 토큰이 만료된 것으로 보고 `POST /auth/refresh`를 **한 번** 호출한다.
2. 재발급에 성공하면 새 access 토큰을 저장하고 원래 요청을 다시 보낸다.
3. 재발급이 401이면 저장한 토큰을 지우고 로그인 화면으로 보낸다.
4. **`/auth/**` 요청이 받은 401에는 재발급을 시도하지 않는다.** 로그인 실패(`INVALID_CREDENTIALS`)도 401이므로, 이 규칙이 없으면 로그인 실패를 토큰 만료로 오해하고 재발급을 무한히 반복한다.
5. 여러 요청이 동시에 401을 받아도 재발급은 한 번만 한다. 재발급 중에는 다른 요청이 기다렸다가 새 토큰으로 다시 보낸다. (Ktor `Auth` 플러그인의 bearer 방식을 쓰면 이 처리를 플러그인이 맡는다. 4-2를 본다.)

### 2-7. SNS 로그인 (나중에 구현)

| API | `token`에 넣는 값 | 주 사용처 |
|---|---|---|
| `POST /auth/login/kakao` | 카카오 SDK가 준 **access 토큰** | Android, iOS |
| `POST /auth/login/google` | 구글 로그인이 준 **idToken** | Android, iOS |
| `POST /auth/login/apple` | Apple 로그인이 준 **identityToken** | iOS |

요청은 `{ "token": "..." }`, 응답은 2-2와 같은 `TokenResponse`다. Apple만 요청 필드가 더 있다 (2-8).

오류: 401 `INVALID_SNS_TOKEN`, 409 `SOCIAL_EMAIL_CONFLICT` (같은 이메일로 가입된 계정이 이미 있음 → 기존 방식으로 로그인한 뒤 계정 연동을 안내한다)

계정 연동 API(`/members/me/links/*`)의 형식은 `openapi.json`을 본다. 해제 경로의 `provider`는 대문자(`KAKAO`, `GOOGLE`, `APPLE`, `LOCAL`)다.

### 2-8. Apple 로그인 (iOS)

```json
// 요청 POST /auth/login/apple
{
  "token": "<identityToken>",
  "nonce": "<앱이 만든 원본 nonce, 선택>",
  "nickname": "<첫 로그인 때 받은 이름, 선택, 20자 이하>"
}
```

| 필드 | 규칙 |
|---|---|
| `token` | `ASAuthorizationAppleIDCredential.identityToken`을 UTF-8 문자열로 바꾼 값 |
| `nonce` | 앱이 만든 **원본** 임의 문자열. Apple 요청(`ASAuthorizationAppleIDRequest.nonce`)에는 이 값의 **SHA-256 16진수 소문자**를 넣는다. 서버는 원본을 해싱해서 토큰의 `nonce`와 비교한다. 지금은 선택이지만 출시 전에 필수로 바뀐다. |
| `nickname` | Apple은 이름을 **첫 로그인 때만** 앱에 준다. 그때 받은 이름을 보낸다. 처음 가입할 때만 쓰이고, 이미 가입된 계정이면 무시된다. 없으면 "사용자"로 가입된다. |

- 응답은 2-2와 같은 `TokenResponse`다.
- 오류: 401 `INVALID_SNS_TOKEN` (서명·발급자·앱 ID·만료·nonce 중 하나라도 맞지 않음), 409 `SOCIAL_EMAIL_CONFLICT`, 400 `INVALID_REQUEST`
- 사용자가 이메일 숨기기를 고르면 `email`은 `...@privaterelay.appleid.com` 같은 Apple 대리 주소다.
- 연동: `POST /members/me/links/apple`에 `{ "token": "...", "nonce": "..." }` (응답 204)
- 서버의 환경변수 `APPLE_CLIENT_IDS`에 iOS 앱의 번들 ID가 들어 있어야 한다. 없으면 모든 Apple 로그인이 401이다.
- **로그인 화면 구성은 플랫폼마다 다르다.**
  - iOS: 이메일, 카카오, 구글, **Apple**. App Store는 제3자 로그인을 제공하는 앱에 Apple 로그인 같은 개인정보 보호형 로그인을 함께 요구한다.
  - Android: 이메일, 카카오, 구글. Apple 로그인 버튼은 두지 않는다.
  - iOS에서 Apple로만 가입한 사용자가 Android를 쓰려면, iOS에서 이메일·카카오·구글 수단을 먼저 연동해 두도록 안내한다.
- Apple 로그인 SDK 호출과 nonce 해싱(SHA-256, 예: CryptoKit)은 iOS 플랫폼 코드에서 한다. 공통 코드에는 결과(identityToken, 원본 nonce, 이름)만 넘긴다.

## 3. 더빙

### 3-1. 전체 흐름

```
1. GET  /voices                → 고를 수 있는 목소리 목록
2. POST /dubbing-jobs          → 202 접수 (status: PENDING)
3. GET  /dubbing-jobs/{id}     → 1초 간격으로 반복 조회
                                  PENDING → PROCESSING → COMPLETED 또는 FAILED
4. COMPLETED 응답의 audioUrl    → S3에서 mp3를 직접 받거나 스트리밍 재생
```

더빙은 요청 즉시 결과를 주지 않는다. 서버가 음성을 합성하는 동안 앱이 기다리지 않도록 **접수(202)와 결과 조회를 나눈** 비동기 방식이다. 보통 접수 후 1~3초 안에 끝난다.

### 3-2. 목소리 목록 `GET /voices`

```json
// 응답 200
{
  "presets": [ { "id": "DYLAN", "name": "딜런" } ],
  "custom": []
}
```

- `presets`: AI 목소리. 더빙 요청의 `presetVoice`에 `id`를 넣는다.
- `custom`: 내 목소리 (`{ "id": 3, "name": "내 목소리", "createdAt": "..." }`). 더빙 요청의 `customVoiceId`에 `id`를 넣는다.
- **목소리 id를 앱에 하드코딩하지 않는다.** 지금은 `DYLAN` 하나지만 한국어 목소리로 바뀔 예정이다. 화면은 항상 이 목록으로 그린다.

### 3-3. 더빙 요청 `POST /dubbing-jobs`

```json
// 요청: presetVoice와 customVoiceId 중 정확히 하나만 넣는다
{ "text": "안녕하세요. 첫 번째 더빙입니다.", "presetVoice": "DYLAN" }

// 응답 202 + 헤더 Location: /dubbing-jobs/12
{
  "id": 12, "status": "PENDING", "text": "안녕하세요. 첫 번째 더빙입니다.",
  "presetVoice": "DYLAN", "customVoiceId": null,
  "audioUrl": null, "createdAt": "2026-11-02T15:40:43.105877", "completedAt": null
}
```

| 필드 | 규칙 |
|---|---|
| `text` | 공백만으로 된 문장은 안 된다. 2000자 이하 |
| `presetVoice` | `GET /voices`의 `presets[].id` |
| `customVoiceId` | `GET /voices`의 `custom[].id` |

오류:
- 400 `INVALID_REQUEST`: 텍스트 규칙 위반, 또는 목소리를 둘 다 넣거나 하나도 넣지 않음 (`fieldErrors[].field`가 `voiceSelectedOnce`)
- 400 `MALFORMED_BODY`: `presetVoice`에 목록에 없는 값
- 404 `CUSTOM_VOICE_NOT_FOUND`: 내 목소리가 아니거나 없는 id

### 3-4. 상태 조회 `GET /dubbing-jobs/{id}`

응답 형식은 3-3과 같다.

| status | 뜻 | 앱 동작 |
|---|---|---|
| `PENDING` | 접수됨, 순서 대기 | 계속 조회 |
| `PROCESSING` | 합성 중 | 계속 조회 |
| `COMPLETED` | 완료. `audioUrl`, `completedAt`이 채워진다 | 조회 중단, 재생 |
| `FAILED` | 실패 | 조회 중단, "더빙에 실패했어요. 다시 시도해 주세요" 안내 |

조회 규칙:
1. 1초 간격으로 조회한다.
2. 60초가 지나도 끝나지 않으면 조회를 멈추고, 나중에 목록에서 확인하도록 안내한다.
3. 화면을 벗어나면 조회를 멈춘다 (ViewModel의 코루틴 범위 안에서 반복한다).
4. 실패 이유는 응답에 없다. 일시적인 오류는 서버가 이미 최대 3번까지 재시도했다. 사용자가 다시 시도하면 새 더빙 요청을 보낸다.

오류: 404 `DUBBING_JOB_NOT_FOUND` (없는 작업, 남의 작업, 삭제된 작업)

### 3-5. 음성 파일 받기 (`audioUrl`)

- `audioUrl`은 S3의 **presigned URL**이다. 서명이 들어 있는 임시 주소이고, HTTPS다.
- **유효 시간은 조회 응답을 받은 시점부터 10분**이다. 만료되면 S3가 403을 돌려준다. 파일이 이미 지워진 경우도 403이다.
- **이 주소를 저장해 두고 나중에 쓰지 않는다.** 재생하기 직전에 `GET /dubbing-jobs/{id}`를 다시 호출해서 새 주소를 받는다. 조회할 때마다 새 주소가 만들어진다.
- **이 주소에는 `Authorization` 헤더를 붙이지 않는다.** 서명이 이미 인증 역할을 하므로, 헤더를 붙이면 S3가 400 `InvalidArgument`("Only one auth mechanism allowed")로 거절한다. API용 Ktor 클라이언트가 이 주소에 토큰을 붙이지 않도록 설정한다 (4-2). 재생할 때는 주소를 플랫폼 플레이어(Android Media3, iOS AVPlayer)에 바로 넘긴다.
- 파일 형식: mp3, 320kbps, 44.1kHz, 모노

### 3-6. 더빙 기록 `GET /dubbing-jobs?page=0&size=20`

```json
// 응답 200
{
  "content": [ { "id": 12, "status": "COMPLETED", "...": "3-3과 같은 형식" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "hasNext": false
}
```

- `page`는 0부터 시작한다. 다음 페이지가 있는지는 `hasNext`로 판단한다.
- 기본 정렬은 최신순(`id` 내림차순)이다.
- 목록 안의 `audioUrl`도 10분 동안만 유효하다. 재생할 때는 3-5 규칙을 따른다.

### 3-7. 더빙 삭제 `DELETE /dubbing-jobs/{id}`

- 응답 204 (본문 없음). 서버의 음성 파일도 함께 지워진다.
- 처리 중인 작업도 지울 수 있다.
- 오류: 404 `DUBBING_JOB_NOT_FOUND`

### 3-8. 내 목소리 (지금은 쓰지 않음)

Typecast 무료 플랜에는 목소리 복제 슬롯이 없어서 지금은 등록이 실패한다. 유료 플랜으로 바꾼 뒤에 연동한다.

- 등록 `POST /voices` (`multipart/form-data`)
  - `file`: 녹음 파일. WAV 또는 MP3, 5~150초, 25MB 이하
  - `name`: 목소리 이름, 30자 이하
  - `consentAgreed`: `true`. 목소리 주인 본인이 복제에 동의했다는 값이다. 앱에서 동의 화면을 거친 뒤에만 `true`로 보낸다.
  - 응답 201: `{ "id": 3, "name": "내 목소리", "createdAt": "..." }`
  - 오류: 400 `INVALID_REQUEST`, 400 `INVALID_VOICE_SAMPLE`, 409 `CUSTOM_VOICE_LIMIT_EXCEEDED` (회원당 1개), 413 `FILE_TOO_LARGE`, 503 `VOICE_SERVICE_UNAVAILABLE`
  - 서버가 Typecast에 복제를 요청하는 동안 응답이 늦을 수 있다. 이 요청만 읽기 시간 제한을 60초 이상으로 둔다.
- 삭제 `DELETE /voices/{id}`: 응답 204. 오류 404 `CUSTOM_VOICE_NOT_FOUND`

## 4. 앱 구현 메모 (Compose Multiplatform)

### 4-1. 공통 코드와 플랫폼 코드 나누기

| 위치 | 담당 |
|---|---|
| `commonMain` | 서버 호출(Ktor Client), 요청·응답 모델(kotlinx.serialization), 토큰 처리(2-6), 더빙 상태 조회 반복(3-4), Repository, ViewModel, 화면 |
| `androidMain` | 카카오·구글 로그인 SDK 호출, Media3 재생, HTTP 허용 설정 |
| `iosMain` 또는 Swift 코드 | 카카오·구글·**Apple** 로그인 SDK 호출, nonce 해싱, AVPlayer 재생, HTTP 허용 설정 |

- 로그인 SDK는 플랫폼마다 다르다. 공통 코드에는 "SNS 토큰을 받아 온다"는 인터페이스만 두고, 플랫폼 코드가 SDK를 호출해서 토큰 문자열을 돌려준다. 그 토큰으로 서버를 호출하는 것은 공통 코드다. (`expect`/`actual` 또는 인터페이스 주입)
- 이렇게 나누면 토큰 처리 규칙(2-6)과 더빙 흐름(3절)을 한 번만 구현한다.

### 4-2. 네트워크 (Ktor Client)

- 엔진은 플랫폼별로 고른다. Android는 OkHttp 엔진, iOS는 Darwin 엔진을 쓴다. 클라이언트 설정 코드는 공통이다.
- 토큰 처리(2-6)는 `Auth` 플러그인의 bearer 방식으로 구현한다. 401 → 재발급 → 원래 요청 재시도, 그리고 동시에 여러 요청이 401을 받아도 재발급을 한 번만 하는 처리를 플러그인이 맡는다. 다음 두 가지는 직접 설정한다.
  1. **토큰은 API 서버로 가는 요청에만 붙인다.** `sendWithoutRequest`에서 요청 주소가 API 서버일 때만 `true`를 돌려준다. presigned URL(S3)에 토큰이 붙으면 S3가 거절한다 (3-5).
  2. **`/auth/**` 요청의 401에는 재발급하지 않는다.** `refreshTokens` 안에서 원래 요청 경로가 `/auth/`로 시작하면 재발급하지 않고 `null`을 돌려준다 (2-6의 4번).
- 내 목소리 등록(3-8)은 응답이 늦을 수 있다. 이 요청에만 `HttpTimeout`을 60초 이상으로 둔다.

### 4-3. 서버 주소와 HTTP 허용 (debug 빌드만)

| 실행 환경 | 서버 주소 |
|---|---|
| 개발 서버 | `http://54.117.33.15` |
| 로컬 서버 + Android 에뮬레이터 | `http://10.0.2.2:8080` (에뮬레이터에서 내 컴퓨터를 가리키는 특수 주소) |
| 로컬 서버 + iOS 시뮬레이터 | `http://localhost:8080` (시뮬레이터는 Mac의 네트워크를 그대로 쓴다) |
| 로컬 서버 + 실기기 | `http://<컴퓨터의 내부 IP>:8080` (기기와 컴퓨터가 같은 와이파이에 있어야 한다) |

- 서버 주소는 빌드 설정 한 곳에서 관리한다 (예: KMP용 빌드 설정 생성 도구, 또는 플랫폼별 값). 나중에 도메인과 HTTPS로 바꿀 때 이 값만 바꾼다.
- 지금 서버는 HTTP라서 두 플랫폼 모두 기본 설정으로는 접속이 막힌다. **debug 빌드에만** 예외를 둔다.
  - Android: debug 소스 세트의 `network_security_config.xml`에 `54.117.33.15`, `10.0.2.2`만 cleartext로 허용하고, debug 매니페스트에서만 연결한다.
  - iOS: Info.plist의 `NSAppTransportSecurity`에 `NSAllowsArbitraryLoads = YES`를 **Debug 구성에만** 넣는다. 도메인별 예외(`NSExceptionDomains`)는 IP 주소에 적용되지 않기 때문이다. Release 구성에는 넣지 않는다.
  - S3 주소(음성 파일)는 HTTPS라서 예외가 필요 없다.
- 도메인과 HTTPS를 붙이면 두 예외 설정을 모두 지운다.

### 4-4. 토큰 저장

- 두 토큰은 플랫폼의 보안 저장소에 둔다. Android는 Android Keystore로 보호하는 저장소, iOS는 Keychain이다.
- 공통 코드에는 저장소 인터페이스만 두고, 플랫폼별로 구현한다.

### 4-5. 레이어 배치 (공통 코드)

- Presentation: 화면(Compose), ViewModel. 더빙 상태 조회 반복은 ViewModel의 코루틴 범위에서 한다. 화면을 벗어나면 자동으로 멈춘다.
- Domain: `AuthRepository`, `DubbingRepository`, SNS 토큰 공급 인터페이스
- Data: Ktor API, 토큰 저장소, `Auth` 플러그인 설정

## 5. 이 문서와 `openapi.json` 갱신

서버 API가 바뀌면 `openapi.json`을 다시 받는다 (개발 서버에 새 버전을 배포한 뒤).

```bash
curl -s http://54.117.33.15/v3/api-docs | python3 -m json.tool --no-ensure-ascii > docs/openapi.json
```

동작 규칙이 바뀌면 이 문서를 함께 고친다.
