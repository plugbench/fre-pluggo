#!/usr/bin/env bash
#
# Runs a daemon against a NATS server of its own, and visits and lists
# through it.  Needs fre-pluggo, fre, and nats-server in the PATH.
set -euo pipefail

work=$(mktemp -d)
pids=()
cleanup() {
  kill "${pids[@]}" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

port=$(( 20000 + RANDOM % 20000 ))
export NATS_URL="nats://127.0.0.1:$port" NATS_TOKEN=s3cret
nats-server -a 127.0.0.1 -p "$port" --auth "$NATS_TOKEN" >"$work/nats.log" 2>&1 &
pids+=($!)

fails=0
check() {
  local name=$1 expected=$2 actual=$3
  if [ "$expected" = "$actual" ]; then
    printf 'ok - %s\n' "$name"
  else
    printf 'not ok - %s\n  expected: %q\n  actual:   %q\n' "$name" "$expected" "$actual"
    fails=$(( fails + 1 ))
  fi
}

for _ in $(seq 50); do
  fre-pluggo list >/dev/null 2>&1 && break
  if grep -q 'Server is ready' "$work/nats.log"; then break; fi
  sleep 0.1
done

out=$(fre-pluggo add /x 2>&1 || true)
check "visits fail when nothing keeps them" "fre-pluggo: nothing is answering cmd.add.frecent" "$out"

out=$(NATS_TOKEN=wrong fre-pluggo list 2>&1 || true)
check "the wrong token is refused" "fre-pluggo: connecting to NATS: NATS: Authorization Violation" "$out"

fre-pluggo daemon --store "$work/store.json" 2>"$work/daemon.log" &
pids+=($!)
for _ in $(seq 50); do
  fre-pluggo list >/dev/null 2>&1 && break
  sleep 0.1
done

host=$(uname -n)
mkdir -p "$work/repo/sub"
touch "$work/repo/a.txt" "$work/repo/sub/b.txt"
fre-pluggo add "$work/repo/a.txt"
fre-pluggo add -w "$work/repo" sub/b.txt sub/b.txt
fre-pluggo add "$work/repo" https://example.com/

out=$(fre-pluggo list "$work/repo" | sort)
check "listing under a directory has it and what's in it" \
  "$(printf 'file://%s%s/\nfile://%s%s\nfile://%s%s' "$host" "$work/repo" "$host" "$work/repo/a.txt" "$host" "$work/repo/sub/b.txt")" \
  "$out"

out=$(fre-pluggo list -p "$work/repo/sub" )
check "listings can be of local paths" "$work/repo/sub/b.txt" "$out"

out=$(fre-pluggo list | head -1)
check "what's visited most is listed first" "file://$host$work/repo/sub/b.txt" "$out"

out=$(fre-pluggo list https://)
check "anything can be visited" "https://example.com/" "$out"

out=$(fre-pluggo add '' 2>&1 || true)
check "blank visits are refused" 'fre-pluggo: not something to visit: ""' "$out"

exit $(( fails > 0 ))
