#!/usr/bin/env bash
# Installs (or updates) the ControlWeb site fragment for the Caddy that Rotten Odds runs, WITHOUT any risk of breaking that Caddy.
#
#   sudo deploy/caddy/caddy-install.sh [--check] [FRAGMENT]        (as root, from a reviewed checkout; default FRAGMENT: controlweb.caddy)
#
# The danger: Rotten Odds' Caddyfile does `import /etc/caddy/sites/*.caddy`. A broken fragment on disk would be read the next time
# that Caddy starts, and Rotten Odds would not come back. So a fragment never reaches that folder before it has been proven good:
#   1. the fragment is checked for what it may contain (only OUR site address, only `reverse_proxy controlweb-app:8080`, no import /
#      exec / file access / environment placeholders / global options);
#   2. it is validated by a DISPOSABLE container of the SAME image (by id) as the Caddy that is running, with the real Caddyfile and a
#      copy of the sites folder that already contains the new fragment, no network, read-only, all capabilities dropped;
#   3. only then it is moved into place with an atomic rename (the temporary name starts with a dot and does not end in .caddy, so the
#      import glob can never pick up a half-written file);
#   4. only then `caddy reload` runs (a graceful reload: no connection is dropped);
#   5. if the reload fails the previous fragment is put back (or the new one removed) by another atomic rename and the script exits
#      with an error. Any earlier failure leaves the folder exactly as it was.
# Other projects' fragments in the folder are never touched. --check does steps 1 and 2 only.
set -euo pipefail

: "${SITES_DIR:=/opt/caddy-sites}"
: "${DOCKER:=docker}"
: "${CADDY_CONTAINER:=}"                                  # explicit container name; empty = find it by compose labels
: "${CADDY_PROJECT:=rottenodds}"
: "${CADDY_SERVICE:=caddy}"
: "${SITE_ADDRESS:=status-api.josemariamerchan.dev}"
: "${UPSTREAM:=controlweb-app:8080}"
: "${FRAGMENT_NAME:=controlweb.caddy}"
: "${CADDYFILE_IN_CONTAINER:=/etc/caddy/Caddyfile}"
: "${SITES_IN_CONTAINER:=/etc/caddy/sites}"
: "${LOCK_DIR:=/run/controlweb-caddy-install.lock}"
: "${VALIDATE_TIMEOUT:=90}"
: "${RELOAD_TIMEOUT:=60}"
: "${TIMEOUT:=timeout}"                                   # only the tests change these (under sudo the environment is reset)

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

[ "${ALLOW_NON_ROOT:-}" = 1 ] || [ "$(id -u)" -eq 0 ] || die "run it with sudo"
SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

check_only=no
fragment=""
for argument in "$@"; do
  case "$argument" in
    --check) check_only=yes ;;
    -*) die "unknown option: $argument (usage: caddy-install.sh [--check] [FRAGMENT])" ;;
    *) [ -z "$fragment" ] || die "only one fragment can be installed at a time"; fragment=$argument ;;
  esac
done
[ -n "$fragment" ] || fragment="$SRC/$FRAGMENT_NAME"
[ -f "$fragment" ] || die "the fragment $fragment does not exist"

# ---- 1. what the fragment may contain ----
check_fragment() {
  local size line sites=0 proxies=0
  size=$(wc -c < "$fragment")
  [ "$size" -gt 0 ] && [ "$size" -le 8192 ] || die "the fragment must be between 1 byte and 8 KiB ($size bytes)"
  [ "$(tr -d '\000' < "$fragment" | wc -c)" -eq "$size" ] || die "the fragment contains a NUL byte"
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in
      '' | '#'* | [[:space:]]*'#'*) continue ;;
      [[:space:]]*) ;;
      '}') ;;
      *)
        [ "$line" = "$SITE_ADDRESS {" ] || die "the only top-level block allowed is '$SITE_ADDRESS {' (found: '$line'): this fragment must not define any other site or global option"
        sites=$((sites + 1))
        ;;
    esac
    if [[ "$line" =~ (^|[[:space:]])(import|exec|file_server|root|php_fastcgi|admin|acme_ca|storage|servers|forward_proxy|tls|log|bind|handle_path|rewrite)([[:space:]]|$) ]]; then
      die "the directive '${BASH_REMATCH[2]}' is not allowed in this fragment"
    fi
    [[ "$line" != *'{$'* && "$line" != *'{env.'* && "$line" != *'{file.'* && "$line" != *'{http.request.header'* ]] || die "placeholders that read the environment or files are not allowed"
    if [[ "$line" =~ ^[[:space:]]*reverse_proxy([[:space:]]|$) ]]; then
      [[ "$line" =~ ^[[:space:]]*reverse_proxy[[:space:]]+"$UPSTREAM"[[:space:]]*$ ]] || die "the only reverse_proxy allowed is 'reverse_proxy $UPSTREAM' (found: '$line')"
      proxies=$((proxies + 1))
    fi
  done < "$fragment"
  [ "$sites" -eq 1 ] || die "the fragment must define exactly one site block ($SITE_ADDRESS), it defines $sites"
  [ "$proxies" -eq 1 ] || die "the fragment must contain exactly one 'reverse_proxy $UPSTREAM'"
}
check_fragment
note "ok:  the fragment only defines $SITE_ADDRESS -> $UPSTREAM"

# ---- the running Caddy ----
if [ -z "$CADDY_CONTAINER" ]; then
  found=$("$DOCKER" ps --filter "label=com.docker.compose.project=$CADDY_PROJECT" --filter "label=com.docker.compose.service=$CADDY_SERVICE" \
    --filter status=running --format '{{.Names}}') || die "could not ask Docker for the Caddy container"
  [ -n "$found" ] && [ "$(printf '%s\n' "$found" | wc -l)" -eq 1 ] \
    || die "expected exactly ONE running container for the compose project '$CADDY_PROJECT', service '$CADDY_SERVICE', and found: '${found//$'\n'/ }' (set CADDY_CONTAINER to name it)"
  CADDY_CONTAINER=$found
