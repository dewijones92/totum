"""The endpoint SmartTube actually streams from: a WEB_EMBEDDED_PLAYER response, then its SABR endpoint.

Captured 2026-09-06 (docs/todos/sabr-stops-at-one-megabyte.md): SmartTube's SABR bytes come from the
embedded player (client 56, thirdParty.embedUrl) with a BotGuard PO token in serviceIntegrityDimensions,
and it appends the streaming token as `pot=` to the videoplayback URL itself. This reproduces that
endpoint. NO_POT=1 asks without any token, to measure the client alone. Same three output lines as
websabr.py / tvsabr.py."""
import base64, json, re, subprocess, sys, urllib.parse, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
CLIENT_VERSION = "2.20260708.00.00"

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "User-Agent": UA, "Referer": "https://www.youtube.com/tv",
                 "X-Youtube-Client-Name": "56", "X-Youtube-Client-Version": CLIENT_VERSION})
    return json.load(urllib.request.urlopen(req, timeout=30))

import os
D = os.environ.get("POT_DIR", ".")
no_pot = os.environ.get("NO_POT") == "1"
visitor_enc = open(os.path.join(D, "visitor.txt")).read().strip() if os.path.exists(os.path.join(D, "visitor.txt")) else None
player_pot = None if no_pot else open(os.path.join(D, "player_pot.txt")).read().strip()
streaming_pot = None if no_pot or not os.path.exists(os.path.join(D, "streaming_pot.txt")) else open(os.path.join(D, "streaming_pot.txt")).read().strip()
video = sys.argv[1] if len(sys.argv) > 1 else "uSMGENDH_QI"

# The signature timestamp comes from the player script, same as the app reads it.
page = urllib.request.urlopen(urllib.request.Request(
    "https://www.youtube.com/iframe_api", headers={"User-Agent": UA}), timeout=30).read().decode()
ver = re.search(r'player\\?/([0-9a-f]{8})\\?/', page) or re.search(r"/s/player/([0-9a-f]{8})/", page)
player_url = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js" % ver.group(1)
js = urllib.request.urlopen(urllib.request.Request(player_url, headers={"User-Agent": UA}), timeout=30).read().decode()
sts = int(re.search(r"signatureTimestamp[=:](\d+)", js).group(1))
print("[embeddedsabr] player=%s sts=%d" % (player_url, sts), file=sys.stderr)

# The embed page's own config carries `encryptedHostFlags`, and the embedded player refuses without it
# ("This video is unavailable") — measured 2026-09-06 against SmartTube's captured request, where it and
# thirdParty.embedUrl were the only two fields that mattered; the PO token was not one of them.
embed_html = urllib.request.urlopen(urllib.request.Request(
    "https://www.youtube.com/embed/%s?html5=1" % video,
    headers={"User-Agent": UA, "Referer": "https://www.reddit.com/"}), timeout=30).read().decode("utf-8", "replace")
m = re.search(r'"encryptedHostFlags"\s*:\s*"([^"]+)"', embed_html)
host_flags = m.group(1) if m else None
print("[embeddedsabr] encryptedHostFlags from the embed page: %s" % (bool(host_flags)), file=sys.stderr)
client = {"clientName": "WEB_EMBEDDED_PLAYER", "clientVersion": CLIENT_VERSION, "clientScreen": "WATCH",
          "userAgent": UA, "browserName": "Chrome", "browserVersion": "124.0.0.0",
          "acceptLanguage": "en-US", "acceptRegion": "US", "utcOffsetMinutes": "60"}
if visitor_enc:
    client["visitorData"] = urllib.parse.unquote(visitor_enc)
body = {"context": {"client": client, "user": {"enableSafetyMode": False, "lockedSafetyMode": False},
                    "thirdParty": {"embedUrl": "https://www.reddit.com/"}},
        "videoId": video, "contentCheckOk": True, "racyCheckOk": True,
        "playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS", "lactMilliseconds": 60000,
                                                       "isInlinePlaybackNoAd": True, "signatureTimestamp": sts,
                                                       **({"encryptedHostFlags": host_flags} if host_flags else {})},
                            "devicePlaybackCapabilities": {"supportsVp9Encoding": True, "supportXhr": True}}}
if player_pot:
    body["serviceIntegrityDimensions"] = {"poToken": player_pot}
print("[embeddedsabr] asking: visitorData=%s playerPot=%s streamingPot=%s" % (bool(visitor_enc), bool(player_pot), bool(streaming_pot)), file=sys.stderr)
resp = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body)
status = "%s %s" % (resp.get("playabilityStatus", {}).get("status"), resp.get("playabilityStatus", {}).get("reason") or "")
sd = resp.get("streamingData", {})
endpoint = sd.get("serverAbrStreamingUrl")
config = resp.get("playerConfig", {}).get("mediaCommonConfig", {}).get(
    "mediaUstreamerRequestConfig", {}).get("videoPlaybackUstreamerConfig")
print("[embeddedsabr] status=%s endpoint=%s config=%s formats=%d" % (
    status, bool(endpoint), bool(config), len(sd.get("adaptiveFormats", []) or [])), file=sys.stderr)
if not endpoint or not config:
    print("[embeddedsabr] no SABR endpoint to work with", file=sys.stderr); sys.exit(1)

n = re.search(r"[?&]n=([^&]+)", endpoint)
print("[embeddedsabr] endpoint carries n=%s" % bool(n), file=sys.stderr)
if n:
    sys.path.insert(0, "/home/dewi/code/totum/lib/ytdlp-chaquopy/src/main/python")
    import yt_dlp
    from yt_dlp.extractor.youtube.jsc.provider import JsChallengeRequest, JsChallengeType, NChallengeInput
    ydl = yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "js_runtimes": {"node": {}}})
    ie = ydl.get_info_extractor("Youtube"); ie.initialize()
    req = JsChallengeRequest(type=JsChallengeType.N,
                             input=NChallengeInput(challenges=[n.group(1)], player_url=player_url))
    solved = {}
    for _r, response in ie._jsc_director.bulk_solve([req]):
        solved.update(response.output.results)
    print("[embeddedsabr] solved %d/1" % len(solved), file=sys.stderr)
    if n.group(1) in solved:
        endpoint = endpoint.replace("n=" + n.group(1), "n=" + solved[n.group(1)])
    else:
        print("[embeddedsabr] COULD NOT SOLVE n", file=sys.stderr); sys.exit(2)

if streaming_pot:
    endpoint += "&pot=" + urllib.parse.quote(streaming_pot, safe="")
print(endpoint)
print(base64.b64encode(base64.urlsafe_b64decode(config + "=" * (-len(config) % 4))).decode())
# The best audio format, so the probe can name a track the server will honour.
audio = [f for f in (sd.get("adaptiveFormats") or []) if str(f.get("mimeType","")).startswith("audio/")]
audio.sort(key=lambda f: f.get("bitrate") or 0, reverse=True)
best = audio[0] if audio else {}
print("%s,%s,%s,%s" % (best.get("itag"), best.get("lastModified"),
                       best.get("xtags") or "", best.get("contentLength") or 0))
