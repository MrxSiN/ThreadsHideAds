# Package Rename Implementation Plan

The goal is to rename the package from `io.github.nexalloy.threadshideads` to `my.github.MrxSiN.threadshideads`.

## Proposed Changes

### [app]

#### [MODIFY] [build.gradle.kts](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/build.gradle.kts)
- Update `namespace` and `applicationId`.

#### [MODIFY] [xposed_init](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/assets/xposed_init)
- Update the entry point class name.

#### [NEW] [MainHook.java](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/java/my/github/MrxSiN/threadshideads/MainHook.java)
- Create the file in the new directory structure.
- Update the package declaration.

#### [DELETE] [MainHook.java](file:///C:/Users/SiN/AndroidStudioProjects/ThreadsHideAds-LSPosed/app/src/main/java/io/github/nexalloy/threadshideads/MainHook.java)
- Remove the old file and directory structure.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify the build.
- Perform a final `grep` to ensure no instances of the old package name remain.

### Manual Verification
- Verify the module is correctly recognized by LSPosed on a device (if available).
