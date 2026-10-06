# Kept because Python calls this back BY NAME.
#
# `totum_ytdlp.py` invokes `listener.onProgress(...)` on a Kotlin object handed across
# Chaquopy's Java proxying. Nothing in Kotlin references the interface or its method
# reflectively, so R8 has no way to infer that the name is load-bearing: in a release build
# it renamed the anonymous implementation to `b` and the call died with
#
#   com.chaquo.python.PyException: AttributeError: 'b' object has no attribute 'onProgress'
#
# A release-only failure — debug builds aren't minified, so no amount of debug testing finds
# it. Caught on Dewi's Pixel 7 (0.1.142) by the crash reporter, seconds into a video download.
#
# These live in the LIBRARY's consumer rules, not the app's, because the constraint belongs to
# this module: any app that consumes it needs the rule, and none of them can be expected to
# know that.
-keep interface com.dewijones92.totum.ytdlp.chaquopy.ProgressListener { *; }
-keep class * implements com.dewijones92.totum.ytdlp.chaquopy.ProgressListener { *; }
-keep interface com.dewijones92.totum.ytdlp.chaquopy.JsChallengeRuntime { *; }
-keep class * implements com.dewijones92.totum.ytdlp.chaquopy.JsChallengeRuntime { *; }
