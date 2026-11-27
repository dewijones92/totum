---
title: Hold the top-right corner to send a report
kind: todo
status: built
batch: C (2026-11)
priority: high
updated: 2026-11-27
---

# Hold the top-right corner to send a report

Dewi, reports 20261121T063645 and 20261121T061944. Decision and reasoning: [ADR 190](../adr/0190-hold-the-top-right-corner-to-send-a-report-and-a-report-never-blocks-the-next.md).

Built 2026-11-27: `ReportSubmitter` (the one send path for Settings, the tile and shortcut, speech-boost feedback and the corner hold),
`CornerHoldRecogniser`, `ui/report/CornerHold.kt`, `WindowCapture` (PixelCopy), `QuickReport` and `QuickReportSheet`, the
`reportGesture` setting, the uploader mutex, and the fix for "a second report can't be sent" (the Settings row's `sent` flag).

Open: a run on Dewi's phone over a live video and the Shorts reel; whether 1.5 s feels right.
