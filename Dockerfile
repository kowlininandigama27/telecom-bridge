# syntax=docker/dockerfile:1.7
# Multi-stage build for both runtime images.
#   docker build --target gateway   -t telecom-bridge/gateway .
#   docker build --target simulator -t telecom-bridge/simulator .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version --no-daemon
COPY diameter-core diameter-core
COPY diameter-simulator diameter-simulator
COPY gateway-service gateway-service
COPY load-tester load-tester
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gateway-service:bootJar :diameter-simulator:installDist :load-tester:installDist -x test --no-daemon

FROM eclipse-temurin:25-jre AS runtime-base
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app

FROM runtime-base AS gateway
COPY --from=build --chown=app:app /src/gateway-service/build/libs/gateway-service.jar /app/gateway-service.jar
USER app
EXPOSE 8080
# Container-aware heap sizing; G1 keeps pauses well below the 100 ms latency budget.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/gateway-service.jar"]

FROM runtime-base AS simulator
COPY --from=build --chown=app:app /src/diameter-simulator/build/install/diameter-simulator /app
USER app
EXPOSE 3868
ENTRYPOINT ["/app/bin/diameter-simulator"]

FROM runtime-base AS load-tester
COPY --from=build --chown=app:app /src/load-tester/build/install/load-tester /app
USER app
ENTRYPOINT ["/app/bin/load-tester"]
