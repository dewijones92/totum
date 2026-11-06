---
title: Undo after every remove
kind: todo
status: decided
batch: A (2026-11)
priority: medium
updated: 2026-11-06
---

# Undo after every remove

Dewi 2026-11-06. One shared helper from `QueueUndo.rememberRemoveWithUndo` (the alarm screen's copy folds into it);
covers queue, downloads (a deferred delete, since the file goes), playlists, Ask chats; group delete keeps its hold to
confirm.

Whole-batch review: move `rememberRemoveWithUndo` from `ui/queue` (internal) to `ui/common` first; the alarm screen's copy folds in.
