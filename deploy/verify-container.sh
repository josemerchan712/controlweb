#!/usr/bin/env bash
# Checks what Docker REALLY applied to a running ControlWeb application container (not what the compose file says).
#
#   deploy/verify-container.sh <container-name-or-id>
#
# Every line is a plain `docker inspect` / `docker exec` check you can also run by hand; docs/DESPLIEGUE.md lists them.
# Exit status 0 = everything as designed. The comparisons ignore formatting: surrounding spaces, blank lines, CRLF line
# endings and the order of a list of names never make a check fail (VerifyScriptsTest runs this script against a fake docker).
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash on Windows must not rewrite /proc/... or /dev/null

container=${1:?usage: deploy/verify-container.sh <container>}
failures=0

# trim <text>: removes CRs and the whitespace (spaces, tabs, blank lines) around the text.
trim() {
  local s=${1//$'\r'/}
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}
# words: reads text on stdin and prints its words sorted and joined by one space, whatever separated them before.
words() { tr -s '[:space:]' '\n' | sed '/^$/d' | sort | paste -sd' ' -; }

inspect() { trim "$(docker inspect --format "$1" "$container" 2>/dev/null)"; }

expect() { # expect <description> <expected> <actual>
  local actual
  actual=$(trim "$3")
  if [ "$2" = "$actual" ]; then echo "ok:   $1 = $actual"; else echo "FAIL: $1: expected '$2' but found '$actual'" >&2; failures=$((failures + 1)); fi
}
expect_contains() { # expect_contains <description> <needle> <actual>
  local actual
  actual=$(trim "$3")
  case "$actual" in *"$2"*) echo "ok:   $1 contains $2" ;; *) echo "FAIL: $1: '$actual' does not contain '$2'" >&2; failures=$((failures + 1)) ;; esac
}

# --- as configured (docker inspect) ---
expect "user" "10001:10001" "$(inspect '{{.Config.User}}')"
expect "read-only root filesystem" "true" "$(inspect '{{.HostConfig.ReadonlyRootfs}}')"
expect "capabilities dropped" "[ALL]" "$(inspect '{{.HostConfig.CapDrop}}')"
expect "privileged" "false" "$(inspect '{{.HostConfig.Privileged}}')"
added=$(inspect '{{.HostConfig.CapAdd}}')
case "$added" in '[]'|'<no value>'|'<nil>'|'') echo "ok:   no capability added" ;; *) echo "FAIL: capabilities added: $added" >&2; failures=$((failures + 1)) ;; esac
expect "security options" "[no-new-privileges:true]" "$(inspect '{{.HostConfig.SecurityOpt}}')"
expect "pids limit" "256" "$(inspect '{{.HostConfig.PidsLimit}}')"
expect "memory limit (bytes)" "536870912" "$(inspect '{{.HostConfig.Memory}}')"
expect "memory+swap limit (bytes, equal = no swap)" "536870912" "$(inspect '{{.HostConfig.MemorySwap}}')"
expect "restart policy" "unless-stopped" "$(inspect '{{.HostConfig.RestartPolicy.Name}}')"
expect "log driver" "json-file" "$(inspect '{{.HostConfig.LogConfig.Type}}')"
expect "log max-size" "10m" "$(inspect '{{index .HostConfig.LogConfig.Config "max-size"}}')"
expect "log max-file" "3" "$(inspect '{{index .HostConfig.LogConfig.Config "max-file"}}')"
expect_contains "tmpfs /tmp options" "noexec" "$(inspect '{{json .HostConfig.Tmpfs}}')"
ports=$(inspect '{{json .HostConfig.PortBindings}}')
case "$ports" in '{}'|'null') echo "ok:   no published port" ;; *) echo "FAIL: published ports: $ports" >&2; failures=$((failures + 1)) ;; esac
# shellcheck disable=SC2016 # the $name / $net below are variables of the Go template that docker inspect runs, not of the shell
expect "networks" "controlweb_db controlweb_egress controlweb_proxy" \
  "$(inspect '{{range $name, $net := .NetworkSettings.Networks}}{{$name}} {{end}}' | words)"
expect "IP on the proxy network" "10.77.0.20" "$(inspect '{{(index .NetworkSettings.Networks "controlweb_proxy").IPAddress}}')"

# --- state ---
expect "health" "healthy" "$(inspect '{{.State.Health.Status}}')"
expect "OOMKilled" "false" "$(inspect '{{.State.OOMKilled}}')"
expect "restart count" "0" "$(inspect '{{.RestartCount}}')"

# --- as applied inside the container (docker exec) ---
expect "effective uid" "10001" "$(docker exec "$container" id -u)"
expect "effective capabilities (CapEff)" "0000000000000000" "$(docker exec "$container" sh -c "awk '/^CapEff/ {print \$2}' /proc/1/status")"
expect "NoNewPrivs" "1" "$(docker exec "$container" sh -c "awk '/^NoNewPrivs/ {print \$2}' /proc/1/status")"
if docker exec "$container" sh -c 'touch /app/probe 2>/dev/null'; then
  echo "FAIL: the root filesystem is writable" >&2; failures=$((failures + 1))
else
  echo "ok:   cannot write to /app (read-only root filesystem)"
fi
if docker exec "$container" sh -c 'touch /tmp/probe && rm /tmp/probe' 2>/dev/null; then echo "ok:   /tmp is writable (tmpfs)"; else echo "FAIL: /tmp is not writable" >&2; failures=$((failures + 1)); fi
if docker exec "$container" sh -c 'cp /bin/true /tmp/probe-exec 2>/dev/null && /tmp/probe-exec' 2>/dev/null; then
  echo "FAIL: a file in /tmp can be executed (noexec missing)" >&2; failures=$((failures + 1))
else
  echo "ok:   nothing in /tmp can be executed (noexec)"
fi
health=$(docker exec "$container" wget -q -O - http://127.0.0.1:8080/actuator/health 2>/dev/null || true)
expect "GET /actuator/health" '{"status":"UP"}' "$health"
for path in /actuator/env /actuator/beans /v3/api-docs /swagger-ui.html; do
  if docker exec "$container" wget -q -O /dev/null "http://127.0.0.1:8080$path" 2>/dev/null; then
    echo "FAIL: $path answers 2xx" >&2; failures=$((failures + 1))
  else
    echo "ok:   $path does not answer 2xx"
  fi
done

if [ "$failures" -gt 0 ]; then echo "$failures check(s) failed" >&2; exit 1; fi
echo "container verified"
