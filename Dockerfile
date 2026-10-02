FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY backend-reference/pom.xml backend-reference/pom.xml
COPY contracts/realtime.proto contracts/realtime.proto
COPY backend-reference/src/main/ backend-reference/src/main/

# 仓库根的 proto 是唯一协议源；运行时依赖与应用 JAR 分开放置，避免修改 Maven 工程。
RUN mvn -B -f backend-reference/pom.xml -Dmaven.test.skip=true package \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=/build/backend-reference/target/runtime-libs

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=build /build/backend-reference/target/backend-reference-0.1.0-SNAPSHOT.jar backend-reference.jar
COPY --from=build /build/backend-reference/target/runtime-libs/ lib/

# Artifact 必须使用持久卷；数据库连接、身份凭据和 TLS 证书均由部署环境提供。
ENV AI_MEETING_HTTP_BIND=0.0.0.0:8080 \
    AI_MEETING_WSS_BIND=0.0.0.0:8443 \
    AI_MEETING_ARTIFACT_ROOT=/var/lib/meeting/artifacts
VOLUME ["/var/lib/meeting/artifacts"]
EXPOSE 8080 8443

ENTRYPOINT ["java", "-cp", "/app/backend-reference.jar:/app/lib/*", "com.czur.reference.application.bootstrap.ReferenceMeetingMain"]
