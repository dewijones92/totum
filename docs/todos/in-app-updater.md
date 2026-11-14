---
title: Update straight from the banner
kind: todo
status: built; tests written in part
batch: A (2026-11)
priority: high
updated: 2026-11-14
---

# Update straight from the banner

Dewi 2026-11-06 (the Pixel 4a was three releases behind). The banner's Update downloads the release APK and opens
Android's installer (REQUEST_INSTALL_PACKAGES, a FileProvider; none exist today). Obtainium keeps working beside it.

Whole-batch review: extend `Release` once with the APK asset and notes, shared with what's new.

Built 2026-11-06: see [out-of-date banner](../features/out-of-date-banner.md) and [ADR 130](../adr/0130-update-from-the-banner-and-say-whats-new.md). Checked before install: package, signing key, version. Untested on a device; tests listed in the feature doc.

## Tests written 2026-11-14

`ApkCheckTest` (every refusal of `judgeApk` and the first one wins; the APK becoming a download item). Still owed: `AppUpdateInstaller` (needs a `Context`), a banner test per install state and the instrumented install of a re-signed APK, which must be refused.
