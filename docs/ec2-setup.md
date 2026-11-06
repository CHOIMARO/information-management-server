# EC2(서울) 생성 및 앱 배포 가이드

서울 리전(`ap-northeast-2`)에 EC2를 새로 만들고, Docker로 앱을 실행한다.
앱 이미지는 GitHub Actions가 GHCR에 올린 것을 그대로 받는다 (`.github/workflows/ci.yml`).

**환경 운영 방침**: 출시 전까지 이 서버는 **개발 서버**로 쓴다 (Swagger 켬, 보안 그룹은 회사·집 IP만 허용).
출시할 때 이 서버를 **운영 서버**로 전환하고, 개발 서버는 작게 새로 만든다. 전환 절차는 `docs/dubbing-todo.md` 0번에 있다.

```
[앱] ── HTTP(80) ──> [EC2 서울: Docker 컨테이너 magic-editor-server(8080)] ──> [RDS 서울]
                                  ├── IAM 역할 ──> [S3 서울]
                                  └── HTTPS ────> [Typecast API]
```

## 0. 전체 순서 (서울 리전 구성)

아래 순서대로 진행한다. 순서가 바뀌면 앞 단계에서 만든 값을 고르지 못하는 단계가 생긴다.

1. S3 버킷을 만든다 → `docs/s3-setup.md` 1단계
2. S3용 IAM 정책을 만든다 → `docs/s3-setup.md` 2단계
3. EC2용 IAM 역할을 만든다 → `docs/s3-setup.md` 3-1단계 (EC2 연결은 5번에서 EC2를 만들 때 한다)
4. (선택) 로컬 개발용 IAM 사용자를 만들고 로컬에서 더빙을 시험한다 → `docs/s3-setup.md` 5단계
5. EC2를 만든다 → 이 문서 1~4단계
6. RDS를 만든다 → 이 문서 5단계 (`docs/rds-setup.md` 참고)
7. 앱을 실행한다 → 이 문서 6단계
8. 앱의 서버 주소를 바꾸고, 버지니아 리소스를 정리한다 → 이 문서 9단계

## 1. 시작 전 확인

- **리전**: 콘솔 오른쪽 위에서 **아시아 태평양(서울) ap-northeast-2**를 고른다.
  키 페어, 보안 그룹, Elastic IP는 리전마다 따로 있다. 버지니아에서 쓰던 것은 서울에서 보이지 않으므로 새로 만든다.
  IAM(정책, 역할)은 리전 구분이 없어 그대로 쓴다.
- **아키텍처**: 앱 이미지는 amd64(x86) 전용이다. **t3 계열**을 고른다.
  t4g(ARM, Graviton)를 고르면 컨테이너가 `exec format error`로 실행되지 않는다.
- **비용**: 퍼블릭 IPv4 주소는 인스턴스에 연결되어 있어도 시간 단위로 요금이 붙는다. 결제 콘솔에서 예산 알림을 걸어 둔다.

## 2. EC2 인스턴스 생성

EC2 콘솔 → **인스턴스 시작**:

1. **이름**: `magic-editor-server`
2. **AMI**: Amazon Linux 2023 (64비트 **x86**)
3. **인스턴스 유형**: `t3.small` (vCPU 2, 메모리 2GiB)
4. **키 페어**: **새 키 페어 생성**을 누른다.
   - 이름: `magic-editor-key`, 유형: RSA, 형식: `.pem`
   - 내려받은 파일은 다시 받을 수 없다. 안전한 곳에 보관한다.
   - 터미널에서 권한을 줄인다: `chmod 400 ~/Downloads/magic-editor-key.pem`
5. **네트워크 설정** → **편집**:
   - 퍼블릭 IP 자동 할당: **활성화**
   - 보안 그룹: **보안 그룹 생성**, 이름 `magic-editor-server-sg`
   - 인바운드 규칙 1: 유형 `SSH`(22), 소스 **내 IP**
   - 인바운드 규칙 2: 유형 `HTTP`(80), 소스 **위치 무관**(`0.0.0.0/0`) — 앱 사용자가 어디서든 접속해야 한다
