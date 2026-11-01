---
title: 🏠 Move or copy a phone download to home, filed into Jellyfin by Haiku
kind: todo
status: done
area: downloads
updated: 2026-11-01
---

# 🏠 Move or copy a phone download to home

Dewi, report 20261101T092142 (v0.1.824), on a downloaded Peppa Pig YouTube video's menu: *"shouldn't I have the option
to move this to the raspberry pi that would involve like the AI to … intelligently put it in the jellyfin film or
series"*.

Decided (AskUserQuestion, 2026-11-01):

- **Both in the menu** of any item downloaded on the phone: "🏠 Also at home" (copy, keep both) and "🏠 Move to home"
  (delete the phone copy once home reports it ready; a 🔒 locked download stays). The Pi fetches it itself with the
  app's own routes (ADR 110), nothing is uploaded from the phone.
- **Haiku decides where a YouTube video goes in Jellyfin**: a film, an episode of a series (Peppa Pig as a show), or
  left in Totum's folder, through the same filer as torrents (ADR 111), widened beyond torrents.

Built 2026-11-01: ADR 110 (the menu, the move) and ADR 111 (YouTube filing, with its dry runs).
