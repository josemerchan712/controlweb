#!/usr/bin/env bash
# Weekly proof that the database can be dumped AND restored, in a throw-away database.
#
#   /usr/local/lib/controlweb/restore-test.sh      (root; controlweb-restore-test.timer, Sundays 05:00 UTC)
#
# What it proves, and what it cannot:
#   * A FRESH dump of the live database is restored with `pg_restore --exit-on-error` into a temporary database
#     (controlweb_restore_test) of the same PostgreSQL, and the result is compared with the live database: the same tables, the
#     same Flyway versions, and every row count between the count before the dump and the count after the restore.
#   * It also checks the NEWEST STORED copy: it exists, is recent (MAX_BACKUP_AGE_HOURS), starts with the age header and has a sane size.
#   * It CANNOT decrypt the stored copies: the private key is never on this server. Restoring a stored copy end to end is done on the
#     owner's PC (deploy/backup/download-backups.ps1 -VerifyRestore), and by hand after any incident (restore.sh).
#   * The temporary database is dropped when the script ends, WHATEVER happens (success, failure, Ctrl-C, kill). The name is fixed
#     and the script refuses to touch any other database.
set -euo pipefail
umask 077
# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

DB=""
CREATED=""

drop_test_db() {
  # The only DROP DATABASE in this script, and it can only name the fixed test database.
  [ "$TEST_DB" = "controlweb_restore_test" ] || { echo "refusing to drop '$TEST_DB'" >&2; return 1; }
  psql_in_db postgres "DROP DATABASE IF EXISTS ${TEST_DB} WITH (FORCE)" > /dev/null 2>&1 || log "WARNING: could not drop ${TEST_DB}; it will be dropped by the next run" >&2
}

cleanup() {
  if [ -n "$DB" ] && [ -n "$CREATED" ]; then drop_test_db; fi
  release_lock
}

fail() {
  log "RESTORE TEST FAILED: $*" >&2
  write_state last-restore-test "$(date -u +%FT%TZ) FAILED: $*" || true
  exit 1
}

count_rows() { # count_rows <database> <table>
  psql_in_db "$1" "select count(*) from public.$2"
}

main() {
  require_root
  acquire_lock
  trap cleanup EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM

  DB=$(find_container controlweb-db)
  [ -n "$DB" ] || fail "the database container is not running"
  local live_db
  live_db=$(docker exec "$DB" sh -c 'printf %s "$POSTGRES_DB"')
  [ "$TEST_DB" != "$live_db" ] || fail "refusing to run: the test database would be the real one"

  # 1. the newest stored copy (what can be checked without the private key)
  local newest size age_minutes
  newest=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'controlweb-*.dump.age' | sort -r | head -n 1)
  [ -n "$newest" ] || fail "there is no stored backup in $BACKUP_DIR"
  age_minutes=$(( ($(date +%s) - $(stat -c %Y "$newest")) / 60 ))
  [ "$age_minutes" -le $((MAX_BACKUP_AGE_HOURS * 60)) ] || fail "the newest backup ($(basename "$newest")) is $((age_minutes / 60)) hours old (limit ${MAX_BACKUP_AGE_HOURS})"
  size=$(wc -c < "$newest")
  [ "$size" -ge 200 ] || fail "the newest backup is only $size bytes"
  [ "$(head -c 21 "$newest")" = "age-encryption.org/v1" ] || fail "the newest backup does not start with the age header"

  # 2. a leftover from a crashed run, then a clean database
  CREATED=yes
  drop_test_db
  local before_sites before_checks before_incidents
  before_sites=$(count_rows "$live_db" sites)
  before_checks=$(count_rows "$live_db" checks)
  before_incidents=$(count_rows "$live_db" incidents)
  psql_in_db postgres "CREATE DATABASE ${TEST_DB}" > /dev/null

  # 3. fresh dump -> restore, stopping at the first error
  log "restoring a fresh dump into ${TEST_DB}"
  set +e
  docker exec "$DB" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=9 --no-owner --no-privileges' \
    | docker exec -i "$DB" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$1" --exit-on-error --no-owner --no-privileges' sh "$TEST_DB"
  local statuses=("${PIPESTATUS[@]}")
  set -e
  [ "${statuses[0]}" -eq 0 ] || fail "pg_dump failed (exit ${statuses[0]})"
  [ "${statuses[1]}" -eq 0 ] || fail "pg_restore failed (exit ${statuses[1]}); it stops at the first error"

  # 4. compare with the live database
  local table restored
  for table in sites checks incidents; do
    restored=$(count_rows "$TEST_DB" "$table")
    local low high
    case "$table" in
      sites) low=$before_sites ;;
      checks) low=$before_checks ;;
      incidents) low=$before_incidents ;;
    esac
    high=$(count_rows "$live_db" "$table")
    if [ "$restored" -lt "$low" ] || [ "$restored" -gt "$high" ]; then
      fail "table $table: $restored rows restored, but the live database had $low before and $high after"
    fi
  done
  local live_versions restored_versions
  live_versions=$(psql_in_db "$live_db" "select coalesce(max(installed_rank),0) from public.flyway_schema_history")
  restored_versions=$(psql_in_db "$TEST_DB" "select coalesce(max(installed_rank),0) from public.flyway_schema_history")
  [ "$live_versions" = "$restored_versions" ] || fail "Flyway history differs: live $live_versions, restored $restored_versions"

  write_state last-restore-test "$(date -u +%FT%TZ) OK newest-backup=$(basename "$newest") sites=$(count_rows "$TEST_DB" sites) checks=$(count_rows "$TEST_DB" checks)"
  log "restore test passed; the temporary database is dropped now"
}

main "$@"
