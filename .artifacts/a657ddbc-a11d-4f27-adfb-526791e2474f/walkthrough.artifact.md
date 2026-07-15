# Walkthrough - Package Rename to `my.github.MrxSiN.threadshideads`

I have successfully renamed the project's package and application ID to `my.github.MrxSiN.threadshideads`.

## Changes Made

### 1. Build Configuration
- Updated `namespace` and `applicationId` in [build.gradle.kts](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/build.gradle.kts).

### 2. Xposed Configuration
- Updated the entry point class name in [xposed_init](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/assets/xposed_init) to match the new package.

### 3. Source Code Relocation
- Created the new package directory: `app/src/main/java/my/github/MrxSiN/threadshideads/`.
- Moved [MainHook.java](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/java/my/github/MrxSiN/threadshideads/MainHook.java) to the new location and updated its package declaration.
- Deleted the old `io.github.nexalloy.threadshideads` directory structure.

## Verification

### Build Success
- Ran `./gradlew :app:assembleDebug` and the build finished successfully.

render_diffs(file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/build.gradle.kts)
render_diffs(file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/assets/xposed_init)
render_diffs(file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/java/my/github/MrxSiN/threadshideads/MainHook.java)
