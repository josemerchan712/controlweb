#!/usr/bin/env bash
# Shared code of backup.sh, restore.sh and restore-test.sh. It is only ever SOURCED by them, from the same root-owned folder.
#
# Installed as root:root 0755 in /usr/local/lib/controlweb/ by deploy/backup/install.sh and run by root (systemd). None of these files
# may be writable by jose: whoever can edit a script that root runs can become root.
#
# Everything below can be overridden by an environment variable. That exists for the tests; under systemd the environment is clean,
# and `sudo` resets it, so a normal user cannot use it to change what root does.

: "${PROJECT:=controlweb}"                              # compose project of the production stack
: "${BACKUP_DIR:=/var/backups/controlweb}"              # encrypted copies; root only (0700)
: "${STATE_DIR:=/var/lib/controlweb-backup}"            # small status files anybody may read (0755): when, which file, how big
: "${RECIPIENT_FILE:=/etc/controlweb/backup-recipient.txt}"   # the age PUBLIC key; the private one is never on this server
: "${LOCK_DIR:=/run/controlweb-backup.lock}"            # one backup / restore at a time
: "${KEEP_DAYS:=14}"                                    # copies older than this are rotated out...
: "${MIN_KEEP:=3}"                                      # ...but the newest MIN_KEEP are never rotated out
: "${MIN_FREE_MB:=500}"                                 # refuse to start with less free space than this
: "${MAX_BACKUP_AGE_HOURS:=26}"                         # the restore test complains if the newest copy is older than this
: "${WAIT_SECONDS:=120}"                                # how long to wait for the verification of a copy to finish

# The only database the restore test may create or drop. Never the real one.
# shellcheck disable=SC2034 # read by restore-test.sh and restore.sh, which source this file
TEST_DB=controlweb_restore_test
# Objects every valid copy must contain (checked in the table of contents of the archive).
REQUIRED_OBJECTS=(sites checks incidents alert_rules users flyway_schema_history)

log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_root() {
  [ "${ALLOW_NON_ROOT:-}" = 1 ] && return 0
  [ "$(id -u)" -eq 0 ] || die "this script must run as root (it is installed in /usr/local/lib/controlweb and started by systemd)"
}

# The age PUBLIC key that copies are encrypted to. A private key found here is a mistake that must stop everything.
read_recipient() {
  [ -r "$RECIPIENT_FILE" ] || die "there is no public key at $RECIPIENT_FILE (docs/DESPLIEGUE.md, backups: generate the key pair on your PC)"
  if grep -q 'AGE-SECRET-KEY' "$RECIPIENT_FILE"; then
    die "$RECIPIENT_FILE contains a PRIVATE key. The private key must live only on your PC: remove it from the server and generate a new pair"
  fi
  local keys count
  keys=$(grep -E '^age1[a-z0-9]{58}$' "$RECIPIENT_FILE" || true)
  count=$(printf '%s\n' "$keys" | grep -c . || true)
  [ "$count" -eq 1 ] || die "expected exactly one age public key (age1...) in $RECIPIENT_FILE, found $count"
  printf '%s' "$keys"
}

# find_container <service>: prints the id of the running container of that compose service (empty if none).
find_container() {
  docker ps -q --filter "label=com.docker.compose.project=$PROJECT" --filter "label=com.docker.compose.service=$1" | head -n 1
}

# --- lock: a directory (atomic) holding the pid, so a crashed run does not block the next one forever ---
LOCK_HELD=""
acquire_lock() {
  mkdir -p "$(dirname "$LOCK_DIR")"
  if ! mkdir "$LOCK_DIR" 2> /dev/null; then
    local other
    other=$(cat "$LOCK_DIR/pid" 2> /dev/null || true)
    if [ -n "$other" ] && kill -0 "$other" 2> /dev/null; then
      die "another backup or restore is already running (pid $other)"
    fi
    log "removing a stale lock (pid ${other:-unknown} is gone)"
    rm -rf "$LOCK_DIR"
    mkdir "$LOCK_DIR" || die "cannot take the lock $LOCK_DIR"
  fi
  echo $$ > "$LOCK_DIR/pid"
  LOCK_HELD=1
}
release_lock() {
  if [ -n "$LOCK_HELD" ]; then rm -rf "$LOCK_DIR"; LOCK_HELD=""; fi
}

# write_state <name> <text>: a small world-readable status file (deploy.sh shows it), replaced atomically.
write_state() {
  mkdir -p "$STATE_DIR"
  chmod 755 "$STATE_DIR" 2> /dev/null || true
  printf '%s\n' "$2" > "$STATE_DIR/$1.tmp"
  chmod 644 "$STATE_DIR/$1.tmp"
  mv "$STATE_DIR/$1.tmp" "$STATE_DIR/$1"
}

# list_has_required <toc-file>: 0 if the table of contents lists every object a real copy of ControlWeb contains.
list_has_required() {
  local object
  for object in "${REQUIRED_OBJECTS[@]}"; do
    grep -Eq "(TABLE|TABLE DATA) public ${object} " "$1" || { echo "the archive does not contain ${object}" >&2; return 1; }
  done
}

# wait_for_file <file> <seconds>: waits for a file written by a background verification.
wait_for_file() {
  local tries=$(($2 * 5)) waited=0 # polled every 0.2 s
  while [ ! -e "$1" ] && [ "$waited" -lt "$tries" ]; do sleep 0.2; waited=$((waited + 1)); done
  [ -e "$1" ]
}

# psql_in_db <database> <sql>: runs one statement as the database superuser inside the database container.
psql_in_db() {
  docker exec "$DB" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atqc "$2"' sh "$1" "$2"
}
