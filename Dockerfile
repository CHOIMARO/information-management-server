# syntax=docker/dockerfile:1

# ---------- 1단계: 빌드 (JDK 필요) ----------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY . .
# --mount=type=cache: Gradle 캐시를 빌드 간에 재사용해서 매번 의존성을 새로 받지 않는다.
# bootJar만 실행한다 — build와 달리 실행 가능한 jar 하나만 만들어져 아래 COPY가 단순해진다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# ---------- 2단계: 실행 (JRE면 충분) ----------
# 빌드 도구(JDK, Gradle, 소스)는 최종 이미지에 남지 않는다 → 이미지가 작고 공격 표면도 좁다
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

# 컨테이너를 root로 돌리지 않는 것이 보안 기본기.
# H2 파일 DB(./data)를 쓸 수 있도록 작업 디렉토리 소유권을 넘긴다.
RUN useradd --system --create-home appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
