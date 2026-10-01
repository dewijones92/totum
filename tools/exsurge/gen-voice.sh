#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
VOICE="${EXSURGE_VOICE:-it-IT-DiegoNeural}"
OUT=app/src/main/res/raw
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

clip() {
  local name="$1" rate="$2" pitch="$3" text="$4"
  edge-tts --voice "$VOICE" --rate="$rate" --pitch="$pitch" --text "$text" --write-media "$TMP/$name.mp3" >/dev/null
  ffmpeg -hide_banner -loglevel error -y -i "$TMP/$name.mp3" -ac 1 -ar 48000 -c:a libopus -b:a 32k "$OUT/exsurge_$name.ogg"
  printf '%-16s %6s bytes  %s\n' "$name" "$(wc -c <"$OUT/exsurge_$name.ogg")" "$text"
}

clip summon         "-10%" "-12Hz" "Exsurge, Dewi."
clip summon_louder  "+0%"  "-6Hz"  "Exsurge! Exsurge!"
clip summon_oration "-5%"  "-10Hz" "Quo usque tandem, Dewi, sella abutere? Exsurge!"
clip go             "-5%"  "-8Hz"  "Alea iacta est!"
clip risen          "+5%"  "-4Hz"  "Bene! Ambula, disce!"
clip two_minutes    "-10%" "-10Hz" "Duo minuta restant."
clip free_1         "-5%"  "-6Hz"  "Satis! Liber es!"
clip free_2         "-5%"  "-6Hz"  "Veni, vidi, didici! Liber es!"
clip skipped        "-20%" "-14Hz" "Et tu, Dewi?"
clip promoted       "-5%"  "-6Hz"  "Salve! Gradum ascendisti!"
