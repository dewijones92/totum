# Bundled ffmpeg (Android)

`app/src/main/jniLibs/<abi>/libffmpeg.so` is a **statically linked ffmpeg
executable** (not a shared library), named `lib*.so` so Android's installer
extracts it to the app's `nativeLibraryDir`, the one app-private location that
stays executable under Android's W^X policy (minSdk 33). yt-dlp is pointed
at it via `ffmpeg_location` (see `ChaquopyYtDlpEngine` / `FfmpegBinary`).

## Why bundled

yt-dlp needs ffmpeg to **merge** separate best-quality video+audio streams
(YouTube serves 1080p+ as DASH) and to **remove SponsorBlock segments** from
downloads. PyPI has no `aarch64-linux-android` ffmpeg wheel, so Chaquopy can't
`pip` it — the binary must be built and shipped.

## Provenance

- Source: **FFmpeg 7.1.1**, `https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz`,
  GPG-verified against the FFmpeg release signing key
  (`FCF986EA 15E6E293 A5644F10 B4322F04 D67658D8`).
- Toolchain: Android NDK 28.2.13676358 clang, API 34.
- Built by `build-ffmpeg-android.sh` (adjust the hard-coded `WORK`/`NDK` paths
  for another machine).

## Build config (remux-only, deliberately minimal)

`--disable-decoders --disable-encoders` — yt-dlp merges and cuts with `-c copy`,
so no codecs are needed; demuxers, muxers, parsers, bitstream filters and the
file protocol are kept so every real YouTube container/codec can be remuxed.
`--disable-ffprobe --disable-network --disable-doc --enable-small` trim the
rest. Result: ~6.9 MB (arm64), ~7.6 MB (x86_64), vs ~27 MB for a stock build
with ffprobe. ffprobe is not shipped; yt-dlp degrades gracefully without it.

To rebuild: run `build-ffmpeg-android.sh`, then copy
`out/<abi>/bin/ffmpeg` → `app/src/main/jniLibs/<abi>/libffmpeg.so`.
