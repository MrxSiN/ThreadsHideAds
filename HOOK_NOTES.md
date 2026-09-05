# Hook notes

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

The entry point only wires layers together. Each package owns one concern.

| Package | Responsibility |
| --- | --- |
| `core` | Logging and rate-limited counters |
| `detect` | Label vocabulary, model ad signals, bounded reflective detection |
| `model` | List and nested-collection sanitization |
| `discovery` | DexKit search and candidate classification |
| `hook` | Installing model-level hooks |
| `ui` | Rendered-label fallback, view traversal, hide/restore |

The sanitizers depend on the `AdDetector` interface rather than on the
reflective implementation, so detection can be replaced without touching them.

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
