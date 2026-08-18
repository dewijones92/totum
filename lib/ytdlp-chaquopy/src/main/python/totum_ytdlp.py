"""Bridge between ChaquopyYtDlpEngine (Kotlin) and yt-dlp.

Contract: every function returns a JSON string; expected failures are values
({"ok": false, "kind": ...}), never exceptions. Keeping the boundary
string-typed avoids leaking PyObject lifetimes into Kotlin.
"""
import json
import platform

import yt_dlp


def versions():
    return json.dumps({
        "yt_dlp": yt_dlp.version.__version__,
        "python": platform.python_version(),
    })


# Ask the ANDROID player client as well as yt-dlp's defaults.
#
# Made-for-kids videos — Ms Rachel and everything like her — serve NO playable streams to
# any default client. Measured against this exact yt-dlp (2026.07.04) on 2026-07-30: tv,
# tv_embedded, web, web_safari, mweb, ios, android_vr and web_embedded each returned zero
# audio/video formats (the web client returns only storyboards, and tv reports "DRM
# protected"), so the app said "This video is not available" for every one of them. The
# android client returns format 18 — 360p progressive H.264+AAC — which plays.
#
# APPENDED to the defaults rather than replacing them, verified both ways: a kids video
# resolves at 360p where it previously failed outright, and an ordinary video still offers
# its full ladder up to 1080p+.
#
# 360p is NOT the quality YouTube has for this content — it is the best yt-dlp can currently
# REACH. The higher formats are present in the player response but arrive with no URL:
# YouTube serves them SABR-only (yt-dlp issue #12482), and yt-dlp does not speak that
# protocol, so it drops them. Confirmed here: cookies from a signed-in browser do not
# restore the URLs, nor does `formats=missing_pot`, and the bundled yt-dlp is already the
# newest on PyPI. SmartTube plays the same videos at full quality because it implements
# YouTube's own streaming path rather than extracting plain URLs — which is a project, not
# a flag. Until then this is a real ceiling on kids content and nothing else.
# web_embedded added 2026-08-18, and it is the whole reason anything streams.
#
# YouTube began serving roughly the first megabyte of a stream and refusing the rest, so the app could
# play nothing it had not already downloaded. Measured from Dewi's own connection, on fresh URLs: the
# only client in `default` for this yt-dlp is `android_vr`, whose URLs carry no `n` and answer 403 at
# any offset past ~1MB. `web_embedded` returns `c=WEB_EMBEDDED_PLAYER` with a DECIPHERED `n`, and those
# served the middle of the file on 2 of the 3 videos tried.
#
# It costs nothing new: solving `n` needs a JavaScript runtime, and this app already runs QuickJS for
# extraction on purpose (see the note below). The durable URLs were available all along and simply
# never asked for.
#
# `MediaMetadata.isDurable` is what makes it count — requesting the client is not enough, because
# yt-dlp's own ranking still prefers `android_vr`'s audio on size and language, both of which it wins
# while being unfetchable.
#
# A PARTIAL fix. One of those three videos was refused even here; the full answer is a PO token. See
# docs/todos/youtube-requires-attestation.md.
PLAYER_CLIENTS = {"youtube": {"player_client": ["default", "android", "web_embedded"]}}

# Path to the bundled QuickJS interpreter, set once from Kotlin (see configure_js_runtime).
#
# yt-dlp has deprecated YouTube extraction without a JavaScript runtime and silently drops
# formats without one — a 1080p made-for-kids video came back as a single 360p stream on
# Dewi's phone. Its default runtime is deno, which is not something you ship to a phone at
# ~100MB; quickjs is the smallest of the four it supports, at about a megabyte.
_JS_RUNTIME_PATH = None


def configure_js_runtime(qjs_path):
    """Point yt-dlp at the bundled `qjs`. Called once at engine construction."""
    global _JS_RUNTIME_PATH
    _JS_RUNTIME_PATH = qjs_path


def _js_runtimes():
    """yt-dlp's `js_runtimes` param: {runtime: {config}}, or none enabled at all.

    An EMPTY dict rather than the default when we have no binary: leaving the default in
    place makes yt-dlp hunt for a `deno` that cannot exist on Android and warn about it on
    every single extraction.
    """
    if not _JS_RUNTIME_PATH:
        return {}
    return {"quickjs": {"path": _JS_RUNTIME_PATH}}