6. **스토리지**: `20` GiB, gp3 (Docker 이미지와 로그를 둘 공간)
7. **고급 세부 정보**:
   - **IAM 인스턴스 프로파일**: `magic-editor-ec2-role` (0단계 3번에서 만든 역할, `docs/s3-setup.md` 3-2단계)
   - **메타데이터 버전**: V2만(토큰 필수)
   - **메타데이터 응답 홉 제한**: **2**
8. **인스턴스 시작**을 누른다.

**홉 제한이 2여야 하는 이유**: 컨테이너 안의 앱은 EC2 메타데이터 서버에서 IAM 역할 자격 증명을 받는다.
컨테이너 네트워크를 한 단계 더 거치므로 홉 제한이 1이면 응답이 컨테이너까지 오지 못한다.
그러면 S3 업로드가 `Unable to load credentials` 오류로 실패한다.

## 3. 고정 IP(Elastic IP) 연결

자동 할당된 퍼블릭 IP는 인스턴스를 중지했다가 시작하면 바뀐다. 그러면 앱에 넣은 서버 주소가 깨진다.

1. EC2 콘솔 → **탄력적 IP** → **탄력적 IP 주소 할당** → 할당.
2. 할당한 주소를 고르고 **작업 → 탄력적 IP 주소 연결**을 누른다.
3. 인스턴스로 `magic-editor-server`를 고르고 연결한다.

이 주소가 앞으로의 서버 주소다. 아래에서 `<서버 IP>`로 쓴다.

- 주의: 인스턴스를 삭제할 때 Elastic IP도 함께 **해제**한다. 연결되지 않은 Elastic IP에도 요금이 붙는다.

## 4. Docker 설치 (EC2 안에서)

```bash
# 로컬 터미널에서 접속
ssh -i ~/Downloads/magic-editor-key.pem ec2-user@<서버 IP>

# EC2 안에서 실행
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl enable --now docker      # 지금 시작하고, 재부팅 후에도 자동 시작
sudo usermod -aG docker ec2-user        # sudo 없이 docker 명령을 쓰기 위해 그룹에 추가
exit
```

그룹 변경은 다시 접속해야 반영된다. 다시 접속한 뒤 `docker version`이 오류 없이 나오면 성공이다.

## 5. RDS 생성

`docs/rds-setup.md` 1~3단계를 따른다. 다음 두 가지만 확인한다.

1. 리전이 **서울**인지 확인한다.
2. **연결** 항목에서 **EC2 컴퓨팅 리소스에 연결**을 고르고 `magic-editor-server` 인스턴스를 지정한다.
   AWS가 RDS 보안 그룹을 자동으로 구성해 준다. 그래서 EC2를 RDS보다 먼저 만든다.

완료되면 RDS **엔드포인트**와 마스터 비밀번호를 준비한다.

## 6. 앱 실행

### 6-1. 환경변수 파일 만들기

비밀 값은 이미지와 저장소에 넣지 않고, EC2 안의 파일 하나에만 둔다.

```bash
# EC2 안에서 실행. JWT 서명 키로 쓸 임의 문자열을 만든다 (32바이트 이상 필요)
openssl rand -base64 48

# 환경변수 파일 작성
vi ~/magic-editor.env
chmod 600 ~/magic-editor.env          # 소유자(ec2-user)만 읽을 수 있게 한다
```

`~/magic-editor.env` 내용:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://<RDS 엔드포인트>:5432/magiceditor
SPRING_DATASOURCE_USERNAME=magiceditor
SPRING_DATASOURCE_PASSWORD=<RDS 마스터 비밀번호>
JWT_SECRET=<위에서 만든 임의 문자열>
TYPECAST_API_KEY=<Typecast API 키>
AWS_S3_BUCKET=<S3 버킷 이름>
AWS_REGION=ap-northeast-2
TZ=Asia/Seoul
```

운영 서버로 전환할 때는 아래 두 줄을 추가해서 API 문서(Swagger)를 끈다. 개발 서버에는 넣지 않는다.

```
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

