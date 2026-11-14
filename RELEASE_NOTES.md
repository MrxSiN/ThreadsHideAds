# Threads Hide Ads v2.1.0

## Ads between the replies of a post

Some accounts saw ads between the replies when opening a post, even though the feed was clean. Threads loads those ads with a separate request that it enables per account, so the feed filters never saw them. This release stops that request, so no ad is placed between the replies.

### Changed

- Hides ads between the replies of a post. The fetch step is found by its shape and only hooked when exactly one method matches; if Threads changes it, nothing broad is installed and the feed filtering keeps working.
- Contribution rules (`AGENTS.md`) and the list of work still to move to BBC + XAX (`docs/MIGRATION.md`).
- Sponsor link in the README.

### Verified

- EXECUTED on a Pixel 8 Pro, Android 17, Vector 2.2, Threads 455.0.0.55.72 (debug build of this code): the permalink-ads hook installed on exactly one method, 34 model hooks and 28 Pando hooks; the account that showed ads in post replies no longer does; the feed stays clean; empty crash buffer.
- EXECUTED: JVM tests (31), compiler tests (8), generated-file check, static project checks.
- NOT RUN: this signed release APK on a device; LSPatch, 32-bit and x86 devices.

### Version metadata

- Version name: `2.1.0`
- Android version code: `9`
- Git tag: `v2.1.0`
- APK filename: `ThreadsHideAds-v2.1.0.apk`

### Updating

Install over v2.0.0. With hot reload the update applies to a running Threads; otherwise force-stop and reopen Threads once, then open a post to confirm no ad appears between the replies.
