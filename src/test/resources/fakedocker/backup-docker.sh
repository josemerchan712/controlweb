#!/usr/bin/env bash
# A fake `docker` for BackupScriptsTest: it plays the PostgreSQL container. State and call log live in $FAKE_STATE.
# Settings (environment):
#   FAKE_DUMP_FILE    the bytes pg_dump "produces"               FAKE_DUMP_FAIL   pg_dump fails half way
#   FAKE_DUMP_DELAY   seconds pg_dump takes                      FAKE_DB_DOWN     the database container is not running
#   FAKE_LIST_FAIL    pg_restore --list rejects the archive      FAKE_TOC_MISSING an object missing from the table of contents
#   FAKE_RESTORE_FAIL pg_restore (the real restore) fails   FAKE_RESTORE_DELAY seconds the real restore takes        FAKE_LIVE_COUNT / FAKE_RESTORED_COUNT / FAKE_RESTORED_FLYWAY
state=${FAKE_STATE:?}
touch "$state/calls.log"
echo "docker $*" >> "$state/calls.log"

command_name=$1
shift
case "$command_name" in
  ps)
    case "$*" in
      *controlweb-db*) [ -n "${FAKE_DB_DOWN:-}" ] || echo "db-container" ;;
      *controlweb-app*) [ -n "${FAKE_APP_DOWN:-}" ] || echo "app-container" ;;
    esac
    ;;
  stop) echo "stopped $1" >> "$state/calls.log" ;;
  exec)
    if [ "$1" = "-i" ]; then shift; fi
    shift # the container
    arguments="$*"
    # shellcheck disable=SC2016 # the literal text of the command to recognise: its $ must NOT expand here
    db_name_probe='printf %s "$POSTGRES_DB"'
    case "$arguments" in
      *pg_dump*)
        [ -z "${FAKE_DUMP_DELAY:-}" ] || sleep "$FAKE_DUMP_DELAY"
        if [ -n "${FAKE_DUMP_FAIL:-}" ]; then head -c 100000 "${FAKE_DUMP_FILE:?}"; exit 1; fi
        cat "${FAKE_DUMP_FILE:?}"
        ;;
      *"pg_restore --list"*)
        head -c 16 > /dev/null # pg_restore --list reads the table of contents and stops: it does not read the whole stream
        if [ -n "${FAKE_LIST_FAIL:-}" ]; then echo "pg_restore: error: input file does not appear to be a valid archive" >&2; exit 1; fi
        for object in sites checks incidents alert_rules users flyway_schema_history; do
          [ "$object" = "${FAKE_TOC_MISSING:-}" ] && continue
          echo "; 215; 1259 16401 TABLE public $object controlweb"
        done
        # a complete table of contents AND an error status: only the exit status can reveal it
        if [ -n "${FAKE_LIST_FAIL_AFTER_TOC:-}" ]; then echo "pg_restore: warning: errors ignored on read" >&2; exit 1; fi
        ;;
      *pg_restore*)
        # docker exec -i db sh -c 'pg_restore ... -d "$1" ...' sh <database>
        database=$(echo "$arguments" | awk '{print $NF}')
        touch "$state/restore-started"
        [ -z "${FAKE_RESTORE_DELAY:-}" ] || sleep "$FAKE_RESTORE_DELAY" # a restore that takes a while (to interrupt it)
        cat > "$state/restored-$database.bin"
        echo "pg_restore into $database: $arguments" >> "$state/calls.log"
        [ -z "${FAKE_RESTORE_FAIL:-}" ] || { echo "pg_restore: error: could not execute query" >&2; exit 1; }
        ;;
      *"$db_name_probe"*) printf '%s' "controlweb" ;;
      *psql*)
        # docker exec db sh -c 'psql ... "$1" ... "$2"' sh <database> <sql>
        sql=${arguments#*sh -c * sh }
        database=${sql%% *}
        sql=${sql#* }
        echo "psql[$database]: $sql" >> "$state/calls.log"
        case "$sql" in
          "DROP DATABASE"*) rm -f "$state/test-db-exists" ;;
          "CREATE DATABASE"*) touch "$state/test-db-exists" ;;
          *installed_rank*) if [ "$database" = "controlweb" ]; then echo 3; else echo "${FAKE_RESTORED_FLYWAY:-3}"; fi ;;
          *flyway_schema_history*) echo 3 ;;
          "select count(*)"*) if [ "$database" = "controlweb" ]; then echo "${FAKE_LIVE_COUNT:-10}"; else echo "${FAKE_RESTORED_COUNT:-10}"; fi ;;
        esac
        ;;
      *) echo "fake docker: unexpected exec: $arguments" >&2; exit 2 ;;
    esac
    ;;
  *) echo "fake docker: unexpected command: $command_name" >&2; exit 2 ;;
esac
