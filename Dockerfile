# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Gradle 래퍼와 빌드 파일 먼저 복사 (의존성 캐시 레이어 분리)
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 의존성만 먼저 다운로드 (소스 변경 없으면 이 레이어 캐시 재사용)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# 소스 복사 및 빌드
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime (JRE만 포함, JDK 없음)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# root가 아닌 사용자로 실행한다(컨테이너가 뚫려도 호스트 root 권한으로 이어지지 않게). UID를 고정해 두면
# 호스트에서 권한·로그를 볼 때 누구인지 알 수 있다. jar는 root 소유로 두어 앱이 자기 실행 파일을 못 바꾼다
RUN addgroup -S -g 10001 checkbook && adduser -S -D -H -u 10001 -G checkbook checkbook

COPY --from=build /app/build/libs/*.jar app.jar

USER checkbook:checkbook
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
