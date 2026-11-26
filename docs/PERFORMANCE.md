# Performance

All numbers from a Pixel 8 Pro (arm64, Android SDK 37), Threads
448.0.0.54.85, unless marked "host" (Windows, MSVC build of the same C).

## Threads 457.0.0.57.74 (2026-11-27)

Release build with `-PhotPathStats`, signed with the debug key (the signing
variables unset), Pixel 8 Pro. Each run is a cold launch plus 20 scroll passes.
Before means commit `141ec3b`, after means this change. MEASURED, 3 runs each:

| | Before | After |
| --- | --- | --- |
| `Application.attach` to initialization complete | 1 898–1 952 ms | 353–588 ms |
| Item hook discovery on the attach thread | 947–993 ms | 10–18 ms |
| Feed boundary (before), per call | 7.5–26.6 ms | 1.7–3.0 ms |
| Feed boundary time per 10 s of scrolling | 0.84–2.37 s | 0.15–0.35 s |
| Pando accessor, per call | 37–199 µs | 17–80 µs |

Where the time went (profiled per hooked method, 30 s of scrolling):

- The merge `BarcelonaFeedCache.A0O` and the methods it calls (`A01`, `A0F`,
  `A0D`, `A0E`) each walked the same new feed item to the detector's
  1200-node budget, about 12 ms per walk. 457's items carry a `Media` graph
  large enough to use the whole budget. With the organic item memo, the nested
  calls cost about 0.05 ms and only `A0O` walks a new item, about 13 ms.
  Lowering that further needs a policy change to the detector budget or
  order, which was not made.
- Threads' own ad check was found with a DexKit `addInvoke` query. Any invoke
  matcher makes DexKit build its whole-app cross-reference index (about
  0.6 s), even when the query is scoped to `Media`. It now runs on a
  background thread after the hooks are in place. The one-item filters use
  the detector alone for that first ~0.8 s.
- Closing DexKit takes about 0.26 s and now happens on that background thread.

## In-app A/B (release builds, same signing key, same script)

Cold launch, 25 scroll passes, per-hook timing logged every 10 s by
`hook/HotPathStats` (release built with `-PhotPathStats`; the legacy build got
identical probes). Steady-state windows after the first 10 s:

| Hook | v1.2.1 legacy | v1.3.0 Brainfuck | Calls / 10 s |
| --- | --- | --- | --- |
| Feed boundary (before) | 1.6–2.5 ms/call | 1.9–2.8 ms/call | 60–90 |
| Pando list accessor | 7–12 µs/call | 22–33 µs/call | 2 500–9 500 |
| Time in hooks during scrolling (both hooks) | ≈ 210–260 ms per 10 s (≈ 2.3 % of one core) | ≈ 260–310 ms per 10 s (≈ 2.8 % of one core) | — |

Removals and visible results were identical (10–12 ads per run, no labels in
dumps). The feed boundary is within run-to-run noise of legacy. The Pando
path is about 2–3× legacy per call; it is dominated by JNI up-calls (one per
list element that is not already known organic, plus JSON streaming for new
trees).

## Micro-benchmarks (on device, release, `DeviceCoreTest.hotPathBenchmark`)

| Case | Legacy p50 / p95 | Brainfuck p50 / p95 | Programs / call | Host callbacks / call |
| --- | --- | --- | --- | --- |
| feed item (unit-type enum + payload) | 4.2 / 7.0 µs | 17.1 / 26.8 µs | 8.7 | 12.7 |
| random model graph | 0.2 / 1.5 µs | 2.1 / 4.3 µs | 1.3 | 1.2 |
| one-item batch sanitize | 4.4 / 7.5 µs | 18.0 / 27.8 µs | 9.7 | 16.0 |
| Pando string list | 0.3 / 0.4 µs | 3.1 / 4.7 µs | 1.0 | 2.0 |
| Pando tree list (5, cached) | 1.3 / 1.4 µs | 5.4 / 7.2 µs | 1.0 | 3.0 |
| label `Sponsored ·` | 1.0 / 1.3 µs | 1.9 / 2.4 µs | 1.0 | 0 |
| 3 KB organic JSON, structured-value check | 101 / 112 µs | 88 / 102 µs | 1.0 | 0 |

The AOT DFA is faster than the legacy lowercase-and-`contains` on ARM; the
remaining cost is the bridge (≈ 1 µs per JNI up-call on this device).

Host (`build/benchmark/host.txt`, JVM + MSVC build) shows the same shape; MSVC
does not turn the DFA switches into tables as well as clang, so text matching
is slower there than on device.

## 2.0.0: hand-written sources vs assembler output (host)

Same machine, same JVM harness (`HostBenchmarkTest`), the generated C of
commit `c88f7c4` (assembler) against the C of the hand-written sources,
alternating builds twice; p50 in µs:

