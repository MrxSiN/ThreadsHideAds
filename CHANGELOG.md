# Changelog

## Unreleased

## 1.3.0 — 2026-09-26

- Moved all filtering policy into a Brainfuck core (`brainfuck/src/*.bfa`). A
  Python toolchain (`tools/bftool`) generates canonical `.bf`, optimizes it and
  emits C that the NDK compiles into `libthreadsbf.so`; there is no interpreter
  in the APK. Java keeps the mechanisms (hooks, DexKit, reflection, collection
  mutation, logging) behind a documented ABI (`docs/BRAINFUCK_ABI.md`).
- Removed the Java `detect` and `model` packages and `ui/AdLabelScanner`; frozen
  copies serve as the parity oracle in the test sources.
- Added parity suites against the 1.2.1 policy on the JVM and on device, ABI
  robustness tests, toolchain tests and benchmarks (`docs/PARITY.md`,
  `docs/PERFORMANCE.md`).
- Hooks re-entered from app code run on their own engine, at most two levels
  deep per thread; statistics never wait more than 50 ms for the core.
- CI verifies generated files and runs the Python and JVM tests.

Verified on a Pixel 8 Pro with Threads 448.0.0.54.85: same removals as 1.2.1.

## 1.2.1 — 2026-09-06

- Split model inspection out of `Diagnostics` into a new `ModelProbes` class, so
  recording what the hooks did and inspecting an unknown model are separate
  concerns.
- Removed the marker-keyword walk and the JSON top-level-key dump. Both were
  written to find the sponsored signal and neither could: Pando models expose no
  fields to the walk, and the tree JSON is a shallow wrapper. The class-shape and
  enum-constant probes that did identify the unit-type enum are kept.
- Removed a per-row tree serialization that the Pando response hooks performed
  only for tracing.
- Refreshed the documented behavior of the static project checks.

No change to filtering behavior; verified on the same device and Threads build as
1.2.0.

## 1.2.0 — 2026-09-06

- Advertisements are now identified by their feed unit type. Threads tags every
  feed item with a unit-type enum whose constants keep readable names, and its
  advertisement values (`AD`, `AD4AD`, `INTENT_AWARE_AD_PIVOT`,
  `STAND_ALONE_MULTI_AD_PIVOT`, and the `ADS_FEEDBACK_INTERFACE` variants) are
  matched whole. Recommendation units such as the `NETEGO` and `SUGGESTED`
  families are deliberately left alone.
- Fixed every removal being blocked on Threads 445. Feed items reach the cache
  one at a time, so filtering a batch left it empty, and the rule that a
  non-empty list is never emptied then discarded the result. Feed boundaries
  may now empty a batch, while nested inner lists still may not, which is what
  that rule was written to protect.
- Added a Pando response layer that hooks the list accessors on
  `com.facebook.pando.TreeJNI` by shape. Their names come from the native
  binding rather than the obfuscator, so this layer needs neither DexKit nor
  its native library and survives the renaming that breaks boundary discovery.
- Pando tree JSON is now read with whichever flag pair yields the most content,
  chosen once per model class, instead of always using the default pair that
  answers `{}` for generated model classes.
- Fixed the primary feed boundary being rejected on current Threads builds. The
  boundary is an interface method, and Threads 445 exposes it through one
  interface plus two concrete implementations. Requiring exactly one structural
  match therefore discarded the real target and fell through to broad
  discovery. Every concrete match is now sanitized, up to a sanity limit of
  eight, and abstract or interface methods are skipped because they cannot be
  hooked.
- Fixed advertisements slipping through on a cold launch. The rendered-label
  layer now waits for the card to be attached and laid out before deciding
  which view to collapse, instead of hiding only the label badge while the
  first feed page was still unmeasured.
- Added the short `Ad` badge, plus further localized label variants, to the
  rendered-label vocabulary. Matching stays whole-label only, so captions that
  merely contain these words are still treated as organic content.
- Labels are now normalized with their decorative separators stripped, so
  forms such as `Sponsored ·` are recognized.
