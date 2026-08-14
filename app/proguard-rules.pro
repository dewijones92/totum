# App-specific R8 keep rules. Library rules (Room, Media3, Chaquopy, Compose)
# ship as consumer rules inside their artifacts and apply automatically.
# Keep this file minimal: prefer fixing reflection at the source over keeps.

# Keep crash reports readable.
#
# Your Pixel's R8 download crash came back with frames like `j0.z0.f` and
# `hf.d.b(r8-map-id-0864bb…)` — the exception was diagnosable only because it originated in
# Python, whose frames R8 never touched. A crash in our own Kotlin would have been a wall of
# one-letter names.
#
# These keep the source file and line numbers so a trace can be retraced against the build's
# mapping.txt (R8 already stamps the map id into the trace for exactly that purpose). The
# rename keeps the file name itself obfuscated, which is the standard trade.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the NAMES of exception classes, for the same reason and a worse one.
#
# Report 0.1.383 carried `wv1: Response code: 403` and `ob1: Source error` — those are Media3's
# InvalidResponseCodeException and ExoPlaybackException, and the trail was readable only because
# their messages happened to say enough. Three places print an exception's class name: Diag's
# error suffix, `playback.lastLoadError`, and the crash report's `exception` / `causeException`.
#
# The last of those is the serious one. The crashlog server's web index **groups by exception**,
# and an R8 name is only stable within one build — so the same fault reported from two versions
# lands in two groups with names that mean nothing, which is precisely the "group by version
# before counting" trap wearing a different hat.
#
# Names only, not members: nothing here is about reflection, so the size cost is a string table.
-keepnames class * extends java.lang.Throwable
