#!/usr/bin/env bash
# A stateful stand-in for `iptables` (filter table only) for the tests of the egress firewall. State: one file per chain in
# $FAKE_IPT_STATE, "chain.NAME", one rule per line (the arguments joined by single spaces). It enforces the same rules the real
# one does where they matter for us:
#   -N fails if the chain exists          -X fails if the chain is built-in, not empty, or still referenced by a "-j CHAIN"
#   -D fails if no such rule              -C exits 1 if no such rule (2 if it jumps to a missing chain, as nf_tables does)
#   -F / -S fail if the chain does not exist
#   any "-t" (another table, e.g. nat) is refused: the firewall must only use the filter table
# Fault injection: FAKE_IPT_FAIL_AT=n makes the n-th call fail (exit 4), counting every call. The call log is $FAKE_IPT_STATE/.log.
# Only bash builtins are used: starting external programs is what makes a fake slow on Windows, and the tests make hundreds of calls.
set -u
state=${FAKE_IPT_STATE:?}
mkdir -p "$state"
n=0
[ -f "$state/.calls" ] && read -r n < "$state/.calls"
n=$((n + 1))
echo "$n" > "$state/.calls"
echo "$*" >> "$state/.log"
if [ "${FAKE_IPT_FAIL_AT:-0}" = "$n" ]; then
  echo "iptables: Resource temporarily unavailable. (injected failure)" >&2
  exit 4
fi

builtin=" INPUT FORWARD OUTPUT PREROUTING POSTROUTING "
args=("$@")
clean=()
i=0
while [ $i -lt ${#args[@]} ]; do
  case "${args[$i]}" in
    -w | -W) i=$((i + 1)); case "${args[$i]:-}" in '' | *[!0-9]*) i=$((i - 1)) ;; esac ;; # wait option with an optional number
    -t | --table) echo "iptables: the fake supports the filter table only (-t refused)" >&2; exit 9 ;;
    *) clean+=("${args[$i]}") ;;
  esac
  i=$((i + 1))
done
set -- "${clean[@]}"
op=${1:-}
shift || true
chain=${1:-}
shift || true
file="$state/chain.$chain"
rule="$*"
rules=()

exists() { [ -f "$state/chain.$1" ]; }
load() { rules=(); mapfile -t rules < "$file"; }
save() { if [ ${#rules[@]} -eq 0 ]; then : > "$file"; else printf '%s\n' "${rules[@]}" > "$file"; fi; }
nochain() { echo "iptables: No chain/target/match by that name." >&2; exit 1; }
norule() { echo "iptables: Bad rule (does a matching rule exist in that chain?)." >&2; exit 1; }
index_of() { # index_of <rule>: sets $found to the index of the first equal rule, or -1
  found=-1
  local k
  for k in "${!rules[@]}"; do
    if [ "${rules[$k]}" = "$1" ]; then found=$k; return 0; fi
  done
}

case "$op" in
  -N)
    exists "$chain" && { echo "iptables: Chain already exists." >&2; exit 1; }
    : > "$file"
    ;;
  -F)
    exists "$chain" || nochain
    : > "$file"
    ;;
  -X)
    exists "$chain" || nochain
    case "$builtin" in *" $chain "*) echo "iptables: Cannot delete a built-in chain." >&2; exit 1 ;; esac
    load
    [ ${#rules[@]} -eq 0 ] || { echo "iptables: Directory not empty." >&2; exit 1; }
    for other in "$state"/chain.*; do
      [ -e "$other" ] || continue
      mapfile -t others < "$other"
      for line in "${others[@]}"; do
        if [[ "$line" == *"-j $chain" || "$line" == *"-j $chain "* ]]; then echo "iptables: Too many links." >&2; exit 1; fi
      done
    done
    rm -f "$file"
    ;;
  -I)
    exists "$chain" || nochain
    position=1
    case "${1:-}" in '' | *[!0-9]*) ;; *) position=$1; shift; rule="$*" ;; esac
    load
    rules=("${rules[@]:0:$((position - 1))}" "$rule" "${rules[@]:$((position - 1))}")
    save
    ;;
  -A)
    exists "$chain" || nochain
    load
    rules+=("$rule")
    save
    ;;
  -D)
    exists "$chain" || nochain
    load
    index_of "$rule"
    [ "$found" -ge 0 ] || norule
    unset 'rules[found]'
    rules=("${rules[@]}")
    save
    ;;
  -C)
    exists "$chain" || nochain
    # nf_tables answers exit 2 (not 1) when the rule jumps to a chain that does not exist: "Chain 'X' does not exist"
    target="" previous=""
    for word in $rule; do [ "$previous" = -j ] && target=$word; previous=$word; done
    case "$target" in '' | ACCEPT | DROP | REJECT | RETURN | LOG) ;; *) exists "$target" || { echo "iptables: Chain '$target' does not exist" >&2; exit 2; } ;; esac
    load
    index_of "$rule"
    [ "$found" -ge 0 ] || norule
    ;;
  -S)
    if [ -n "$chain" ]; then
      exists "$chain" || nochain
      case "$builtin" in *" $chain "*) ;; *) echo "-N $chain" ;; esac
      load
      for line in "${rules[@]}"; do echo "-A $chain $line"; done
    else
      for f in "$state"/chain.*; do
        [ -e "$f" ] || continue
        name=${f##*/chain.}
        case "$builtin" in *" $name "*) ;; *) echo "-N $name" ;; esac
        mapfile -t rules < "$f"
        for line in "${rules[@]}"; do echo "-A $name $line"; done
      done
    fi
    ;;
  --version) echo "iptables v1.8.10 (fake)" ;;
  *) echo "iptables: the fake does not know '$op'" >&2; exit 9 ;;
esac
exit 0
