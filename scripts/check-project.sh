#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
HOOK="$ROOT/app/src/main/java/my/github/MrxSiN/threadshideads/MainHook.java"

test -f "$HOOK"
grep -q 'com.instagram.barcelona' "$HOOK"
grep -q 'DexKitBridge' "$HOOK"
grep -q 'returnConstant(false)' "$HOOK"
grep -q 'sanitizeNestedCollections' "$HOOK"
grep -q 'installPrimaryFeedBoundaryHooks' "$HOOK"
grep -q 'removeIndexesInPlace' "$HOOK"
grep -q 'isCurrentAdMarker' "$HOOK"
grep -q 'recordReplacementFailure' "$HOOK"
grep -q 'markerView' "$HOOK"
grep -q 'val appVersion = "1.1.1"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 2' "$ROOT/app/build.gradle.kts"

test "$(find "$ROOT/app/src/main/java" -name '*.java' | wc -l | tr -d ' ')" = "1"
echo "Static project checks passed."
