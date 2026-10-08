#!/usr/bin/env bash
# A fake `docker` for VerifyScriptsTest (copied to a temporary directory as "docker"): it answers the questions
# deploy/verify-image.sh asks about an image. The "image" is a tar file (FAKE_TAR) and a jar (FAKE_JAR) built by the test.
# FAKE_USER, FAKE_HEALTH, FAKE_ENV change one answer; FAKE_NOISE=crlf|spaces|newlines changes the FORMAT of every answer.
emit() {
  case "${FAKE_NOISE:-}" in
    crlf) printf '%s\r\n' "$1" ;;
    spaces) printf '  %s  \n' "$1" ;;
    newlines) printf '\n\n%s\n\n' "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

command_name=$1
shift
case "$command_name" in
  create) echo "fakecontainerid" ;;
  rm) exit 0 ;;
  export) cat "$FAKE_TAR" ;;
  cp) cp "$FAKE_JAR" "$2" ;; # docker cp <container>:/app/application.jar <destination>
  image) emit "200000000" ;;  # docker image inspect --format '{{.Size}}' <image>
  inspect)
    format=$2
    case "$format" in
      *Config.User*) emit "${FAKE_USER-10001:10001}" ;;
      *Healthcheck*) emit "${FAKE_HEALTH-yes}" ;;
      *Config.Env*)
        env_lines=${FAKE_ENV-$'SPRING_PROFILES_ACTIVE=prod\nLANG=C.UTF-8\nPATH=/usr/bin'}
        while IFS= read -r line; do emit "$line"; done <<< "$env_lines"
        ;;
      *) echo "fake docker: unexpected inspect format: $format" >&2; exit 2 ;;
    esac
    ;;
  *) echo "fake docker: unexpected command: $command_name" >&2; exit 2 ;;
esac
