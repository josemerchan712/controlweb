#!/usr/bin/env bash
# Stand-in for docker in the tests of download-backups.ps1 -VerifyRestore. Every call is appended to $FAKE_DOCKER_LOG.
#   FAKE_DOCKER_DOWN=1 (docker info fails)   FAKE_RESTORE_EXIT=n (pg_restore exits n)   FAKE_MISSING_TABLE=name (psql fails for that table)
set -u
echo "$*" >> "$FAKE_DOCKER_LOG"
case "${1:-}" in
  info) [ "${FAKE_DOCKER_DOWN:-}" = 1 ] && exit 1; echo 29.0.0 ;;
  run) echo 0123456789abcdef ;;
  logs) echo "database system is ready to accept connections"; echo "database system is ready to accept connections" ;;
  cp | rm) ;;
  exec)
    case "$*" in
      *pg_restore*) echo "pg_restore: noise on stderr, as the real one can write" >&2; exit "${FAKE_RESTORE_EXIT:-0}" ;;
      *flyway_schema_history\ where*) echo "3 3" ;;
      *psql*)
        all="$*"
        table=${all##*from }
        if [ "$table" = "${FAKE_MISSING_TABLE:-}" ]; then echo "ERROR: relation \"$table\" does not exist" >&2; exit 1; fi
        echo 7
        ;;
    esac
    ;;
esac
exit 0
