# Hook notes

## v2.0.0 release scope

Version 2.0.0 moves the entry point to the libxposed API 102
(`ModuleMain`, `META-INF/xposed/`), adds hot reload (the retiring generation
closes its core and hands the hidden-view registry to the new one), replaces
the launcher bitmaps with an adaptive vector icon, and rewrites all six policy
programs as hand-written Brainfuck (`brainfuck/src/*.bf`); the `.bfa`
assembler is gone. The hooks below are unchanged in what they do, and
the policy was differentially tested against the programs it replaced.

## v1.3.0 release scope

Version 1.3.0 moves every policy decision into a Brainfuck core
(then `brainfuck/src/*.bfa`, compiled ahead of time into `libthreadsbf.so`). The
hooks, discovery queries and view handling below are unchanged in what they
do; which of them run, in what order, and what they remove is now decided by
the core. Behavior is checked against the frozen 1.2.1 code (`docs/PARITY.md`).
The intentional differences are listed in `docs/MIGRATION.md`.

## v1.2.x release scope

Version 1.2.0 adds unit-type detection, the `allowEmptying` rule for top-level
feed batches, and the Pando response layer. The findings behind those changes
are recorded at the end of this file, under "What actually identifies an
advertisement". Version 1.2.1 separates `Diagnostics` from `ModelProbes` and
drops the probes that never produced a signal, without changing filtering.

## v1.1.2 release scope

Version 1.1.2 packages the attach-gated startup path, bounded initialization retries, lazy DexKit loading, and base-APK bridge fallback introduced after v1.1.1. The feed-boundary and recycled-view filtering changes remain those stabilized in v1.1.1.

## Startup reliability

The previous build started DexKit directly inside `handleLoadPackage()` and set its global initialized flag before discovery began. If Threads' final class loader was not ready, the native library was temporarily unavailable, or DexKit bridge creation failed, that process was still marked initialized and could not try again. Closing and reopening Threads created a new process, which explains why the next launch often worked.

The revised entry path mirrors the reliable lifecycle used by TwitterHideAds:

1. install the framework-level rendered-label fallback immediately;
2. hook `android.app.Application.attach(Context)`;
3. resolve model hooks only after the real Threads context and app class loader are available;
4. mark initialization complete only when at least one model hook is installed; and
5. retry unsuccessful attempts after 250 ms, 1 second, and 3 seconds.

DexKit native loading is now lazy rather than performed in a static class initializer. The resolver first opens all dex paths exposed by the final app class loader and falls back to the base APK path if loader-based bridge creation throws.

Expected successful startup sequence:

```text
ThreadsHideAds: Application attach guard installed
ThreadsHideAds: Application.attach observed; starting hook resolution
ThreadsHideAds: Initialization attempt 1 via Application.attach
ThreadsHideAds: Hook initialization complete; installedHooks=...
```

## Current observed feed boundary

The supplied runtime log shows the active feed sanitizer repeatedly removing one sponsored item from:

```text
X.02AX.Dqi(Integer, Integer, List) -> X.00l1
```

Startup now searches for this boundary first using the `Sponsored` marker plus the structural signature `(Integer/int, Integer/int, List) -> non-void`. The target is installed only when exactly one structural match is found. Broad marker discovery is skipped in that case and retained only as an app-update fallback.

The input list is sanitized before the method runs. The revised hook also:

1. mutates mutable lists in place before creating a replacement;
2. sanitizes mutable arguments again after the method returns, in case the method appended an item internally;
3. never reports an immutable post-call argument as changed when it cannot be written back to the caller; and
4. scans bounded collection fields inside the returned `X.00l1` wrapper, replacing immutable nested lists through writable fields, map entries, list slots, or array slots.

## Intermittent-ad root cause addressed

The rendered-label fallback previously restored a hidden card whenever any descendant `TextView` received ordinary text. A sponsored card can continue binding its author, caption, timestamp, or engagement text after the `Sponsored` label has already hidden the card. One of those later updates could therefore restore the ad.

The saved state now retains the exact label view that caused the collapse. Only that same view may restore the card when RecyclerView reuses it for an organic item. Before restoration, the card is scanned once more for any remaining sponsored label. Posted hide callbacks also re-read the current text and content description before collapsing a card, so a stale callback cannot hide a view that has already been rebound to organic content.

