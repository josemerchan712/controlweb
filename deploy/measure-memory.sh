#!/usr/bin/env bash
# Measures the REAL memory use of the production stack: starts compose.prod.yml (limit 512 MiB for the application, 256 MiB for
# PostgreSQL), registers a few dozen real sites, lets the scheduler run for N minutes under some API traffic and reports
# `docker stats`, the process RSS, the cgroup memory counters, OOM kills, restarts and the start-up time.
#
#   docker build -t controlweb-app:measure .          # once, nothing else building at the same time
#   MINUTES=10 deploy/measure-memory.sh controlweb-app:measure
#
# Everything runs in its own Compose project (cwmeasure) with its own volume, and is removed at the end; the developer's
# own PostgreSQL container is never touched. All credentials are random, live in a temporary file (mode 600) and are deleted.
# The sites are the IANA example domains (a few requests per second at most), the author's own three sites, and two
# unresolvable hosts so that incidents and alerts also exercise the code. Output: a report file (path printed at the end).
set -uo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash on Windows must not rewrite /proc/... as a Windows path

image=${1:?usage: deploy/measure-memory.sh <image>}
minutes=${MINUTES:-10}
sample_every=${SAMPLE_EVERY:-30}
project=cwmeasure
out_dir=${OUT_DIR:-$(mktemp -d)}
report="$out_dir/measure-report.txt"

native() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

work=$(mktemp -d)
env_file="$work/env"
created_network=no
compose() { docker compose -p "$project" -f compose.prod.yml --env-file "$(native "$env_file")" "$@"; }
log() { echo "$*" | tee -a "$report"; }

