#!/usr/bin/env bash
# Encrypted backup of the ControlWeb database.
#
#   /usr/local/lib/controlweb/backup.sh        (root; started by controlweb-backup.service, by the timer and before every deploy)
#
# What it does, and why:
#   * pg_dump (custom format, compressed) runs INSIDE the database container and its output is encrypted with age on the way
#     out (`pg_dump | age`): no plaintext copy of the database is ever written to the disk of the server.
#   * The same bytes are also fed to `pg_restore --list` (inside the container) while they stream: that proves the archive is a
#     real PostgreSQL archive and that its table of contents has every table of ControlWeb. (It reads the table of contents,
#     not every data block; restore-test.sh does a full restore of a fresh dump every week, and the downloader on the owner's PC
#     can decrypt and restore the stored files.)
#   * Only the PUBLIC key of the owner is on the server (/etc/controlweb/backup-recipient.txt). The server can write backups but
#     cannot read them: whoever steals the disk gets nothing, and so would a compromised server.
#   * A copy only becomes a copy when EVERYTHING checked out: every stage of the pipeline succeeded, the verification passed, the
#     file starts with the age header and is not tiny. Then it is renamed into place (atomic) and ONLY THEN old copies are rotated.
#     A failed run removes its partial file and touches nothing else: the previous copies are all still there.
#   * Rotation: copies older than KEEP_DAYS (14) go, but the newest MIN_KEEP (3) always stay, whatever their age.
#   * Status for humans and for deploy.sh: /var/lib/controlweb-backup/last (or last-failure), readable by everybody.
set -euo pipefail
umask 077
# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

WORK=""
PARTIAL=""
cleanup() {
  if [ -n "$PARTIAL" ]; then rm -f "$PARTIAL"; fi
  if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
  release_lock
}

# fail <reason>: records the failure, removes what this run created (the trap), keeps every older copy, exits 1.
fail() {
  log "BACKUP FAILED: $*" >&2
  write_state last-failure "$(date -u +%FT%TZ) $*" || true
  exit 1
}

# rotate: delete copies older than KEEP_DAYS, never the newest MIN_KEEP. Only called after a successful copy.
rotate() {
  local index=0 file removed=0
  while IFS= read -r file; do
    index=$((index + 1))
    [ "$index" -le "$MIN_KEEP" ] && continue
    if [ -n "$(find "$file" -maxdepth 0 -mtime +"$((KEEP_DAYS - 1))" 2> /dev/null)" ]; then
      rm -f "$file"
      removed=$((removed + 1))
    fi
  done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'controlweb-*.dump.age' | sort -r)
  log "rotation: $removed old copy(ies) removed, newest $MIN_KEEP always kept"
}

main() {
  require_root
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"
  acquire_lock
  trap cleanup EXIT

  # leftovers of a crashed run (we hold the lock, so nobody is using them)
  find "$BACKUP_DIR" -maxdepth 1 \( -name '.controlweb-*.partial' -o -name '.work.*' \) -exec rm -rf {} + 2> /dev/null || true

  local recipient
  recipient=$(read_recipient)
  DB=$(find_container controlweb-db)
  [ -n "$DB" ] || fail "the database container is not running"
  local free_mb
  free_mb=$(df -Pm "$BACKUP_DIR" | awk 'NR==2 {print $4}')
  [ "${free_mb:-0}" -ge "$MIN_FREE_MB" ] || fail "only ${free_mb:-0} MB free in $BACKUP_DIR (at least $MIN_FREE_MB MB are needed)"

  local stamp final
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  final="$BACKUP_DIR/controlweb-$stamp.dump.age"
  PARTIAL="$BACKUP_DIR/.controlweb-$stamp.dump.age.partial"
  WORK=$(mktemp -d "$BACKUP_DIR/.work.XXXXXX")

  log "dumping the database, encrypted on the fly"
  # Stages: 0 pg_dump (in the container) | 1 tee | 2 age. The tee also feeds `pg_restore --list`; after pg_restore has read the table
  # of contents it exits, so the rest of the stream is drained (`cat > /dev/null`): otherwise tee would die of SIGPIPE and the
  # encrypted copy would be cut short.
  set +e
  docker exec "$DB" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=9 --no-owner --no-privileges' \
    | tee >({ docker exec -i "$DB" pg_restore --list > "$WORK/list.txt" 2> "$WORK/list.err"; echo $? > "$WORK/list.status"; cat > /dev/null; }) \
    | age --recipient "$recipient" > "$PARTIAL"
  local statuses=("${PIPESTATUS[@]}")
  set -e

  [ "${statuses[0]}" -eq 0 ] || fail "pg_dump failed (exit ${statuses[0]})"
  [ "${statuses[1]}" -eq 0 ] || fail "the stream was interrupted (tee exit ${statuses[1]})"
  [ "${statuses[2]}" -eq 0 ] || fail "age failed to encrypt (exit ${statuses[2]})"
  wait_for_file "$WORK/list.status" "$WAIT_SECONDS" || fail "pg_restore --list did not finish in ${WAIT_SECONDS}s"
  [ "$(cat "$WORK/list.status")" = "0" ] || fail "pg_restore --list rejected the archive: $(head -c 300 "$WORK/list.err" | tr '\n' ' ')"
  list_has_required "$WORK/list.txt" 2> "$WORK/missing.txt" || fail "$(cat "$WORK/missing.txt")"

  local size
  size=$(wc -c < "$PARTIAL")
  [ "$size" -ge 200 ] || fail "the encrypted file is only $size bytes: it cannot be a database backup"
  [ "$(head -c 21 "$PARTIAL")" = "age-encryption.org/v1" ] || fail "the file does not start with the age header: it is not an encrypted backup"

  mv "$PARTIAL" "$final"
  PARTIAL=""
  chmod 600 "$final"
  sync || true
  write_state last "$(date -u +%FT%TZ) $(basename "$final") ${size} bytes"
  rm -f "$STATE_DIR/last-failure"
  log "backup written: $(basename "$final") (${size} bytes)"
  rotate
}

main "$@"