## Detection layers

1. DexKit discovers ad-related methods in all dex paths visible to the Threads class loader.
2. Likely feed list boundaries are sanitized before and after execution.
3. Returned feed wrappers are checked for nested lists.
4. Structured model fields and JSON-style metadata are inspected with bounded recursion.
5. Rendered labels are detected through `TextView.setText`, content descriptions, and views attached below list containers.

## Safety behavior

- The module remains scoped to `com.instagram.barcelona`.
- Broad words such as plain `ad` are not used as DexKit markers.
- Methods associated with messaging, notifications, media playback, bug reports, uploads, and quick promotions are excluded from list-boundary hooks.
- Ordinary captions that merely contain “sponsored” are not classified as ads.
- Reflection depth, object budgets, UI traversal, and nested-result traversal are bounded.

## Cold-launch failure addressed

On a cold launch the first feed page is bound before the view hierarchy has
settled. Two things went wrong at that moment.

First, the label itself was not recognized. Threads renders a short `Ad` badge
next to the author name, and the rendered-label vocabulary only listed longer
words such as `Sponsored`. The vocabulary now includes `Ad` and further
localized variants. Matching remains whole-label only, so a caption containing
one of those words is still treated as organic content.

Second, the card could not be identified yet. The hider walks up from the label
looking either for a direct child of a `RecyclerView`/`ListView`, or for an
ancestor wide enough to be a card. During the first bind the card is often
still detached, and every ancestor measures zero, so neither test could
succeed. The old code fell straight through to its last-resort branch and hid
only the badge, leaving the advertisement on screen.

`FeedItemHider` now treats "cannot tell yet" as distinct from "no card". When
the hierarchy cannot answer, it arms a single deferred retry: an attach
listener when the label is still detached, otherwise a one-shot pre-draw
listener so the retry runs after the next layout pass. Up to four retries are
attempted before the label-only fallback is used. Each retry re-reads the
current label text, so a recycled view that has since been rebound to organic
content is never collapsed.

## Module structure

The entry point only wires layers together. Java owns mechanisms; the native
core owns decisions (`docs/ARCHITECTURE.md`).

| Package / directory | Responsibility |
| --- | --- |
| `brainfuck/src` | Policy: scope, retries, install order, discovery rules, classification, sanitization, Pando, UI fallback, statistics |
| `bridge` | ABI constants (generated), JNI entry points, handle table, host capabilities, typed `Policy` requests |
| `core` | Module runtime (hot reload), logging, rate-limited counter wording |
| `discovery` | DexKit queries and class lookups requested by the core |
| `hook` | Installing model-level and Pando hooks; `ChainCallFrame` adapts a libxposed chain to the core's call frame |
| `ui` | Rendered-label listeners, view traversal, hide/restore |

## Threads 445.0.0.46.83 findings

Taken from the installed APK on a Pixel 8 Pro (13 dex files, `dexdump -l xml`).

### The feed boundary now has several implementations

The `(Integer, Integer, List) -> non-void` shape carrying the `Sponsored`
marker resolves to three methods:

```text
X.1Cb.DuY(Integer, Integer, List) -> X.0k7   (interface declaration)
X.1Ca.DuY(Integer, Integer, List) -> X.0k7   (implementation)
X.PbI.DuY(Integer, Integer, List) -> X.0k7   (implementation)
```

`X.1Cb` is an interface; `X.1Ca` and `X.PbI` implement it. This is the renamed
successor of the `X.02AX.Dqi(Integer, Integer, List) -> X.00l1` boundary
recorded above. Because startup accepted the target only when exactly one
structural match existed, three matches meant the boundary was discarded and
broad discovery ran instead. Startup now hooks every concrete match and skips
abstract and interface methods, which cannot be hooked.

### The rendered-label fallback cannot fire on this build

The feed is Jetpack Compose. A `uiautomator` dump shows one real view,
`com.instagram.barcelona:id/compose_view`, with the feed list carrying the test
tag `IgLazyColumn` and each post carrying `FeedPostRow`. Every node below the
`ComposeView` is a semantics node, so the `android.widget.TextView` and
`android.widget.Button` class names in a dump are Compose roles rather than
real classes.

