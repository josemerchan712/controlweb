#!/usr/bin/env bash
# Sets up the READ-ONLY download of the encrypted backups for the owner's PC: the account `cwbackup`, its ONE restricted SSH key and
# the sudoers rule for serve-backup.sh. Run install.sh first (it creates /usr/local/lib/controlweb).
#
#   sudo deploy/backup/install-reader.sh --pubkey /path/to/controlweb_backup_ed25519.pub [--from 203.0.113.7]
#
# What it creates, and why each piece is root-owned:
#   /usr/local/lib/controlweb/serve-backup.sh        root:root 0755   the only thing the key can run (as root, read-only)
#   /etc/sudoers.d/controlweb-reader                 root:root 0440   cwbackup may run exactly that script, no arguments
#   /var/lib/cwbackup/.ssh/authorized_keys           root:root 0644   restrict,command="sudo -n .../serve-backup.sh" <your PUBLIC key>
#   the account cwbackup: system account, locked password, home /var/lib/cwbackup (root-owned, so it cannot rewrite its own key line)
# The key line starts with `restrict` (no terminal, no port/agent/X11 forwarding, no ~/.ssh/rc) and a forced command: whatever the
# client asks for, sshd runs ONLY that command. The optional --from limits the key to a source address.
set -euo pipefail

: "${LIB_DIR:=/usr/local/lib/controlweb}"
: "${HOME_DIR:=/var/lib/cwbackup}"
: "${SUDOERS_DIR:=/etc/sudoers.d}"
: "${READER:=cwbackup}"
: "${VISUDO:=visudo}"              # only the tests change these
: "${USERADD:=useradd}"
: "${GETENT:=getent}"
: "${OWNER:=root:root}"
: "${CHECK_PARENTS:=yes}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

[ "${ALLOW_NON_ROOT:-}" = 1 ] || [ "$(id -u)" -eq 0 ] || die "run it with sudo"
SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OWNER_USER=${OWNER%%:*}
OWNER_GROUP=${OWNER##*:}
SUDOERS_NAME=controlweb-reader
FORCED_COMMAND="sudo -n $LIB_DIR/serve-backup.sh"

pubkey="" from=""
while [ $# -gt 0 ]; do
  case "$1" in
    --pubkey) [ $# -ge 2 ] || die "--pubkey needs a file"; pubkey=$2; shift 2 ;;
    --from) [ $# -ge 2 ] && [ -n "$2" ] || die "--from needs an address"; from=$2; shift 2 ;;
    *) die "unknown option: $1 (usage: install-reader.sh --pubkey FILE [--from ADDRESS])" ;;
  esac
done
[ -n "$pubkey" ] || die "--pubkey FILE is required: the PUBLIC key (.pub) generated on your PC"
[ -f "$pubkey" ] || die "$pubkey does not exist"

# --- the key: exactly one line, ed25519, public. Options are NOT accepted from the file: they are added here. ---
if grep -q -E 'PRIVATE KEY|AGE-SECRET-KEY' "$pubkey"; then die "$pubkey contains a PRIVATE key: never copy it to the server (use the .pub file)"; fi
[ "$(grep -c -v -E '^[[:space:]]*$' "$pubkey")" -eq 1 ] || die "$pubkey must contain exactly one key"
key=$(grep -v -E '^[[:space:]]*$' "$pubkey" | tr -d '\r')
[[ "$key" =~ ^ssh-ed25519\ AAAA[A-Za-z0-9+/]{40,}={0,2}(\ [A-Za-z0-9@._-]{1,60})?$ ]] \
  || die "$pubkey is not a plain 'ssh-ed25519 AAAA... comment' public key (no options, no other key types)"
options="restrict,command=\"$FORCED_COMMAND\""
if [ -n "$from" ]; then
  [[ "$from" =~ ^[0-9A-Fa-f:./]{3,64}$ ]] || die "--from must be an IP address or a CIDR range (no names, no wildcards)"
  options="from=\"$from\",$options"
fi

for file in serve-backup.sh "$SUDOERS_NAME.sudoers"; do [ -f "$SRC/$file" ] || die "$SRC/$file is missing"; done
bash -n "$SRC/serve-backup.sh" || die "serve-backup.sh has a syntax error"
rules=$(grep -v -E '^[[:space:]]*(#|$)' "$SRC/$SUDOERS_NAME.sudoers" || true)
expected_rules="Defaults:$READER !use_pty
$READER ALL=(root) NOPASSWD: /usr/local/lib/controlweb/serve-backup.sh"
[ "$rules" = "$expected_rules" ] || die "$SUDOERS_NAME.sudoers must contain exactly two lines: 'Defaults:$READER !use_pty' and the one rule for /usr/local/lib/controlweb/serve-backup.sh"
"$VISUDO" -cf "$SRC/$SUDOERS_NAME.sudoers" > /dev/null || die "visudo does not accept $SUDOERS_NAME.sudoers"
[ -d "$LIB_DIR" ] || die "$LIB_DIR does not exist: run deploy/backup/install.sh first"

# --- the account ---
if "$GETENT" passwd "$READER" > /dev/null 2>&1; then
  note "ok:  account $READER already exists"
else
  "$USERADD" --system --user-group --home-dir "$HOME_DIR" --no-create-home --shell /bin/bash --comment "ControlWeb backup download (read-only)" "$READER" \
    || die "could not create the account $READER"
  note "created the system account $READER (locked password)"
fi

# --- files (all root-owned) ---
install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SRC/serve-backup.sh" "$LIB_DIR/serve-backup.sh"
install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$HOME_DIR" "$HOME_DIR/.ssh"
authorized="$HOME_DIR/.ssh/authorized_keys"
temporary="$HOME_DIR/.ssh/authorized_keys.new"
printf '%s %s\n' "$options" "$key" > "$temporary"
chown "$OWNER" "$temporary"
chmod 644 "$temporary"
mv -f "$temporary" "$authorized"

install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SUDOERS_DIR"
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
expect "$LIB_DIR/serve-backup.sh" 755
expect "$HOME_DIR" 755
expect "$HOME_DIR/.ssh" 755
expect "$authorized" 644
expect "$SUDOERS_DIR/$SUDOERS_NAME" 440
if [ "$CHECK_PARENTS" = yes ]; then
  for start in "$LIB_DIR" "$HOME_DIR/.ssh" "$SUDOERS_DIR"; do
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
  done
  note "ok:  every folder above the script, the key and the rule is root-owned and not writable by others"
fi
[ "$problems" -eq 0 ] || die "$problems permission problem(s): remove $authorized until they are fixed"
"$VISUDO" -c > /dev/null || die "visudo -c reports an error in the sudoers configuration: fix it NOW (run: sudo visudo -c)"

note "installed. Key line in $authorized:"
note "  ${options} ssh-ed25519 AAAA...(your key)"
note "Test it FROM YOUR PC with: .\\deploy\\backup\\download-backups.ps1 -SshTarget $READER@HOST -List"
