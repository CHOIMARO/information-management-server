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
- Spring Boot는 `JWT_SECRET`을 `jwt.secret` 속성으로 읽는다 (relaxed binding: 대문자·밑줄 이름을 소문자·점 이름에 자동으로 맞춘다).
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
그 다음 `memos.http`의 주소를 `http://<서버 IP>`로 바꿔 가입·로그인·메모 요청을 시험한다.

## 7. 새 버전 배포 (업데이트)

main 브랜치에 push하면 CI가 테스트를 통과한 뒤 새 `latest` 이미지를 GHCR에 올린다. 그 다음 EC2에서 아래를 실행한다.

```bash
docker pull ghcr.io/choimaro/information-management-server:latest
docker rm -f magic-editor-server
docker run -d --name magic-editor-server --restart unless-stopped -p 80:8080 \
  --env-file ~/magic-editor.env ghcr.io/choimaro/information-management-server:latest
```

- 컨테이너를 바꾸는 몇 초 동안 서버가 응답하지 않는다.
- 문제가 생기면 이전 커밋의 이미지로 되돌린다. CI는 커밋마다 `sha-<커밋 앞 7자리>` 태그를 함께 올린다.
  예: `ghcr.io/choimaro/information-management-server:sha-45d5d8c`

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