| Case | assembler (run 1 / 2) | hand-written (run 1 / 2) |
| --- | --- | --- |
| feed item | 22.9 / 25.0 | 26.8 / 24.5 |
| random model graph | 5.0 / 5.3 | 5.3 / 5.1 |
| one-item batch sanitize | 25.7 / 28.1 | 25.8 / 30.0 |
| Pando string list | 5.7 / 5.7 | 4.5 / 5.7 |
| Pando tree list (5) | 17.9 / 18.4 | 17.3 / 18.1 |
| label matcher | 1.3 / 1.4 | 1.3 / 1.2 |
| 3 KB JSON structured matcher | 77.7 / 78.1 | 73.8 / 80.0 |

The differences are within run-to-run noise (these runs were taken with the
test JVM rebuilt each time, so all numbers sit above the quieter figures
earlier on this page). Cost is dominated by JNI crossings, which the rewrite
did not change. Program size after optimization:

| Program | BF commands, assembler → hand | IR ops, assembler → hand |
| --- | --- | --- |
| core | 222 491 → 151 449 | 7 342 → 7 124 |
| text | 496 894 → 225 546 | 17 530 → 4 124 |
| detect | 183 975 → 231 216 | 5 144 → 5 338 |
| sanitize | 61 879 → 69 343 | 1 759 → 1 882 |
| nested | 219 337 → 296 307 | 6 159 → 5 911 |
| pando | 85 437 → 113 160 | 2 454 → 2 674 |

`libthreadsbf.so` (arm64) went from 729 KB to 647 KB, mostly from the
smaller text machines. The on-device A/B above was measured on 1.3.0 and has
not been repeated for 2.0.0.

## What was optimized, and the effect

| Change | Effect |
| --- | --- |
| Value/copy propagation and equality idiom in the optimizer | text program 112 k → 17.5 k IR ops |
| Byte classes + two-level state dispatch + delegation to root transitions | DFA code size ÷ 5 |
| Equality runs emitted as C `switch` | host feed item 20 → 12 µs |
| Child descriptor passed with `CAP_CALL`, batched releases, `MODE_FIELD_NEXT`, visit-and-reset | callbacks per feed item 25 → 14.5 |
| Cached Pando verdict and flag memo inside descriptors; `CAP_CHILDREN` read-only pass | Pando tree list 24 → 3 callbacks |
| Bulk buffer copies; identity sets replaced instead of cleared | removed O(capacity) work per request |
| Pooled engines instead of one global lock | no cross-thread waiting (and no deadlock) |

## Startup

| Step | Time on device |
| --- | --- |
| `libthreadsbf` load + core creation + limits/names | < 10 ms (load-package) |
| Attach → initialization complete (DexKit, 38 + 28 hooks) | ≈ 200 ms, as legacy |

DexKit and broad reflection run only during initialization; steady-state
filtering uses per-class memos, cached field lists and per-tree verdicts.

## Size

| Artifact | v1.2.1 | v1.3.0 |
| --- | --- | --- |
| Release APK (unsigned) | 2 814 944 B | 5 597 644 B (2.0.0: 4 862 549 B) |
| Debug APK | 3 161 638 B | 5 927 009 B |
| `libthreadsbf.so` arm64 / armv7 / x86 / x86_64 | — | 729 / 533 / 699 / 787 KB |
| Generated C | — | ≈ 2.0 MB source, 6 programs, 37 k IR ops |

`-Os` was measured and saved under 1 %, so the build uses `-O2` with LTO in
release. The size is dominated by the DFA state machines of the text program.

## Allocation and crossings in the hot path

- One JNI down-call per hook invocation; one up-call per capability.
- No JSON or string serialization of feed objects except the Pando JSON the
  legacy code also produced, and text of individual leaf values.
- Host allocations per request: handle-table entries (arrays reused), boxed
  mark indexes, the lowercase copy of streamed text, replacement lists.

## Reproducing

```bash
./gradlew :app:testDebugUnitTest --tests '*HostBenchmarkTest*'
./gradlew :app:assembleRelease :app:assembleReleaseAndroidTest -PbenchmarkRelease
adb install -r app/build/outputs/apk/release/ThreadsHideAds-v1.3.0.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w -e class my.github.MrxSiN.threadshideads.device.DeviceCoreTest io.github.mrxsin.threadshideads.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s ThreadsBfDevice
```

`-PbenchmarkRelease` and `-PhotPathStats` sign with the debug key when no
release key is configured; unset `ANDROID_KEYSTORE_*` to force that. Avoid
`connectedAndroidTest`: it uninstalls the app afterwards, which clears the
LSPosed enablement.
