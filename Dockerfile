# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre-jammy AS builder

WORKDIR /workspace

# Package the JAR already built and tested by CI without compiling it again.
COPY build/libs/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM alpine:3.22 AS otel-agent
RUN apk add --no-cache curl
RUN curl --fail --location --retry 3 \
      https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.32.0/opentelemetry-javaagent.jar \
      --output /opentelemetry-javaagent.jar \
    && echo 'f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82  /opentelemetry-javaagent.jar' | sha256sum -c -

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system spring \
    && useradd --system --gid spring --home-dir /app --shell /usr/sbin/nologin spring

WORKDIR /app

# Stable dependencies are reused when only application code changes.
COPY --from=builder --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /workspace/extracted/application/ ./
COPY --from=otel-agent --chown=spring:spring --chmod=0444 /opentelemetry-javaagent.jar /opt/otel/opentelemetry-javaagent.jar

# Dev containers stay quiet by default; ECS PROD_ENV enables OTLP traces explicitly.
ENV OTEL_TRACES_EXPORTER=none \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-javaagent:/opt/otel/opentelemetry-javaagent.jar", "-jar", "/app/application.jar"]
