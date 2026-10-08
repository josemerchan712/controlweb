#!/usr/bin/env bash
# A fake `docker` for VerifyScriptsTest (copied to a temporary directory as "docker"): it answers the questions
# deploy/verify-container.sh asks about a perfectly configured container. Environment variables change one answer
# (FAKE_NETWORKS, FAKE_READONLY, FAKE_USER) or the FORMAT of every answer (FAKE_NOISE=crlf|spaces|newlines).
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
  inspect)
    format=$2 # docker inspect --format <format> <container>
    case "$format" in
      *Config.User*) emit "${FAKE_USER:-10001:10001}" ;;
      *ReadonlyRootfs*) emit "${FAKE_READONLY:-true}" ;;
      *CapDrop*) emit "[ALL]" ;;
      *CapAdd*) emit "[]" ;;
      *Privileged*) emit "false" ;;
      *SecurityOpt*) emit "[no-new-privileges:true]" ;;
      *PidsLimit*) emit "256" ;;
      *MemorySwap*) emit "536870912" ;;
      *HostConfig.Memory*) emit "536870912" ;;
      *RestartPolicy*) emit "unless-stopped" ;;
      *LogConfig.Type*) emit "json-file" ;;
      *max-size*) emit "10m" ;;
      *max-file*) emit "3" ;;
      *Tmpfs*) emit '{"/tmp":"rw,noexec,nosuid,nodev,size=32m"}' ;;
      *PortBindings*) emit "{}" ;;
      *IPAddress*) emit "10.77.0.20" ;;
      *Networks*)
        # the real template ends every name with a space, so the real answer ends with a space
        emit "${FAKE_NETWORKS-controlweb_proxy controlweb_db controlweb_egress }" ;;
      *Health.Status*) emit "healthy" ;;
      *OOMKilled*) emit "false" ;;
      *RestartCount*) emit "0" ;;
      *) echo "fake docker: unexpected inspect format: $format" >&2; exit 2 ;;
    esac
    ;;
  exec)
    shift # the container
    arguments="$*"
    case "$arguments" in
      "id -u") emit "10001" ;;
      *CapEff*) emit "0000000000000000" ;;
      *NoNewPrivs*) emit "1" ;;
      *"touch /app/probe"*) exit 1 ;;
      *"touch /tmp/probe"*) exit 0 ;;
      *"/tmp/probe-exec"*) exit 1 ;;
      *"-O - http://127.0.0.1:8080/actuator/health"*) emit '{"status":"UP"}' ;;
      *"-O /dev/null"*) exit 8 ;;
      *) echo "fake docker: unexpected exec: $arguments" >&2; exit 2 ;;
    esac
    ;;
  *) echo "fake docker: unexpected command: $command_name" >&2; exit 2 ;;
esac
