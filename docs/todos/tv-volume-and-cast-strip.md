---
title: TV volume from the phone; the strip says what is cast
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# TV volume from the phone; the strip says what is cast

Dewi 2026-11-05. Volume buttons and the player's volume gesture drive the TV while casting (`RemoteCastPlayer`
has `setDeviceVolume`; `TimeMappedCastPlayer` forwards it), plus a slider in the cast strip. The strip says the
quality and codec sent, e.g. "📺 1440p VP9 on LG OLED".