That makes all three rendered-label hooks inert on this build:

- `TextView.setText` never sees feed text, because no `TextView` holds it.
- `View.setContentDescription` never fires, because Compose sets semantics.
- The card walk looks for a `RecyclerView` or `ListView` ancestor, and a
  `LazyColumn` is neither.

The advertisement badge renders as a semantics node with content description
`"Ad "`, alongside an author line reading `"<author> · Ad "`. Filtering has to
happen in the model layer on this build.

### Client-side ad injection

`com.instagram.barcelona.feed.data.cache.BarcelonaSpoolFeedCacheHandler` holds
`A0i(X.0hJ, int) -> boolean`, whose Kotlin lambda class names it as
`injectAdToFeedSessionAtPosition`. `X.0hJ` is the feed item type. The same
class also exposes `A0G(X.2dU, Integer, Integer, Integer, X.2cx, boolean,
boolean, boolean)`, the stored-feed read path.

Feed item fields are obfuscated, so field-name detection cannot match on this
build. Detection has to rely on nested class names and on the structured
`toString` check for `"is_sponsored":true`; both `is_sponsored` and
`sponsored_label` are still present as strings in the dex.

## Pando response path

Every feed model on this build is a `com.facebook.pando.TreeJNI` subtree, and
the app reads its rows through the list accessors declared on that base class.
Those accessors are the first point where a GraphQL response becomes readable
Java objects, which makes them a better filtering point than the feed
boundaries used so far:

- their names come from the native binding rather than from the obfuscator, so
  they survive the renaming that repeatedly broke boundary discovery;
- they need neither DexKit nor its native library, so they are installed before
  the bridge is opened and stay installed when it fails; and
- a sponsored row is removed before the feed cache, the repository, or the
  client-side ad injector has seen it.

`PandoResponseHooks` selects them by shape — declared on `TreeJNI`, returning a
`List`, neither abstract nor native, because a native method cannot be hooked —
and filters each returned list after the call.

### Detection and cost

`PandoAdSignals` decides whether one tree is sponsored. The tree JSON is still
the only Java-side view of a Pando model, and producing it serializes the whole
subtree, so:

- lists are rejected on their first element unless it is a Pando tree, which
  skips the string and number lists that share the same accessors;
- lists longer than 256 entries are skipped as bulk value lists; and
- every verdict is cached against the tree that produced it, through weak keys
  so the cache never keeps a feed model alive.

A tree whose JSON exceeds 64 KB is reported as not sponsored even when it
carries a sponsored marker. A single feed item serializes well below that, so an
oversized tree is a page or a ranking container, and dropping it would take
organic posts with it. Those trees are traced and left to the boundary layer.

### Writing the filtered list back

Pando hands out immutable lists. The filtered copy is returned as an
`ArrayList` only when the declared return type accepts one; otherwise the
original list type rebuilds itself through a static `copyOf` factory found on
its own class or a supertype. When neither works the result is left untouched
and counted as a replacement failure rather than risking a `ClassCastException`.

The rule that a non-empty list is never emptied applies here as well: when every
entry is sponsored, the item owning the list is what should be dropped, one
level above.

## What actually identifies an advertisement (Threads 445.0.0.46.83)

Verified on a Pixel 8 Pro against the running app.

The Pando JSON view is a dead end on this build. `TreeJNI.asJSONNative` answers
`{}` for the generated model classes and returns only a shallow
`{"fields": ...}` wrapper for the base `TreeWithGraphQL`, whichever pair of
boolean flags it is given. Sponsored markers never appear in it, which is why
JSON-based detection removed nothing.

The unit type does identify an advertisement, and it is not obfuscated. A feed
item `X.0hJ` carries:

| Field | Meaning |
| --- | --- |
| `A0q` (`X.0hK`) | payload wrapper holding the `Thread` and the same type enum |
| `A0r` (`X.3cp`) | feed unit type, for example `THREAD(thread)` |

`X.3cp` keeps readable constant names, and its advertisement constants are
`AD(ad)`, `AD4AD(ad4ad)`, `INTENT_AWARE_AD_PIVOT(intent_aware_ad_pivot)`,
`STAND_ALONE_MULTI_AD_PIVOT(stand_alone_multi_ad_pivot)` and the three
`ADS_FEEDBACK_INTERFACE` variants. Those values are now matched whole, as unit
types, by `AdFieldSignals.isAdUnitType`. The remaining constants are
recommendation units (`*_NETEGO`, `SUGGESTED_*`) and are deliberately left
alone.

