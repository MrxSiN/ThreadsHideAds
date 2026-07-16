# Threads Hide Ads v1.1.2

## Maintenance and startup-reliability release

This release keeps the v1.1.1 ad-filtering behavior while improving hook initialization, release automation, and application branding.

### Fixed

- Fixed occasional cold starts where model hooks were not installed until Threads was closed and reopened.
- Prevented a transient DexKit, native-library, or class-loader failure from permanently marking the process as initialized.
- Fixed GitHub Actions conditions that could skip release building, signing, verification, or APK upload outside tag runs.

### Improved

- Defers model discovery until `Application.attach()` provides the final Threads application context and class loader.
- Automatically retries unsuccessful or zero-hook initialization after 250 ms, 1 second, and 3 seconds.
- Loads the DexKit native library lazily so temporary startup failures remain recoverable.
- Falls back to opening the base APK when DexKit cannot open the application class loader.
- Keeps the rendered-label fallback active while model-hook initialization is pending or retrying.
- Adds a production launcher icon based on the Threads visual identity with an ad-blocking shield overlay.
- Includes legacy, round, adaptive, Play Store, and Android 13 monochrome icon variants.
- Builds, signs, verifies, and uploads the release APK on branch pushes and manual workflow runs.
- Uploads every successful APK as a workflow artifact; `v*` tags additionally attach it to the matching GitHub Release.
- Updates the Gradle wrapper to 9.6.1.

### Version metadata

- Version name: `1.1.2`
- Android version code: `3`
- Recommended Git tag: `v1.1.2`
- APK filename: `ThreadsHideAds-v1.1.2.apk`

### Updating

Install the v1.1.2 APK over v1.1.1, keep Threads as the module’s only LSPosed scope, then force-stop and reopen Threads once. Confirm the LSPosed log reports `Application.attach observed` followed by `Hook initialization complete`.