# ON FOR EXTRACTION TOO, deliberately — and this comment used to say the opposite, which is
# why it is worth spelling out.
#
# It was once downloads-only, to save what QuickJS costs: a normal video's extraction goes from
# ~1.7s to ~6s with the runtime, and a challenged one to 25s, every time (yt-dlp does not cache
# the result across runs).
#
# That was wrong. WITHOUT the runtime yt-dlp hands back URLs that do not PLAY — the working
# laptop URL says it plainly, `c=WEB_EMBEDDED_PLAYER … &n=…`, a stream with a deciphered `n`.
# Turning the runtime off downgraded extraction to clients whose URLs 403, which is why a whole
# InnerTube fallback got built around what was really a missing JS runtime (commit 5ca1789).
#
# So the cost is paid on purpose, and the prefetcher is what pays it back: it resolves the next
# queue item 45 seconds ahead, so in normal listening the wait lands only on the first
# deliberate tap.
#
# DO NOT "optimise" this away without checking that videos PLAY, not merely resolve.
#
# And do not try to make a download reuse the extraction a play already did. Measured
# 2026-07-31 against this exact yt-dlp: passing an extracted info dict to
# `process_ie_result(info, download=True)` — the same entry point `--load-info-json` uses —
# fails with HTTP 403, both sanitized and raw, while a fresh `extract_info(download=True)` with
# identical options succeeds in 1.7s. YouTube's format URLs do not survive being handed to a
# second YoutubeDL, which is exactly why yt-dlp re-extracts inside a download. The duplicate
# extraction per queued play is therefore inherent; InteractiveFirstEngine orders the two so
# playback never waits behind one, and that is as far as it goes.


def extract(url):
    # No watch-progress tracking is captured here, deliberately. yt-dlp runs
    # unauthenticated, so the tracking URLs in its player response address an
    # anonymous session: pinging them returns 204 and credits nobody. Measured
    # 2026-07-31 — a full playback left the account's history byte-identical. The
    # app now fetches its own via an authenticated InnerTube call instead.
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extractor_args": PLAYER_CLIENTS,
        "js_runtimes": _js_runtimes(),
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.sanitize_info(ydl.extract_info(url, download=False))
            return json.dumps({"ok": True, "info": info})
    except yt_dlp.utils.DownloadError as e:
        return json.dumps({"ok": False, "kind": _classify(e), "detail": str(e)})


# The solver, built ONCE. Rebuilding it per call threw away yt-dlp's own cache of the
# preprocessed player, so every solve re-parsed a 2.9MB script: measured 16.6s on an emulator
# for a single parameter, paid again on the next video. Held here so the second solve of a
# session is nearly free.
_N_SOLVER = None


def _n_solver():
    global _N_SOLVER
    if _N_SOLVER is None:
        from yt_dlp.extractor.youtube import YoutubeIE

        ydl = yt_dlp.YoutubeDL(
            {"quiet": True, "no_warnings": True, "js_runtimes": _js_runtimes()}
        )
        extractor = YoutubeIE()
        extractor.set_downloader(ydl)
        extractor.initialize()
        _N_SOLVER = extractor
    return _N_SOLVER


