# AWS S3 버킷 생성 및 연결 가이드 (더빙 결과 저장)

더빙 결과 mp3는 S3 비공개 버킷에 저장한다.
앱은 서버가 발급한 presigned URL(서명된 임시 URL)로만 파일을 받는다.

```
[앱] ── GET /dubbing-jobs/{id} ──> [EC2 Spring] ── presigned URL 응답
[앱] ── presigned URL로 다운로드 ─────────────────> [S3 비공개 버킷]
[EC2 Spring] ── IAM 역할 권한으로 업로드·삭제 ────> [S3 비공개 버킷]
```

## 0. 핵심 원칙

- 코드와 설정 파일에는 AWS Access Key를 두지 않는다.
- EC2는 **IAM 역할**로 권한을 받는다. AWS SDK가 이 권한을 자동으로 찾는다.
- 로컬 개발은 `aws configure`로 만든 자격 증명을 같은 SDK가 자동으로 찾는다.
- 버킷은 **퍼블릭 액세스 차단**을 켠 상태로 둔다.

## 1. 버킷 생성

S3 콘솔 → **버킷 만들기**:

1. **리전**: EC2·RDS와 같은 리전을 고른다 (예: 서울 `ap-northeast-2`).
2. **버킷 이름**: 전 세계에서 유일해야 한다. 예: `magic-editor-<임의 문자열>`.
3. **객체 소유권**: ACL 비활성화 (기본값).
4. **퍼블릭 액세스 차단**: **모두 차단** (기본값). 끄지 않는다.
5. **버킷 버전 관리**: 비활성화 (기본값).
6. **기본 암호화**: SSE-S3 (기본값).

버킷 이름을 복사해 둔다. 이 값이 `AWS_S3_BUCKET` 환경변수가 된다.

## 2. IAM 정책 만들기 (최소 권한)

