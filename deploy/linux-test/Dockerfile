# Throw-away Linux (Ubuntu + JDK 21) in which deploy/test-linux.ps1 runs the tests of the shell scripts the way CI does.
# Only for development on Windows: nothing here is deployed. The base is pinned by digest like every other image of the project.
FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b

# The tests run as an ordinary user, like on the GitHub runner: some tests behave differently (or are skipped) for root.
RUN useradd -m -u 1001 runner
USER runner
WORKDIR /home/runner

# git (DeployScriptTest builds real repositories), python3 (verify-image.sh), gawk: what the GitHub ubuntu runner has and the scripts use.
USER root
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends git python3 gawk \
 && rm -rf /var/lib/apt/lists/*
USER runner
