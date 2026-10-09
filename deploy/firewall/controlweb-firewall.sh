#!/usr/bin/env bash
# Egress firewall of the ControlWeb application.
#
#   /usr/local/sbin/controlweb-firewall  start | stop | status | guard      (root; started by controlweb-firewall.service)
#
# WHY: the application fetches URLs that the administrator typed in (it monitors websites). Without a firewall, a URL pointing to
# 169.254.169.254, to the Docker bridges of Rotten Odds or to the private network of the VPS would be a way into things it must never
# reach (SSRF). The application already refuses such URLs; this is the second, independent wall.
#
# EVERYTHING HANGS OFF ONE BRIDGE, cw-egress (the Docker network controlweb_egress, whose only member is the application). Both entry
# points are a single jump guarded by `-i cw-egress`:
#     DOCKER-USER : -i cw-egress -j CW-EGRESS        (traffic the application sends to other networks: FORWARD)
#     INPUT       : -i cw-egress -j CW-INPUT         (traffic the application sends to the VPS itself)
# A packet that does not enter through cw-egress (Rotten Odds' bridges, cw-proxy, cw-db, the real network card, loopback) never matches
# that jump, so it never even looks at any other rule of this script. The three chains are ours (CW-*); nothing else is modified:
# no policy changes, no flush of a Docker/other chain, no nat table, no iptables-save/restore.
#
#   CW-EGRESS: 1 ESTABLISHED,RELATED                                                -> RETURN
#              2 tcp, ctstate DNAT, ctorigdst <PUBLIC_IP>, ctorigdstport 443 / 80   -> RETURN   (the "hairpin": the application
#                reaching the VPS's own public address, which Docker rewrites (DNAT) to Caddy's address on a private network)
#              3 optional DNS servers on a private address (ALLOW_DNS)               -> RETURN
#              4 destination in a private / reserved / special-use range            -> CW-DENY
#              5 everything else (the public Internet)                              -> RETURN
#   CW-DENY:   rate-limited LOG, then REJECT (tcp-reset, or icmp-admin-prohibited): the application is told at once, never a silent DROP.
#   CW-INPUT:  ESTABLISHED,RELATED -> ACCEPT ; everything else -> REJECT (SSH included).
#
# Order of work: the chains are filled first and the two jumps are added LAST, so a failure half way never leaves a half-built
# ruleset active. `stop` removes the jumps FIRST and then deletes the chains, so it removes every rule of ours and nothing else.
# Applying twice gives the same rules (the chains are rebuilt; the jumps are only added when missing).
#
# Configuration: /etc/controlweb/firewall.conf (root-owned, parsed line by line, never sourced):
#     PUBLIC_IP=203.0.113.10          the public address of the VPS (required; never stored in the repository)
#     ALLOW_DNS=10.0.0.2 10.0.0.3     optional: resolvers that live on a PRIVATE address and must stay reachable
set -euo pipefail

: "${IPTABLES:=iptables}"                       # only the tests change these
: "${DOCKER:=docker}"
: "${CONF_FILE:=/etc/controlweb/firewall.conf}"

BRIDGE=cw-egress
# Compose names the container <project>-<service>-<n> (controlweb-controlweb-app-1), so it is found by its Compose labels, never by a name.
APP_SERVICE=controlweb-app
APP_PROJECT=controlweb
# Destinations the application may never reach through cw-egress (the plan's rule 4).
BLOCKED=(0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.0.0.0/24 192.0.2.0/24 192.168.0.0/16
  198.18.0.0/15 198.51.100.0/24 203.0.113.0/24 224.0.0.0/4 240.0.0.0/4)

log() { printf 'controlweb-firewall: %s\n' "$*"; }
die() { printf 'controlweb-firewall: ERROR: %s\n' "$*" >&2; exit 1; }
ipt() { "$IPTABLES" -w 5 "$@"; }