- Split the single `MainHook` class into `core`, `detect`, `model`,
  `discovery`, `hook`, and `ui` packages, each with one responsibility. The
  entry point now only observes `Application.attach()` and delegates startup to
  a model-hook installer, and the sanitizers depend on an `AdDetector`
  abstraction rather than on the reflective implementation.

## 1.1.2 — 2026-08-01

- Replaced the placeholder launcher artwork with a custom Threads-style ad-blocking shield icon.
- Added legacy, round, adaptive, Play Store, and Android 13 monochrome launcher assets.
- Moved DexKit and model-hook initialization from `handleLoadPackage()` to an `Application.attach()` guard so discovery runs with Threads’ final application context and class loader.
- Fixed startup sessions where a transient class-loader or DexKit failure could leave the module inactive until Threads was closed and reopened.
- Added bounded initialization retries after 250 ms, 1 second, and 3 seconds.
- Changed DexKit native-library loading to be lazy and retryable instead of running from a static initializer.
- Added a base-APK bridge fallback when DexKit cannot open the final application class loader.
- Kept the rendered-label fallback active while model-hook discovery is pending or retrying.
- Updated the Gradle wrapper to 9.6.1 and refreshed release documentation.

## 1.1.1 — 2026-07-16

- Added a primary DexKit search for the confirmed `Integer, Integer, List` feed-boundary shape using the `Sponsored` marker; broad discovery now runs only when this unique target is unavailable.
- Changed list filtering to mutate mutable lists in place before considering replacement.
- Prevented post-call sanitization from reporting removals when an immutable argument cannot actually be replaced after the call.
- Added replacement support for immutable lists stored in returned object fields, map values, list elements, and object-array elements.
- Revalidated rendered labels inside posted UI callbacks before hiding a card, preventing stale callbacks from collapsing recycled organic content.
- Added rate-limited model/UI counters so LSPosed logs remain useful during long scrolling sessions.
- Fixed an intermittent UI fallback defect where any later text update inside a hidden sponsored card could restore the whole ad.
- Restricted recycled-card restoration to the exact label view that originally triggered hiding.
- Added a final subtree check before restoring a recycled card.
- Added post-call sanitization for list arguments that Threads mutates inside a feed boundary.
- Added bounded sanitization of lists nested inside returned feed-wrapper objects.
- Expanded structured ad-metadata detection without treating ordinary captions containing the word “sponsored” as ads.
- Added an attached-view fallback to detect sponsored labels that were assigned before a view entered the feed hierarchy.
- Reduced broad DexKit hook selection, suppressed per-candidate discovery spam, and cached reflection and view-class lookups.
- Promoted the tested reliability and performance fixes to the stable `1.1.1` release.
- Updated GitHub release automation to use the built-in repository token and avoid duplicate tag/release runs.

## 1.1.0 — 2026-07-15

- Added the initial app icon.
- Added feed-level ad filtering to remove sponsored items before they are displayed.
- Added detection for sponsored, promoted, direct-ad, AdChoices, and paid-partnership markers.
- Added a UI fallback that hides posts containing rendered sponsored labels.
- Added support for several localized sponsored-label variants.
- Added automatic restoration for recycled feed views to prevent normal posts from remaining hidden.
- Improved DexKit scanning by searching dex files available through the Threads class loader.
- Expanded detection beyond boolean methods to include feed lists, parsers, mappers, and model objects.
- Improved compatibility with obfuscated and frequently changing Threads builds.
- Added more detailed LSPosed logging for method discovery, hook installation, and removed ads.
- Fixed ads still appearing when Threads injected them directly into feed lists.
- Fixed split-APK and secondary-dex code being missed during method discovery.
- Fixed potentially incorrect hooks caused by forcing every ad-related boolean method to return false.
- Fixed the fallback scan being skipped after finding unrelated string matches.
- Fixed recycled feed cards occasionally hiding legitimate posts.

## 1.0.0 — 2026-07-14

- Scoped the LSPosed module to `com.instagram.barcelona`.
- Updated the sponsored-content hook from legacy void/no-op behavior to the current Boolean `insertItem(Object, Object)` behavior returning `false`.
- Added a unique-match shape fallback, legacy compatibility fallback, and diagnostic LSPosed logging.
