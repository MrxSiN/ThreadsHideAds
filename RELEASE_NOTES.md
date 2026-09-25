# Threads Hide Ads v1.3.0

## Brainfuck policy core

This release rewrites the module's decision-making without changing what it removes. Every policy decision — scope, initialization retries, hook install order, discovery candidate rules, advertisement classification, list and nested-collection sanitization, recursion limits, Pando filtering, the rendered-label fallback and statistics rate limits — now lives in Brainfuck-family source that is compiled ahead of time into native code. Java only performs the operations the core asks for.

### Changed

- Adds `libthreadsbf.so` (arm64-v8a, armeabi-v7a, x86, x86_64), generated from `brainfuck/src/*.bfa`. No interpreter ships in the APK.
- Removes the Java detection and sanitization classes; their 1.2.1 behavior is the oracle for the new parity tests.
- Hooks that re-enter the module from app code run on a separate engine, at most two levels deep, and otherwise leave content untouched. This replaces a global lock that could deadlock initialization.

### Verified

- Parity with 1.2.1 on the JVM (text matchers, detector, list and nested sanitizers, boundary flow, core decisions) and on an arm64 device (detector and sanitizer).
- On a Pixel 8 Pro with Threads 448.0.0.54.85 under LSPosed: initialization completed on every cold start, 10–12 advertisements removed per session as with 1.2.1, no `Ad` or `Sponsored` label in any `uiautomator` dump, no crash-buffer entries.
- Time spent in hooks while scrolling is about 2.8 % of one core (1.2.1: about 2.3 %).

LSPatch modes, 32-bit and x86 devices, and older Android versions were not re-run for this release.

### Version metadata

- Version name: `1.3.0`
- Android version code: `7`
- Recommended Git tag: `v1.3.0`
- APK filename: `ThreadsHideAds-v1.3.0.apk`

### Updating

Install the v1.3.0 APK over v1.2.1, keep Threads as the module’s only LSPosed scope, then force-stop and reopen Threads once. Confirm the LSPosed log reports `Application.attach observed` followed by `Hook initialization complete`.
