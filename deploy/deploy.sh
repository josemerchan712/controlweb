#!/usr/bin/env bash
# Deploys a ControlWeb release on the VPS, or goes back to the previous one. Run by jose (no root); the only privileged
# action is the pre-deploy backup, started with one exact sudo command (docs/DESPLIEGUE.md).
#
#   deploy/deploy.sh <commit> <image.tar>   load the image built on the developer's PC, start it, wait until it is healthy;
#                                           if it never is, go back to the previous release automatically
#   deploy/deploy.sh rollback [--force]     go back to the previous release by hand
#   deploy/deploy.sh status                 what runs now and what a rollback would go back to
#
# The image is built and tested elsewhere (deploy/release.ps1 on the developer's PC): the server never compiles anything.
# <commit> is the (short) commit the image was built from; the image is tagged controlweb-app:<commit>. The clone in
# ~/controlweb is only for the deploy files (compose.prod.yml, scripts) and is moved to the same commit, so file and image match.
#
# What a deploy does, in this order, stopping at the first problem BEFORE anything changes:
#   1. checks: arguments, checksum of the tar, .env.prod, the shared network, no local changes in the clone, the commit is
#      pushed, the egress firewall is active, and compose.prod.yml of the new commit accepts the variables of .env.prod;
#   2. backup of the database (not on the very first deploy, when there is nothing to protect) -- if it fails, nothing changes;
#   3. docker load; current -> previous; <commit> -> current; the clone moves to <commit>;
#   4. docker compose up -d and wait (HEALTH_TIMEOUT, 240 s by default) until the application is healthy.
# If it is not healthy: without new database migrations the previous release is restored by itself; WITH new migrations the
# application is stopped and nothing is restored automatically (Flyway only goes forward), with instructions to restore
# the backup taken in step 2.
#
# Settings (environment): ENV_FILE (/opt/controlweb/.env.prod), COMPOSE_PROJECT (controlweb), HEALTH_TIMEOUT (240),
# POLL_SECONDS (3), FIREWALL_UNIT (controlweb-firewall.service; empty = do not check), BACKUP_UNIT
# (controlweb-backup.service; empty = no backup), BACKUP_STATUS_FILE, SUDO (sudo).
set -euo pipefail

REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-/opt/controlweb/.env.prod}
PROJECT=${COMPOSE_PROJECT:-controlweb}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-240}
POLL_SECONDS=${POLL_SECONDS:-3}
FIREWALL_UNIT=${FIREWALL_UNIT-controlweb-firewall.service}
BACKUP_UNIT=${BACKUP_UNIT-controlweb-backup.service}
BACKUP_STATUS_FILE=${BACKUP_STATUS_FILE-/var/lib/controlweb-backup/last}
SUDO=${SUDO-sudo}
IMAGE=controlweb-app
SERVICE=controlweb-app
STATE_DIR="$REPO_DIR/.deploy"
MIGRATIONS=src/main/resources/db/migration

START_DIR=$PWD # a relative image path is relative to where the script was started
cd "$REPO_DIR"

log() { printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*"; }
warn() { printf '%s WARNING: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

compose() { docker compose -p "$PROJECT" -f "$REPO_DIR/compose.prod.yml" --env-file "$ENV_FILE" "$@"; }
image_exists() { docker image inspect "$1" > /dev/null 2>&1; }
state() { cat "$STATE_DIR/$1" 2> /dev/null || true; }

# True (0) if the database migrations differ between two commits. An unknown commit counts as "differ": be careful.
migrations_changed() {
  local rc=0
  git diff --quiet "$1" "$2" -- "$MIGRATIONS" 2> /dev/null || rc=$?
  [ "$rc" -ne 0 ]
}

check_firewall() {
  if [ -z "$FIREWALL_UNIT" ]; then
    warn "the egress firewall check is disabled (FIREWALL_UNIT is empty)"
  elif ! systemctl is-active --quiet "$FIREWALL_UNIT"; then
    die "the egress firewall ($FIREWALL_UNIT) is not active; the application must not run without it (docs/DESPLIEGUE.md, S4)"
  fi
}

# Waits until the application container is healthy. Returns 1 if it becomes unhealthy, stops, or the time runs out.
wait_healthy() {
  local waited=0 container status=""
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    container=$(compose ps -q "$SERVICE" 2> /dev/null || true)
    status=missing
    if [ -n "$container" ]; then
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2> /dev/null || echo missing)
    fi
    case "$status" in
      healthy) log "the application is healthy (after ${waited}s)"; return 0 ;;
      unhealthy|exited|dead) log "the application is $status (after ${waited}s)"; break ;;
    esac
    if [ $((waited % 15)) -eq 0 ] && [ "$waited" -gt 0 ]; then log "still waiting: $status (${waited}s of ${HEALTH_TIMEOUT}s)"; fi
    sleep "$POLL_SECONDS"
    waited=$((waited + POLL_SECONDS))
  done
  [ "$waited" -ge "$HEALTH_TIMEOUT" ] && log "no health after ${HEALTH_TIMEOUT}s"
  log "last lines of the application log:"
  compose logs --no-color --tail 40 "$SERVICE" >&2 || true
  return 1
}