### Emptying a top-level feed list is required

Threads delivers feed items to the cache one at a time, so the list crossing
`BarcelonaFeedCache.A0G(..., List, ...)` usually holds a single item. The rule
that a non-empty list is never emptied therefore blocked every removal.

`ListSanitizer` now takes an explicit `allowEmptying` flag. The feed-boundary
hooks pass `true`, because emptying a top-level batch means "this batch was
nothing but an advertisement". Nested inner lists still pass `false`, which is
what the original crash required.

### Measured result

One launch plus sixteen scroll passes: 9 advertisements removed at the cache
boundary, no `Ad` badge or `Sponsored` label present in any `uiautomator` dump,
no crash buffer entries, feed rendering normally.

### Diagnostics used

`Diagnostics` records what the hooks did: boundary calls, unfiltered lists,
recognized advertisements, blocked emptying, and Pando tree lists.

`ModelProbes` inspects an unknown model: `traceClassShape` reports supertypes
and non-null fields one level down, `traceEnumConstants` lists an enum's
constants with their server values, `traceDeclaredMethods` lists what a class
declares, and `traceTreeKeys` names the sponsored-looking JSON keys a tree
carries when the vocabulary did not match it.

The class-shape and enum probes are what identified `X.3cp`. The earlier
marker-keyword walk was removed: Pando models expose nothing to it, so it
reported empty markers on every feed item.

All of it reports names, types, sizes and key names only, never feed content,
and was gated behind `Diagnostics.ENABLED`, which was `false` for release builds.
Both classes were removed in 2.0.0; git history has them for the next
investigation.

## Threads 448.0.0.54.85 findings

- The primary boundary resolves to `X.4At.Dyg(Integer, Integer, List)`;
  initialization installs 38 model hooks and 28 Pando hooks on every cold start.
- The same unit-type enum identifies advertisements; 10–12 were removed per
  launch and 25 scroll passes, the same as 1.2.1 on this build.
- Hook code runs on several threads and can re-enter itself through class
  initialization. A single global lock around the native core deadlocked
  initialization here, so stateless requests now run on per-thread pooled
  engines and only the persistent core state is serialized
  (`docs/MIGRATION.md`, intentional changes).

## Threads 455.0.0.55.72: permalink ads

Ads between the replies of a post are not feed items. `PermalinkScreen` gets
them from `com.instagram.barcelona.permalink.adsusecase.PermalinkAdsUseCase`,
which calls `com.instagram.barcelona.sponsored.permalink.ThreadsPermalinkAdFetcher`
(`text_feed/threads_permalink_ads/`) and pushes the result into its own state
flow, so neither the feed cache boundaries nor the Pando accessors see them.
Placement is experiment gated (`PermalinkAdsMode`, `PlacementRules`), which is
why one account showed them and another did not.

The fetch step is the use case's only static method that returns `Object` and
takes the use case itself: the Kotlin suspend body
`A00(X.1Ls, PermalinkAdsUseCase, Continuation)`. Every path through it returns
`Unit`, including the failure path, so `FeedModelHooks.hookPermalinkAdsLoader`
returns `Unit` without running it: no request, no ad. The core installs it only
when exactly one method has that shape (OP 0x18/0x19).

Evidence (2026-11-14, Threads 455.0.0.55.72, Pixel 8 Pro):

- EXECUTED on device: before the change, a second account showed an `Ad` row
  between the replies of a post while the feed was clean; the log had no hook
  errors and no Pando removal. After installing the debug build the log reads
  `Disabled permalink ad loader: ...PermalinkAdsUseCase.A00(...)`, the same
  posts show no ad, and the crash buffer has no Threads entry.
- EXECUTED: `CoreParityTest.permalinkAdsLoaderExactlyOne` (shape, exactly-one
  decision, ambiguity, reset), all JVM and compiler tests, `gen --check`,
  `check-project.sh`, `lint` on `core.bf`.
- NOT RUN: a release-signed build on device (the installed copy is debug-signed).


