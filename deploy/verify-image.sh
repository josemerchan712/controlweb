#!/usr/bin/env bash
# Checks what a BUILT image really contains and how it is configured.
#
#   deploy/verify-image.sh controlweb-app:local
#
# It looks at the image only (it never starts it): no source code, tests, .env files, .git or docs/ inside; the jar holds
# compiled classes only (no .java, no test classes); the process runs as an unprivileged numeric user; a health check
# exists; and no environment variable of the image looks like a secret. Exit status 0 = all good.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash on Windows must not rewrite /app/... as a Windows path
native() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

image=${1:?usage: deploy/verify-image.sh <image>}
failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }
ok() { echo "ok:   $*"; }

work=$(mktemp -d)
cid=$(docker create "$image")
cleanup() { docker rm -f "$cid" > /dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

# --- files in the image (names only, nothing is extracted) ---
docker export "$cid" | tar -t > "$work/files.txt"

forbidden='(^|/)(\.git|\.github|\.mvn|docs|src|target|deploy)(/|$)|(^|/)\.env(\.[^/]*)?$|(^|/)(Dockerfile|\.dockerignore|compose[^/]*\.ya?ml|pom\.xml|mvnw(\.cmd)?|README\.md|CLAUDE\.md)$|\.java$'
if grep -E "$forbidden" "$work/files.txt" | grep -vE '^(usr|opt/java|lib|etc|bin|sbin|var)/' > "$work/bad.txt" && [ -s "$work/bad.txt" ]; then
  fail "files that must not be in the image:"; head -20 "$work/bad.txt" >&2
else
  ok "no source, tests, .env, .git, docs, build files or Dockerfile outside the system directories"
fi
if grep -qE '^app/' "$work/files.txt"; then ok "the application lives in /app"; else fail "/app is missing"; fi

# --- the jar: compiled classes only ---
PY=$(command -v python3 || command -v python)
docker cp "$cid:/app/application.jar" "$(native "$work/application.jar")" > /dev/null
"${PY}" - "$(native "$work/application.jar")" <<'PYEOF' || failures=$((failures + 1))
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
bad = [n for n in names if n.endswith('.java') or n.startswith('src/') or 'src/test' in n
       or n.endswith(('Test.class', 'Tests.class')) or n.endswith('.env') or '/.git/' in n]
if bad:
    print('FAIL: the application jar contains: ' + ', '.join(bad[:10]), file=sys.stderr)
    sys.exit(1)
print('ok:   the application jar has %d entries: compiled classes and resources only' % len(names))
PYEOF

# --- configuration ---
user=$(docker inspect --format '{{.Config.User}}' "$image")
case "$user" in
  ''|0|0:*|root|root:*) fail "the image runs as root (User='$user')" ;;
  *) ok "runs as user $user" ;;
esac
if [ "$(docker inspect --format '{{if .Config.Healthcheck}}yes{{end}}' "$image")" = yes ]; then ok "has a HEALTHCHECK"; else fail "no HEALTHCHECK"; fi
if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$image" | grep -qiE '(PASSWORD|SECRET|TOKEN|API_?KEY|CREDENTIAL)'; then
  fail "an environment variable of the image looks like a secret"
else
  ok "no secret-looking environment variable in the image"
fi
profile=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$image" | grep '^SPRING_PROFILES_ACTIVE=' || true)
[ "$profile" = "SPRING_PROFILES_ACTIVE=prod" ] && ok "default profile is prod" || fail "default profile is not prod ($profile)"

echo "size: $(docker image inspect --format '{{.Size}}' "$image" | awk '{printf "%.0f MB", $1/1000000}')"
if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed" >&2
  exit 1
fi
echo "image verified"