IAM 콘솔 → **정책** → **정책 생성** → JSON 탭에 아래 내용을 넣는다.
`<버킷 이름>`은 1번에서 만든 이름으로 바꾼다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::<버킷 이름>/dubbings/*"
    }
  ]
}
```

- 정책 이름 예: `magic-editor-s3`.
- 이 정책은 `dubbings/` 경로 아래 파일의 업로드·다운로드·삭제만 허용한다.
- `s3:GetObject`가 필요한 이유: presigned URL은 "서버의 권한을 잠깐 빌려주는 URL"이다. 서버에 읽기 권한이 없으면 URL도 동작하지 않는다.

## 3. EC2용 IAM 역할 만들기 (운영)

### 3-1. 역할 생성

1. IAM 콘솔 → **역할** → **역할 생성**을 연다.
2. **신뢰할 수 있는 엔터티 선택** 화면: 엔터티 유형 **AWS 서비스**, 사용 사례 **EC2**를 고르고 **다음**을 누른다.
3. **권한 추가** 화면: 2번에서 만든 정책(`magic-editor-s3`)을 검색해 체크하고 **다음**을 누른다.
4. **이름 지정, 검토 및 생성** 화면: 역할 이름을 입력한다. 예: `magic-editor-ec2-role`.
5. 화면 맨 아래의 **역할 생성**을 누른다. 이 버튼을 눌러야 역할이 실제로 만들어진다.
6. 역할 목록에서 `magic-editor-ec2-role`을 열고, **권한** 탭에 `magic-editor-s3`가 있는지 확인한다.

사용 사례를 EC2로 고르면 AWS가 같은 이름의 **인스턴스 프로파일**을 자동으로 만든다.
인스턴스 프로파일은 EC2가 역할을 받기 위한 껍데기다. 그래서 EC2 화면에서는 "IAM 인스턴스 프로파일"이라는 이름으로 이 역할을 고른다.

### 3-2. EC2에 역할 연결 (EC2가 있을 때 한다)

EC2가 아직 없으면 이 단계를 지금 하지 않는다. EC2를 만들 때 함께 한다.

- 새로 만드는 EC2: 인스턴스 생성 화면의 **고급 세부 정보 → IAM 인스턴스 프로파일**에서 `magic-editor-ec2-role`을 고른다 (`docs/ec2-setup.md` 2단계 7번).
- 이미 실행 중인 EC2: EC2 콘솔 → 인스턴스 → **작업 → 보안 → IAM 역할 수정**에서 고른다.

EC2 안에서는 별도 키 설정 없이 SDK가 역할 권한을 사용한다.
앱은 Docker 컨테이너 안에서 실행되므로, 인스턴스의 **메타데이터 응답 홉 제한이 2**여야 역할 권한을 받을 수 있다.

## 4. 서버에 필요한 환경변수 (참고표)

이 단계에는 할 작업이 없다. 서버가 읽는 값의 목록이다.
실제로 값을 넣는 곳은 서버를 실행하는 환경마다 다르다.

| 변수 | 값 | 필수 |
|---|---|---|
| `TYPECAST_API_KEY` | Typecast API 키 | 더빙을 쓸 때 필수 |
| `AWS_S3_BUCKET` | 1번의 버킷 이름 | 더빙을 쓸 때 필수 |
| `AWS_REGION` | 버킷 리전 | 선택 (기본 `ap-northeast-2`) |

| 실행 환경 | 값을 넣는 곳 |
|---|---|
| 내 컴퓨터, IntelliJ | 실행 구성의 Environment variables (5단계 5번) |
| 내 컴퓨터, `docker compose` | 명령을 실행하는 터미널의 환경변수 |
| EC2 | `~/magic-editor.env` 파일 (`docs/ec2-setup.md` 6단계) |

- 컴퓨터 전체의 환경변수(`~/.zshrc` 등)에는 넣지 않는다. 다른 프로그램도 키를 읽을 수 있게 되기 때문이다.
- AWS 액세스 키는 이 표에 없다. 액세스 키는 환경변수가 아니라 `~/.aws/credentials` 파일로 전달한다 (5단계).
- 두 필수 값이 없어도 서버는 뜬다. 메모 기능은 그대로 쓸 수 있고, 더빙 작업만 실패한다.

## 5. 로컬 개발 환경

1. IAM 콘솔에서 로컬 개발용 **IAM 사용자**를 만든다. 예: `magic-editor-dev`.
2. 그 사용자에게 2번의 정책 **하나만** 연결한다. AdministratorAccess 같은 넓은 권한은 주지 않는다.
3. 그 사용자의 **액세스 키**를 발급한다 (사용 사례: CLI).
4. AWS CLI를 설치한다: `brew install awscli`.
5. 터미널에서 `aws configure`를 실행하고 아래 값을 입력한다. 결과는 `~/.aws/credentials`와 `~/.aws/config`에 저장된다.
   - AWS Access Key ID: 3번의 액세스 키
   - AWS Secret Access Key: 3번의 비밀 액세스 키
   - Default region name: `ap-northeast-2`
   - Default output format: 빈칸으로 두고 Enter
6. IntelliJ 실행 구성의 Environment variables에 `TYPECAST_API_KEY`와 `AWS_S3_BUCKET`을 넣는다.
   - **Run → Edit Configurations…** 에서 앱 실행 구성을 고른다.
   - Environment variables 칸이 없으면 **Modify options → Environment variables**를 눌러 칸을 연다.
   - 값은 세미콜론으로 구분한다: `TYPECAST_API_KEY=<키>;AWS_S3_BUCKET=<버킷 이름>`

### 5-1. 자격 증명 확인 (서버 없이)

1. `aws sts get-caller-identity`를 실행한다. 출력의 `Arn` 끝이 `user/magic-editor-dev`이면 키가 맞다.
2. 업로드를 시험한다: `echo test | aws s3 cp - s3://<버킷 이름>/dubbings/test.txt`
3. 시험 파일을 지운다: `aws s3 rm s3://<버킷 이름>/dubbings/test.txt`

`aws s3 ls s3://<버킷 이름>`은 `AccessDenied`가 나는 것이 정상이다. 정책이 목록 보기(`s3:ListBucket`)를 허용하지 않기 때문이다.

- 주의: 액세스 키를 저장소에 커밋하지 않는다. 키가 새면 즉시 IAM 콘솔에서 비활성화한다.
- `docker compose up`으로 앱 컨테이너를 띄우면 `~/.aws`가 컨테이너에 읽기 전용으로 연결된다 (`compose.yaml` 참고).

## 6. 동작 확인

1. `memos.http`의 "더빙 요청 — AI 목소리"를 실행한다.
2. "더빙 상태 조회"를 몇 초 간격으로 실행해 `COMPLETED`가 되는지 본다.
3. 응답의 `audioUrl`을 브라우저로 열어 소리를 들어 본다.
4. S3 콘솔의 버킷에서 `dubbings/{회원 id}/{작업 id}.mp3` 파일을 확인한다.

## 7. 문제 해결

| 증상 (서버 로그) | 확인할 것 |
|---|---|
| `Unable to load credentials` | 로컬: `aws configure`를 했는지 확인한다. EC2: IAM 역할이 연결됐는지, 메타데이터 응답 홉 제한이 2인지 확인한다. |
| `AccessDenied` | 정책의 `Resource`에 버킷 이름과 `/dubbings/*`가 정확한지 확인한다. |
| `NoSuchBucket` | `AWS_S3_BUCKET` 값과 `AWS_REGION`이 실제 버킷과 같은지 확인한다. |
| 작업이 계속 `PENDING`/`FAILED` | `TYPECAST_API_KEY`가 설정됐는지, Typecast 크레딧이 남았는지 확인한다. |
