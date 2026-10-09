#!/usr/bin/env bash
# Installs the egress firewall as ROOT-OWNED files. It does NOT start it, does NOT enable it and does NOT create the configuration:
# all three are decisions for the owner (docs/DESPLIEGUE.md, "Firewall de salida").
#
#   sudo deploy/firewall/install.sh          (as root, from a checkout of the repository that you have reviewed)
#
#   /usr/local/sbin/controlweb-firewall                      root:root 0755   the script (the units run it as root: nobody else may edit it)
#   /etc/systemd/system/controlweb-firewall.service          root:root 0644
#   /etc/systemd/system/controlweb-firewall-guard.service    root:root 0644
#   /etc/controlweb/firewall.conf                            NOT created here: PUBLIC_IP=... (root-owned; see the docs)
set -euo pipefail

: "${SBIN_DIR:=/usr/local/sbin}"
: "${SYSTEMD_DIR:=/etc/systemd/system}"
: "${ETC_DIR:=/etc/controlweb}"
: "${SYSTEMD_ANALYZE:=systemd-analyze}"      # only the tests change these
: "${SYSTEMCTL:=systemctl}"
: "${OWNER:=root:root}"
: "${CHECK_PARENTS:=yes}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

[ "${ALLOW_NON_ROOT:-}" = 1 ] || [ "$(id -u)" -eq 0 ] || die "run it with sudo"
SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OWNER_USER=${OWNER%%:*}
OWNER_GROUP=${OWNER##*:}
UNITS="controlweb-firewall.service controlweb-firewall-guard.service"
for file in controlweb-firewall.sh $UNITS; do [ -f "$SRC/$file" ] || die "$SRC/$file is missing"; done
bash -n "$SRC/controlweb-firewall.sh" || die "controlweb-firewall.sh has a syntax error"

install -d -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SBIN_DIR" "$SYSTEMD_DIR" "$ETC_DIR"
install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 755 "$SRC/controlweb-firewall.sh" "$SBIN_DIR/controlweb-firewall"
for unit in $UNITS; do install -o "$OWNER_USER" -g "$OWNER_GROUP" -m 644 "$SRC/$unit" "$SYSTEMD_DIR/$unit"; done

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
expect "$SBIN_DIR/controlweb-firewall" 755
for unit in $UNITS; do expect "$SYSTEMD_DIR/$unit" 644; done
if [ -e "$ETC_DIR/firewall.conf" ]; then
  owner=$(stat -c '%U' "$ETC_DIR/firewall.conf")
  mode=$(stat -c '%a' "$ETC_DIR/firewall.conf")
  if [ "$owner" != "$OWNER_USER" ] || [ $((8#$mode & 8#022)) -ne 0 ]; then
    printf 'BAD: %s (%s, mode %s) must belong to root and not be writable by others\n' "$ETC_DIR/firewall.conf" "$owner" "$mode" >&2
    problems=$((problems + 1))
  fi
fi
if [ "$CHECK_PARENTS" = yes ]; then
  for start in "$SBIN_DIR" "$SYSTEMD_DIR" "$ETC_DIR"; do
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
  note "ok:  every folder above the script, the units and the configuration is root-owned and not writable by others"
fi
[ "$problems" -eq 0 ] || die "$problems permission problem(s): do NOT start the firewall until they are fixed"

if command -v "$SYSTEMD_ANALYZE" > /dev/null 2>&1; then
  (cd "$SYSTEMD_DIR" && "$SYSTEMD_ANALYZE" verify $UNITS) || die "systemd-analyze verify rejected the units"
fi
"$SYSTEMCTL" daemon-reload || die "systemctl daemon-reload failed"

if [ ! -e "$ETC_DIR/firewall.conf" ]; then
  note ""
  note "Next: create $ETC_DIR/firewall.conf with the PUBLIC address of the VPS (root-owned, 0644):"
  note "  echo 'PUBLIC_IP=<public address of the VPS>' | sudo tee $ETC_DIR/firewall.conf && sudo chmod 644 $ETC_DIR/firewall.conf"
fi
note "installed. The firewall is NOT started and NOT enabled yet (docs/DESPLIEGUE.md, 'Firewall de salida')."
