#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
HOOK="$ROOT/app/src/main/java/io/github/nexalloy/threadshideads/MainHook.java"

test -f "$HOOK"
grep -q 'com.instagram.barcelona' "$HOOK"
grep -q 'SponsoredContentController' "$HOOK"
grep -q 'protoShorty("ZLL")' "$HOOK"
grep -q 'returnConstant(Boolean.FALSE)' "$HOOK"

test "$(find "$ROOT/app/src/main/java" -name '*.java' | wc -l | tr -d ' ')" = "1"
echo "Static project checks passed."