fi
image=$("$DOCKER" inspect --format '{{.Image}}' "$CADDY_CONTAINER") || die "could not inspect $CADDY_CONTAINER"
[[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] || die "the image of $CADDY_CONTAINER is '$image', not an image id"
mount_source() { "$DOCKER" inspect --format "{{range .Mounts}}{{if eq .Destination \"$1\"}}{{.Source}}{{end}}{{end}}" "$CADDY_CONTAINER"; }
caddyfile=$(mount_source "$CADDYFILE_IN_CONTAINER")
[ -n "$caddyfile" ] && [ -f "$caddyfile" ] || die "could not find the Caddyfile of $CADDY_CONTAINER on this host (mount of $CADDYFILE_IN_CONTAINER: '$caddyfile')"
sites_mount=$(mount_source "$SITES_IN_CONTAINER")
[ -n "$sites_mount" ] && [ "$(cd "$sites_mount" 2> /dev/null && pwd -P)" = "$(cd "$SITES_DIR" 2> /dev/null && pwd -P)" ] \
  || die "$CADDY_CONTAINER does not mount $SITES_DIR on $SITES_IN_CONTAINER (mount: '$sites_mount'): Rotten Odds' Compose change (docs/DESPLIEGUE.md, R1) is not in place"
grep -Eq "^[[:space:]]*import[[:space:]]+$SITES_IN_CONTAINER/\*\.caddy[[:space:]]*$" "$caddyfile" \
  || die "the Caddyfile of $CADDY_CONTAINER has no 'import $SITES_IN_CONTAINER/*.caddy' line: the fragment would never be read (docs/DESPLIEGUE.md, R1)"
note "ok:  Caddy is $CADDY_CONTAINER (image ${image:0:19}...), Caddyfile $caddyfile"

mkdir "$LOCK_DIR" 2> /dev/null || die "another caddy-install.sh is running (or a stale lock at $LOCK_DIR)"
stage=""
cleanup() { [ -z "$stage" ] || rm -rf "$stage"; rmdir "$LOCK_DIR" 2> /dev/null || true; }
trap cleanup EXIT

# ---- 2. validate with a disposable container of the SAME image ----
stage=$(mktemp -d)
mkdir "$stage/sites"
chmod 755 "$stage" "$stage/sites"
for other in "$SITES_DIR"/*.caddy; do
  [ -e "$other" ] || continue
  [ "$(basename "$other")" = "$FRAGMENT_NAME" ] || cp -- "$other" "$stage/sites/"
done
cp -- "$fragment" "$stage/sites/$FRAGMENT_NAME"
chmod 644 "$stage/sites/"*
# The Caddyfile may use environment variables of the running container: give the throw-away container the same ones (it has no network).
umask 077
"$DOCKER" inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$CADDY_CONTAINER" > "$stage/env" || die "could not read the environment of $CADDY_CONTAINER"
umask 022
note "validating with a disposable container of the same image as $CADDY_CONTAINER..."
"$TIMEOUT" "$VALIDATE_TIMEOUT" "$DOCKER" run --rm --network none --memory 256m --read-only --tmpfs /tmp --tmpfs /data --tmpfs /config \
  --cap-drop ALL --security-opt no-new-privileges --env-file "$stage/env" \
  -v "$caddyfile:$CADDYFILE_IN_CONTAINER:ro" -v "$stage/sites:$SITES_IN_CONTAINER:ro" \
  "$image" caddy validate --config "$CADDYFILE_IN_CONTAINER" --adapter caddyfile \
  || die "Caddy rejected the configuration with this fragment: NOTHING was changed"
note "ok:  the complete configuration, with the new fragment, is valid"
if [ "$check_only" = yes ]; then
  note "--check: nothing was installed"
  exit 0
fi

# ---- 3. atomic move, 4. graceful reload, 5. undo on failure ----
final="$SITES_DIR/$FRAGMENT_NAME"
previous="$SITES_DIR/.$FRAGMENT_NAME.previous"
incoming="$SITES_DIR/.$FRAGMENT_NAME.new"
had_previous=no
if [ -e "$final" ]; then
  had_previous=yes
  cp -p -- "$final" "$previous"
fi
install -o root -g root -m 644 "$fragment" "$incoming" 2> /dev/null || install -m 644 "$fragment" "$incoming"
mv -f -- "$incoming" "$final"
note "installed $final"

undo() {
  if [ "$had_previous" = yes ]; then
    mv -f -- "$previous" "$final"
    note "the previous fragment was put back"
  else
    rm -f -- "$final"
    note "the new fragment was removed (there was none before)"
  fi
}
if ! "$TIMEOUT" "$RELOAD_TIMEOUT" "$DOCKER" exec "$CADDY_CONTAINER" caddy reload --config "$CADDYFILE_IN_CONTAINER" --adapter caddyfile; then
  undo
  # a reload that timed out may have been applied: reload again so that the running Caddy matches the folder
  "$TIMEOUT" "$RELOAD_TIMEOUT" "$DOCKER" exec "$CADDY_CONTAINER" caddy reload --config "$CADDYFILE_IN_CONTAINER" --adapter caddyfile > /dev/null 2>&1 || true
  die "caddy reload failed: the configuration on disk is back to what it was before"
fi
note "caddy reloaded (graceful, no connection dropped). The previous fragment, if any, is kept as $previous"