## Threads 457.0.0.57.74: primary boundary gained parameters

The `Sponsored`-marked delivery method is now
`X.4Kg.ELT(Integer, Integer, List, int, boolean) -> X.1Dk`
(`onSponsoredContentDelivered`). The old shape required exactly three
parameters, so 2.1.0 logged `Primary feed-boundary search found no structural
match; using broad fallback` and installed 34 hooks instead of 35. The shape
now takes three or more parameters with the same first three; it is still the
only structural match.

Evidence (2026-11-27, Threads 457.0.0.57.74, Pixel 8 Pro, debug build):

- EXECUTED on device: log reads `Installed confirmed feed boundary: ...X.4Kg.ELT(...)`,
  `Primary feed boundaries installed: 1; broad discovery skipped`,
  `installedHooks=35, pandoHooks=28`; ads removed at `BarcelonaFeedCache.A0O`
  while scrolling; no `Ad`/`Sponsored` label in 6 `uiautomator` dumps; no crash-buffer entry.
- MEASURED: feed boundary 4.6–7.1 ms/call (2.1.0 before the fix: 5.4 ms/call;
  448 recorded 1.9–3.0), Pando accessor 18–74 µs/call. Not investigated further.
- EXECUTED: `gen --check`, `lint` on `core.bf`, `check-project.sh`,
  `:app:testDebugUnitTest :app:assembleDebug`, compiler tests.
- NOT RUN: release-signed build on device; permalink reply ads (loader hook
  installed, posts not opened).

## Threads 457.0.0.57.74: one-item writes and inserts (from HushThreads)

HushThreads (GPL-3.0, v0.0.19) lists the For You cache paths that skip a page
merge. On 457:

- The spool classes (`BarcelonaSpoolFeedCacheHandler`, `...SpoolFeedNetworkSource`)
  are gone; `BarcelonaFeedCacheHandler` now extends `ForYouFeedLocalDataSource`.
  The old injector hook resolves nothing and logs nothing.
- List writes (refresh, cold start, paging, background prefetch) are
  `BarcelonaFeedCache` methods taking a `List`; the feed-owner rule already
  hooks all 22 of them.
- One-item writes `A0R`, `A0S`, `A0T`, `A0U` take the feed item `X.5eY` first and
  answer `Object` (suspend, `Unit` when done). New: the core hooks every
  instance, non-private `Object` method of the cache whose first parameter is
  the insert's item type (OP 0x1C); an item kept out returns `Unit` unwritten.
- `BarcelonaFeedCacheHandler.DKX(X.5eY, int) -> Integer` inserts one item at a
  position. Its codes are a shared Integer pool: 0 inserted, 1 no page,
  2 negative position, 3 past the end, and `IndexOutOfBoundsException` maps to
  1. The sponsored controller (`X.4Kg`) retries only its own code 5. An item
  kept out is inserted at position -1, so the insert declines on its own path.
- Threads' own ad check is `Media.AKe()`, which returns `X.9dt.A00(...)`, the
  static check using `"injected".hashCode()`. The item's only Media getter is
  `X.5eY.A06()`. Both are found by shape and must be unique (OP 0x1D–0x20); the
  check is a second signal beside the detector for one-item writes and inserts
  only (OP 0x21). The list sanitizer does not use it.
  It is searched on a background thread after attach, because DexKit's invoke
  query builds a whole-app index (about 0.6 s).
- Permalink: HushThreads empties `ThreadsPermalinkAdFetcher`'s result. Here
  `PermalinkAdsUseCase.A00` already returns before the fetch runs, so no second
  filter was added.

Evidence (2026-11-27, Pixel 8 Pro, debug build):

- EXECUTED on device: `Using Threads' own ad check: ...Media.AKe()`,
  `Installed item insert filter: ...DKX(X.5eY,int)`, four
  `Installed one-item write filter` lines, `installedHooks=40, pandoHooks=28`;
  9 ads removed at `BarcelonaFeedCache.A0O` over 85 scroll passes; 75 feed rows,
  no `Ad`/`Sponsored` label in 8 `uiautomator` dumps; no crash-buffer entry.
- NOT RUN: a one-item write or insert carrying an ad. None happened on this
  account in this session, so the new filters' removal path is covered only by
  JVM tests.
