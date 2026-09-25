#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/app/src/main/java/my/github/MrxSiN/threadshideads"
BF="$ROOT/brainfuck/src"
CPP="$ROOT/app/src/main/cpp"

# Entry point: attach-gated startup, scope decided by the core.
grep -q 'policy.loadPackage' "$SRC/MainHook.java"
grep -q 'Application.attach observed' "$SRC/MainHook.java"
grep -q 'ModelHookInstaller.request' "$SRC/MainHook.java"
grep -q 'com.instagram.barcelona' "$ROOT/app/src/main/res/values/arrays.xml"
grep -q 'regex THREADS_PACKAGE = "com.instagram.barcelona"' "$BF/vocabulary.bfa"

# Startup: DexKit opening, retry decisions and install order from the core.
grep -q 'DexKitBridge.create' "$SRC/hook/ModelHookInstaller.java"
grep -q 'policy.initRequest' "$SRC/hook/ModelHookInstaller.java"
grep -q 'policy.installStep' "$SRC/hook/ModelHookInstaller.java"
grep -q 'case 0 { outc32 250 }' "$BF/core.bfa"
grep -q 'case 1 { outc32 1000 }' "$BF/core.bfa"
grep -q 'default { outc32 3000 }' "$BF/core.bfa"
grep -q 'PandoResponseHooks.install' "$SRC/hook/ModelHookInstaller.java"

# Model hooks: boundary flow, neutralized injector, disabled ad predicates.
grep -q 'policy.boundaryBefore' "$SRC/hook/FeedModelHooks.java"
grep -q 'policy.boundaryAfter' "$SRC/hook/FeedModelHooks.java"
grep -q 'returnConstant(false)' "$SRC/hook/FeedModelHooks.java"
grep -q 'policy.pandoFilter' "$SRC/hook/PandoResponseHooks.java"

# Policy lives in Brainfuck: unit types, emptying rule, bounded walks.
grep -q '"intent_aware_ad_pivot"' "$BF/vocabulary.bfa"
grep -q 'ifz allow_emptying { set blocked 1 }' "$BF/sanitize.bfa"
grep -q 'const DETECT_MAX_BUDGET = 1200' "$BF/limits.bfa"
grep -q 'const NESTED_MAX_DEPTH = 4' "$BF/limits.bfa"
grep -q 'const PANDO_MAX_LIST = 256' "$BF/limits.bfa"

# The host holds no ad vocabulary: these words appear only in brainfuck/src.
if grep -rqiE '"(ad4ad|is_sponsored|sponsorisé|quickpromotion)"' "$SRC"; then
    echo "Ad vocabulary found in host code; it belongs in brainfuck/src." >&2
    exit 1
fi

# Release path is AOT native code, not an interpreter.
grep -q 'add_library(threadsbf SHARED' "$CPP/CMakeLists.txt"
grep -q 'System.loadLibrary("threadsbf")' "$SRC/bridge/NativeCore.java"
if grep -rq 'bftool.ref\|BfInterpreter' "$SRC"; then
    echo "An interpreter is referenced from production code." >&2
    exit 1
fi

# Rendered-label fallback: restore only by the label that hid a card.
grep -q 'wasHiddenBy' "$SRC/ui/FeedItemHider.java"

# Tracing stays off in a release build.
grep -q 'ENABLED = false' "$SRC/core/Diagnostics.java"

# Xposed metadata required by LSPosed and LSPatch.
grep -q 'my.github.MrxSiN.threadshideads.MainHook' "$ROOT/app/src/main/assets/xposed_init"
grep -q 'xposedmodule' "$ROOT/app/src/main/AndroidManifest.xml"
grep -q 'xposedscope' "$ROOT/app/src/main/AndroidManifest.xml"

# Release metadata and signing configuration.
grep -q 'val appVersion = "1.3.0"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 7' "$ROOT/app/build.gradle.kts"
grep -q 'applicationId = "io.github.mrxsin.threadshideads"' "$ROOT/app/build.gradle.kts"
grep -q 'envKeystorePath' "$ROOT/app/build.gradle.kts"
grep -q 'envKeyPassword' "$ROOT/app/build.gradle.kts"

# Release automation.
grep -q 'workflow_dispatch:' "$ROOT/.github/workflows/android.yml"
grep -q 'Build release APK' "$ROOT/.github/workflows/android.yml"
grep -q 'gen.py --check' "$ROOT/.github/workflows/android.yml"
grep -q 'actions/upload-artifact@v4' "$ROOT/.github/workflows/android.yml"
grep -q 'softprops/action-gh-release@v2' "$ROOT/.github/workflows/android.yml"
grep -qi 'apksigner.*verify' "$ROOT/.github/workflows/android.yml"

echo "Static project checks passed."
