# Architecture before the Brainfuck rewrite (v1.2.1, commit 7ac6a78)

This document records the Java implementation that the rewrite replaces, so the
behavior vectors and parity tests can refer to a fixed baseline. The frozen
policy classes are kept, unchanged apart from logging stubs, under
`app/src/test/java/my/github/MrxSiN/threadshideads/legacy/`.

## Build and release baseline

| Item | Value |
| --- | --- |
| applicationId | `io.github.mrxsin.threadshideads` |
| namespace / Xposed entry | `my.github.MrxSiN.threadshideads.MainHook` (`assets/xposed_init`) |
| version | `1.2.1`, versionCode `6` |
| minSdk / target / compile | 23 / 36 / 36 |
| dependencies | `org.luckypray:dexkit:2.2.0`, `de.robv.android.xposed:api:82` (compileOnly) |
| Xposed metadata | `xposedmodule`, `xposeddescription`, `xposedminversion=93`, `xposedscope=@array/xposed_scope` |
| scope | `com.instagram.barcelona` |
| release | unsigned locally unless `ANDROID_KEYSTORE_*` env vars are set; CI signs with secrets |
| checks | `scripts/check-project.sh` (grep-based static checks), no unit tests |
| baseline build | `./gradlew clean assembleDebug assembleRelease` succeeded, 1 min 28 s |
| baseline APK sizes | debug 3,161,638 bytes, release (unsigned) 2,814,944 bytes |

## Runtime flow

1. `MainHook.handleLoadPackage`: returns unless package is Threads and the
   process is the main process (a null process name counts as main). Installs
   `RenderedLabelHooks` (idempotent), then, once per process, hooks
   `Application.attach(Context)`. If that hook cannot be installed it calls
   `ModelHookInstaller.request(null, lpparam.classLoader, "load-package fallback")`.
2. `Application.attach` after-hook: logs, resolves the class loader
   (`context.getClassLoader()`, falling back to the load-package loader) and calls
   `ModelHookInstaller.request(...)`.
3. `ModelHookInstaller.request`: ignored when initialized; a null class loader
   schedules a retry without counting an attempt; concurrent attempts are
   dropped; otherwise attempt N runs `installHooks`. Success =
   feed hooks or Pando hooks installed. Failure (false or exception) schedules
   retry index 0,1,2 with delays 250, 1000, 3000 ms on the main looper;
   after the third retry it logs exhaustion.
4. `installHooks`: Pando hooks first (no DexKit), `System.loadLibrary("dexkit")`
   once, `DexKitBridge.create(classLoader, true)` falling back to
   `DexKitBridge.create(sourceDir)` when the source dir is non-empty; feed-cache
   hooks by class name; primary boundaries; broad discovery only when no primary
   boundary installed and no feed hook exists yet.

## Policy inventory (what the rewrite moves)

| Legacy location | Decision |
| --- | --- |
| `MainHook` | package/process scope, attach-guard fallback |
| `ModelHookInstaller` | init state machine, retry table, install order, bridge fallback |
| `FeedBoundaryFinder` | primary marker `Sponsored`, primary shape, 1..8 match rule, 13 DEX markers, list-boundary and boolean-predicate classification, non-feed tokens |
| `FeedCacheLocator` | 15 feed owner classes, list-carrying rule, injector shape and uniqueness |
| `FeedModelHooks` | before/after sanitization flow per argument and result, injector returns false |
| `ListSanitizer` | keep/drop per item, nested lists skipped, emptying rule, in-place then replacement |
| `NestedCollectionSanitizer` | depth 4, budget 192, 48 entries, slot write-back, replacement failures |
| `ReflectiveAdDetector` | depth 8, budget 1200, 32 entries, visit order, short-circuit |
| `AdFieldSignals` | unit types, structured values, field names, field values, class tokens |
| `Reflection` | field class depth 6, app-model prefixes, 262144-char text limit, structured toString |
| `PandoTrees`, `PandoAdSignals` | TreeJNI subclass test, accessor names, flag probing, 524288 JSON limit, 65536 item limit |
| `PandoResponseHooks` | accessor shape, 256 list limit, first-element gate, emptying rule, replacement order |
| `AdLabelVocabulary` | 31 labels, normalization, separators |
| `AdLabelScanner`, `FeedItemHider`, `ViewTrees`, `RenderedLabelHooks`, `HiddenViewRegistry` | scan limits 320/160/64, card heuristics 0.70/0.80/80dp, ancestor depth 14, list-container depth 8, four layout retries, restore rule |
| `ModuleStats` | log rate limits (12/25, 8/25, 3/10) |
| `Diagnostics`, `ModelProbes` | investigation tracing, disabled (`ENABLED = false`) |

## Mechanisms (stay in the host)

Xposed hook registration, `Application.attach`, class loading, DexKit queries,
reflection (field reads/writes, method lookup and invoke), Java collection
mutation, `Handler` scheduling, View listeners/layout params, logging transport.