start_release() { compose up -d || true; }

# --- going back to the previous release (used by the automatic and by the manual rollback) ---
restore_previous() {
  local previous_sha failed_sha
  previous_sha=$(state previous)
  failed_sha=$(state current)
  image_exists "$IMAGE:previous" || die "there is no $IMAGE:previous image to go back to"
  [ -n "$previous_sha" ] || die "no previous release recorded"
  log "going back to the previous release ($previous_sha)"
  docker tag "$IMAGE:previous" "$IMAGE:current"
  git checkout --quiet --detach "$previous_sha"
  printf '%s\n' "$previous_sha" > "$STATE_DIR/current"
  rm -f "$STATE_DIR/previous" # nothing older to go back to now; the failed image stays tagged $failed_sha
  start_release
  if wait_healthy; then
    log "rolled back: $previous_sha is running again"
    return 0
  fi
  return 1
}

# --- subcommands ---
cmd_status() {
  echo "running release : $(state current || true)"
  echo "rollback target : $(state previous || true)"
  echo "clone is at     : $(git rev-parse --short=12 HEAD)"
  docker images --format '{{.Repository}}:{{.Tag}}' "$IMAGE" || true
  compose ps || true
}

cmd_rollback() {
  local force=no
  [ "${1:-}" = "--force" ] && force=yes
  check_firewall
  local current_sha previous_sha
  current_sha=$(state current)
  previous_sha=$(state previous)
  [ -n "$previous_sha" ] || die "no previous release recorded: nothing to go back to"
  if migrations_changed "$previous_sha" "$current_sha"; then
    if [ "$force" != yes ]; then
      die "the database migrations differ between $previous_sha and $current_sha. Flyway only goes forward: the previous release may not start on the newer schema, and a rollback does not undo the migrations. Restore the pre-deploy backup instead (docs/DESPLIEGUE.md), or repeat with --force if the new migrations only ADD things."
    fi
    warn "going back across a migration change (--force)"
  fi
  restore_previous || die "the previous release did not become healthy either: look at the log above"
}

