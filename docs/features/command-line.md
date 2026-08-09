---
title: Totum on the command line
kind: feature
status: shipped
area: cli
updated: 2026-08-09
---

# `totum "jazz live stream"`

Dewi, 2026-08-09: *"I want to on the command line in Linux stream audio … put in jazz live stream
as a string parameter … I think the YouTube extraction capabilities in our code base is good and
can be reused elsewhere"*.

```
totum "jazz live stream"        # search, play the first hit, audio only
totum https://youtu.be/xyz      # straight to a link
totum play <thing> --watch      # keep the picture
totum resolve <thing> --json    # print the stream, do not play it
totum search <words> --limit=5  # list what a phrase finds
```

## Install

```
curl -L https://github.com/dewijones92/totum/releases/latest/download/totum-cli.tar.gz | tar xz
./totum/bin/totum "jazz live stream"
```

Same release as the APK, same version number, same commit — so the CLI and the phone are
provably the same code. Needs Java 17+, `python3` with `yt-dlp`, and `mpv`, `vlc` or `ffplay`.

## Why it is in this repo

Because the alternative is a second copy of the hardest part.

The extraction stack was written pure-JVM-first, so most of it has no Android in it at all:
`:lib:common`, `:lib:ytdlp`, `:lib:innertube`, `:lib:sabr`, `:core:domain` and `:core:data` all
run on a laptop unchanged. What is Android-only is the *engine* — `:lib:ytdlp-chaquopy` embeds
CPython — and the UI.

So the CLI is a **second front end, not a second app**:

| | Android | Command line |
|---|---|---|
| Engine | `:lib:ytdlp-chaquopy` (embedded CPython) | `:lib:ytdlp-process` (system `python3`) |
| Bridge script | `totum_ytdlp.py` | **the same file** |
| Stream picking | `bestAudioFormat` / `bestPlayableFormat` | **the same functions** |
| Playback | Media3 | `mpv` / `vlc` / `ffplay` |

### One Python script, two engines

`totum_ytdlp.py` imports only `json`, `platform` and `yt_dlp` — nothing Chaquopy, nothing
Android — so it runs on a desktop untouched. It gained a `if __name__ == "__main__":` block and
nothing else; Chaquopy imports it as a module and never runs it, so the block is inert there.

It stays in the Chaquopy module (where Android needs it on a specific path) and is **copied into
the CLI's resources by a Gradle task**. Copied, not duplicated: there is one file on disk, and a
test asserts the packaged copy still contains the android-player-client fallback, so the copy
cannot silently stop happening.

### One parser

`BridgeJson` moved from `:lib:ytdlp-chaquopy` to `:lib:ytdlp` when the second engine appeared. It
parses the bridge's JSON contract, and both engines speak that contract, so it belongs to neither
of them.

### One set of rules about which stream to play

This is the part worth having. `bestAudioFormat(wanted)` is the app's own picker, so the
auto-dub avoidance shipped in 0.1.374 applies on the command line for free, and a change to
either is a change to both. That is also why `--watch` picks a different format rather than a
different code path.

## Decisions

- **Playback is delegated.** Decoding audio would be weeks of work for a worse result than mpv
  already gives, and would put this in the business of codecs rather than of finding the right
  stream. `$TOTUM_PLAYER` overrides the whole command line.
- **Audio means audio, even when only a video stream exists.** A 24/7 live stream publishes no
  audio-only format at all, so the picture is suppressed at the *player* whenever `--watch` was
  not asked for. Found by running it: the first version happily decoded video nobody was
  looking at.
- **A single argument that parses as a URL is a URL; anything else is a search.** Nobody types
  `--url`, and pasting a link is the commonest thing anyone will do.
- **Silent by default.** `TOTUM_VERBOSE=1` routes the same `Diag` trail the phone records to
  stderr — the app's own diagnostics, on a terminal.

## What it does NOT do

- **No sign-in.** Age-restricted and members-only content needs the InnerTube account path, and
  the app's `TokenStore` is SharedPreferences; a file-backed one plus its own device-code flow is
  real work and deliberately not in v1.
- **No downloads.** `ProcessYtDlpEngine.download` refuses loudly rather than half-working.
- **No SponsorBlock.** The data source is right there in `:core:data`; acting on it needs player
  control this does not have yet.

## Tests

| Level | Where | What |
|---|---|---|
| unit | `cli/…/CommandParsingTest` | every command, flag and shorthand, exhaustively |
| unit | `cli/…/CliBehaviourTest` | which stream, which player arguments, what is printed, what it exits with |
| unit | `cli/…/PlayerCommandTest` | the exact words handed to mpv / vlc / ffplay |
| unit | `lib/ytdlp-process/…/ProcessYtDlpEngineTest` | arguments, failures, and that the script is packaged |
| live | `lib/ytdlp-process/…/LiveExtractionTest` | real python, real yt-dlp, real YouTube — `RUN_LIVE_EXTRACTION=1` |

The live one is skipped by default: YouTube refuses datacentre addresses, which is the same
reason the app's live tests go through the home tunnel. It was run on this machine and passes.

Verified end to end from the release tarball itself (3.9MB): `totum version` reports the injected
version, `totum "jazz live stream"` found a live stream and played it, and a 19-second video
played to its end and exited 0.

**One environment note**: on WSL the default `mpv` blocks waiting for an audio device. That is
the machine, not the tool — `TOTUM_PLAYER="mpv --ao=pulse"` or running it on a real desktop is
the answer, and it is why the verification above pins `--ao=null` in places.
