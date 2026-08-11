#!/bin/sh
# Installs the Totum command line tool.
#
#   curl -fsSL https://github.com/dewijones92/totum/releases/latest/download/install.sh | sh
#
# What it does, and nothing else:
#   - downloads the latest totum-cli.tar.gz and verifies its sha256
#   - unpacks it into ~/.local/share/totum-cli
#   - puts a `totum` symlink in ~/.local/bin
#   - runs `totum doctor` and prints the exact command for anything missing
#
# No sudo, no package manager, nothing outside $HOME — so uninstalling is deleting two paths, and
# it can never half-modify a system. POSIX sh rather than bash: this is the first thing that runs
# on a machine and assuming bash is how an installer fails on the one that hasn't got it.
#
# Everything lives in main(), called on the LAST line. Piping a download into a shell runs whatever
# arrived, so a connection dropped halfway would otherwise execute half an installer; this way a
# truncated file defines an incomplete function and runs nothing at all.
set -eu

main() {

  REPO=dewijones92/totum
  ASSET=totum-cli.tar.gz
  PREFIX="${TOTUM_PREFIX:-$HOME/.local}"
  SHARE="$PREFIX/share/totum-cli"
  BIN="$PREFIX/bin"
  BASE="${TOTUM_DOWNLOAD_BASE:-https://github.com/$REPO/releases/latest/download}"

  say() { printf '%s\n' "$*"; }
  die() { printf 'error: %s\n' "$*" >&2; exit 1; }

  need() { command -v "$1" >/dev/null 2>&1; }

  # --- fetch ------------------------------------------------------------------------------------
  tmp=$(mktemp -d)
  # Cleaned up however this exits, including a failed download: a half-unpacked temp directory left
  # behind is the sort of thing that makes the SECOND attempt fail confusingly.
  trap 'rm -rf "$tmp"' EXIT INT TERM

  if need curl; then
    fetch() { curl -fsSL "$1" -o "$2"; }
  elif need wget; then
    fetch() { wget -qO "$2" "$1"; }
  else
    die "needs curl or wget to download anything"
  fi

  say "Downloading $ASSET…"
  fetch "$BASE/$ASSET" "$tmp/$ASSET" || die "could not download $BASE/$ASSET"

  # Verified when the checksum is published and skipped loudly when it is not — a silent skip is
  # indistinguishable from a check that passed.
  if fetch "$BASE/$ASSET.sha256" "$tmp/$ASSET.sha256" 2>/dev/null; then
    expected=$(awk '{print $1}' "$tmp/$ASSET.sha256")
    if need sha256sum; then actual=$(sha256sum "$tmp/$ASSET" | awk '{print $1}')
    elif need shasum; then actual=$(shasum -a 256 "$tmp/$ASSET" | awk '{print $1}')
    else actual=""; say "note: no sha256sum or shasum here, so the download was not verified"
    fi
    if [ -n "$actual" ] && [ "$actual" != "$expected" ]; then
      die "checksum mismatch — expected $expected, got $actual"
    fi
    [ -n "$actual" ] && say "Checksum verified."
  else
    say "note: no published checksum found, so the download was not verified"
  fi

  # --- install ----------------------------------------------------------------------------------
  tar -xzf "$tmp/$ASSET" -C "$tmp" || die "could not unpack $ASSET"
  [ -x "$tmp/totum/bin/totum" ] || die "the archive did not contain totum/bin/totum"

  mkdir -p "$SHARE" "$BIN"
  # Replaced wholesale rather than merged: an upgrade that leaves an old jar behind puts two
  # versions of the same class on the classpath, which fails in ways nobody should have to debug.
  rm -rf "$SHARE"
  mkdir -p "$(dirname "$SHARE")"
  mv "$tmp/totum" "$SHARE"
  ln -sf "$SHARE/bin/totum" "$BIN/totum"
  say "Installed to $SHARE, linked as $BIN/totum"

  # --- check ------------------------------------------------------------------------------------
  case ":$PATH:" in
    *":$BIN:"*) ;;
    *)
      say ""
      say "$BIN is not on your PATH. Add this to your shell profile:"
      say "    export PATH=\"\$PATH:$BIN\""
      ;;
  esac

  if ! need java; then
    say ""
    say "Java 17 or newer is needed and was not found. Install one of:"
    say "    apt install default-jre     # Debian/Ubuntu"
    say "    dnf install java-21-openjdk # Fedora"
    say "    brew install openjdk        # macOS"
    say ""
    say "Then run: totum doctor"
    exit 1
  fi

  say ""
  "$BIN/totum" doctor || {
    say ""
    say "Once those are sorted: totum \"jazz live stream\""
    exit 1
  }
  say ""
  say "Try: totum \"jazz live stream\""
}

main "$@"
