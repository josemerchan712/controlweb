#!/usr/bin/env bash
# Installs the backup scripts as ROOT-OWNED files, creates the folders and checks the permissions.
#
#   sudo deploy/backup/install.sh          (as root, from a checkout of the repository that you have reviewed)
#
# Why root-owned: backup.sh, restore.sh and restore-test.sh are run as root by systemd. If jose (or anything running as jose) could
# edit them, jose could make root run anything: the permissions ARE the security of this feature. So they are never run from the
# repository clone in the home folder (jose can edit it); they are COPIED here with `install`, which sets owner and mode, and every
# folder above them must be root-owned and not writable by anybody else. The last step of this script checks all of it and fails
# loudly if something is off. Run it again after every change to these scripts (it is safe to repeat).
#
# Layout:  /usr/local/lib/controlweb/   the scripts (root:root 0755)       /etc/controlweb/backup-recipient.txt   the PUBLIC key (0644)
#          /var/backups/controlweb/     encrypted copies (root:root 0700)  /var/lib/controlweb-backup/            status files (0755)
#          /etc/systemd/system/controlweb-*.service|timer (root:root 0644)  /etc/sudoers.d/controlweb-backup (root:root 0440)
#
# The timers are NOT enabled here (that is a decision for the owner, see docs/DESPLIEGUE.md); the sudoers rule is installed only after
# `visudo -cf` accepts it, through a temporary name that sudo ignores (a name with a dot), so a broken file never becomes active.
set -euo pipefail

: "${LIB_DIR:=/usr/local/lib/controlweb}"
: "${ETC_DIR:=/etc/controlweb}"
: "${BACKUP_DIR:=/var/backups/controlweb}"
: "${STATE_DIR:=/var/lib/controlweb-backup}"
: "${SYSTEMD_DIR:=/etc/systemd/system}"
: "${SUDOERS_DIR:=/etc/sudoers.d}"
: "${VISUDO:=visudo}"              # only the tests change these four
: "${SYSTEMD_ANALYZE:=systemd-analyze}"
: "${SYSTEMCTL:=systemctl}"
: "${OWNER:=root:root}"            # only the tests change this
: "${CHECK_PARENTS:=yes}"          # only the tests change this

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

