#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/app/src/main/java/my/github/MrxSiN/threadshideads"
BF="$ROOT/brainfuck/src"
CPP="$ROOT/app/src/main/cpp"
XPOSED_META="$ROOT/app/src/main/resources/META-INF/xposed"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

# "! cmd" is exempt from set -e, so negative checks must fail explicitly.
refute() {
    if "$@"; then
        echo "check-project: unexpected match: $*" >&2
        exit 1
    fi
}

# Entry point: attach-gated startup, scope decided by the core.
grep -q 'extends XposedModule' "$SRC/ModuleMain.java"
grep -q 'policy.loadPackage' "$SRC/ModuleMain.java"
grep -q 'Application.attach observed' "$SRC/ModuleMain.java"
grep -q 'ModelHookInstaller.request' "$SRC/ModuleMain.java"

# Hot reload: the old generation retires its core, the new one takes over.
grep -q 'onHotReloading' "$SRC/ModuleMain.java"
grep -q 'onHotReloaded' "$SRC/ModuleMain.java"
grep -q 'Core.shutdown' "$SRC/ModuleMain.java"
grep -q 'nativeDestroy' "$SRC/bridge/NativeCore.java"
grep -q 'setId(id)' "$SRC/core/ModuleRuntime.java"
grep -q 'THREADS PACKAGE (12): exactly com dot instagram dot barcelona' "$BF/text.bf"

# Startup: DexKit opening, retry decisions and install order from the core.
grep -q 'DexKitBridge.create' "$SRC/hook/ModelHookInstaller.java"
grep -q 'policy.initRequest' "$SRC/hook/ModelHookInstaller.java"
grep -q 'policy.installStep' "$SRC/hook/ModelHookInstaller.java"
grep -q 'RETRY after 250 or 1000 or 3000 ms' "$BF/core.bf"
grep -q 'PandoResponseHooks.install' "$SRC/hook/ModelHookInstaller.java"

# Model hooks: boundary flow, neutralized injector, disabled ad predicates.
grep -q 'policy.boundaryBefore' "$SRC/hook/FeedModelHooks.java"
grep -q 'policy.boundaryAfter' "$SRC/hook/FeedModelHooks.java"
grep -q 'chain -> Boolean.FALSE' "$SRC/hook/FeedModelHooks.java"
grep -q 'policy.pandoFilter' "$SRC/hook/PandoResponseHooks.java"

# Policy lives in hand-written Brainfuck: one .bf per program, no assembler.
for prog in core text detect sanitize nested pando; do
    grep -q "\"src/$prog.bf\"" "$ROOT/brainfuck/programs.json"
    grep -q '^# Hand written Brainfuck' "$BF/$prog.bf"
done
refute sh -c 'ls "$1"/*.bfa >/dev/null 2>&1' sh "$BF"
refute test -f "$ROOT/tools/bftool/asm.py"
grep -q 'quote is_sponsored quote colon true' "$BF/text.bf"
grep -q 'allow replacement; allow emptying' "$BF/sanitize.bf"
grep -q 'Limits: depth 8; budget 1200 inspections per traversal' "$BF/detect.bf"
grep -q 'Limits: depth 4; budget 192 visits per traversal' "$BF/nested.bf"
grep -q 'at most 256 entries' "$BF/pando.bf"

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

# Modern Xposed API (libxposed 102) metadata; the legacy entry is gone.
grep -q '^my.github.MrxSiN.threadshideads.ModuleMain$' "$XPOSED_META/java_init.list"
grep -q '^com.instagram.barcelona$' "$XPOSED_META/scope.list"
grep -q '^minApiVersion=102$' "$XPOSED_META/module.prop"
grep -q '^targetApiVersion=102$' "$XPOSED_META/module.prop"
grep -q '^autoHotReload=true$' "$XPOSED_META/module.prop"
grep -q 'compileOnly("io.github.libxposed:api:102.0.0")' "$ROOT/app/build.gradle.kts"
grep -q 'merges += "META-INF/xposed/\*"' "$ROOT/app/build.gradle.kts"
refute test -f "$ROOT/app/src/main/assets/xposed_init"
refute grep -q 'xposedmodule\|xposedminversion\|xposedscope' "$MANIFEST"
refute grep -rq 'de.robv.android.xposed' "$ROOT/app/src/main/java"

# Adaptive vector icon with a themed (monochrome) layer.
grep -q '<monochrome' "$ROOT/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
refute test -d "$ROOT/app/src/main/res/mipmap-xxxhdpi"

# Release metadata and signing configuration.
grep -q 'val appVersion = "2.1.0"' "$ROOT/app/build.gradle.kts"
grep -q 'versionCode = 9' "$ROOT/app/build.gradle.kts"
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
