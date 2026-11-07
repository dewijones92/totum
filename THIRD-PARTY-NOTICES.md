# Third-party notices

Totum is released under the [GNU General Public License, version 3](LICENSE). It bundles the programs below, each
under its own licence. The source of Totum, and the scripts that build the binaries marked "built from source here",
is this repository.

| Component | Where it is in the app | Licence | Source |
|---|---|---|---|
| FFmpeg 7.1.1 and FFprobe | `libffmpeg.so`, `libffprobe.so` in `app/src/main/jniLibs`, run as processes to merge and repackage media | LGPL 2.1 or later: configured without `--enable-gpl` and without `--enable-nonfree`, with every decoder and encoder disabled (`--disable-decoders --disable-encoders`) | <https://ffmpeg.org/releases/>; built from source here by `tools/ffmpeg/build-ffmpeg-android.sh` |
| QuickJS-ng (`qjs`) | `libqjs.so` in `app/src/main/jniLibs`, the JavaScript runtime yt-dlp's challenge solver falls back to | MIT | <https://github.com/quickjs-ng/quickjs>; built from source here by `tools/quickjs/build-quickjs-android.sh` |
| Chaquopy 17 | embeds CPython 3.12 so yt-dlp runs inside the app | MIT (the Python runtime it embeds is under the Python Software Foundation licence) | <https://chaquo.com/chaquopy/> |
| yt-dlp | installed by Chaquopy; a newer wheel from PyPI is fetched and checked at launch | The Unlicense | <https://github.com/yt-dlp/yt-dlp> |
| yt-dlp-ejs | the challenge-solver scripts yt-dlp loads | The Unlicense, bundling third-party JavaScript under its own licences listed in that project | <https://github.com/yt-dlp/ejs> |

The Android libraries Totum is built with (Jetpack Compose, Media3, Room, OkHttp, Coil, kotlinx and the rest) are
declared in `gradle/libs.versions.toml` and are released under the Apache License 2.0 or a similarly permissive licence;
check a library's own page for the exact text before relying on this summary.

If a notice above is wrong or missing, please open an issue.
