FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY . .
ARG GRADLE_MAX_WORKERS
RUN --mount=type=cache,target=/root/.gradle \
    set --; \
    if [ -n "$GRADLE_MAX_WORKERS" ]; then set -- "--max-workers=$GRADLE_MAX_WORKERS"; fi; \
    chmod +x gradlew && ./gradlew "$@" :apps:mcp-server:bootJar --no-daemon

FROM docker.io/library/eclipse-temurin:21-jre-alpine AS runtime-base
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS ci-runtime
COPY --chown=app:app apps/mcp-server/build/libs/app.jar /app/app.jar

FROM runtime-base AS runtime
COPY --from=builder --chown=app:app /workspace/apps/mcp-server/build/libs/app.jar /app/app.jar
