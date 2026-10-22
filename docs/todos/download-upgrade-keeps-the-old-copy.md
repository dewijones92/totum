---
title: Upgrading a download keeps the old copy until the new one lands
kind: todo
status: open
area: downloads
priority: medium
requested: 2026-10-22
updated: 2026-10-22
---

# Upgrading a download keeps the old copy until the new one lands

Found in review (2026-10-22): an audio copy and a full video copy share one file path, and asking for the video
overwrites the record with Downloading at once. Cancelling it deletes the file and forgets the record; a failure leaves a
Failed record. Either way the audio copy that was there is lost. Locked items are protected (ADR 75); unlocked ones are
not. Fix: fetch an upgrade into its own file and swap only on success; a cancel or failure leaves the old copy and its
record.
