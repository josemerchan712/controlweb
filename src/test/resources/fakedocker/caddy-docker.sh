#!/usr/bin/env bash
# Stand-in for docker in the tests of deploy/caddy/caddy-install.sh. Every call is appended to $FAKE_STATE/docker.log.
#   FAKE_CADDY_NAMES     what `docker ps` prints (one name per line)          FAKE_IMAGE      the image id of the running Caddy
#   FAKE_CADDYFILE       host path of the Caddyfile mount                     FAKE_SITES_MOUNT host path of the sites mount
#   FAKE_SITES_REAL      the real sites folder (to record what is on disk when `caddy reload` runs)
#   FAKE_VALIDATE_FAIL=1 validation always fails; otherwise it fails when a staged fragment contains the word BROKEN
#   FAKE_RELOAD_FAIL=1   every reload fails         FAKE_RELOAD_FAIL_FIRST=1  only the first reload fails
set -u
state=${FAKE_STATE:?}
mkdir -p "$state"
echo "$*" >> "$state/docker.log"
case "${1:-}" in
  ps)
    [ -z "${FAKE_CADDY_NAMES:-}" ] || printf '%s\n' "$FAKE_CADDY_NAMES"
    ;;
  inspect)
    format=$3
    case "$format" in
      '{{.Image}}') echo "${FAKE_IMAGE:-}" ;;
      *'.Config.Env'*) printf 'TZ=UTC\nCADDY_SECRET_LIKE=do-not-log-me\n' ;;
      *'"/etc/caddy/Caddyfile"'*) printf '%s' "${FAKE_CADDYFILE:-}" ;;
      *'"/etc/caddy/sites"'*) printf '%s' "${FAKE_SITES_MOUNT:-}" ;;
      *) echo "caddy-docker.sh: unexpected inspect format $format" >&2; exit 9 ;;
    esac
    ;;
  run)
    sites=""
    for argument in "$@"; do
      case "$argument" in *:/etc/caddy/sites:ro) sites=${argument%%:/etc/caddy/sites:ro} ;; esac
    done
    if [ -n "$sites" ]; then
      { echo "--- validated this folder:"; ls -A "$sites"; } >> "$state/validated.txt"
      cat "$sites"/*.caddy > "$state/validated-content.txt" 2> /dev/null || true
    fi
    [ "${FAKE_VALIDATE_FAIL:-}" != 1 ] || { echo "Error: adapting config using caddyfile: (injected failure)" >&2; exit 1; }
    if [ -n "$sites" ] && grep -q BROKEN "$sites"/*.caddy 2> /dev/null; then
      echo "Error: adapting config using caddyfile: unrecognized directive: BROKEN" >&2
      exit 1
    fi
    echo "Valid configuration"
    ;;
  exec)
    if [ -n "${FAKE_SITES_REAL:-}" ]; then
      { echo "--- on disk at reload:"; ls -A "$FAKE_SITES_REAL"; } >> "$state/reload-saw.txt"
      cat "$FAKE_SITES_REAL/controlweb.caddy" > "$state/reload-saw-fragment.txt" 2> /dev/null || rm -f "$state/reload-saw-fragment.txt"
    fi
    count=$(($(cat "$state/reloads" 2> /dev/null || echo 0) + 1))
    echo "$count" > "$state/reloads"
    [ "${FAKE_RELOAD_FAIL:-}" != 1 ] || { echo "Error: reload failed (injected)" >&2; exit 1; }
    [ "${FAKE_RELOAD_FAIL_FIRST:-}" != 1 ] || [ "$count" -gt 1 ] || { echo "Error: reload failed (injected, first call)" >&2; exit 1; }
    echo "reloaded"
    ;;
  *) echo "caddy-docker.sh: unexpected command $*" >&2; exit 9 ;;
esac
exit 0
