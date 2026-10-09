#!/usr/bin/env bash
# Restores an encrypted backup into the REAL database. A destructive, deliberate operation.
#
#   sudo /usr/local/lib/controlweb/restore.sh --identity /dev/shm/controlweb-identity.txt [--yes] [options] <backup.dump.age>
#
# The backups can only be decrypted with the PRIVATE age key, which lives on the owner's PC, never on this server. To restore, the
# key is copied here for the minutes it takes, into RAM (/dev/shm), and deleted afterwards (docs/DESPLIEGUE.md, "Restaurar"):
#   scp identity.txt jose@host:/dev/shm/controlweb-identity.txt      ...restore...      shred -u /dev/shm/controlweb-identity.txt
# The script refuses an identity file that is on a disk (a real file system) unless --allow-disk-identity is given.
#
# Order of events, stopping at the first problem BEFORE anything is changed:
#   1. the identity decrypts the backup, and the decrypted archive is a PostgreSQL archive with every ControlWeb table;
#   2. without --yes it only prints what it would do (exit status 2);
#   3. a safety backup of the CURRENT database is taken (backup.sh) unless --no-safety-backup;
#   4. the application is stopped (it must not write while the database is replaced);
#   5. pg_restore --clean --if-exists --exit-on-error --single-transaction: all or nothing;
#   6. the application is NOT started again: deploy the release that matches the restored data (deploy.sh rollback --force, or a new release).
#
# Options:  --target-db NAME   restore into another (existing) database instead of the real one; the application is not stopped.
set -euo pipefail
umask 077
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=common.sh
. "$HERE/common.sh"

: "${RAM_FS_TYPES:=tmpfs ramfs}"

usage() { sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

identity_in_ram() {
  local type
  type=$(findmnt -no FSTYPE -T "$1" 2> /dev/null || stat -f -c %T "$1" 2> /dev/null || echo unknown)
  case " $RAM_FS_TYPES " in *" $type "*) return 0 ;; esac
  return 1
}

# archive_toc_ok: reads an archive on stdin, lists it inside the container and checks the table of contents.
archive_toc_ok() {
  local toc
  toc=$(mktemp)
  # pg_restore --list stops after the table of contents; the rest of the stream is drained so the writer never gets SIGPIPE
  { docker exec -i "$DB" pg_restore --list > "$toc" 2> /dev/null || true; cat > /dev/null; }
  local ok=0
  list_has_required "$toc" 2> /dev/null || ok=1
  rm -f "$toc"
  return "$ok"
}

main() {
  require_root
  local identity="" backup="" yes=no safety=yes target="" allow_disk=no
  while [ $# -gt 0 ]; do
    case "$1" in
      --identity) identity=${2:?--identity needs a file}; shift 2 ;;
      --yes) yes=yes; shift ;;
      --no-safety-backup) safety=no; shift ;;
      --target-db) target=${2:?--target-db needs a name}; shift 2 ;;
      --allow-disk-identity) allow_disk=yes; shift ;;
      -h|--help) usage ;;
      -*) die "unknown option $1" ;;
      *) backup=$1; shift ;;
    esac
  done
  [ -n "$identity" ] && [ -n "$backup" ] || usage
  [ -f "$backup" ] || die "the backup file '$backup' does not exist"
  case "$(basename "$backup")" in controlweb-*.dump.age) ;; *) die "'$backup' does not look like a ControlWeb backup (controlweb-*.dump.age)" ;; esac
  [ -r "$identity" ] || die "cannot read the identity file '$identity'"
  grep -q 'AGE-SECRET-KEY-' "$identity" || die "'$identity' is not an age identity (no AGE-SECRET-KEY line)"
  if [ "$allow_disk" != yes ] && ! identity_in_ram "$identity"; then
    die "the identity file is on a disk. Put it in RAM (/dev/shm) and delete it afterwards, or pass --allow-disk-identity if you accept the risk"
  fi

  DB=$(find_container controlweb-db)
  [ -n "$DB" ] || die "the database container is not running"
  local real_db
  real_db=$(docker exec "$DB" sh -c 'printf %s "$POSTGRES_DB"')
  local db_name=${target:-$real_db}
  if [ "$db_name" = "$TEST_DB" ]; then die "$TEST_DB is reserved for the weekly restore test"; fi

  log "checking that the identity decrypts $(basename "$backup") and that the archive is complete"
  age --decrypt --identity "$identity" < "$backup" | archive_toc_ok \
    || die "the backup cannot be decrypted with this identity, or it is not a complete ControlWeb archive. Nothing was changed"

  if [ "$yes" != yes ]; then
    echo "Would restore $(basename "$backup") into the database '$db_name' (this DELETES its current contents)."
    [ "$safety" = yes ] && echo "A safety backup of the current database would be taken first."
    [ -z "$target" ] && echo "The application would be stopped and left stopped."
    echo "Repeat the command with --yes to do it."
    exit 2
  fi

  if [ "$safety" = yes ]; then
    log "safety backup of the current database"
    "$HERE/backup.sh" || die "the safety backup failed: nothing was changed (use --no-safety-backup only if you accept losing the current data)"
  fi

  # From here on nobody else may back up or restore (the safety backup above took and released the lock itself)
  acquire_lock
  trap release_lock EXIT

  if [ -z "$target" ]; then
    local app
    app=$(find_container controlweb-app)
    if [ -n "$app" ]; then log "stopping the application"; docker stop "$app" > /dev/null; fi
  fi

  log "restoring into '$db_name' (all or nothing)"
  set +e
  age --decrypt --identity "$identity" < "$backup" \
    | docker exec -i "$DB" sh -c 'pg_restore -U "$POSTGRES_USER" -d "$1" --clean --if-exists --no-owner --no-privileges --exit-on-error --single-transaction' sh "$db_name"
  local statuses=("${PIPESTATUS[@]}")
  set -e
  if [ "${statuses[0]}" -ne 0 ] || [ "${statuses[1]}" -ne 0 ]; then
    die "the restore FAILED (age exit ${statuses[0]}, pg_restore exit ${statuses[1]}). It runs in a single transaction, so the database is as it was before. The application is still stopped"
  fi
  local versions
  versions=$(psql_in_db "$db_name" "select count(*) from public.flyway_schema_history")
  [ "$versions" -gt 0 ] || die "the restore finished but the Flyway history is empty: something is wrong, do not start the application"

  log "restored. Flyway history has $versions entries."
  if [ -z "$target" ]; then
    echo "The application is STOPPED. Now run the release that matches this data:"
    echo "  deploy/deploy.sh rollback --force     (if the restored data is from the previous release)"
    echo "  or deploy the commit that was running when the backup was taken."
  fi
}

main "$@"
