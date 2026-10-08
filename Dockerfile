# ControlWeb production image. Two stages: build with the JDK, run with the JRE only.
#
# Base images are pinned by EXACT tag AND digest (the digest of the multi-architecture index), so the same source builds
# the same image next month. Dependabot (docker ecosystem) proposes the updates; the tag in a comment says what the digest is.
#
# Build (from the repository root):   docker build -t controlweb-app:local .
# The context is a whitelist (.dockerignore): only pom.xml, the Maven wrapper and src/main enter the build, so source of
# the tests, .env files, .git and docs/ can never end up in the image.

# ---------------------------------------------------------------------------------------------------------------- build
# eclipse-temurin:21.0.12_8-jdk-alpine-3.23
FROM eclipse-temurin:24-jdk-alpine-3.22@sha256:8fdbcb6bc6b846640cea7058e6eeb56c311fae4efaa506a213789134065c6b90 AS build

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/
# The Maven repository lives in a BuildKit cache mount: dependencies are downloaded once per machine, and never become
# part of any layer of the image.
# MAVEN_OPTS caps the build JVM so it stays within a laptop-sized Docker VM.
RUN --mount=type=cache,target=/root/.m2 MAVEN_OPTS="-Xmx512m" ./mvnw -B -ntp -Dmaven.test.skip=true package \
 && cp target/controlweb-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ------------------------------------------------------------------------------------------------------------- runtime
# eclipse-temurin:21.0.12_8-jre-alpine-3.23
FROM eclipse-temurin:24-jre-alpine-3.22@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f AS runtime

# A fixed, unprivileged user with no login shell and no home worth having.
RUN addgroup -S -g 10001 controlweb && adduser -S -u 10001 -G controlweb -h /app -s /sbin/nologin controlweb

WORKDIR /app

# Layers from the least to the most frequently changing, so a code change re-sends only a few KB.
COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

ENV SPRING_PROFILES_ACTIVE=prod \
    LANG=C.UTF-8

USER 10001:10001
EXPOSE 8080

# No curl in this image: busybox wget is enough. /actuator/health answers {"status":"UP"} and nothing else (profile prod);
# wget fails on a 4xx/5xx, so a DOWN health makes the container unhealthy. The long start period covers the JVM start,
# the Flyway migrations and the first scheduler cycle.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# JVM flags come from JAVA_TOOL_OPTIONS (set in compose.prod.yml), so they can be tuned without rebuilding.
ENTRYPOINT ["java", "-jar", "application.jar"]
