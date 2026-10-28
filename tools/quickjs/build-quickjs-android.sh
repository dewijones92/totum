#!/usr/bin/env bash
# Cross-compile the QuickJS interpreter (`qjs`) for Android (arm64-v8a, x86_64) using the
# project's NDK. Output: libqjs.so per ABI — a PIE executable named lib*.so so Android
# extracts it to the executable nativeLibraryDir, exactly as we ship ffmpeg.
#
# VERSION MATTERS: yt-dlp warns that quickjs-ng older than 0.12.0 "are missing important
# optimizations and will solve the JS challenges very slowly" — and it is not exaggerating.
# Built against 0.10.1 first, and a format listing that takes node 2.9s had not finished
# after 500 seconds. Do not downgrade this below 0.12.0.
#
# WHY: yt-dlp has deprecated YouTube extraction without a JavaScript runtime. Without one it
# silently drops formats — a made-for-kids video that YouTube serves at 1080p came back as a
# single 360p stream on Dewi's phone, while the same yt-dlp with `--js-runtimes node` on a
# laptop returned the full ladder. Chaquopy has no JS runtime, so we ship the smallest one
# yt-dlp supports: it looks for quickjs as a binary named `qjs`.
set -euo pipefail

VERSION=v0.17.0
WORK=/home/dewi/code/totum-quickjs-build
SRC="$WORK/quickjs-${VERSION#v}"
NDK=/home/dewi/code/android-sdk/ndk/28.2.13676358
CMAKE=/home/dewi/code/android-sdk/cmake/3.22.1/bin/cmake
API=34
OUT="$WORK/out"
mkdir -p "$OUT"

if [ ! -d "$SRC" ]; then
  mkdir -p "$WORK"; cd "$WORK"
  curl -sL -o qjs.tar.gz "https://github.com/quickjs-ng/quickjs/archive/refs/tags/$VERSION.tar.gz"
  tar xzf qjs.tar.gz
fi

build_abi() {
  local abi="$1"
  echo "===================== building $abi ====================="
  local bdir="$WORK/build-$abi"
  rm -rf "$bdir"
  "$CMAKE" -S "$SRC" -B "$bdir" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DQJS_BUILD_LIBREGEXP=ON
  "$CMAKE" --build "$bdir" --target qjs -j"$(nproc)"
  mkdir -p "$OUT/$abi"
  cp "$bdir/qjs" "$OUT/$abi/libqjs.so"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$OUT/$abi/libqjs.so"
}

build_abi arm64-v8a
build_abi x86_64

echo "===================== DONE ====================="
for abi in arm64-v8a x86_64; do
  f="$OUT/$abi/libqjs.so"
  printf "%-12s %s\n" "$abi" "$(du -h "$f" | cut -f1) $(file "$f" | cut -d, -f1-3)"
done
echo
echo "Install with:  cp \$OUT/<abi>/libqjs.so app/src/main/jniLibs/<abi>/"
