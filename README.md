<div align="center">

# <img src="branding/threads-hide-ads-icon.png" width="36" height="36"> Threads Hide Ads

### A focused LSPosed module for removing sponsored feed items from Threads

[![Android](https://img.shields.io/badge/Android-LSPosed-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/LSPosed/LSPosed)
[![Target](https://img.shields.io/badge/Target-Threads-000000?style=for-the-badge&logo=threads&logoColor=white)](https://www.threads.net/)
[![DexKit](https://img.shields.io/badge/Powered%20by-DexKit-6A5ACD?style=for-the-badge)](https://github.com/LuckyPray/DexKit)
[![JDK](https://img.shields.io/badge/JDK-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)

</div>

<p align="center"><img src="branding/threads-hide-ads-icon.png" width="160" alt="Threads Hide Ads app icon"></p>

## ✨ Features

- Removes sponsored feed insertions from Threads.
- Waits for `Application.attach()` so Threads’ final app class loader is ready before model discovery.
- Uses DexKit to locate obfuscated methods across app updates.
- Retries transient initialization failures without requiring the user to reopen Threads.
- Includes a conservative fallback for renamed methods.
- Retains compatibility logic for older Threads versions.
- Writes useful match, ambiguity, and failure information to the LSPosed log.
- Limits its scope to the official Threads Android package.

## 🎯 Scope

The module hooks only:

```text
com.instagram.barcelona
```

Do not enable additional applications in the module scope.

## 🔍 How it works

The current module uses several independent layers because Threads frequently changes obfuscated class and method names:

1. The LSPosed entry point installs a lightweight `Application.attach()` guard instead of running DexKit immediately from `handleLoadPackage()`.
2. After Threads supplies its real application context and final class loader, DexKit searches for the confirmed feed-boundary shape using the `Sponsored` marker and `(Integer/int, Integer/int, List) -> non-void` signature.
3. A class-loader scan is preferred; the base APK path is used as a fallback when the loader cannot be opened by DexKit.
4. When exactly one primary boundary is found, it is installed and the expensive broad discovery pass is skipped. Broad marker discovery remains as an app-update fallback.
5. Failed or zero-hook initialization attempts are retried after 250 ms, 1 second, and 3 seconds. The initialized state is committed only after at least one model hook is actually installed.
6. Feed lists are sanitized in place when mutable; replacements are used only where the caller, result, field, map entry, list slot, or array slot can actually be updated.
7. Mutable arguments are sanitized again after execution to catch late insertion, without falsely reporting immutable lists as changed.
8. Collection fields inside returned feed-wrapper objects are sanitized with bounded recursion.
9. A UI fallback collapses a feed card when a localized sponsored label is rendered, after revalidating the marker at callback execution time.

The runtime log used for this revision identifies the active boundary as:

```text
X.02AX.Dqi(Integer, Integer, List) -> X.00l1
```

The UI fallback tracks the exact label view that hid each card. This prevents caption, author, timestamp, or engagement updates from accidentally restoring a sponsored card. A card is restored only when that original label view is rebound for a recycled organic item.

## ✅ Requirements

### Runtime

- A rooted Android device.
- A working LSPosed installation.
- The official Threads application (`com.instagram.barcelona`).
- The **Threads Hide Ads** APK installed and enabled in LSPosed.

### Build environment

- Android Studio with JDK 17 or newer, **or** a standalone JDK 17+ setup.
- Gradle 9.6.1 when building without an existing wrapper.
- Android SDK Platform 36.
- Git or a downloaded copy of the project source.

## 🛠️ Build

From the project directory, build the release APK with a local Gradle
installation:

```bash
gradle :app:assembleRelease
```

The generated APK will be located under:

```text
app/build/outputs/apk/release/
```

### Create and use the Gradle wrapper

A helper script is included to create a standard Gradle wrapper when a local
Gradle installation is available.

#### Linux / macOS

```bash
./scripts/create-wrapper.sh
./gradlew :app:assembleRelease
```

#### Windows PowerShell

```powershell
.\scripts\create-wrapper.ps1
.\gradlew.bat :app:assembleRelease
```

## 📦 Installation

1. Build and install the release APK.
2. Open the LSPosed manager.
3. Enable **Threads Hide Ads**.
4. Select **Threads** as the module's only scope.
5. Force-stop Threads once after installing or updating the module, then reopen it.
6. Review LSPosed logs for entries beginning with:

```text
ThreadsHideAds:
```

## 🧪 Validation status

The release version is `1.2.0` (`versionCode 4`). Static project checks cover the attach-gated initialization path, retry support, feed sanitization, release signing configuration, APK verification, artifact upload, and GitHub Release publishing. Feed filtering was additionally measured on a Pixel 8 Pro against Threads 445.0.0.46.83: advertisements are removed at the cache boundary, no sponsored label reaches the view hierarchy, and the feed renders normally.

The included GitHub Actions workflow builds a release APK on every push, pull request, and manual run. Repository pushes and tag builds are signed and uploaded as workflow artifacts when the four signing secrets are configured. Pull-request builds remain unsigned for security. A `v*` tag also creates or updates the GitHub Release and attaches the signed APK.

## 🩺 Troubleshooting

| Problem | Suggested action |
| --- | --- |
| Sponsored posts still appear | Confirm that only Threads is selected in the LSPosed scope and inspect whether the log reports `Hook initialization complete`. |
| Initialization attempt failed | Keep Threads open for several seconds while the automatic retries run, then export the `ThreadsHideAds:` log if all retries are exhausted. |
| No module log entries | Verify that the module is enabled, force-stop Threads once, and inspect the LSPosed framework status. |
| Ambiguous method warning | The installed Threads build may contain multiple possible targets. Avoid forcing a broad hook and report the relevant log output. |
| Hook failure after an update | Threads may have changed its internal classes or method signature. Capture the `ThreadsHideAds:` log entries for analysis. |

## 🙏 Credits

This project depends on and benefits from the following open-source work:

| Project | Contribution |
| --- | --- |
| [DexKit](https://github.com/LuckyPray/DexKit) by LuckyPray | High-performance runtime DEX parsing and discovery of obfuscated classes and methods. Licensed under Apache-2.0. |
| [LSPosed](https://github.com/LSPosed/LSPosed) | Provides the Android runtime hooking framework used to load and execute this module. Licensed under GPL-3.0. |
| ReVanced-family Threads patches | Provided the maintained method-signature reference used to update the sponsored-content hook. |

The module is derived from the GPL-3.0 Threads ad-removal concept and has been
updated for the newer sponsored-content insertion method.

## ⚠️ Disclaimer

This project is not affiliated with, endorsed by, or sponsored by Meta or
Threads. It is provided for educational and personal use. App updates may break
the hook without notice.
