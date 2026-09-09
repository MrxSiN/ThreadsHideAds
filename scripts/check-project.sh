#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/app/src/main/java/my/github/MrxSiN/threadshideads"

# Entry point: Threads only, attach-gated startup, delegated installation.
grep -q 'com.instagram.barcelona' "$SRC/MainHook.java"
grep -q 'Application.attach observed' "$SRC/MainHook.java"
grep -q 'ModelHookInstaller.request' "$SRC/MainHook.java"

# Startup: DexKit opening, bounded retries, lazy native library, Pando layer.
grep -q 'DexKitBridge' "$SRC/hook/ModelHookInstaller.java"
grep -q 'createBridge' "$SRC/hook/ModelHookInstaller.java"
grep -q 'RETRY_DELAYS_MS' "$SRC/hook/ModelHookInstaller.java"
grep -q 'NATIVE_LIBRARY_LOADED' "$SRC/hook/ModelHookInstaller.java"
grep -q 'PandoResponseHooks.install' "$SRC/hook/ModelHookInstaller.java"

# Model hooks: list sanitizing, neutralized injector, disabled ad predicates.
grep -q 'hookListBoundary' "$SRC/hook/FeedModelHooks.java"
grep -q 'hookAdInjectionPredicate' "$SRC/hook/FeedModelHooks.java"
grep -q 'returnConstant(false)' "$SRC/hook/FeedModelHooks.java"
grep -q 'nestedSanitizer.sanitize' "$SRC/hook/FeedModelHooks.java"

# Pando response layer: shape-based accessor selection, no native hooking.
grep -q 'com.facebook.pando.TreeJNI' "$SRC/hook/PandoResponseHooks.java"
grep -q 'Modifier.isNative' "$SRC/hook/PandoResponseHooks.java"

# Detection: unit types, structured signals, bounded reflective walk.
grep -q 'isAdUnitType' "$SRC/detect/AdFieldSignals.java"
grep -q 'intent_aware_ad_pivot' "$SRC/detect/AdFieldSignals.java"
grep -q 'isStructuredAdValue' "$SRC/detect/ReflectiveAdDetector.java"
grep -q 'MAX_BUDGET' "$SRC/detect/ReflectiveAdDetector.java"
grep -q 'isSponsoredTree' "$SRC/detect/PandoAdSignals.java"

# Sanitizing: in-place removal, replacement accounting, emptying rule.
grep -q 'removeIndexesInPlace' "$SRC/model/ListSanitizer.java"
grep -q 'allowEmptying' "$SRC/model/ListSanitizer.java"
grep -q 'recordReplacementFailure' "$SRC/model/NestedCollectionSanitizer.java"

# Rendered-label fallback: whole-label matching and restore safety.
grep -q 'markerView' "$SRC/ui/FeedItemHider.java"

# Tracing stays off in a release build.
grep -q 'ENABLED = false' "$SRC/core/Diagnostics.java"

# Release metadata and signing configuration.
grep -q 'val appVersion = "1.2.1"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 6' "$ROOT/app/build.gradle.kts"
grep -q 'envKeystorePath' "$ROOT/app/build.gradle.kts"
grep -q 'envKeyPassword' "$ROOT/app/build.gradle.kts"

# Release automation.
grep -q 'workflow_dispatch:' "$ROOT/.github/workflows/android.yml"
grep -q 'Build release APK' "$ROOT/.github/workflows/android.yml"
grep -q 'actions/upload-artifact@v4' "$ROOT/.github/workflows/android.yml"
grep -q 'softprops/action-gh-release@v2' "$ROOT/.github/workflows/android.yml"
grep -qi 'apksigner.*verify' "$ROOT/.github/workflows/android.yml"

echo "Static project checks passed."
