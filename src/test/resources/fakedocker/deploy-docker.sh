#!/usr/bin/env bash
# A fake `docker` for DeployScriptTest (copied to a temporary directory as "docker"). It keeps a tiny world in $FAKE_STATE:
#   images     lines "<tag> <underlying image>"       (an "image" is just the tag it was loaded as)
#   running    the underlying image `docker compose up` started   |   stopped   present after `compose stop`
#   polls      how many times the container health was asked since the last `up`
#   calls.log  every call, in order, plus "up current=<underlying image>"
# Settings: FAKE_BAD_SHA (the release that never becomes healthy), FAKE_NO_NETWORK, FAKE_CONFIG_FAIL.
state=${FAKE_STATE:?}
touch "$state/images" "$state/calls.log"
echo "docker $*" >> "$state/calls.log"

underlying() { awk -v t="$1" '$1 == t {u = $2} END {print u}' "$state/images"; }
remove_tag() { grep -v "^$1 " "$state/images" > "$state/images.tmp" || true; mv "$state/images.tmp" "$state/images"; }

command_name=$1
shift
case "$command_name" in
  image) # docker image inspect <ref>
    [ -n "$(underlying "$2")" ] || exit 1
    ;;
  load) # docker load -i <file>: the first line of the file is the tag it contains
    tag=$(head -1 "$2" | tr -d '\r')
    echo "$tag img-$tag" >> "$state/images"
    echo "Loaded image: $tag"
    ;;
  tag)
    image=$(underlying "$1")
    [ -n "$image" ] || { echo "Error response from daemon: No such image: $1" >&2; exit 1; }
    remove_tag "$2"
    echo "$2 $image" >> "$state/images"
    ;;
  images) # docker images --format <format> controlweb-app
    awk '$1 ~ /^controlweb-app:/ {print $1}' "$state/images" | while read -r reference; do
      case "$*" in *Repository*) echo "$reference" ;; *) echo "${reference#controlweb-app:}" ;; esac
    done
    ;;
  rmi) remove_tag "$1" ;;
  network) [ -z "${FAKE_NO_NETWORK:-}" ] || exit 1 ;;
  inspect) # docker inspect --format <format> <container>
    if [ -e "$state/stopped" ] || [ ! -e "$state/running" ]; then echo "exited"; exit 0; fi
    polls=$(( $(cat "$state/polls" 2> /dev/null || echo 0) + 1 ))
    echo "$polls" > "$state/polls"
    if [ "$polls" -lt 2 ]; then echo "starting"; exit 0; fi
    if [ -n "${FAKE_BAD_SHA:-}" ] && [ "$(cat "$state/running")" = "img-controlweb-app:$FAKE_BAD_SHA" ]; then echo "unhealthy"; else echo "healthy"; fi
    ;;
  compose)
    while [ $# -gt 0 ]; do
      case "$1" in -p|-f|--env-file) shift 2 ;; *) break ;; esac
    done
    subcommand=$1
    shift
    case "$subcommand" in
      config) [ -z "${FAKE_CONFIG_FAIL:-}" ] || { echo "required variable is missing" >&2; exit 1; } ;;
      up)
        release=$(underlying "controlweb-app:current")
        echo "$release" > "$state/running"
        rm -f "$state/stopped"
        echo 0 > "$state/polls"
        touch "$state/database"
        echo "up current=$release" >> "$state/calls.log"
        ;;
      stop) touch "$state/stopped"; echo "stop $*" >> "$state/calls.log" ;;
      ps)
        if [ "${1:-}" = "-q" ]; then
          case "${2:-}" in
            controlweb-app) [ -e "$state/running" ] && echo "container-app" ;;
            controlweb-db) [ -e "$state/database" ] && echo "container-db" ;;
          esac
        else
          echo "NAME  STATUS"
        fi
        true
        ;;
      logs) echo "fake application log line" ;;
      *) echo "fake docker: unexpected compose subcommand: $subcommand" >&2; exit 2 ;;
    esac
    ;;
  *) echo "fake docker: unexpected command: $command_name" >&2; exit 2 ;;
esac