- 값에 따옴표를 붙이지 않는다. Docker는 따옴표까지 값으로 읽는다.
- `export`를 붙이지 않는다. 이 파일은 셸 스크립트가 아니라 Docker 환경변수 파일이다.
- API 문서(Swagger UI)는 개발용이다. 개발 서버에서는 `http://<서버 IP>/swagger-ui.html`, 로컬에서는 `http://localhost:8080/swagger-ui.html`로 연다.
- SNS 로그인과 탈퇴에 필요한 줄을 추가한다. 값이 없는 SNS의 로그인은 모두 401로 거절된다.

  ```
  KAKAO_APP_ID=<카카오 앱 ID (숫자)>
  KAKAO_ADMIN_KEY=<카카오 어드민 키>
  GOOGLE_CLIENT_IDS=<구글 Web 클라이언트 ID>
  APPLE_CLIENT_IDS=<iOS 번들 ID>
  APPLE_TEAM_ID=<Apple Team ID>
  APPLE_KEY_ID=<Sign in with Apple 키의 Key ID>
  APPLE_PRIVATE_KEY=<.p8 파일 내용을 한 줄로: BEGIN/END 줄을 빼고 Base64 본문만>
  TOKEN_ENCRYPTION_KEY=<openssl rand -base64 32 결과>
  ```

  - `KAKAO_APP_ID`는 카카오 developers > 내 애플리케이션 > 앱 설정 > 요약 정보의 숫자 앱 ID다. 앱 키가 아니다.
  - `KAKAO_ADMIN_KEY`는 탈퇴할 때 카카오 연결 끊기에만 쓴다. 모든 사용자를 다룰 수 있는 키다. 카카오 콘솔에서 어드민 키의 "사용 가능 API"를 연결 끊기로 제한하고, "호출 허용 IP"에 EC2의 공인 IP를 등록한다.
  - `GOOGLE_CLIENT_IDS`는 Android·iOS 클라이언트 ID가 아니라 **Web 클라이언트 ID**다. 앱이 이 값을 serverClientId로 넘기면 idToken의 aud가 이 값이 된다.
  - `APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`, `TOKEN_ENCRYPTION_KEY`가 없으면 Apple 로그인은 되지만, 탈퇴할 때 Apple 토큰을 취소하지 못한다 (App Store 심사 요구 사항).
  - 주의: `TOKEN_ENCRYPTION_KEY`를 바꾸면 이미 저장한 Apple 토큰을 읽을 수 없다. 한 번 정하면 바꾸지 않는다.
- `JWT_SECRET`은 필수다. `application.properties`의 `jwt.secret=${JWT_SECRET}`가 이 값을 읽는다.
  기본값이 없으므로 이 줄이 빠지면 서버가 시작하지 않는다. 공개된 개발용 키로 서버가 뜨는 사고를 막기 위해서다.
- 주의: `JWT_SECRET`을 바꾸면 이미 발급된 토큰이 모두 무효가 된다. 사용자는 다시 로그인해야 한다.
- 주의: S3 설정을 마치기 전에는 `TYPECAST_API_KEY` 줄을 비워 둔다. 키만 있으면 더빙 1건마다 크레딧을 여러 번 쓴다 (`docs/dubbing-todo.md` 2-1).

### 6-2. 컨테이너 실행

```bash
docker pull ghcr.io/choimaro/information-management-server:latest

docker run -d \
  --name magic-editor-server \
  --restart unless-stopped \
  -p 80:8080 \
  --env-file ~/magic-editor.env \
  ghcr.io/choimaro/information-management-server:latest

docker logs -f magic-editor-server      # Ctrl+C로 로그 보기만 멈춘다 (앱은 계속 실행)
```

