---
title: Totum — name, palette and icon
kind: feature
status: shipped
area: branding
updated: 2026-09-25
---

# Totum

**Totum** is Latin for "the whole". Chosen 2026-07-25 after ruling out the obvious
constructions: *duo-* and *uni-* both encode a pillar count, and would become a lie the
moment a third source type appears. "The whole" doesn't.

The old name was UniApp. The **repo** is still `dewiuniapp`, as are the local clone path
and the signing directory — renaming a git remote and a working copy mid-flight buys
nothing and breaks tooling. Only the app is Totum.

## What changed

| | Before | After |
|---|---|---|
| App name | UniApp | Totum |
| `applicationId` | `com.dewijones92.uniapp` | `com.dewijones92.totum` |
| Package root | `com.dewijones92.uniapp` | `com.dewijones92.totum` |
| Database | `uniapp.db` | `totum.db` |
| Python bridge | `uniapp_ytdlp.py`, `uniapp_bootstrap.py` | `totum_ytdlp.py`, `totum_bootstrap.py` |
| Icon | Android Studio template robot | Totum mark (below) |
| Palette | Android Studio template purple | Tangerine / cyan / lemon |

**The `applicationId` change means Android treats this as a different app.** Approved as a
clean slate (Dewi: "nobody has the app, I am the only one with it"), so there is no
migration: the old install stays behind with its data, the new one starts empty and signed
out. Two consequences worth remembering:

- The old **UniApp must be uninstalled by hand** — Android won't replace it.
- **Obtainium needs re-adding.** It tracks by package, so the old entry will silently stop
  updating.

## Palette

Bright and playful by explicit choice. A warm hero with a cool counterpart, which is also
the app's own shape — one whole made of two halves:

| Role | Colour | Use |
|---|---|---|
| Primary | Tangerine `#E85D04` / `#FF8A3D` dark | Actions, FAB, emphasis |
| Secondary | Cyan `#0089B0` / `#7FDFFF` dark | Selection, the second pillar |
| Tertiary | Lemon `#FFD84D` | Highlights |
| Neutrals | Warm sand | Surfaces that sit with the hero rather than fighting it |

Two decisions worth their own note:

- **Dynamic colour is off by default**, reversing the original `CLAUDE.md` decision.
  Dynamic colour substitutes the wallpaper's palette on every device that supports it, so
  keeping it on would mean the brand was never actually seen. Still a parameter, so a
  preview can opt in.
- **`TotumFab` exists** because Material 3 defaults a FAB to `primaryContainer`, a pale
  tint that read timid against a deliberately bright brand. Solid `primary` with white
  content lives in one component, so a new screen gets the boldness without knowing the
  rule.

The palette's single source of truth is `theme/Color.kt`. It is mirrored in exactly one
other place — `ic_launcher_background.xml`, because a vector drawable can't reference
Compose values — and that file says so.

## Typeface (2026-09-25)

**Bricolage Grotesque** (SIL Open Font License), bundled at `res/font/bricolage_grotesque.ttf` with its
licence in `assets/licenses/OFL-BricolageGrotesque.txt`, as the OFL requires when the font ships.

- One variable file, instanced with fontTools: the width axis pinned at 100 and subset to Latin and
  Latin Extended-A plus punctuation, which took it from 399KB to 206KB. Weight and optical size stay
  variable, so each style asks for its own weight through `FontVariation`.
- Display and headline styles use optical size 48, titles and labels 16. Body text stays on the system
  font: a characterful grotesk earns its place in a title and costs readability in a paragraph.
- A glyph outside the subset falls back to the system font rather than to a box.

## Icon

A chunky play triangle whose point radiates two broadcast arcs: watching and listening in
one mark. White on a tangerine gradient.

- The triangle's rounded corners come from stroking the path in its own fill colour with a
  round line-join, not hand-computed curves — same result, readable geometry.
- Everything sits inside the 66dp safe zone, so no launcher mask crops it.
- White-on-transparent, so the foreground doubles as the **monochrome** layer for Android's
  themed icons (which tint by alpha).
- The legacy raster `mipmap-*dpi` fallbacks were **deleted**: minSdk is 34, so adaptive
  icons are always used, and those files were both dead weight and the old template art.

## Verified on-device

- Icon renders correctly in the launcher at drawer size, legible and distinct from
  YouTube's red.
- Palette applies: solid tangerine FAB, cyan selection, warm surfaces.
- Clean slate confirmed — new install starts signed out and empty.
- **The Python bridge survived its rename**: a YouTube video searched, extracted through
  Chaquopy/yt-dlp, and played (`state=PLAYING`), with zero fatal exceptions. This was the
  real risk in the rename, since those files are referenced by string.
