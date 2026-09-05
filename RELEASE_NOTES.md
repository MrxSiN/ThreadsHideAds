# Threads Hide Ads v1.2.1

## Feed-filtering release, with an internal cleanup in 1.2.1

This release makes model-level filtering work again on current Threads builds, where the feed is Jetpack Compose and every feed model is a Pando tree.

### Fixed

- Fixed advertisements reaching the timeline on Threads 445. Feed items are handed to the cache one at a time, so filtering a batch left it empty, and the rule that a non-empty list is never emptied then discarded the result.
- Fixed detection finding nothing inside Pando models. Their JSON view answers `{}` for the generated model classes, so no sponsored marker was ever visible through it.
- Fixed the primary feed boundary being rejected when Threads exposes it through one interface and two implementations.
- Fixed advertisements surviving a cold launch, where the card is still unattached and unmeasured when its label is bound.

### Improved

- Identifies an advertisement by its feed unit type, matched whole: `AD`, `AD4AD`, `INTENT_AWARE_AD_PIVOT`, `STAND_ALONE_MULTI_AD_PIVOT`, and the `ADS_FEEDBACK_INTERFACE` variants. Recommendation units such as the `NETEGO` and `SUGGESTED` families are left alone.
- Allows a top-level feed batch to be emptied, because an emptied batch means it held nothing but an advertisement. Nested inner lists still may not be emptied, which is what that rule was written to protect.
- Adds a Pando response layer that hooks the list accessors on `com.facebook.pando.TreeJNI` by shape. Those names come from the native binding rather than the obfuscator, so the layer needs neither DexKit nor its native library and stays installed when either fails.
- Reads Pando tree JSON with whichever flag pair yields the most content, chosen once per model class.
- Adds the short `Ad` badge and further localized variants to the rendered-label vocabulary, still matched whole-label only.
- Splits the entry point into `core`, `detect`, `model`, `discovery`, `hook`, and `ui` packages, each with one responsibility.

### Internal (1.2.1)

- Splits model inspection out of `Diagnostics` into a new `ModelProbes` class.
- Removes the marker-keyword walk and the JSON key dump, neither of which could see a sponsored signal in a Pando model, and a per-row tree serialization the response hooks performed only for tracing.
- Filtering behavior is unchanged from 1.2.0.

### Verified

Measured on a Pixel 8 Pro against Threads 445.0.0.46.83: advertisements removed at the cache boundary across repeated launches and scroll passes, no `Ad` badge or `Sponsored` label present in any `uiautomator` dump, no crash-buffer entries, and the feed rendering normally.

### Version metadata

- Version name: `1.2.1`
- Android version code: `5`
- Recommended Git tag: `v1.2.1`
- APK filename: `ThreadsHideAds-v1.2.1.apk`

### Updating

Install the v1.2.1 APK over v1.1.2, keep Threads as the module’s only LSPosed scope, then force-stop and reopen Threads once. Confirm the LSPosed log reports `Application.attach observed` followed by `Hook initialization complete`.
