FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml pom.xml
COPY backend-core/pom.xml backend-core/pom.xml
COPY backend-reference/pom.xml backend-reference/pom.xml
COPY contracts/realtime.proto contracts/realtime.proto
COPY backend-core/src/main/ backend-core/src/main/
COPY backend-reference/src/main/ backend-reference/src/main/

# 产品镜像先从同一源码构建并安装两个Module，依赖收集无需预装公共核心。
# proto只在backend-core生成；普通JAR和运行时三方依赖分开放置。
RUN mvn -B -f pom.xml -Dmaven.test.skip=true install \
    && mvn -B -f backend-reference/pom.xml -Dmaven.test.skip=true \
    org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=/build/backend-reference/target/runtime-libs

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=build /build/backend-reference/target/backend-reference-0.1.0-SNAPSHOT.jar backend-reference.jar
COPY --from=build /build/backend-reference/target/runtime-libs/ lib/

# file 模式的 Artifact 使用持久卷；s3 模式由部署环境提供对象存储配置。
ENV AI_MEETING_HTTP_BIND=0.0.0.0:8080 \
    AI_MEETING_WSS_BIND=0.0.0.0:8443 \
    AI_MEETING_ARTIFACT_ROOT=/var/lib/meeting/artifacts
VOLUME ["/var/lib/meeting/artifacts"]
EXPOSE 8080 8443

ENTRYPOINT ["java", "-cp", "/app/backend-reference.jar:/app/lib/*", "com.czur.reference.application.bootstrap.ReferenceMeetingMain"]