def solve_n(challenges, player_url):
    """Deobfuscate YouTube `n` throttling parameters using the bundled QuickJS.

    Exists so the app can play AGE-RESTRICTED videos, which yt-dlp cannot reach at all: it
    has no credentials and its own advice is "use --cookies". The app does have an account,
    and a signed-in InnerTube call as the downgraded TV client returns those videos with
    plain URLs (see docs/todos/age-restricted-videos.md). Those URLs carry a raw `n` and 403
    until it is transformed, and transforming it needs a JavaScript engine — which this
    module already configures for yt-dlp's own use.

    So this borrows the engine rather than adding one. yt-dlp's solver parses the player and
    regenerates code rather than pattern-matching it, which is why it still works where
    NewPipe's regexes have stopped (measured 2026-08-01: NewPipe returns the parameter
    unchanged and the URL 403s).

    Returns a JSON map of obfuscated -> deobfuscated. Unsolved parameters are simply absent,
    so the caller can leave those formats alone rather than play a URL that will 403.

    This reaches into yt-dlp's `jsc` internals, which are not a public API, and yt-dlp
    self-updates on every launch. Hence the broad except and the explicit `ok` flag: a wheel
    that moves this machinery must degrade to "age-restricted videos stopped working" and
    say so, never to a crash on the playback path.
    """
    if not _JS_RUNTIME_PATH:
        return json.dumps({"ok": False, "detail": "no JavaScript runtime bundled"})
    try:
        from yt_dlp.extractor.youtube.jsc.provider import (
            JsChallengeRequest,
            JsChallengeType,
            NChallengeInput,
        )

        extractor = _n_solver()
        request = JsChallengeRequest(
            type=JsChallengeType.N,
            input=NChallengeInput(challenges=list(challenges), player_url=player_url),
        )
        solved = {}
        for _request, response in extractor._jsc_director.bulk_solve([request]):
            solved.update(response.output.results)
        return json.dumps({"ok": True, "solved": solved})
    except Exception as e:  # noqa: BLE001 - see docstring: never crash playback
        return json.dumps({"ok": False, "detail": "{}: {}".format(type(e).__name__, e)})


def search(query, max_results):
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": "in_playlist",
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.sanitize_info(
                ydl.extract_info(f"ytsearch{int(max_results)}:{query}", download=False)
            )
            entries = [
                {
                    "id": entry.get("id"),
                    "title": entry.get("title"),
                    "uploader": entry.get("uploader") or entry.get("channel"),
                    "duration": entry.get("duration"),
                    "view_count": entry.get("view_count"),
                    "members_only": _members_only(entry),
                    "url": entry.get("url") or entry.get("webpage_url"),
                    "thumbnail": _first_thumbnail(entry),
                }
                for entry in (info.get("entries") or [])
            ]
            return json.dumps({"ok": True, "entries": entries})
    except yt_dlp.utils.DownloadError as e:
        return json.dumps({"ok": False, "kind": _classify(e), "detail": str(e)})


def _members_only(entry):
    """Behind a channel membership. yt-dlp says so in availability, and — for a flat
    extraction, which does not fetch the player — sometimes only in the availability
    string. Either way the caller wants to know BEFORE trying to play it."""
    return entry.get("availability") in ("subscriber_only", "premium_only")


def _first_thumbnail(entry):
    thumbnails = entry.get("thumbnails") or []
    return thumbnails[-1].get("url") if thumbnails else entry.get("thumbnail")


def _uploads_tab_url(url):
    # A bare channel URL resolves to its tab list (Videos/Shorts/Live); target the
    # uploads tab directly so we get individual videos, not one entry per tab.
    trimmed = url.rstrip("/")
    if trimmed.endswith(("/videos", "/streams", "/shorts", "/featured")):
        return trimmed
    return trimmed + "/videos"


def channel(url, max_videos):
    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "extract_flat": "in_playlist",
        "playlist_items": f"1:{int(max_videos)}",
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.sanitize_info(ydl.extract_info(_uploads_tab_url(url), download=False))
            if not info.get("entries"):
                return json.dumps({"ok": False, "kind": "not_channel", "detail": "No uploads found"})
            videos = [
                {
                    "id": entry.get("id"),
                    "title": entry.get("title"),
                    "uploader": entry.get("uploader") or info.get("channel") or info.get("uploader"),
                    "duration": entry.get("duration"),
                    "view_count": entry.get("view_count"),
                    "members_only": _members_only(entry),
                    "url": entry.get("url") or entry.get("webpage_url"),
                    "thumbnail": _first_thumbnail(entry),
                }
                for entry in info["entries"]
            ]
            return json.dumps({
                "ok": True,
                "channel_id": info.get("channel_id") or info.get("id"),
                "title": info.get("channel") or info.get("title") or "Channel",
                "videos": videos,
            })
    except yt_dlp.utils.DownloadError as e:
        kind = "network" if isinstance(
            (e.exc_info[1] if e.exc_info else e), (OSError, yt_dlp.utils.network_exceptions),
        ) else "not_channel"
        return json.dumps({"ok": False, "kind": kind, "detail": str(e)})


