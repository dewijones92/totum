"""A TV player response with attestation, then its SABR endpoint.

Exists because eighteen request-body variations could not move a sixty-second ceiling, and the one
axis never varied is the CLIENT. SmartTube streams whole videos over SABR and it is a TV client
carrying its own PO token; every endpoint tried here has come from a WEB or ANDROID response.

Prints the endpoint, the ustreamer config, and the best audio format, for the Kotlin probe to use.
"""
import base64, json, os, re, sys, urllib.parse, urllib.request

UA = ("Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), "
      "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)")
D = os.environ.get("POT_DIR", ".")
video = sys.argv[1] if len(sys.argv) > 1 else "uSMGENDH_QI"
player_pot = open(os.path.join(D, "player_pot.txt")).read().strip()
visitor = open(os.path.join(D, "visitor.txt")).read().strip()

# The signature timestamp comes from the player script, same as the app reads it.
iframe = urllib.request.urlopen(urllib.request.Request(
    "https://www.youtube.com/iframe_api", headers={"User-Agent": UA}), timeout=30).read().decode()
# The iframe API is fetched with a browser UA: a Cobalt UA gets a page with no player id in it.
iframe = urllib.request.urlopen(urllib.request.Request(
    "https://www.youtube.com/iframe_api",
    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                           "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"}), timeout=30).read().decode()
ver = re.search(r"player\\?/([0-9a-f]{8})\\?/", iframe) or re.search(r"/s/player/([0-9a-f]{8})/", iframe)
player_url = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js" % ver.group(1)
js = urllib.request.urlopen(urllib.request.Request(player_url, headers={"User-Agent": UA}), timeout=30).read().decode()
sts = int(re.search(r"signatureTimestamp[=:](\d+)", js).group(1))

body = {
    "context": {"client": {
        "clientName": "TVHTML5",
        "clientVersion": "7.20260114.12.00",
        "hl": "en",
        "visitorData": urllib.parse.unquote(visitor),
    }},
    "videoId": video,
    "contentCheckOk": True,
    "racyCheckOk": True,
    "playbackContext": {"contentPlaybackContext": {"signatureTimestamp": sts}},
    "serviceIntegrityDimensions": {"poToken": player_pot},
}
req = urllib.request.Request(
    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    data=json.dumps(body).encode(),
    headers={"Content-Type": "application/json", "User-Agent": UA})
resp = json.load(urllib.request.urlopen(req, timeout=30))

status = resp.get("playabilityStatus", {})
sd = resp.get("streamingData", {})
endpoint = sd.get("serverAbrStreamingUrl")
config = resp.get("playerConfig", {}).get("mediaCommonConfig", {}).get(
    "mediaUstreamerRequestConfig", {}).get("videoPlaybackUstreamerConfig")
print("[tvsabr] status=%s reason=%s endpoint=%s config=%s formats=%d" % (
    status.get("status"), status.get("reason"), bool(endpoint), bool(config),
    len(sd.get("adaptiveFormats", []) or [])), file=sys.stderr)
if not endpoint or not config:
    sys.exit(1)

n = re.search(r"[?&]n=([^&]+)", endpoint)
print("[tvsabr] endpoint carries n=%s" % bool(n), file=sys.stderr)
if n:
    sys.path.insert(0, "/home/dewi/code/totum/lib/ytdlp-chaquopy/src/main/python")
    import yt_dlp
    from yt_dlp.extractor.youtube.jsc.provider import JsChallengeRequest, JsChallengeType, NChallengeInput
    ydl = yt_dlp.YoutubeDL({"quiet": True, "no_warnings": True, "js_runtimes": {"node": {}}})
    ie = ydl.get_info_extractor("Youtube"); ie.initialize()
    r = JsChallengeRequest(type=JsChallengeType.N,
                          input=NChallengeInput(challenges=[n.group(1)], player_url=player_url))
    solved = {}
    for _q, response in ie._jsc_director.bulk_solve([r]):
        solved.update(response.output.results)
    if n.group(1) not in solved:
        print("[tvsabr] could not solve n", file=sys.stderr); sys.exit(2)
    endpoint = endpoint.replace("n=" + n.group(1), "n=" + solved[n.group(1)])

print(endpoint)
print(base64.b64encode(base64.urlsafe_b64decode(config + "=" * (-len(config) % 4))).decode())
audio = [f for f in (sd.get("adaptiveFormats") or []) if str(f.get("mimeType", "")).startswith("audio/")]
audio.sort(key=lambda f: f.get("bitrate") or 0, reverse=True)
b = audio[0] if audio else {}
print("%s,%s,%s,%s" % (b.get("itag"), b.get("lastModified"), b.get("xtags") or "", b.get("contentLength") or 0))