# "Is it there?" questions. iptables answers 1 for "no such rule/chain"; ANY other failure (lock, kernel, bad call) is a real error and
# must stop the script, not be mistaken for "absent" (that would, for example, add a second copy of a jump).
rule_present() {
  local rc=0
  ipt -C "$@" 2> /dev/null || rc=$?
  case $rc in 0) return 0 ;; 1) return 1 ;; *) die "iptables -C failed (exit $rc)" ;; esac
}
chain_present() {
  local rc=0
  ipt -S "$1" > /dev/null 2>&1 || rc=$?
  case $rc in 0) return 0 ;; 1) return 1 ;; *) die "iptables -S failed (exit $rc)" ;; esac
}

ip_to_int() { local IFS=. a b c d; read -r a b c d <<< "$1"; echo $(((a << 24) + (b << 16) + (c << 8) + d)); }
valid_ipv4() {
  local octet
  [[ "$1" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || return 1
  for octet in ${1//./ }; do [ "$((10#$octet))" -le 255 ] || return 1; done
}
in_cidr() { # in_cidr <ip> <cidr>
  local net=${2%/*} bits=${2#*/} mask
  mask=$(((0xFFFFFFFF << (32 - bits)) & 0xFFFFFFFF))
  [ $(($(ip_to_int "$1") & mask)) -eq $(($(ip_to_int "$net") & mask)) ]
}

PUBLIC_IP=""
ALLOW_DNS=()
read_conf() {
  [ -r "$CONF_FILE" ] || die "$CONF_FILE does not exist: write PUBLIC_IP=<public address of the VPS> in it (docs/DESPLIEGUE.md, firewall)"
  if [ "${ALLOW_NON_ROOT:-}" != 1 ]; then
    [ "$(stat -c '%U' "$CONF_FILE")" = root ] && [ $((8#$(stat -c '%a' "$CONF_FILE") & 8#022)) -eq 0 ] \
      || die "$CONF_FILE must belong to root and not be writable by others"
  fi
  local line key value word cidr
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in '' | '#'*) continue ;; esac
    [[ "$line" =~ ^(PUBLIC_IP|ALLOW_DNS)=([0-9.\ ]*)$ ]] || die "unexpected line in $CONF_FILE (only PUBLIC_IP= and ALLOW_DNS= are accepted)"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    if [ "$key" = PUBLIC_IP ]; then
      valid_ipv4 "$value" || die "PUBLIC_IP in $CONF_FILE is not an IPv4 address"
      for cidr in "${BLOCKED[@]}"; do
        ! in_cidr "$value" "$cidr" || die "PUBLIC_IP $value is inside $cidr: it must be the PUBLIC address of the VPS"
      done
      PUBLIC_IP=$value
    else
      for word in $value; do
        valid_ipv4 "$word" || die "ALLOW_DNS has '$word', which is not an IPv4 address"
        [ "$word" != 169.254.169.254 ] || die "ALLOW_DNS must not contain the cloud metadata address"
        ALLOW_DNS+=("$word")
      done
    fi
  done < "$CONF_FILE"
  [ -n "$PUBLIC_IP" ] || die "PUBLIC_IP is missing in $CONF_FILE"
}

remove_jumps() {
  # `-C` first: deleting a rule that is not there is an error, and `stop` must work when there is nothing to stop
  while rule_present INPUT -i "$BRIDGE" -j CW-INPUT; do ipt -D INPUT -i "$BRIDGE" -j CW-INPUT; done
  while rule_present DOCKER-USER -i "$BRIDGE" -j CW-EGRESS; do ipt -D DOCKER-USER -i "$BRIDGE" -j CW-EGRESS; done
}

remove_chains() {
  local chain
  for chain in CW-EGRESS CW-INPUT CW-DENY; do # CW-DENY last: CW-EGRESS refers to it
    if chain_present "$chain"; then
      ipt -F "$chain"
      ipt -X "$chain"
    fi
  done
}

cmd_stop() {
  remove_jumps
  remove_chains
  log "stopped: every CW-* rule and chain is gone; nothing else was touched"
}

new_or_flush() { if chain_present "$1"; then ipt -F "$1"; else ipt -N "$1"; fi; }

# Anything that fails while starting takes our own rules away again: never leave a half-built firewall.
undo_on_failure() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    log "FAILED: removing our rules again" >&2
    remove_jumps || true
    remove_chains || true
  fi
}

cmd_start() {
  read_conf
  trap undo_on_failure EXIT

  new_or_flush CW-DENY
  new_or_flush CW-EGRESS
  new_or_flush CW-INPUT

  ipt -A CW-DENY -m limit --limit 6/min --limit-burst 10 -j LOG --log-prefix "CW-DENY:" --log-level 4
  ipt -A CW-DENY -p tcp -j REJECT --reject-with tcp-reset
  ipt -A CW-DENY -j REJECT --reject-with icmp-admin-prohibited

  ipt -A CW-EGRESS -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
  local port dns cidr
  for port in 443 80; do
    ipt -A CW-EGRESS -p tcp -m conntrack --ctstate DNAT --ctorigdst "$PUBLIC_IP" --ctorigdstport "$port" -j RETURN
  done
  for dns in "${ALLOW_DNS[@]+"${ALLOW_DNS[@]}"}"; do
    ipt -A CW-EGRESS -d "$dns" -p udp --dport 53 -j RETURN
    ipt -A CW-EGRESS -d "$dns" -p tcp --dport 53 -j RETURN
  done
  for cidr in "${BLOCKED[@]}"; do
    ipt -A CW-EGRESS -d "$cidr" -j CW-DENY
  done
  ipt -A CW-EGRESS -j RETURN

  ipt -A CW-INPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  ipt -A CW-INPUT -p tcp -j REJECT --reject-with tcp-reset
  ipt -A CW-INPUT -j REJECT --reject-with icmp-admin-prohibited

  # Docker creates DOCKER-USER itself when it starts; if we run first we create it, and Docker keeps it (it never flushes it).
  chain_present DOCKER-USER || ipt -N DOCKER-USER
  # The jumps go in LAST and at the TOP (position 1), and only if they are not there already.
  rule_present DOCKER-USER -i "$BRIDGE" -j CW-EGRESS || ipt -I DOCKER-USER 1 -i "$BRIDGE" -j CW-EGRESS
  rule_present INPUT -i "$BRIDGE" -j CW-INPUT || ipt -I INPUT 1 -i "$BRIDGE" -j CW-INPUT
  trap - EXIT
  log "started: egress rules hang off $BRIDGE only (public address $PUBLIC_IP allowed on 80/443 for the hairpin)"
}

# 0 if both jumps and the three chains are in place.
cmd_status() {
  rule_present DOCKER-USER -i "$BRIDGE" -j CW-EGRESS || { log "DOCKER-USER does not jump to CW-EGRESS"; return 1; }
  rule_present INPUT -i "$BRIDGE" -j CW-INPUT || { log "INPUT does not jump to CW-INPUT"; return 1; }
  local chain
  for chain in CW-EGRESS CW-INPUT CW-DENY; do chain_present "$chain" || { log "chain $chain is missing"; return 1; }; done
  log "active"
}

# Fail closed after a reboot: if Docker came up and the firewall did not, stop THE APPLICATION (never anything else).
cmd_guard() {
  if cmd_status > /dev/null 2>&1; then
    log "guard: the firewall is active, nothing to do"
    return 0
  fi
  log "guard: the firewall is NOT active"
  local ids id
  ids=$("$DOCKER" ps -q --filter "label=com.docker.compose.project=$APP_PROJECT" --filter "label=com.docker.compose.service=$APP_SERVICE" 2> /dev/null || true)
  if [ -z "$ids" ]; then
    log "guard: no running container of the service $APP_SERVICE of the project $APP_PROJECT: nothing to stop"
    return 0
  fi
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    if "$DOCKER" stop "$id" > /dev/null 2>&1; then
      log "guard: the application ($id) was stopped because it must not run without the firewall (start it again after fixing the firewall)"
    else
      log "guard: could not stop the application ($id) (is Docker running?)"
    fi
  done <<< "$ids"
  return 0
}

case "${1:-}" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  guard) cmd_guard ;;
  *) printf 'usage: controlweb-firewall start|stop|status|guard\n' >&2; exit 2 ;;
esac