[ "${ALLOW_NON_ROOT:-}" = 1 ] || [ "$(id -u)" -eq 0 ] || die "run it with sudo"
SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OWNER_USER=${OWNER%%:*}
OWNER_GROUP=${OWNER##*:}
command -v age > /dev/null 2>&1 || die "age is not installed: sudo apt install age"
for script in common.sh backup.sh restore.sh restore-test.sh; do
  [ -f "$SRC/$script" ] || die "$SRC/$script is missing"
  bash -n "$SRC/$script" || die "$script has a syntax error"
done
UNITS="controlweb-backup.service controlweb-backup.timer controlweb-restore-test.service controlweb-restore-test.timer"
SUDOERS_NAME=controlweb-backup
for unit in $UNITS; do [ -f "$SRC/systemd/$unit" ] || die "$SRC/systemd/$unit is missing"; done
[ -f "$SRC/$SUDOERS_NAME.sudoers" ] || die "$SRC/$SUDOERS_NAME.sudoers is missing"
# the rule is checked BEFORE anything is installed: a single rule line, no wildcard or tag, and sudo itself must accept the syntax
rules=$(grep -c -v -E '^[[:space:]]*(#|$)' "$SRC/$SUDOERS_NAME.sudoers" || true)
[ "$rules" -eq 1 ] || die "$SUDOERS_NAME.sudoers must contain exactly one rule line (it has $rules)"
if grep -v -E '^[[:space:]]*#' "$SRC/$SUDOERS_NAME.sudoers" | grep -q -E '[*?[]|[\\]|SETENV|NOEXEC|EXEC|ALL[[:space:]]*$'; then
  die "$SUDOERS_NAME.sudoers has a wildcard, a tag or an open command: refusing to install it"
fi
"$VISUDO" -cf "$SRC/$SUDOERS_NAME.sudoers" > /dev/null || die "visudo does not accept $SUDOERS_NAME.sudoers"

install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$LIB_DIR"
for script in common.sh backup.sh restore.sh restore-test.sh; do
  install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SRC/$script" "$LIB_DIR/$script"
done
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$ETC_DIR"
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 700 "$BACKUP_DIR"
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$STATE_DIR"
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SYSTEMD_DIR"
for unit in $UNITS; do
  install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 644 "$SRC/systemd/$unit" "$SYSTEMD_DIR/$unit"
done
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SUDOERS_DIR"
# sudo ignores files whose name contains a dot, so the .new file is inert until it has been validated and renamed
rm -f "$SUDOERS_DIR/$SUDOERS_NAME.new"
install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 440 "$SRC/$SUDOERS_NAME.sudoers" "$SUDOERS_DIR/$SUDOERS_NAME.new"
if ! "$VISUDO" -cf "$SUDOERS_DIR/$SUDOERS_NAME.new" > /dev/null; then
  rm -f "$SUDOERS_DIR/$SUDOERS_NAME.new"
  die "visudo rejected the installed copy of the rule: it was NOT activated"
fi
mv -f "$SUDOERS_DIR/$SUDOERS_NAME.new" "$SUDOERS_DIR/$SUDOERS_NAME"

# --- check permissions ---
problems=0
expect() { # expect <path> <mode>
  local owner mode
  owner=$(stat -c '%U:%G' "$1")
  mode=$(stat -c '%a' "$1")
  if [ "$owner" != "$OWNER" ] || [ "$mode" != "$2" ]; then
    printf 'BAD: %s is %s %s, expected %s %s\n' "$1" "$owner" "$mode" "$OWNER" "$2" >&2
    problems=$((problems + 1))
  else
    printf 'ok:  %s %s %s\n' "$owner" "$mode" "$1"
  fi
}
for script in common.sh backup.sh restore.sh restore-test.sh; do expect "$LIB_DIR/$script" 755; done
expect "$LIB_DIR" 755
expect "$BACKUP_DIR" 700
expect "$STATE_DIR" 755
expect "$ETC_DIR" 755
for unit in $UNITS; do expect "$SYSTEMD_DIR/$unit" 644; done
expect "$SUDOERS_DIR/$SUDOERS_NAME" 440
if [ -e "$ETC_DIR/backup-recipient.txt" ]; then
  expect "$ETC_DIR/backup-recipient.txt" 644
  if grep -q 'AGE-SECRET-KEY' "$ETC_DIR/backup-recipient.txt"; then printf 'BAD: a PRIVATE key is in %s\n' "$ETC_DIR/backup-recipient.txt" >&2; problems=$((problems + 1)); fi
fi

if [ "$CHECK_PARENTS" = yes ]; then
  # every folder from the scripts up to / must belong to root and be writable by nobody else
  for start in "$LIB_DIR" "$SYSTEMD_DIR" "$SUDOERS_DIR"; do
    path=$start
    while [ "$path" != "/" ] && [ "$path" != "." ]; do
      owner=$(stat -c '%U' "$path")
      mode=$(stat -c '%a' "$path")
      if [ "$owner" != "$OWNER_USER" ] || [ $((8#$mode & 8#022)) -ne 0 ]; then
        printf 'BAD: %s (%s, mode %s) can be changed by somebody other than root\n' "$path" "$owner" "$mode" >&2
        problems=$((problems + 1))
      fi
      path=$(dirname "$path")
    done
    note "ok:  every folder above $start is root-owned and not writable by others"
  done
fi

[ "$problems" -eq 0 ] || die "$problems permission problem(s): do NOT enable the timers until they are fixed"

# the whole sudoers configuration must still be valid, and the units must pass systemd's own checks
"$VISUDO" -c > /dev/null || die "visudo -c reports an error in the sudoers configuration: fix it NOW (run: sudo visudo -c)"
if command -v "$SYSTEMD_ANALYZE" > /dev/null 2>&1; then
  (cd "$SYSTEMD_DIR" && "$SYSTEMD_ANALYZE" verify $UNITS) || die "systemd-analyze verify rejected the units"
fi
"$SYSTEMCTL" daemon-reload || die "systemctl daemon-reload failed"

if [ ! -e "$ETC_DIR/backup-recipient.txt" ]; then
  note ""
  note "Next: generate the age key pair ON YOUR PC (docs/DESPLIEGUE.md, backups), then install ONLY the public key here:"
  note "  sudo install -o root -g root -m 644 backup-recipient.txt $ETC_DIR/backup-recipient.txt"
fi
note "installed. The timers are NOT enabled yet; when the first backup has been tested by hand:"
note "  sudo systemctl enable --now controlweb-backup.timer controlweb-restore-test.timer"
