#!/bin/bash
# Restores the emulator's YouTube sign-in from the off-device backup.
#
# Exists because a sign-in is the one thing on this emulator that cannot be rebuilt without a
# human: the device-code flow needs Dewi to type a code at google.com/device, the automation
# browser profile is signed out, and a code is single-use and short-lived. The token itself is
# perishable in a way the rest of the app data is not -- `./gradlew connectedAndroidTest`
# uninstalls the app when it finishes, `pm clear` wipes it, and an uncleanly killed emulator
# comes back on an older snapshot without it. It was lost that way twice in two days.
#
# The refresh token is long-lived, so restoring this file resurrects the sign-in even months
# later: the app exchanges it for a fresh access token on the next call. That turns a nine-minute
# human-in-the-loop into a two-second copy.
#
# The backup lives outside the repo on purpose -- it is a real OAuth credential.
set -euo pipefail
export PATH=$PATH:/home/dewi/code/android-sdk/platform-tools

BACKUP=${BACKUP:-$HOME/.credentials/totum-emulator/youtube_account.xml}
DEV=${ANDROID_SERIAL:-emulator-5554}
PKG=com.dewijones92.totum

[ -f "$BACKUP" ] || { echo "no backup at $BACKUP — the sign-in has to be redone by hand"; exit 1; }
adb -s "$DEV" shell pm list packages | grep -q "^package:$PKG$" || {
  echo "$PKG is not installed on $DEV — install it first, or the prefs dir does not exist"; exit 1; }

adb -s "$DEV" push "$BACKUP" /data/local/tmp/ya.xml > /dev/null
adb -s "$DEV" shell "run-as $PKG sh -c 'cat /data/local/tmp/ya.xml > shared_prefs/youtube_account.xml'"
adb -s "$DEV" shell rm -f /data/local/tmp/ya.xml

# Confirm by what is actually on the device, not by this script having exited 0: a run-as
# redirection can fail silently and leave a zero-byte file, which reads as signed out.
LANDED=$(adb -s "$DEV" shell "run-as $PKG stat -c%s shared_prefs/youtube_account.xml" | tr -d '\r')
EXPECTED=$(stat -c%s "$BACKUP")
[ "$LANDED" = "$EXPECTED" ] || { echo "restore FAILED: $LANDED bytes on device vs $EXPECTED expected"; exit 1; }
echo "restored $LANDED bytes to $PKG on $DEV — force-stop the app so it re-reads the prefs"
adb -s "$DEV" shell am force-stop $PKG