cmd_deploy() {
  local sha=${1:-} tar=${2:-}
  [[ "$sha" =~ ^[0-9a-f]{7,40}$ ]] || die "usage: deploy.sh <commit> <image.tar>  (the commit must be 7-40 lowercase hex characters)"
  case "$tar" in /*|'') ;; *) tar="$START_DIR/$tar" ;; esac
  [ -n "$tar" ] && [ -r "$tar" ] || die "the image file '$tar' does not exist or is not readable"
  [ -r "$tar.sha256" ] || die "the checksum file '$tar.sha256' is missing: release.ps1 uploads it next to the image"
  (cd "$(dirname "$tar")" && sha256sum --check --status "$(basename "$tar").sha256") \
    || die "the checksum of the image does not match: the upload is damaged, nothing was changed"
  [ -r "$ENV_FILE" ] || die "cannot read $ENV_FILE (the secrets file; docs/DESPLIEGUE.md, S3)"
  docker network inspect controlweb_proxy > /dev/null 2>&1 || die "the network controlweb_proxy does not exist (docs/DESPLIEGUE.md, S4)"
  check_firewall

  [ -z "$(git status --porcelain --untracked-files=no)" ] \
    || die "local changes in $REPO_DIR: the clone is only for deploying what is in GitHub"
  git fetch --quiet origin || die "cannot fetch from GitHub"
  git cat-file -e "$sha^{commit}" 2> /dev/null || die "commit $sha is not in the repository: is it pushed?"

  # compose.prod.yml of the NEW commit must accept the variables of .env.prod, checked before anything is changed
  SCRATCH=$(mktemp -d)
  trap 'rm -rf "$SCRATCH"' EXIT
  git show "$sha:compose.prod.yml" > "$SCRATCH/compose.prod.yml"
  docker compose -p "$PROJECT" -f "$SCRATCH/compose.prod.yml" --env-file "$ENV_FILE" config -q \
    || die "compose.prod.yml of $sha does not accept the variables of $ENV_FILE (is a required variable missing?)"

  mkdir -p "$STATE_DIR"
  local running_sha have_previous=no need_backup=no
  running_sha=$(state current)
  if [ -n "$running_sha" ] && image_exists "$IMAGE:current"; then have_previous=yes; fi
  # Back up whenever there may be data: a release was deployed before, or the database container exists.
  if [ -n "$running_sha" ] || [ -n "$(compose ps -q controlweb-db 2> /dev/null || true)" ]; then need_backup=yes; fi

  if [ "$need_backup" = yes ]; then
    if [ -z "$BACKUP_UNIT" ]; then
      warn "the pre-deploy backup is disabled (BACKUP_UNIT is empty)"
    else
      log "backing up the database before the deploy ($BACKUP_UNIT)"
      $SUDO systemctl start "$BACKUP_UNIT" \
        || die "the pre-deploy backup failed: nothing was changed (is the sudo rule for $BACKUP_UNIT in place? docs/DESPLIEGUE.md)"
      if [ -n "$BACKUP_STATUS_FILE" ] && [ -r "$BACKUP_STATUS_FILE" ]; then log "last backup: $(cat "$BACKUP_STATUS_FILE")"; fi
    fi
  else
    log "first deploy: no previous release and no database yet, so no pre-deploy backup"
  fi

  log "loading the image"
  docker load -i "$tar" > /dev/null
  image_exists "$IMAGE:$sha" || die "the file did not contain the image $IMAGE:$sha"

  local migrations_differ=no
  if [ "$have_previous" = yes ]; then
    docker tag "$IMAGE:current" "$IMAGE:previous"
    printf '%s\n' "$running_sha" > "$STATE_DIR/previous"
    if migrations_changed "$running_sha" "$sha"; then migrations_differ=yes; fi
  fi
  docker tag "$IMAGE:$sha" "$IMAGE:current"
  git checkout --quiet --detach "$sha"
  printf '%s\n' "$sha" > "$STATE_DIR/current"

  log "starting release $sha"
  start_release
  if wait_healthy; then
    log "deployed $sha"
    cleanup_old_images "$sha" "$(state previous)"
    rm -f "$tar" "$tar.sha256"
    compose ps || true
    return 0
  fi

  # --- the new release is not healthy ---
  if [ "$have_previous" != yes ]; then
    compose stop "$SERVICE" || true
    die "the first release is not healthy and there is nothing to go back to. The application is stopped (the database keeps running). Read the log above."
  fi
  if [ "$migrations_differ" = yes ]; then
    compose stop "$SERVICE" || true
    echo "ERROR: release $sha is NOT healthy and it brings new database migrations." >&2
    echo "       It was NOT rolled back automatically (Flyway only goes forward) and the application is stopped." >&2
    echo "       Restore the backup taken just before this deploy, then deploy the previous commit again:" >&2
    echo "         sudo /usr/local/lib/controlweb/restore.sh   (docs/DESPLIEGUE.md, backups)" >&2
    echo "       or, if the new migrations only ADD things, try:  deploy/deploy.sh rollback --force" >&2
    exit 1
  fi
  log "no new migrations: rolling back automatically"
  if restore_previous; then
    die "release $sha was not healthy; the previous release is running again"
  fi
  die "release $sha was not healthy and the previous release did not become healthy either: look at the log above"
}

# Removes the images of releases that are neither the running one nor the previous one.
cleanup_old_images() {
  local keep_current=$1 keep_previous=${2:-} tag
  for tag in $(docker images --format '{{.Tag}}' "$IMAGE" 2> /dev/null); do
    case "$tag" in
      current|previous|"$keep_current"|"$keep_previous") ;;
      *) docker rmi "$IMAGE:$tag" > /dev/null 2>&1 || true ;;
    esac
  done
}

main() {
  case "${1:-}" in
    rollback) shift; cmd_rollback "$@" ;;
    status) cmd_status ;;
    *) cmd_deploy "$@" ;;
  esac
}

# Everything above is parsed before anything runs (git checkout replaces this very file while the script runs).
main "$@"; exit $?
