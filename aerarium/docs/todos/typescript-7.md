---
title: Move to TypeScript 7 once typescript-eslint supports it
kind: todo
status: waiting
updated: 2026-11-04
---

# Move to TypeScript 7 once typescript-eslint supports it

Renovate PR #19 (TypeScript 5/6 → 7.0.2) failed the gate on 2026-11-01: `typescript-eslint 8.72.1` refuses it
("typescript-eslint does not support TS 7.0"); support is tracked in typescript-eslint issue #10940, aimed at TS 7.1.
The PR was closed on 2026-11-04, and **closing a Renovate major PR makes Renovate skip every 7.x release**, so no new
PR will come by itself.

Check: when typescript-eslint's release notes say TS 7 is supported, bump `typescript` in `aerarium/package.json` by
hand (with typescript-eslint), run the gate, and delete this file. Note TS 7 also ships no `tsserver.js`, so editor
tooling that needs it must keep a TS 6 install beside it.
