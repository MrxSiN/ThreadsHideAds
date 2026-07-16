# Hook notes

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
