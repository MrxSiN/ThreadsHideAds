# Threads Hide Ads (LSPosed)

A stripped-down LSPosed module containing only the Threads sponsored-feed hook.
It does not include the YouTube, Reddit, Instagram, Photos, Strava, AllTrails,
Photomath, settings UI, or any other functionality.

## What changed

The original implementation searched for a **void** method containing the
string `SponsoredContentController.processValidatedContent` and replaced it
with a no-op.

Newer Threads builds use a private Boolean method shaped as:

```text
SponsoredContentController.insertItem(Object, Object): boolean
```

This module replaces that method with a constant `false` return value. It also
contains:

1. A conservative name-change fallback that requires a unique private
   `boolean (Object, Object)` method in `SponsoredContentController`.
2. The old void/string hook as a compatibility fallback for older Threads
   versions.
3. LSPosed logging for exact matches, ambiguous matches, and failures.

## Scope

Only:

```text
com.instagram.barcelona
```

## Build

Requirements:

- Android Studio with JDK 17 or newer; or Gradle 9.4.1
- Android SDK Platform 36

From the project directory:

```bash
gradle :app:assembleRelease
```

The APK will be created under:

```text
app/build/outputs/apk/release/
```

A helper script is included to create a standard Gradle wrapper when a local
Gradle installation is available:

```bash
./scripts/create-wrapper.sh
./gradlew :app:assembleRelease
```

On Windows PowerShell:

```powershell
.\scripts\create-wrapper.ps1
.\gradlew.bat :app:assembleRelease
```

## Install

1. Build and install the APK.
2. Enable **Threads Hide Ads** in LSPosed.
3. Select only **Threads** in the module scope.
4. Force-stop Threads, then reopen it. A reboot is advisable if the hook does
   not load after force-stopping.
5. Check the LSPosed log for entries beginning with `ThreadsHideAds:`.

## Validation status

The source structure and hook logic were statically checked. It was not built
against an Android SDK or tested on a rooted device in the creation environment.
Runtime compatibility therefore still needs confirmation on your installed
Threads build.

## Attribution

Derived from the GPL-3.0 Threads ad-removal concept and updated using
the current method signature used by maintained ReVanced-family Threads
patches. Dex discovery is performed with DexKit (LGPL-3.0-or-later).