- `-p 80:8080`: EC2의 80번 포트로 들어온 요청을 컨테이너의 8080번 포트(Spring)로 넘긴다.
- `--restart unless-stopped`: 앱이 죽거나 EC2가 재부팅되면 Docker가 컨테이너를 다시 시작한다. `docker stop`으로 직접 멈춘 경우만 예외다.
- 로그에 Flyway 마이그레이션 적용 줄과 `Started InformationManagementApplication`이 보이면 정상이다.

### 6-3. 동작 확인 (로컬 터미널에서)

```bash
curl -i http://<서버 IP>/voices
```

`401`과 `{"code":"UNAUTHORIZED",...}`가 오면 정상이다. 서버가 떠 있고 인증 검사까지 동작한다는 뜻이다.
`curl -i http://<서버 IP>/dubbing-options`는 로그인 없이 `200`이 와야 한다.
그 다음 `memos.http`의 주소를 `http://<서버 IP>`로 바꿔 SNS 로그인·더빙 요청을 시험한다.

## 7. 새 버전 배포 (업데이트)

main 브랜치에 push하면 CI가 테스트를 통과한 뒤 새 이미지(`latest`, `sha-<커밋 앞 7자리>`)를 GHCR에 올린다.
7-1의 자동 배포를 설정하면 CI가 이어서 EC2의 컨테이너를 그 커밋의 이미지로 바꾼다. 설정하지 않았으면 배포 job은 건너뛴다.
`v*` 태그 push는 이미지만 만들고 배포하지 않는다.

### 7-1. 자동 배포 설정 (GitHub Actions → AWS SSM → EC2)

**방식**: GitHub가 EC2에 SSH로 접속하지 않는다. CI가 AWS Systems Manager(SSM)의 Run Command로 명령을 보내고, EC2 안의 SSM 에이전트가 그 명령을 실행한다.

- SSH(22) 포트를 GitHub에 열지 않아도 된다. 지금 SSH는 회사·집 IP만 허용하고, GitHub 러너의 IP는 정해져 있지 않다.
- AWS 자격 증명은 **OIDC**로 받는다. GitHub가 실행마다 짧게 쓰는 토큰을 발급하고, AWS가 그 토큰을 확인한 뒤 역할을 빌려준다. 오래 쓰는 액세스 키를 GitHub에 저장하지 않는다.
- 배포 순서: 이미지 받기 → 기존 컨테이너 삭제 → 새 컨테이너 실행 → `GET /dubbing-options`가 200을 줄 때까지 최대 3분 대기 → 안 쓰는 이미지 정리.
  3분 안에 응답하지 않으면 컨테이너 로그를 Actions 로그에 남기고 job이 실패한다.

**1. EC2 역할에 SSM 권한을 추가한다**

1. IAM 콘솔 → **역할** → `magic-editor-ec2-role` → **권한 추가 → 정책 연결**.
2. AWS 관리형 정책 `AmazonSSMManagedInstanceCore`를 고르고 연결한다.
3. EC2에 접속해 SSM 에이전트를 다시 시작한다. Amazon Linux 2023에는 에이전트가 이미 설치되어 있다.

   ```bash
   sudo systemctl restart amazon-ssm-agent
   ```
4. Systems Manager 콘솔 → **Fleet Manager**에서 `magic-editor-server`가 **Online**인지 확인한다. 몇 분 걸릴 수 있다.

**2. GitHub OIDC 공급자를 만든다 (계정에 한 번만)**

IAM 콘솔 → **자격 증명 공급자** → **공급자 추가**:

- 공급자 유형: OpenID Connect
- 공급자 URL: `https://token.actions.githubusercontent.com`
- 대상(Audience): `sts.amazonaws.com`

**3. 배포용 역할을 만든다**