def download(url, target_dir, format_id, listener, ffmpeg_location, sponsorblock_categories):
    def hook(d):
        if d.get("status") == "downloading":
            listener.onProgress(
                int(d.get("downloaded_bytes") or 0),
                int(d.get("total_bytes") or d.get("total_bytes_estimate") or 0),
                int(d.get("eta") or 0),
            )

    options = {
        "quiet": True,
        "no_warnings": True,
        "outtmpl": target_dir + "/%(id)s.%(ext)s",
        "progress_hooks": [hook],
        # Fetch everything in Python, never through ffmpeg.
        #
        # Our bundled ffmpeg is built --disable-network, so it has NO http/https protocol.
        # Proven on-device: an https input gives "Protocol not found", and its protocol list
        # holds only file/pipe/hls/concat. yt-dlp reaches for its ffmpeg *downloader* for
        # m3u8 and live formats, which then fails with the singularly unhelpful "ffmpeg
        # exited with code 8" — the unexplained download failure in a real report (Novara
        # Media, 0.1.201). "native" tells yt-dlp to use no external downloader at all, which
        # covers non-live HLS and live DASH; ffmpeg is then only ever asked to merge local
        # files, which is exactly what a remux-only build is for.
        #
        # LIVE HLS is still impossible: get_suitable_downloader returns FFmpegFD for a live
        # m3u8 before it ever looks at this setting. Recording a live stream is not something
        # this build can do, and the failure is classified permanent so it stops retrying.
        "external_downloader": "native",
        "extractor_args": PLAYER_CLIENTS,
        "js_runtimes": _js_runtimes(),
    }
    if format_id:
        options["format"] = format_id
    # A directory holding an executable named `ffmpeg`; lets yt-dlp merge
    # separate video+audio streams and cut SponsorBlock segments.
    if ffmpeg_location:
        options["ffmpeg_location"] = ffmpeg_location
    # `sponsorblock_categories` arrives as a comma-separated string from Kotlin
    # (Chaquopy marshals primitives, not sets).
    categories = [c for c in (sponsorblock_categories or "").split(",") if c]
    if categories:
        options["sponsorblock_remove"] = set(categories)
        options["postprocessors"] = [
            {"key": "SponsorBlock", "categories": set(categories), "when": "after_filter"},
            {"key": "ModifyChapters", "remove_sponsor_segments": set(categories)},
        ]
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.sanitize_info(ydl.extract_info(url, download=True))
            requested = info.get("requested_downloads") or [{}]
            path = requested[0].get("filepath")
            return json.dumps({"ok": True, "filepath": path})
    except yt_dlp.utils.DownloadError as e:
        return json.dumps({"ok": False, "kind": _classify(e), "detail": str(e)})


def _classify(error):
    cause = error.exc_info[1] if error.exc_info else error
    if isinstance(cause, yt_dlp.utils.UnsupportedError):
        return "unsupported"
    if isinstance(cause, (OSError, yt_dlp.utils.network_exceptions)):
        return "network"
    return "extractor"


# --- command line -------------------------------------------------------------------------
#
# The SAME functions, reachable as a subprocess, so the desktop engine (:lib:ytdlp-process)
# extracts exactly what the phone does — the android-client fallback, the JS-runtime handling
# and the format shaping included. A second implementation of any of that would drift within
# a week; this one cannot, because there is only one.
#
# Chaquopy imports this file as a module and never runs it, so the block is inert on Android.

def _main(argv):
    if not argv:
        return json.dumps({"ok": False, "kind": "extractor", "detail": "no command given"})
    command, args = argv[0], argv[1:]
    if command == "versions":
        return versions()
    if command == "extract":
        return extract(args[0])
    if command == "search":
        return search(args[0], int(args[1]))
    if command == "channel":
        return channel(args[0], int(args[1]))
    return json.dumps({"ok": False, "kind": "extractor", "detail": "unknown command " + command})


if __name__ == "__main__":
    import sys

    # Written to stdout as one line; yt-dlp's own chatter goes to stderr and is ignored, which
    # is why the caller must not merge the two streams.
    sys.stdout.write(_main(sys.argv[1:]))
