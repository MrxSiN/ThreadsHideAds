# Android CI and Release Automation Plan

This plan aims to set up a comprehensive GitHub Actions workflow that handles both continuous integration (CI) and automated releases.

## User Review Required

> [!IMPORTANT]
> To automate the **Release** process (uploading a signed APK to GitHub Releases), you will need to configure the following **Secrets** in your GitHub repository (**Settings > Secrets and variables > Actions**):
>
> 1. `SIGNING_KEY`: Base64-encoded content of your `.jks` or `.keystore` file.
> 2. `ALIAS`: Your key alias.
> 3. `KEY_PASSWORD`: Your key password.
> 4. `STORE_PASSWORD`: Your keystore password.
>
> If you don't provide these secrets, the **CI** part (building and verifying) will still work, but the **Release** part will fail.

## Proposed Changes

### GitHub Actions Workflow

#### [NEW] [android.yml](file:///D:/OneDrive/AndroidStudioProjects/ThreadsHideAds/.github/workflows/android.yml)
Create a unified workflow file:
- **CI Trigger**: Runs on every push or pull request to the `main` branch to ensure the project builds correctly.
- **Release Trigger**: Runs when a tag starting with `v` is pushed.
- **Tasks**:
  - Sets up JDK 17 and Gradle.
  - Builds the APK (Debug for CI, Release for Tags).
  - Uploads the build artifact for inspection.
  - On tags: Signs the APK and creates a GitHub Release with the APK attached.

## Verification Plan

### Manual Verification
1.  **CI Check**: Push a commit to `main` and check the **Actions** tab to see the build running.
2.  **Release Check**: Create and push a tag:
    ```bash
    git tag v1.0.1
    git push origin v1.0.1
    ```
3.  Verify that a new Release is created on GitHub with the signed APK.