IAM 콘솔 → **역할** → **역할 생성** → **사용자 지정 신뢰 정책**에 아래를 넣는다. 이름은 `magic-editor-github-deploy`로 한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Federated": "arn:aws:iam::<계정 ID>:oidc-provider/token.actions.githubusercontent.com" },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:CHOIMARO@53159069/information-management-server@1348034912:ref:refs/heads/main"
        }
      }
    }
  ]
}
```

- `sub` 조건: 이 저장소의 **main 브랜치** 실행만 역할을 빌릴 수 있다. PR이나 다른 브랜치, 포크 저장소는 빌리지 못한다.
- `sub` 값에 숫자(`@53159069`, `@1348034912`)가 붙는 이유: 이 저장소는 2026-07-15 이후에 만들어져 GitHub가 바뀌지 않는 id를 붙인 형식(immutable subject)으로 토큰을 준다.
  이름만 쓴 형식(`repo:CHOIMARO/information-management-server:...`)으로 넣으면 `Not authorized to perform sts:AssumeRoleWithWebIdentity` 오류가 난다.
- 확인 명령: `gh api repos/CHOIMARO/information-management-server/actions/oidc/customization/sub`의 `sub_claim_prefix`

역할에 아래 인라인 정책을 추가한다. 이름은 `magic-editor-deploy-ssm`으로 한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SendDeployCommandToDevServer",
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:ap-northeast-2:<계정 ID>:instance/<인스턴스 ID>",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"
      ]
    },
    {
      "Sid": "ReadDeployCommandResult",
      "Effect": "Allow",
      "Action": "ssm:GetCommandInvocation",
      "Resource": "*"
    }
  ]
}
```

- 주의: `AWS-RunShellScript`는 EC2에서 어떤 명령이든 root로 실행할 수 있다. 그래서 대상 인스턴스를 개발 서버 하나로 제한하고, 신뢰 정책으로 main 브랜치만 허용한다.

**4. GitHub 저장소 변수를 넣는다**

저장소 **Settings → Secrets and variables → Actions → Variables**에 넣는다. 비밀 값이 아니라서 시크릿이 아닌 변수로 둔다.

```bash
gh variable set AWS_DEPLOY_ROLE_ARN --body "arn:aws:iam::<계정 ID>:role/magic-editor-github-deploy"
gh variable set EC2_INSTANCE_ID --body "<인스턴스 ID, 예: i-0abc123def4567890>"
```

**5. 확인한다**

1. main에 push하고 Actions 탭에서 `deploy-dev` job이 성공하는지 본다.
2. `curl -i http://<서버 IP>/dubbing-options`가 200이면 배포된 것이다.
3. 실패하면 job 로그에 EC2 스크립트의 출력과 컨테이너 로그가 있다.

- 환경변수 파일 위치는 `/home/ec2-user/magic-editor.env`로 정해져 있다 (`ci.yml`의 `ENV_FILE`). 다른 곳에 두었으면 `ci.yml`을 고친다.
- 환경변수 파일을 바꾼 뒤에는 새 커밋을 push하거나, 아래 수동 명령으로 컨테이너를 다시 만든다. 컨테이너는 만들 때의 파일 내용만 읽는다.

### 7-2. 수동 배포와 되돌리기

자동 배포를 설정하지 않았거나 특정 이미지로 되돌릴 때는 EC2에서 아래를 실행한다.

```bash
docker pull ghcr.io/choimaro/information-management-server:latest
docker rm -f magic-editor-server
docker run -d --name magic-editor-server --restart unless-stopped -p 80:8080 \
  --env-file ~/magic-editor.env ghcr.io/choimaro/information-management-server:latest
```

- 컨테이너를 바꾸는 몇 초 동안 서버가 응답하지 않는다.
- 문제가 생기면 이전 커밋의 이미지로 되돌린다. CI는 커밋마다 `sha-<커밋 앞 7자리>` 태그를 함께 올린다.
  예: `ghcr.io/choimaro/information-management-server:sha-45d5d8c`
- 주의: 새 버전이 DB 마이그레이션(V 파일)을 적용했다면 그 이전 이미지로는 되돌릴 수 없다. 이전 코드는 바뀐 테이블과 맞지 않아 시작하지 못한다 (`ddl-auto=validate`).
  예: V3(이메일 로그인 제거) 이후에는 V3 이전 이미지가 뜨지 않는다. 이때는 문제를 고친 새 커밋을 배포한다.

## 8. (선택) 버지니아 데이터 옮기기

