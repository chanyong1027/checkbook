# Stage 1: Build
FROM eclipse-temurin:17-jdk-alpine AS build
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
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
