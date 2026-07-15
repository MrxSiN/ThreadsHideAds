# Walkthrough - Android CI & Release Automation

I have implemented a unified GitHub Actions workflow that handles both automated builds (CI) and releases.

## Changes Made

### GitHub Actions
#### [NEW] [android.yml](file:///D:/OneDrive/AndroidStudioProjects/ThreadsHideAds/.github/workflows/android.yml)
Created a workflow that:
1.  **Runs on every Push/PR to `main`**: Ensures the project builds correctly (CI).
2.  **Runs on Tags (`v*`)**: Automatically builds, signs, and publishes a Release.

## Setup Instructions

> [!IMPORTANT]
> To enable automated signed releases, you **must** add these secrets to your GitHub repository (**Settings > Secrets and variables > Actions**):
>
> | Secret Name | Description |
> | :--- | :--- |
> | `SIGNING_KEY` | Base64 encoded content of your `.jks` file. |
> | `ALIAS` | Your key alias. |
> | `KEY_PASSWORD` | Your key password. |
> | `STORE_PASSWORD` | Your keystore password. |

### Using the GITHUB_TOKEN
The workflow automatically uses the built-in `GITHUB_TOKEN` to create releases and upload files. You do not need to manually create this token; GitHub provides it to the action session automatically.

## How to Release
1.  Ensure your code is pushed to `main`.
2.  Create a tag: `git tag v1.0.1`
3.  Push the tag: `git push origin v1.0.1`
4.  The workflow will handle the rest!

## Verification
- Workflow file created at `.github/workflows/android.yml`.
- Permissions updated to allow writing contents (necessary for releases).
- Configured to use JDK 17 as required by your project.
