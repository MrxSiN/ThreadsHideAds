# Migration

## Ecosystem rules v1 (2026-11-14): toolchain migration debt

`AGENTS.md` adopts MrxSiN's shared ecosystem rules: new production logic is
XAX or Brainfuck + XAX where the toolchains reach the target, BBC owns the
Brainfuck frontend and XAX the compiler and backends. This repository does not
meet that yet. What is debt and why it stays for now:

| Debt | Where | Status |
| --- | --- | --- |
| Brainfuck to C compiler (optimizer, AOT, generator) | `tools/bftool/*.py` | BLOCKED upstream, see below |
| Native runtime: capability frames, input refill, persistent cells | `app/src/main/cpp/*.c` | BLOCKED upstream, see below |
| Host mechanisms: libxposed, reflection, DexKit, JNI, views | `app/src/main/java` | open; XAX's `android-arm64` target builds libxposed modules (R3), not started here |
| Test harnesses | `app/src/test`, `app/src/sharedTest`, `tests/` | open |

Reproduced on the toolchains' latest `main` (2026-11-14, EXECUTED on Windows 11):

- BBC `856b2d6`: `bbc.exe check` rejects all six programs with
  `BBC: resource limit exceeded` (exit 2). They have 69,343 to 296,307
  commands; BBC's limit is 65,536 instructions before folding. BBC has no native
  shared-library target.
- XAX `affbb53`: the BBC shared-library host `bfc_call_v1`
  (`compiler/integration/bfx/SHARED.md`) is one-shot. It reads all input up
  front, returns output at the end and zeroes the tape on every call. The core
  needs mid-run host requests (capability frames), input refill and a
  persistent 64-cell prefix (`docs/BRAINFUCK_ABI.md`). XAX OI-42 (effectful
  foreign callbacks) is the nearest open item.

Upstream issues: [MrxSiN/BBC#4](https://github.com/MrxSiN/BBC/issues/4)
(instruction limit, native shared-library target) and
[MrxSiN/XAX#47](https://github.com/MrxSiN/XAX/issues/47) (host I/O and
persistent cells for `bfc_call_v1`). No toolchain is pinned because nothing here builds against
one yet; the pin file arrives with the first build that does.

## 2.0.0: hand-written Brainfuck, libxposed API 102

| 1.3.0 | 2.0.0 |
| --- | --- |
| `brainfuck/src/{core,text,detect,sanitize,nested,pando}.bfa` + `lib.bfa`, `frame.bfa`, `limits.bfa`, `vocabulary.bfa` | `brainfuck/src/{core,text,detect,sanitize,nested,pando}.bf`, hand-written; limits and words live in the program that uses them |
| `brainfuck/src/constants.bfa` | `brainfuck/constants.txt` (same `const` lines, parsed by `gen.py`) |
| `brainfuck/generated/*.bf` (assembler output) | removed; the sources are the eight-command programs |
| `tools/bftool/asm.py`, `regex_dfa.py`, `test_asm.py`, `test_regex_dfa.py` | removed; `tools/bftool/lint.py` checks the sources |
| `MainHook` (legacy `IXposedHookLoadPackage`), `assets/xposed_init`, manifest metadata | `ModuleMain` (`XposedModule`), `core/ModuleRuntime`, `META-INF/xposed/*` |
| `hook/XposedCallFrame` | `hook/ChainCallFrame` |
| bitmap launcher icons | adaptive vector icon with a monochrome layer |

The wire ABI (opcodes, frames, capability layouts, limits, reason codes) is
unchanged, so the bridge and the host code did not change for the rewrite;
the generated C is simply compiled from the new sources.

Intentional changes in 2.0.0: minimum Android 8.0 (API 26) and an Xposed
framework with API 102 are required; versionCode 8. There is no intended
filtering change.

## 1.3.0: v1.2.1 Java policy → Brainfuck core

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
| `Diagnostics`, `ModelProbes` | — (investigation tooling, off in release; removed in 2.0.0, see git history) | — |

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
