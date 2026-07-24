# syntax=docker/dockerfile:1.7

ARG MAVEN_IMAGE=maven:3.9.12-eclipse-temurin-21-alpine@sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e
ARG RUNTIME_IMAGE=alpine:3.23@sha256:fd791d74b68913cbb027c6546007b3f0d3bc45125f797758156952bc2d6daf40
ARG EXTENSION_VERSION=2.28.1-o11y.7
ARG OTEL_AGENT_VERSION=2.28.1
ARG OTEL_AGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589

FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build

ARG EXTENSION_VERSION
ARG OTEL_AGENT_VERSION

WORKDIR /workspace

RUN addgroup -S -g 10001 builder \
    && adduser -S -D -u 10001 -G builder builder \
    && chown builder:builder /workspace

COPY --chown=10001:10001 pom.xml ./

USER builder

RUN --mount=type=cache,id=o11y-java-agent-m2,target=/home/builder/.m2,uid=10001,gid=10001,sharing=locked \
    mvn -B -ntp \
      -Drevision="${EXTENSION_VERSION}" \
      -Dopentelemetry.javaagent.version="${OTEL_AGENT_VERSION}" \
      -DskipTests \
      dependency:go-offline

COPY --chown=10001:10001 config ./config
COPY --chown=10001:10001 src ./src

RUN --mount=type=cache,id=o11y-java-agent-m2,target=/home/builder/.m2,uid=10001,gid=10001,sharing=locked \
    mvn -B -ntp \
      -Drevision="${EXTENSION_VERSION}" \
      -Dopentelemetry.javaagent.version="${OTEL_AGENT_VERSION}" \
      clean verify

FROM build AS javaagent-build

ARG EXTENSION_VERSION
ARG OTEL_AGENT_VERSION
ARG OTEL_AGENT_SHA256

RUN mkdir -p /workspace/agent /workspace/embedded/extensions \
    && cp \
      /workspace/target/agent/opentelemetry-javaagent.jar \
      /workspace/agent/opentelemetry-javaagent.jar \
    && printf '%s  %s\n' \
      "${OTEL_AGENT_SHA256}" \
      /workspace/agent/opentelemetry-javaagent.jar \
      | sha256sum -c - \
    && cp \
      "/workspace/target/java-agent-extension-${EXTENSION_VERSION}.jar" \
      /workspace/embedded/extensions/o11y-java-agent-extension.jar \
    && jar \
      --update \
      --file /workspace/agent/opentelemetry-javaagent.jar \
      -C /workspace/embedded extensions \
    && jar \
      --list \
      --file /workspace/agent/opentelemetry-javaagent.jar \
      | grep -Fxq 'extensions/o11y-java-agent-extension.jar'

# Salida mínima para pruebas manuales del mismo JAR combinado que se publica.
# BuildKit puede exportarla sin crear ni ejecutar un contenedor:
# docker build --target javaagent-artifact --output type=local,dest=target/embedded-agent .
FROM scratch AS javaagent-artifact

COPY --from=javaagent-build \
     /workspace/agent/opentelemetry-javaagent.jar \
     /opentelemetry-javaagent.jar

FROM ${RUNTIME_IMAGE} AS extension-runtime

ARG EXTENSION_VERSION

RUN addgroup -S -g 10001 instrumentation \
    && adduser -S -D -H -u 10001 -G instrumentation instrumentation \
    && install -d -o 10001 -g 10001 -m 0750 /instrumentation

COPY --from=build \
     --chown=10001:10001 \
     --chmod=0444 \
     /workspace/target/java-agent-extension-${EXTENSION_VERSION}.jar \
     /extension.jar

VOLUME ["/instrumentation"]

USER 10001:10001

CMD ["/bin/sh", "-eu", "-c", "destination=/instrumentation/o11y-java-agent-extension.jar; temporary=${destination}.tmp; cp /extension.jar ${temporary}; chmod 0444 ${temporary}; mv -f ${temporary} ${destination}"]

FROM ${RUNTIME_IMAGE} AS javaagent-runtime

ARG EXTENSION_VERSION

RUN addgroup -S -g 10001 instrumentation \
    && adduser -S -D -H -u 10001 -G instrumentation instrumentation \
    && install -d -o 10001 -g 10001 -m 0750 /instrumentation

COPY --from=javaagent-build \
     --chown=10001:10001 \
     --chmod=0444 \
     /workspace/agent/opentelemetry-javaagent.jar \
     /javaagent.jar

VOLUME ["/instrumentation"]

USER 10001:10001

CMD ["/bin/sh", "-eu", "-c", "destination=/instrumentation/opentelemetry-javaagent.jar; temporary=${destination}.tmp; cp /javaagent.jar ${temporary}; chmod 0444 ${temporary}; mv -f ${temporary} ${destination}"]
