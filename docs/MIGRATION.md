# Migration: v1.2.1 Java policy → Brainfuck core (v1.3.0)

## Old → new

| v1.2.1 | Decision now in | Mechanism now in |
| --- | --- | --- |
| `MainHook` scope/process checks, once-only guards | `core.bfa` `OP_LOAD_PACKAGE`, `vocabulary.bfa` `THREADS_PACKAGE` | `MainHook` (hook registration) |
| `ModelHookInstaller` state, retry table, install order, bridge fallback | `core.bfa` `OP_INIT_REQUEST`, `OP_INIT_FINISH`, `OP_INSTALL_STEP` | `ModelHookInstaller` (Handler, DexKit, `System.loadLibrary`) |
| `FeedBoundaryFinder` markers, primary shape, 1..8 rule, broad classification, non-feed tokens | `core.bfa` (`CONST_STRING`, `PRIMARY_*`, `BROAD_CANDIDATE`), `vocabulary.bfa` | `FeedBoundaryFinder` (DexKit queries) |
| `FeedCacheLocator` owner classes, list-carrying rule, injector shape and uniqueness | `core.bfa` (`CONST_STRING`, `FEED_OWNER_METHOD`, `INJECTOR_*`) | `FeedCacheLocator` (class loading) |
| `FeedModelHooks` before/after flow | `sanitize.bfa` `OP_BOUNDARY_*` | `FeedModelHooks` + `XposedCallFrame` |
| `ListSanitizer` | `sanitize.bfa` `OP_SANITIZE_LIST` | `Host` marks, in-place removal, ArrayList copy |
| `NestedCollectionSanitizer`, `ValueReplacer` | `nested.bfa` | `Host` slot write-back |
| `ReflectiveAdDetector`, `Reflection` (walk rules, prefixes, limits) | `detect.bfa`, `limits.bfa`, `vocabulary.bfa` | `Host` reads, `Reflector` field lists |
| `AdFieldSignals` | `vocabulary.bfa` regexes + `detect.bfa` | — |
| `AdLabelVocabulary` | `vocabulary.bfa` `AD_LABEL` | `TextEncoder` (case mapping, encoding) |
| `PandoTrees`, `PandoAdSignals` | `pando.bfa` verdict, `detect.bfa` class facts | `Host` JSON accessor invoke, verdict cache |
| `PandoResponseHooks` selection and filter | `core.bfa` `PANDO_ACCESSOR`, `pando.bfa` `OP_PANDO_FILTER` | `PandoResponseHooks`, `Host` copyOf |
| `AdLabelScanner`, `FeedItemHider`, `ViewTrees`, `RenderedLabelHooks` decisions | `core.bfa` `OP_UI_*` | `ui/*` (listeners, layout params, visibility) |
| `ModuleStats` rate limits | `core.bfa` `OP_STATS` | `ModuleStats` (log wording) |
| `Diagnostics`, `ModelProbes` | — (investigation tooling, off in release) | `core/*` |

Removed from production: `detect/*` (7 classes), `model/*` (4 classes),
`ui/AdLabelScanner`. Frozen copies live in `app/src/sharedTest/.../legacy/`
as the parity oracle; `LegacyRules` and `LegacyBoundary` transcribe the
decisions that were embedded in Xposed/View code.

## Phases (as executed)

1. Baseline: build, APK sizes, `docs/current-architecture.md`, legacy code
   frozen into test sources.
2. Toolchain: regex→DFA, reference interpreter, IR optimizer, C emitter,
   assembler, generator with `--check`, MSVC/gcc host builds.
3. Text matchers first, with 8 675 vectors against the legacy functions.
4. Runtime + JNI + bridge; then detect, sanitize, nested, Pando and core.
5. Parity suites (JVM, legacy vs production stack) and ABI robustness.
6. Android layer rewritten as mechanisms; legacy policy deleted.
7. Device validation and in-app A/B performance work.

## Intentional behavior changes

| Change | Why | Covered by |
| --- | --- | --- |
| Hooks fired from app code inside a request run on their own engine, at most two nested levels per thread; deeper ones fail open | a global lock deadlocked on device (lock-order with class initialization); unbounded recursion is not acceptable | `AbiRobustnessTest.nested*`, `concurrent*` |
| A statistics event waits at most 50 ms for the core; otherwise its log line and count are skipped | statistics must never block a hook thread | `Policy.stats` |
| Per-request handle table capped at 16 384 | bounded memory per engine; a larger traversal fails open | `HandleTable` |
| `libthreadsbf` is loaded in every Threads process before the scope check | the scope decision is policy and lives in the core | — |
| Card-size test uses `10·w ≥ 7·screen` and `10·h ≤ 8·screen` with density as 16.16 fixed point | Brainfuck has no floats; equal to the float test for every screen size below 5000 px (checked exhaustively) | `CoreParityTest.cardSearchMatchesLegacy` |
| Diagnostics `traceAdMatch`, `traceTreeList`, `traceOversizedTree`, `traceTreeKeys` removed | their call sites moved into Brainfuck; tracing is off in release | — |
| Version 1.3.0, versionCode 7 | upgrade over 1.2.1 | `scripts/check-project.sh` |

Package identity (`io.github.mrxsin.threadshideads`), the Xposed entry class,
scope and metadata are unchanged, so 1.3.0 installs over 1.2.1.

## Upgrade notes

Install over 1.2.1, force-stop Threads once. Updating in place keeps the
LSPosed enablement; uninstalling and reinstalling does not.
