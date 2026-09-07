"""An ANDROID player response's SABR endpoint, ustreamer config and best audio, for the Kotlin probe.

Same three output lines as tvsabr.py. ANDROID URLs carry no `n`, so nothing needs solving; it is the
endpoint every ceiling measurement in docs/todos/po-token-minting.md was made on, which makes it the
control arm for any request-shape experiment.
"""
import base64, json, sys, urllib.request

video = sys.argv[1] if len(sys.argv) > 1 else "uSMGENDH_QI"
body = {"context": {"client": {"clientName": "ANDROID", "clientVersion": "20.10.38", "androidSdkVersion": 34, "hl": "en"}},
        "videoId": video, "contentCheckOk": True, "racyCheckOk": True}
req = urllib.request.Request("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json",
                                      "User-Agent": "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"})
resp = json.load(urllib.request.urlopen(req, timeout=30))
status = resp.get("playabilityStatus", {})
sd = resp.get("streamingData", {})
endpoint = sd.get("serverAbrStreamingUrl")
config = resp.get("playerConfig", {}).get("mediaCommonConfig", {}).get("mediaUstreamerRequestConfig", {}).get("videoPlaybackUstreamerConfig")
print("[androidsabr] status=%s endpoint=%s config=%s formats=%d" % (status.get("status"), bool(endpoint), bool(config), len(sd.get("adaptiveFormats") or [])), file=sys.stderr)
if not endpoint or not config:
    sys.exit(1)
print(endpoint)
print(base64.b64encode(base64.urlsafe_b64decode(config + "=" * (-len(config) % 4))).decode())
audio = sorted([f for f in sd.get("adaptiveFormats") or [] if str(f.get("mimeType", "")).startswith("audio/")], key=lambda f: f.get("bitrate") or 0, reverse=True)
b = audio[0] if audio else {}
print("%s,%s,%s,%s" % (b.get("itag"), b.get("lastModified"), b.get("xtags") or "", b.get("contentLength") or 0))
