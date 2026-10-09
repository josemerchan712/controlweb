#!/usr/bin/env bash
# Read-only server side of the backup download (download-backups.ps1 on the owner's PC).
#
#   /usr/local/lib/controlweb/serve-backup.sh        (root, via `sudo -n` from the forced command of ONE restricted SSH key)
#
# The request is ONE line on standard input (never an argument, so the sudoers rule can be an exact command with no arguments):
#
#   list                              the stored copies, newest first: "<name> <bytes>" per line
#   sha256 controlweb-<stamp>.dump.age     "<hex>  <name>"
#   get controlweb-<stamp>.dump.age        the bytes of that copy on standard output, nothing else
#
# Anything else is refused. The name must match the exact pattern of the files backup.sh creates, so there is no path, no "..", no glob,
# no option. Only regular files (not links) inside BACKUP_DIR are served, and nothing is ever written or deleted here. The copies are
# encrypted with age, but this account still can only read them, never change them.
set -euo pipefail
: "${BACKUP_DIR:=/var/backups/controlweb}"       # only the tests change this
NAME_PATTERN='^controlweb-[0-9]{8}T[0-9]{6}Z\.dump\.age$'

refuse() { printf 'refused: %s\n' "$1" >&2; exit 2; }

# read ONE line, at most 200 bytes, so a huge request cannot do anything
IFS= read -r -n 200 request || true
request=${request%$'\r'}
[ -n "$request" ] || refuse "empty request"
case "$request" in *[!A-Za-z0-9\ .-]*) refuse "unexpected character in the request" ;; esac

command=${request%% *}
argument=""
[ "$command" != "$request" ] && argument=${request#* }

copy_path() { # validates the name and prints the path of an existing regular file
  [[ "$1" =~ $NAME_PATTERN ]] || refuse "bad file name"
  local path="$BACKUP_DIR/$1"
  [ -f "$path" ] && [ ! -L "$path" ] || refuse "no such copy"
  printf '%s' "$path"
}

case "$command" in
  list)
    [ -z "$argument" ] || refuse "list takes no argument"
    find "$BACKUP_DIR" -maxdepth 1 -type f -name 'controlweb-*.dump.age' -printf '%f %s\n' | sort -r
    ;;
  sha256)
    path=$(copy_path "$argument")
    (cd "$BACKUP_DIR" && sha256sum -- "$(basename "$path")")
    ;;
  get)
    path=$(copy_path "$argument")
    cat -- "$path"
    ;;
  *) refuse "unknown request" ;;
esac
