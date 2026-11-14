# Architecture

Threads Hide Ads is split into **policy**, written by hand in plain
eight-command Brainfuck and compiled ahead of time to native code, and
**mechanism**, the Android/Xposed/DexKit/JNI code that performs what the
policy asks for.

```text
Threads process (Vector/LSPosed or LSPatch, libxposed API 102)
  ModuleMain ────────────► Policy (bridge)  ──JNI──►  libthreadsbf.so
  ModelHookInstaller          typed requests           runtime (bf_engine.c)
  FeedBoundaryFinder          frames in/out            AOT programs (generated C)
  FeedCacheLocator               ▲                        │
  FeedModelHooks                 │ capability frames      │
  PandoResponseHooks          Host (bridge) ◄────────────┘
  RenderedLabelHooks          handles, reflection, text streams,
  FeedItemHider               list mutation, view reads
```

The rule used everywhere: **if an operation needs Android, Java, Xposed,
DexKit or JNI, the host performs it; if it decides what to do with normalized
information, Brainfuck decides.**

## Layers

| Layer | Location | Owns |
| --- | --- | --- |
| Policy source | `brainfuck/src/*.bf` | every decision, word list, limit, state machine and reason code, as hand-written Brainfuck |
| ABI constants | `brainfuck/constants.txt` | opcodes, statuses, capability numbers (exported to `BfAbi.java` and `bf_abi.h`) |
| Memory map | `brainfuck/generated/MEMORY_MAP.md` | cell tables taken from the sources' `%cell` declarations (generated) |
| AOT C | `app/src/main/cpp/generated/bf_programs.generated.c` | optimized C compiled from the BF (generated) |
| Runtime | `app/src/main/cpp/bf_engine.c`, `bf_runtime.h` | request routing, `CAP_CALL` sub-programs, input refill, tapes |
| JNI glue | `app/src/main/cpp/bf_jni.c` | `NativeCore` natives and host callbacks |
| Bridge | `app/src/main/java/.../bridge/` | `Policy` (typed requests), `NativeCore`, `Host` (capabilities), `HandleTable`, `Reflector`, `TextEncoder`, `SharedFacts` |
| Mechanisms | `ModuleMain`, `hook/`, `discovery/`, `ui/`, `core/` | hook registration, hot reload, DexKit, Handler scheduling, View changes, logging |
| Toolchain | `tools/bftool/` | source checker (`lint.py`), optimizer and C emitter (`ir.py`), reference interpreter, generator; migration debt toward BBC + XAX (`docs/MIGRATION.md`) |

## Programs

| Id | Program | Role | Persistent state |
| --- | --- | --- | --- |
| 0 | `core` | lifecycle, init/retry state machine, install order, discovery classification, names, limits, statistics, rendered-label decisions | 64 cells (flags, attempt counter, discovery counters, stats totals) |
| 1 | `text` | 14 text matchers as hand-built byte machines (tries, substring automata; unit types, structured values, field names, labels, …) | none |
| 2 | `detect` | `ReflectiveAdDetector` walk, class facts | none |
| 3 | `sanitize` | `ListSanitizer`, feed-boundary before/after flow | none |
| 4 | `nested` | `NestedCollectionSanitizer` | none |
| 5 | `pando` | Pando tree verdicts, JSON flag probing, response-list filter | none |

Programs call each other with `CAP_CALL`, which the runtime serves natively on
a fresh tape one level deeper, so recursion depth is bounded by the policy
limits and the runtime's 24 levels.

## Request flow (feed boundary)

```text
FeedModelHooks.beforeHookedMethod
  └ Policy.boundaryBefore(ChainCallFrame)           one JNI call down
      └ sanitize: OP_BOUNDARY_BEFORE
          ├ CAP_ARG i            (host: argument, declared-type facts)
          ├ CAP_CALL sanitize OP_SANITIZE_LIST
          │   ├ CAP_NODE / CAP_CHILD           (host: reflection reads)
          │   ├ CAP_CALL detect OP_DETECT ...  (recursive, bounded)
          │   │   └ CAP_CALL text OP_TEXT_MATCH + fetch (host streams text)
          │   ├ CAP_MARK / CAP_REMOVE_MARKED / CAP_FILTERED_COPY
          ├ CAP_SET_ARG          (host: param.args[i] = replacement)
          └ CAP_REPORT           (host: log context)
```

DexKit and broad reflection run only during initialization. Steady-state
filtering uses cached per-class facts (`CAP_MEMO_*`), cached field lists and
per-tree verdicts.

## Threading

`Policy` is thread-safe:

- stateless programs (text, detect, sanitize, nested, pando) run on engines
  borrowed from a pool, with no shared lock, because their capabilities run
  app code (`toString`, field reads, JSON serialization) that may take app
  locks; a single shared lock caused a lock-order deadlock on device;
- a hook fired from inside such app code borrows another engine, up to two
  nested levels per thread, and fails open beyond that;
- the `core` program keeps process state, so its requests are serialized on
  one engine; statistics wait at most 50 ms for it and otherwise skip a log
  line.

Every engine has its own handle table and level buffers; class/field memos,
Pando verdicts and reflection caches are shared (`SharedFacts`).

## Fail-open rules

A request that cannot complete (library missing, nesting too deep, malformed
response, host exception, exhausted handle table) returns the default: keep
the list, report "not an ad", install nothing new. Unknown content is organic.
Ambiguous discovery (0 or more than 8 primary matches, several injector
or permalink-ads candidates) never installs a broad destructive hook.

## Permalink ads

Ads between the replies of a post come from a separate fetch in
`PermalinkAdsUseCase`, not from the feed, so the feed boundary and Pando
layers never see them. The core names the class (name table entry 7) and
picks its fetch step by shape (`OP_PERMALINK_ADS_METHOD`, counted in
persistent cell `pa`; `OP_PERMALINK_ADS_DECISION` installs only for exactly
one match). The host hook returns `Unit` without running it, which is what
the step returns after a failed fetch. Details: `HOOK_NOTES.md`.

## Build pipeline

```text
brainfuck/src/*.bf ──lint (comments, @cell checkpoints, ~N runs, tape bounds)
        │
        parse → IR → optimize (runs, clear/transfer/multiply loops, run-once
        loops, value and copy propagation, equality tests → C switch)
        ▼
app/src/main/cpp/generated/bf_programs.generated.c
        ▼
NDK clang -O2 (+ LTO in release) ──► libthreadsbf.so
```

There is no assembler: the `.bf` files are the source and are edited by
hand. The checker never rewrites them. `python tools/bftool/gen.py --check`
fails when any generated file is stale; CI runs it. The reference
interpreter (`tools/bftool/ref.py`) is test-only.

## Security boundary

No root/detector evasion, concealment, anti-analysis or attestation work.
Xposed metadata is the libxposed API 102 set in `META-INF/xposed/`
(`java_init.list`, `module.prop`, `scope.list`), kept unobfuscated.
Native symbols use hidden visibility except the JNI entry points; release
builds use LTO and the stripping AGP applies.