cleanup() {
  compose down --volumes --remove-orphans > /dev/null 2>&1 || true
  [ "$created_network" = yes ] && docker network rm controlweb_proxy > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

: > "$report"
umask 077
{
  echo "DB_NAME=controlweb"
  echo "DB_USERNAME=controlweb"
  echo "DB_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
  echo "JWT_SECRET=$(head -c 32 /dev/urandom | base64)"
  echo "ADMIN_EMAIL=admin@example.com"
  echo "ADMIN_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n')"
  echo "TRUSTED_PROXY_ADDRESSES=10.77.0.10"
  echo "PUBLIC_CORS_ALLOWED_ORIGINS=https://josemariamerchan.dev"
  echo "CONTROLWEB_TAG=${image##*:}"
} > "$env_file"
admin_email=admin@example.com
admin_password=$(grep '^ADMIN_PASSWORD=' "$env_file" | cut -d= -f2-)

if ! docker network inspect controlweb_proxy > /dev/null 2>&1; then
  docker network create --internal --subnet 10.77.0.0/24 --opt com.docker.network.bridge.name=cw-proxy controlweb_proxy > /dev/null
  created_network=yes
fi

log "== ControlWeb memory measurement: image $image, $minutes min, sample every ${sample_every}s, $(date -u +%FT%TZ) =="
log "Docker VM memory: $(docker info --format '{{.MemTotal}}' | awk '{printf "%.1f GiB", $1/1073741824}')"

start=$(date +%s)
compose up -d --wait --wait-timeout 240 > "$work/up.log" 2>&1 || { log "FAILED TO START:"; tail -30 "$work/up.log" | tee -a "$report"; compose logs --no-color --tail 40 | tee -a "$report"; exit 1; }
healthy_after=$(( $(date +%s) - start ))
app=$(compose ps -q controlweb-app)
db=$(compose ps -q controlweb-db)
log "Healthy $healthy_after s after 'up' (includes the database)."
log "$(docker logs "$app" 2>&1 | grep -m1 'Started ControlwebApplication')"

api() { # api <method> <path> [json-body] [token]
  local method=$1 path=$2 body=${3:-} token=${4:-}
  local args=(-q -O - -T 15)
  [ -n "$token" ] && args+=(--header "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(--header "Content-Type: application/json" --post-data "$body")
  docker exec "$app" wget "${args[@]}" "http://127.0.0.1:8080$path" 2> /dev/null
}

login_body=$(printf '{"email":"%s","password":"%s"}' "$admin_email" "$admin_password")
token=$(api POST /api/auth/login "$login_body" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || { log "login failed"; exit 1; }

add_site() { api POST /api/sites "$(printf '{"name":"%s","url":"%s","checkIntervalSeconds":30}' "$1" "$2")" "$token" > /dev/null; }
count=0
for i in 1 2 3 4 5 6 7 8; do
  for domain in example.com example.org example.net; do add_site "$domain $i" "https://$domain/?n=$i"; count=$((count + 1)); done
done
add_site "portfolio" "https://josemariamerchan.dev/"; add_site "casino" "https://rottenodds.josemariamerchan.dev/"
add_site "casino api" "https://rottenodds-api.josemariamerchan.dev/health"
add_site "missing 1" "https://no-such-host-measure-1.invalid/"; add_site "missing 2" "https://no-such-host-measure-2.invalid/"
log "Registered $((count + 5)) sites (every 30 s): $count example.*, 3 own, 2 unresolvable."

log ""
log "time_s | app mem (docker stats) | app RSS_kB | cgroup anon_MB | cgroup file_MB | threads | app CPU | db mem"
end=$(( $(date +%s) + minutes * 60 ))
tick=0
while [ "$(date +%s)" -lt "$end" ]; do
  sleep "$sample_every"
  tick=$((tick + 1))
  # some traffic on the read API, and from time to time a wrong login (the rate limiter answers 429 after a few)
  api GET /api/status "" "$token" > /dev/null
  api GET /api/incidents "" "$token" > /dev/null
  api GET "/api/sites/1/stats?period=24h" "" "$token" > /dev/null
  api GET "/api/sites/1/checks?size=100" "" "$token" > /dev/null
  [ $((tick % 4)) -eq 0 ] && api POST /api/auth/login '{"email":"admin@example.com","password":"definitely-wrong-password"}' > /dev/null
  rss=$(docker exec "$app" sh -c "awk '/^VmRSS/ {print \$2}' /proc/1/status")
  threads=$(docker exec "$app" sh -c "awk '/^Threads/ {print \$2}' /proc/1/status")
  anon=$(docker exec "$app" sh -c "awk '/^anon / {printf \"%.0f\", \$2/1048576}' /sys/fs/cgroup/memory.stat")
  file=$(docker exec "$app" sh -c "awk '/^file / {printf \"%.0f\", \$2/1048576}' /sys/fs/cgroup/memory.stat")
  a=$(docker stats --no-stream --format '{{.MemUsage}} ({{.MemPerc}}) {{.CPUPerc}}' "$app")
  d=$(docker stats --no-stream --format '{{.MemUsage}}' "$db")
  log "$(( $(date +%s) - start )) | ${a%% *} ${a#* } | $rss | $anon | $file | $threads | ${a##* } | $d"
done

log ""
log "== Final =="
log "OOMKilled: app=$(docker inspect --format '{{.State.OOMKilled}}' "$app") db=$(docker inspect --format '{{.State.OOMKilled}}' "$db")"
log "RestartCount: app=$(docker inspect --format '{{.RestartCount}}' "$app") db=$(docker inspect --format '{{.RestartCount}}' "$db")"
log "Health: $(docker inspect --format '{{.State.Health.Status}}' "$app")"
log "Peak RSS (VmHWM kB): $(docker exec "$app" sh -c "awk '/^VmHWM/ {print \$2}' /proc/1/status")"
log "cgroup memory.peak (bytes): $(docker exec "$app" sh -c 'cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo n/a')"
log "smaps_rollup: $(docker exec "$app" sh -c "awk '/^(Rss|Pss|Anonymous):/ {printf \"%s %s kB; \", \$1, \$2}' /proc/1/smaps_rollup")"
log "Checks stored: $(docker exec "$db" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from checks"') ; incidents: $(docker exec "$db" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from incidents"')"
log "Warnings/errors in the application log: $(docker logs "$app" 2>&1 | grep -cE ' (WARN|ERROR) ')"
log "Image size: $(docker image inspect --format '{{.Size}}' "$image" | awk '{printf "%.0f MB", $1/1000000}')"
log ""
log "Report written to $report"
