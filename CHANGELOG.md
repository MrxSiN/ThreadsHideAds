# Changelog

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

- Add App Icon
- Improved Ad Detection
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

- LSPosed scope to `com.instagram.barcelona`.
- Updated the sponsored-content hook from legacy void/no-op behavior to the
  current Boolean `insertItem(Object, Object)` behavior returning `false`.
- Added a unique-match shape fallback, legacy compatibility fallback, and
  diagnostic LSPosed logging.