- 학습용 데이터라면 옮기지 않고 새로 시작하기를 권한다. 빈 RDS에 Flyway가 V1부터 스키마를 만든다.
- 버지니아 DB는 스키마 이력이 지금 마이그레이션 파일(V1, V2)과 다를 수 있다. 데이터를 꼭 옮겨야 하면 테이블 구조부터 비교한 뒤 방법을 정한다.

## 9. 앱 주소 변경과 버지니아 리소스 정리

1. 안드로이드 앱의 서버 주소를 `http://<서버 IP>`로 바꾸고 동작을 확인한다.
2. 콘솔 리전을 **미국 동부(버지니아 북부) us-east-1**로 바꾼다.
3. 아래 리소스를 정리한다. 남겨 두면 요금이 계속 나간다.
   - EC2 인스턴스: **종료**(terminate). 중지만 하면 디스크(EBS) 요금이 남는다.
   - Elastic IP: **해제**
   - RDS: 삭제 (필요하면 "최종 스냅샷 생성" 선택)
   - EBS 볼륨·스냅샷: 남은 것이 있으면 삭제
   - 키 페어, 보안 그룹: 정리 (요금은 없음)

## 10. 문제 해결

| 증상 | 확인할 것 |
|---|---|
| SSH 접속이 시간 초과 | 보안 그룹 22번 규칙의 소스가 지금 내 IP인지 확인한다 (집·카페에 따라 IP가 바뀐다). |
| SSH가 `Permission denied (publickey...)` | 1. 키 파일 권한이 400인지 확인한다 (`chmod 400`). 2. 사용자 이름이 `ec2-user`인지 확인한다. 3. 인스턴스 **세부 정보 → 키 페어 이름**이 내 `.pem` 파일과 같은지 확인한다. 키 페어는 리전마다 따로 있고, 인스턴스를 만든 뒤에는 바꿀 수 없다. |
| SSH가 `REMOTE HOST IDENTIFICATION HAS CHANGED` | 인스턴스를 다시 만들고 같은 Elastic IP를 연결하면 이 경고가 나온다. `ssh-keygen -R <서버 IP>`로 예전 기록을 지우고 다시 접속한다. |
| `permission denied ... docker.sock` | `usermod` 뒤에 다시 접속했는지 확인한다. |
| 컨테이너가 `exec format error`로 종료 | 인스턴스가 t4g(ARM)인지 확인한다. t3 계열로 다시 만든다. |
| 앱 로그에 DB 연결 시간 초과 | RDS 보안 그룹이 EC2 보안 그룹의 5432 접근을 허용하는지 확인한다 (`docs/rds-setup.md` 2단계). |
| 외부에서 접속이 안 됨 | 보안 그룹 80번 규칙과 `docker ps`의 포트 매핑(`0.0.0.0:80->8080`)을 확인한다. |
| 앱 API와 SSH가 모두 갑자기 시간 초과 | 개인 시험 기간에 80번 규칙을 "내 IP"로 제한했다면, 집 공인 IP가 바뀌었을 수 있다. `curl -4 https://checkip.amazonaws.com`으로 지금 IP를 확인하고, 보안 그룹의 22번과 80번 규칙 소스를 다시 "내 IP"로 지정한다. |
| 더빙이 `Unable to load credentials`로 실패 | IAM 역할이 연결됐는지, 메타데이터 응답 홉 제한이 2인지 확인한다 (2단계 7번). |
| 컨테이너가 바로 종료 | `docker logs magic-editor-server`로 원인을 본다. 환경변수 파일의 오타와 따옴표를 먼저 확인한다. |

## 알아 둘 점: HTTPS

지금 구성은 HTTP(암호화 없음)다. 로그인 비밀번호와 토큰이 평문으로 오간다. 정식 출시 전에는 도메인을 연결하고 HTTPS를 적용해야 한다.
또 Android 9 이상은 기본적으로 HTTP 통신을 막는다. 개발 중에는 앱의 Network Security Config에서 이 서버 주소만 HTTP를 허용한다.
