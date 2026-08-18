import json, urllib.request
VID="aqz-KE-bpKQ"  # Big Buck Bunny 4K60, copyright-free
def player(name, ver, cid, extra_headers=None, ctx_extra=""):
    body={"context":{"client":{"clientName":name,"clientVersion":ver,"hl":"en","gl":"GB"}},
          "videoId":VID,"contentCheckOk":True,"racyCheckOk":True}
    h={"Content-Type":"application/json","X-Youtube-Client-Name":str(cid),"X-Youtube-Client-Version":ver}
    if extra_headers: h.update(extra_headers)
    r=urllib.request.Request("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        data=json.dumps(body).encode(), headers=h)
    try:
        d=json.load(urllib.request.urlopen(r, timeout=25))
    except Exception as e:
        print(f"{name} {ver}: ERROR {e}"); return
    sd=d.get("streamingData",{})
    fmts=sd.get("adaptiveFormats",[])
    abr = "YES" if sd.get("serverAbrStreamingUrl") else "no"
    ust = "YES" if d.get("playerConfig",{}).get("mediaCommonConfig",{}).get("mediaUstreamerRequestConfig",{}).get("videoPlaybackUstreamerConfig") else "no"
    withurl=sum(1 for f in fmts if f.get("url"))
    sixty=[f for f in fmts if (f.get("fps") or 0)>30]
    tall=[f for f in fmts if (f.get("height") or 0)>1080]
    print(f"{name} {ver}: status={d.get('playabilityStatus',{}).get('status')} formats={len(fmts)} withUrl={withurl} sabrUrl={abr} ustreamer={ust} 60fps={len(sixty)} >1080p={len(tall)} maxH={max([f.get('height') or 0 for f in fmts]+[0])}")
    if sixty: print("   60fps itags:", sorted({f['itag'] for f in sixty}))

player("ANDROID","20.10.38",3)
player("TVHTML5","7.20240401.10.00",7)
player("TVHTML5","7.20250312.16.00",7)
