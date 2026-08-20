"""Fetch a WEB player response with attestation, then solve the SABR endpoint's n.
Prints the solved endpoint and the ustreamer config, for the Kotlin probe to use."""
import base64, json, re, subprocess, sys, urllib.parse, urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "User-Agent": UA})
    return json.load(urllib.request.urlopen(req, timeout=30))

import os
D = os.environ.get("POT_DIR", ".")
visitor_enc = open(os.path.join(D, "visitor.txt")).read().strip()
player_pot = open(os.path.join(D, "player_pot.txt")).read().strip()
video = sys.argv[1] if len(sys.argv) > 1 else "uSMGENDH_QI"

# The signature timestamp comes from the player script, same as the app reads it.
page = urllib.request.urlopen(urllib.request.Request(
    "https://www.youtube.com/iframe_api", headers={"User-Agent": UA}), timeout=30).read().decode()
ver = re.search(r'player\\?/([0-9a-f]{8})\\?/', page) or re.search(r"/s/player/([0-9a-f]{8})/", page)
player_url = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js" % ver.group(1)
js = urllib.request.urlopen(urllib.request.Request(player_url, headers={"User-Agent": UA}), timeout=30).read().decode()
sts = int(re.search(r"signatureTimestamp[=:](\d+)", js).group(1))
print("[websabr] player=%s sts=%d" % (player_url, sts), file=sys.stderr)

body = {"context": {"client": {"clientName": "WEB", "clientVersion": "2.20240726.00.00",
                               "visitorData": urllib.parse.unquote(visitor_enc)}},
        "videoId": video, "contentCheckOk": True, "racyCheckOk": True,
        "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": sts}},
        "serviceIntegrityDimensions": {"poToken": player_pot}}
resp = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body)
status = resp.get("playabilityStatus", {}).get("status")
sd = resp.get("streamingData", {})
endpoint = sd.get("serverAbrStreamingUrl")
config = resp.get("playerConfig", {}).get("mediaCommonConfig", {}).get(
    "mediaUstreamerRequestConfig", {}).get("videoPlaybackUstreamerConfig")
print("[websabr] status=%s endpoint=%s config=%s formats=%d" % (
    status, bool(endpoint), bool(config), len(sd.get("adaptiveFormats", []) or [])), file=sys.stderr)
if not endpoint or not config:
    print("[websabr] no SABR endpoint to work with", file=sys.stderr); sys.exit(1)

n = re.search(r"[?&]n=([^&]+)", endpoint)
print("[websabr] endpoint carries n=%s" % bool(n), file=sys.stderr)
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
    print("[websabr] solved %d/1" % len(solved), file=sys.stderr)
    if n.group(1) in solved:
        endpoint = endpoint.replace("n=" + n.group(1), "n=" + solved[n.group(1)])
    else:
        print("[websabr] COULD NOT SOLVE n", file=sys.stderr); sys.exit(2)

print(endpoint)
print(base64.b64encode(base64.urlsafe_b64decode(config + "=" * (-len(config) % 4))).decode())
# The best audio format, so the probe can name a track the server will honour.
audio = [f for f in (sd.get("adaptiveFormats") or []) if str(f.get("mimeType","")).startswith("audio/")]
audio.sort(key=lambda f: f.get("bitrate") or 0, reverse=True)
best = audio[0] if audio else {}
print("%s,%s,%s,%s" % (best.get("itag"), best.get("lastModified"),
                       best.get("xtags") or "", best.get("contentLength") or 0))
