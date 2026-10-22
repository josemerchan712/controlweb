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
FROM eclipse-temurin:21-jdk-alpine-3.20@sha256:108c96f846c4f8affd722908f8392eb15d123b21395616dfbf8b2e6f48db44fa AS build

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
FROM eclipse-temurin:21-jre-alpine-3.20@sha256:3ce6d389fc60453114bf9ec58d6082daf052e2410104652c455482846ac794ab AS runtime

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
# wget fails on a 4xx/5xx, so a DOWN health makes the container unhealthy.
# Timing, measured on a laptop (Docker Desktop, 29 sites): the application is up in 24 s. On the VPS, sharing two vCPUs with
# another stack, assume up to 3x that: ~75 s. The start period (120 s) is well above it, failures inside it do not count, and
# during it the check runs every 5 s (--start-interval), so the container turns healthy within seconds of being ready
# instead of waiting for the next 30 s tick.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --start-interval=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

# JVM flags come from JAVA_TOOL_OPTIONS (set in compose.prod.yml), so they can be tuned without rebuilding.
ENTRYPOINT ["java", "-jar", "application.jar"]
