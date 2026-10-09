#!/usr/bin/env bash
# Stand-in for ssh.exe in the tests of download-backups.ps1. The PowerShell script runs it through cmd.exe, which has already
# redirected stdin (the request file) and stdout (the answer file) and stderr. It behaves like the real chain
# (forced command -> sudo -> serve-backup.sh) by running serve-backup.sh itself, and can misbehave on purpose.
#   SERVE_SCRIPT  path of serve-backup.sh        BACKUP_DIR  the fake backup folder (read by serve-backup.sh)
#   FAKE_SSH_MODE: fail | corrupt (flip one byte of every "get") | truncate (cut 10 bytes of every "get") | badsha (wrong checksum)
set -u
request=$(head -n 1)
if [ "${FAKE_SSH_MODE:-}" = fail ]; then
  echo "ssh: connect to host example.invalid port 22: Connection timed out" >&2
  exit 255
fi
answer=$(mktemp)
printf '%s\n' "$request" | bash "$SERVE_SCRIPT" > "$answer"
code=$?
if [ "$code" -ne 0 ]; then
  exit "$code"
fi
case "$request" in
  get\ *)
    case "${FAKE_SSH_MODE:-}" in
      corrupt) printf 'Z' | dd of="$answer" bs=1 seek=100 conv=notrunc 2> /dev/null ;;
      truncate) head -c -10 "$answer" > "$answer.cut" && mv "$answer.cut" "$answer" ;;
    esac
    ;;
  sha256\ *)
    if [ "${FAKE_SSH_MODE:-}" = badsha ]; then
      printf '%064d  x\n' 0 > "$answer"
    fi
    ;;
esac
cat "$answer"
rm -f "$answer"
