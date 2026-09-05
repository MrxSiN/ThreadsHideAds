# Publishing v1.2.0 on GitHub

## GitHub Actions behavior

The workflow in `.github/workflows/android.yml` performs the following:

- Every branch push builds `assembleRelease`, signs the APK, verifies its signature, and uploads it under the workflow run’s **Artifacts** section.
- A manual **Run workflow** action performs the same signed build and artifact upload.
- Pull requests build and upload an unsigned release APK because GitHub does not expose signing secrets to untrusted PR code.
- A pushed `v*` tag builds and signs the APK, uploads it as a workflow artifact, creates or updates the matching GitHub Release, and attaches the signed APK.

The expected APK name is:

```text
ThreadsHideAds-v1.2.0.apk
```

## Required repository secrets

Open the repository and go to:

```text
Settings > Secrets and variables > Actions > New repository secret
```

Create all four secrets:

- `SIGNING_KEY` — Base64-encoded contents of the Android keystore.
- `ALIAS` — Keystore key alias.
- `STORE_PASSWORD` — Keystore password.
- `KEY_PASSWORD` — Key password.

Normal pushes, manual runs, and tag releases fail with a clear message when any signing secret is missing. This prevents an unsigned APK from being mistaken for a publishable build.

### Encode the keystore

Linux:

```bash
base64 -w 0 release.keystore > release.keystore.base64
```

macOS:

```bash
base64 < release.keystore | tr -d '\n' > release.keystore.base64
```

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) |
    Set-Content -NoNewline release.keystore.base64
```

Copy the complete text from `release.keystore.base64` into the `SIGNING_KEY` secret. Do not commit the keystore or Base64 text to the repository.

## Required on-device startup check

Before creating the release tag, cold-start Threads repeatedly and confirm each session logs `Application.attach observed` and `Hook initialization complete` without requiring a second launch. Export the LSPosed log if the retry sequence is used or exhausted.

## Local checks

```bash
./scripts/check-project.sh
./gradlew clean assembleRelease
```

A local release build remains unsigned unless the following environment variables point to valid signing credentials:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_ALIAS
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_PASSWORD
```

## Publish v1.2.0

Commit the v1.2.0 release files first:

```bash
git add .
git commit -m "Release Threads Hide Ads v1.2.0"
git push origin HEAD
```

After the branch workflow succeeds, create and push the release tag:

```bash
git tag -a v1.2.0 -m "Threads Hide Ads v1.2.0"
git push origin v1.2.0
```

The tag run will attach `ThreadsHideAds-v1.2.0.apk` to the GitHub Release using the built-in `GITHUB_TOKEN`.
