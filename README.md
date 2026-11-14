<div align="center">

<img src="docs/icon.svg" width="120" alt="Threads Hide Ads">

# Threads Hide Ads

**An Xposed module that removes sponsored posts from Threads, with its ad policy written in Brainfuck. Yes, really.**

Ads are taken out of the feed before Threads draws them, not hidden after.

<br>

[![Release](https://img.shields.io/github/v/release/MrxSiN/ThreadsHideAds?include_prereleases&color=5B3DF5&label=release&style=for-the-badge)](https://github.com/MrxSiN/ThreadsHideAds/releases)
[![Downloads](https://img.shields.io/github/downloads/MrxSiN/ThreadsHideAds/total?color=3DDC84&logo=android&logoColor=fff&style=for-the-badge)](https://github.com/MrxSiN/ThreadsHideAds/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=fff&style=for-the-badge)](#requirements)
[![libxposed](https://img.shields.io/badge/libxposed-API%20102-E8A33D?style=for-the-badge)](https://github.com/libxposed/api)
[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-EA4AAA?logo=githubsponsors&logoColor=fff&style=for-the-badge)](https://github.com/sponsors/MrxSiN)

</div>

---

> [!NOTE]
> Threads renames its obfuscated classes with almost every update. The module finds its targets
> again by shape at each start, and when it cannot be sure it installs nothing rather than guess.
> See the [compatibility](#compatibility) table for what has actually been tested.

## Why Brainfuck?

Surely nobody would write the decision logic of an ad blocker in Brainfuck.

Brainfuck was selected for its rich ecosystem, mature package manager, excellent Android SDK
bindings, comprehensive type system, first-class coroutine support, and famously pleasant
debugging experience.

Just joking.

The real idea is much less cursed than it sounds: **keep every decision about Threads out of the
Android code.** Which list is the feed, what counts as an advertisement, how deep to look into a
model object, when to retry, when to give up, which fallback to trust — that is the part that has
to change when Threads changes, and it is the part that tends to leak into hook callbacks,
reflection helpers and view listeners until nobody can say where the policy lives.

Brainfuck is very good at enforcing that boundary, because it literally cannot call Android,
Xposed, DexKit or JNI. It can read bytes and write bytes. Anything that touches a Java object has
to go through a small, documented bridge — "read this field", "remove the items I marked",
"stream me this class name" — and the Java side only ever does what it is asked. The two layers
cannot quietly melt together, because one of them has no way to reach the other.

It is not slow either. The six programs are written by hand in plain eight-command Brainfuck,
compiled ahead of time to C and then to native code with the NDK. There is no interpreter in the
APK.

|  | |
|---|---|
| 🧹 **Removed, not hidden** | Sponsored items are taken out of the feed lists before Threads renders them, so there are no blank gaps and no flashes of an ad. |
| 🔎 **Survives renames** | DexKit finds the feed boundary by shape at each start. An ambiguous match installs nothing broad. |
| 🧠 **Policy in Brainfuck, compiled** | Every decision is hand-written Brainfuck, compiled ahead of time to native code. The APK contains no interpreter. |
| 🛟 **Fails open** | Anything the module cannot classify is left alone. A failed request keeps the list as it was. |
| 🔁 **No restart to update** | Vector hot reloads a new build into a running Threads process. |
| 🧩 **Layered** | Model hooks, a Pando response filter and a rendered-label fallback each work on their own; losing one does not lose the others. |

---

## What it removes

An advertisement is recognized by its **feed unit type**. Threads tags every feed item with a
unit-type enum whose constants keep readable names even in an obfuscated build, and the
advertisement values are matched whole:

```text
AD, AD4AD, INTENT_AWARE_AD_PIVOT, STAND_ALONE_MULTI_AD_PIVOT,
ADS_FEEDBACK_INTERFACE and its two variants
```

Recommendation units, such as the `NETEGO` and `SUGGESTED` families, are deliberately left in
place.

<details open>
<summary><b>🧱 The four layers</b></summary>
<br>

| Layer | Where | What it does |
|---|---|---|
| **Feed boundary** | the method that hands a batch of feed items to the cache, found by DexKit | Removes ad items from the list argument before the call and again after it (late insertion), then cleans lists nested inside the returned wrapper, with bounded recursion. |
| **Pando responses** | list accessors on `com.facebook.pando.TreeJNI`, selected by shape | Filters sponsored trees out of GraphQL response lists. The names come from the native binding, not the obfuscator, so this layer needs no DexKit and stays installed when discovery fails. |
| **Post replies** | `PermalinkAdsUseCase`, the separate fetch that places ads between the replies of a post | Its fetch step, picked by shape, completes at once with no ads, so no request is sent. Threads enables these ads per account, which is why only some accounts showed them. |
| **Rendered label** | framework `TextView` updates | If a localized "Sponsored" label still reaches the screen, collapses its card. The card is restored only when that same label view is rebound to an organic item. |

Lists are sanitized in place when they are mutable. A replacement list is used only where the
caller, result, field, map entry, list slot or array slot can actually take it. A feed batch that
would become empty is left alone; the item that owns it is dropped one level up instead.

</details>

---

## Status

**v2.1.0.** Also hides the ads Threads places between the replies of a post, which some
accounts get and others do not. v2.0.0 moved to the modern Xposed API (libxposed 102) with hot
reload, and every policy program was rewritten by hand in plain Brainfuck. Each rewritten program was run side by side with
the one it replaces over thousands of randomized requests, with identical answers and identical
traffic to the host ([`docs/PARITY.md`](docs/PARITY.md)).

### Compatibility

Each row is one setup somebody has actually run. If you try another, please open a pull request
adding a row.

| Device | Android | Framework | Threads | Module | Result | Tester | Date |
|---|---|---|---|---|---|---|---|
| Pixel 8 Pro | 17 | Vector 2.2 (API 102) | 455.0.0.55.72 | 2.1.0 (debug build) | 34 model + 28 Pando hooks; ads gone from the feed and from post replies on both accounts | @MrxSiN | 2026-11 |
| Pixel 8 Pro | 17 | Vector 2.2 (API 102) | 448.0.0.54.85 | 2.0.0 | 38 model + 28 Pando hooks, 12 ads removed in 35 scrolls, no labels | @MrxSiN | 2026-09 |
| Pixel 8 Pro | 16 | LSPosed | 448.0.0.54.85 | 1.3.0 | 10–12 ads removed per session | @MrxSiN | 2026-09 |
| — | — | LSPatch (embedded and local) | — | 1.x | reported working in [issue #1](https://github.com/MrxSiN/ThreadsHideAds/issues/1) | @qradov | 2026-8 |

### Known limits

- **LSPatch** needs a build that loads libxposed API 102 modules. The reports above are for 1.x;
  2.x has not been tried under LSPatch.
- 32-bit and x86 devices ship the native library but have not been run.
- A Threads update can move the feed boundary. When it does, the Pando and label layers keep
  working and the log says what discovery found.

## Requirements

| | |
|---|---|
| **Android** | 8.0 (API 26) or newer |
| **App** | Threads (`com.instagram.barcelona`) |
| **Framework** | [Vector](https://github.com/JingMatrix/Vector) or LSPosed with libxposed API 102, or LSPatch (see [Known limits](#known-limits)) |
| **Root** | Required by Vector/LSPosed; the module itself asks for none |

Built against the modern [libxposed API](https://github.com/libxposed/api)
(`io.github.libxposed:api`), not the legacy `de.robv.android.xposed` bridge.

## Install

```
1. Install the APK from Releases
2. Enable Threads Hide Ads in Vector or LSPosed
3. Force-stop Threads once, then open it
```

The module declares a **static scope** — Threads only — so there is nothing to pick. Later updates
are hot reloaded into a running Threads process. Vector reloads a module only when its
`versionCode` changes; force-stop Threads if a reload is not offered.

The framework log shows what happened, in lines beginning with `ThreadsHideAds:`:

```text
Application.attach observed; starting hook resolution
Hook initialization complete; installedHooks=38, pandoHooks=28
Removed 1 ad item(s) from argument 4 of ... [modelTotal=1, event=1]
```

<details>
<summary><b>Without root: LSPatch</b></summary>
<br>

The module has no root-only calls, so LSPatch can embed it into Threads:

1. Install the LSPatch manager, or use the LSPatch command-line jar.
2. Patch Threads with **Threads Hide Ads** as an embedded module. Patch the base APK together with
   all of its split APKs.
3. Uninstall the store copy of Threads, then install the patched APKs as a set. The patched build
   is signed with LSPatch's key, so it cannot be installed over the store copy.

**Embedded** mode needs no manager afterwards; **local** mode keeps the module managed by LSPatch
and works alongside Shizuku. See [Known limits](#known-limits) for 2.x.

</details>

---

## How it works

```
Threads process
  ModuleMain ─────────────► Policy (bridge) ──JNI──► libthreadsbf.so
  ModelHookInstaller          typed requests          runtime + six AOT programs
  FeedBoundaryFinder             ▲                        │
  FeedModelHooks                 │  capability frames     │
  PandoResponseHooks          Host (bridge) ◄────────────┘
  RenderedLabelHooks          reflection, text streams, list mutation, view reads
```

One rule decides where code goes: **if it needs Android, Java, Xposed, DexKit or JNI, Java does
it; if it decides what to do with what Java read, Brainfuck decides.**

<details>
<summary><b>Starting at the right moment</b></summary>
<br>

- Loading into Threads installs only a small `Application.attach()` guard and the label fallback.
  Discovery waits until Threads has its real application context and final class loader.
- DexKit opens Threads' class loader, with the base APK path as a fallback, and looks for the
  feed boundary: the `Sponsored` marker and an `(Integer/int, Integer/int, List) -> non-void`
  signature. One to eight matches are installed; zero or more than eight install nothing broad.
- A failed or empty attempt is retried after 250 ms, 1 s and 3 s. The module counts itself
  initialized only once a hook is actually in place.
- The retry schedule, the one-to-eight rule and the install order are all decisions of the
  `core` program.

</details>

<details>
<summary><b>The Brainfuck core</b></summary>
<br>

| Program | Decides |
|---|---|
| `core` | scope, start-up and retry state machine, install order, which discovered methods qualify, names, limits, log rate limits, the rendered-label fallback |
| `text` | 14 text matchers — unit types, structured values, field names, labels in many languages — as hand-built byte machines |
| `detect` | whether one model object is an advertisement, walking its fields, maps, lists and arrays with bounded depth and budget |
| `sanitize` | which list items go, and whether a list is changed in place, replaced or left alone |
| `nested` | lists nested inside a returned feed wrapper |
| `pando` | sponsored Pando trees and the response-list filter |

Each program is a `.bf` file in `brainfuck/src/`. Comments may not contain a command character, so
prose can never turn into code. `%cell` comments name tape cells, `@cell` checkpoints assert where
the data pointer is, and `~N` asserts the length of a run; `tools/bftool/lint.py` checks all of
them and never rewrites a source. The optimizer turns the programs into C (clear and transfer
loops, value propagation, equality tests become `switch`), and the NDK builds `libthreadsbf.so`.

Programs call each other through the runtime on a fresh tape one level deeper, so recursion is
bounded by the policy limits and by the runtime's 24 levels. Java objects never enter Brainfuck
memory: they are opaque, validated 32-bit handles. The frame format, opcodes, handles, limits and
memory layout are in [`docs/BRAINFUCK_ABI.md`](docs/BRAINFUCK_ABI.md) and
[`docs/BRAINFUCK_MEMORY_MAP.md`](docs/BRAINFUCK_MEMORY_MAP.md).

</details>

<details>
<summary><b>Staying fast</b></summary>
<br>

DexKit and broad reflection run only during initialization. While scrolling, the module uses
per-class memos, cached field lists and per-tree verdicts, and a feed batch costs one call down
into native code plus one call back up per object it needs to read. On a Pixel 8 Pro the feed
boundary costs about 2–3 ms per call and the Pando accessors tens of microseconds; the native
matchers are faster than the Java string code they replaced. Numbers and method are in
[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md).

</details>

<details>
<summary><b>Updating without restarting Threads</b></summary>
<br>

Vector hot reloads the module (`autoHotReload=true` in `META-INF/xposed/module.prop`) when the
installed `versionCode` differs from the loaded one. The old generation stops its native core and
hands the cards it has collapsed to the new one, which hooks again at once.

</details>

---

## Build

```bash
python tools/bftool/gen.py                      # check brainfuck/src, regenerate C, ABI and memory map
python -m unittest discover -s tests/compiler   # optimizer, AOT and text parity tests
./gradlew :app:testDebugUnitTest                # JVM parity against the frozen 1.2.1 policy
./gradlew :app:assembleRelease
```

The generated files are committed; the Gradle build checks them with `checkBrainfuck` and never
edits them. `scripts/check-project.sh` runs the static project checks. Contribution rules, including
where new logic may live and the checks to run before a pull request, are in [`AGENTS.md`](AGENTS.md);
work that is not yet Brainfuck + XAX is tracked in [`docs/MIGRATION.md`](docs/MIGRATION.md).

You need JDK 17+, Android SDK 36 with NDK 28.2.13676358 and CMake 3.22.1, Python 3.10+, and for the
JVM tests a host C compiler (MSVC Build Tools on Windows, gcc or clang elsewhere).

CI checks the generated files, runs the Python and JVM tests and builds a release APK on every
push and pull request. Pushes and tags are signed when the four signing secrets are configured;
pull-request builds stay unsigned. A `v*` tag publishes a GitHub Release with the signed APK.

## Design

```
brainfuck/src/      the six hand-written programs: every decision, word list and limit
brainfuck/          programs.json, constants.txt (ABI numbers), generated memory map
tools/bftool/       source checker, optimizer, C emitter, reference interpreter (tests only)
app/src/main/cpp/   runtime, JNI glue and the generated C
bridge/             Policy (typed requests), NativeCore, Host capabilities, handles, reflection
ModuleMain          libxposed entry: routes Threads to the layers, hot reload
hook/               model and Pando hooks, the call-frame adapter
discovery/          DexKit queries and class lookups the core asks for
ui/                 rendered-label listeners, view traversal, hide and restore
core/               module runtime, logging, statistics wording
```

The layering, the ABI and the migration from the Java policy are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/MIGRATION.md`](docs/MIGRATION.md);
Threads-specific findings are in [`HOOK_NOTES.md`](HOOK_NOTES.md).

## Troubleshooting

| Problem | Try |
|---|---|
| Sponsored posts still appear | Check the log for `Hook initialization complete`. |
| An initialization attempt failed | Keep Threads open for a few seconds while the retries run; if all of them fail, export the `ThreadsHideAds:` log lines. |
| No module log lines | Check that the module is enabled, force-stop Threads once, and check the framework status. |
| Ambiguous method warning | The Threads build has several possible targets and nothing broad was installed. Report the log lines. |
| Broken after a Threads update | Threads may have changed its internal classes. Report the `ThreadsHideAds:` log lines with the Threads version. |

## Support

If the module is useful to you, you can [sponsor its development](https://github.com/sponsors/MrxSiN).

## Credits

| Project | Contribution |
|---|---|
| [DexKit](https://github.com/LuckyPray/DexKit) by LuckyPray | Runtime DEX parsing and discovery of obfuscated classes and methods (Apache-2.0). |
| [libxposed API](https://github.com/libxposed/api), [Vector](https://github.com/JingMatrix/Vector), [LSPosed](https://github.com/LSPosed/LSPosed) | The hooking API and frameworks the module runs on. |
| ReVanced-family Threads patches | The method-signature reference used to update the sponsored-content hook. |

The module is derived from the GPL-3.0 Threads ad-removal concept and has been updated for the
newer sponsored-content insertion method.

## Disclaimer

Not affiliated with, endorsed by or sponsored by Meta or Threads. Provided for educational and
personal use. Threads updates may break the hooks without notice.
