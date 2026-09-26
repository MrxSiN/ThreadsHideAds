# Threads Hide Ads v2.0.0

## Modern Xposed API, hot reload and a hand-written Brainfuck core

This release changes how the module is loaded and how its policy is written, without changing what it removes.

### Changed

- Uses the modern Xposed API (libxposed 102). The module is described by `META-INF/xposed/` (`java_init.list`, `module.prop`, `scope.list`) instead of `xposed_init` and manifest metadata. Requires Android 8.0 or newer and a framework that implements API 102 (Vector, current LSPosed).
- Supports hot reload: an update is applied to a running Threads process. The old module generation stops its native core and hands its hidden feed cards to the new one.
- New adaptive vector launcher icon with a themed (monochrome) layer.
- All six policy programs in `libthreadsbf.so` are now hand-written Brainfuck (`brainfuck/src/*.bf`). The previous macro assembler is removed. Each program was tested side by side with the one it replaces: identical responses and identical host traffic over thousands of randomized requests.
- The native library is smaller (arm64: 647 KB, was 729 KB); host benchmarks are unchanged within noise.

### Verified

- Parity with 1.2.1 on the JVM (text matchers, detector, list and nested sanitizers, boundary flow, core decisions).
- libxposed 102 loading and hot reload on a Pixel 8 Pro, Android 17, Vector 2.2.
- The hand-written core on the same device with Threads 448.0.0.54.85: initialization complete on the first attempt (38 model hooks, 28 Pando hooks), 12 advertisements removed over a cold launch and 35 scroll passes, no `Ad` or `Sponsored` label in `uiautomator` dumps, empty crash buffer.

LSPatch modes, 32-bit and x86 devices were not re-run for this release.

### Version metadata

- Version name: `2.0.0`
- Android version code: `8`
- Recommended Git tag: `v2.0.0`
- APK filename: `ThreadsHideAds-v2.0.0.apk`

### Updating

Install the v2.0.0 APK over v1.3.0, keep Threads as the module's only scope, then force-stop and reopen Threads once. Confirm the framework log reports `Application.attach observed` followed by `Hook initialization complete`. Later updates can be hot-reloaded.
