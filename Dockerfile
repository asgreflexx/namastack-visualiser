# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
ARG VERSION=0.1.0-SNAPSHOT
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test -Pversion=${VERSION} \
    && cp build/libs/namastack-visualiser-${VERSION}.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="Namastack Outbox Visualiser" \
      org.opencontainers.image.description="Web UI for Namastack Outbox tables" \
      org.opencontainers.image.source="https://github.com/asgreflexx/namastack-visualiser" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN useradd --system --uid 10001 --no-create-home visualiser
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/config/"
EXPOSE 8090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
