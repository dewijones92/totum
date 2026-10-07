#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
import sys
import time

PACKAGE = "com.dewijones92.totum"
ADB = os.environ.get("ADB", "/home/dewi/code/android-sdk/platform-tools/adb")

DEFAULT_VIDEOS = [
    "-mv1Tf26Vms",
    "_Sq7rvH9hTk",
    "UVWHKLtzEik",
    "WCPjzQsCQYg",
    "p4NlCQi9x9Y",
    "YnI-e_S4ZNw",
    "dQw4w9WgXcQ",
    "jNQXAC9IVRw",
    "edon5wb5Qsc",
    "9bZkp7q19f0",
]

LINE = re.compile(r"dewidebug: \[([^\]]+)\] (.*)$")


def adb(*args, check=False, timeout=60):
    result = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def trail():
    out = adb("logcat", "-d", "-s", "dewidebug:*", timeout=30)
    lines = []
    for raw in out.splitlines():
        found = LINE.search(raw)
        if found:
            lines.append((raw[:18], found.group(1), found.group(2)))
    return lines


def preflight():
    devices = adb("devices")
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not serial or f"{serial}\tdevice" not in devices:
        sys.exit(f"set ANDROID_SERIAL to a connected device; adb devices says:\n{devices}")
    version = re.search(r"versionName=(\S+)", adb("shell", "dumpsys", "package", PACKAGE))
    signed_in = "youtube_account.xml" in adb("shell", "run-as", PACKAGE, "ls", "shared_prefs/")
    services = adb("shell", "dumpsys", "activity", "services", PACKAGE)
    other_fgs = [m for m in re.findall(r"ServiceRecord\{\S+ \S+ ([^ }]+)", services) if "PlaybackService" not in m]
    return {
        "serial": serial,
        "version": version.group(1) if version else "?",
        "signedIn": signed_in,
        "otherServices": other_fgs,
    }


def play_link(video):
    adb("logcat", "-c")
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", f"https://www.youtube.com/watch?v={video}", "-p", PACKAGE)


def wait_for_start(video, timeout_s):
    started = time.monotonic()
    while time.monotonic() - started < timeout_s:
        for _, tag, message in trail():
            if tag == "latency" and video in message and ("first picture" in message or "first sound" in message):
                return True
            if tag == "playback" and ("gave up" in message or "giving up" in message):
                return False
        time.sleep(1)
    return False


def measure(video, events):
    def find(pattern, tag=None):
        rx = re.compile(pattern)
        for _, t, message in events:
            if tag and t != tag:
                continue
            m = rx.search(message)
            if m:
                return m
        return None

    def count(pattern, tag=None):
        rx = re.compile(pattern)
        return sum(1 for _, t, message in events if (not tag or t == tag) and rx.search(message))

    picture = find(rf"first picture for {re.escape(video)} (\d+)ms", "latency")
    sound = find(rf"first sound for {re.escape(video)} (\d+)ms", "latency")
    stream = find(rf"{re.escape(video)} stream (\S+)", "playback")
    route = find(rf"route {re.escape(video)} -> (.*?) \[", "playback")
    clients = find(r"extract steps — (clients [^;]*);", "engine")
    started_at = None
    for i, (_, tag, message) in enumerate(events):
        if tag == "latency" and video in message:
            started_at = i
            break
    after = events[started_at + 1:] if started_at is not None else []
    return {
        "video": video,
        "pictureMs": int(picture.group(1)) if picture else None,
        "soundMs": int(sound.group(1)) if sound else None,
        "quality": stream.group(1) if stream else None,
        "route": route.group(1) if route else None,
        "extraction": clients.group(1) if clients else "cache or none",
        "sabrDegraded": count(r"formats have been skipped as they are missing a URL"),
        "videoDecoded": count(r"^video ", "format"),
        "watching": bool(route and "video" in route.group(1) and "audio" not in route.group(1)),
        "only360": count(rf"{re.escape(video)} offered one quality"),
        "tvDirectAsk": count(rf"{re.escape(video)} resolved as the signed-in TV client"),
        "young403Retries": count(r"403 on a stream issued", "playback"),
        "fatal403": count(r"HTTP 403 from client", "playback"),
        "gaveUp": count(r"gave up|giving up", "playback"),
        "fellBackToDisk": count(r"from the copy on disk instead", "playback"),
        "stallsAfterStart": sum(1 for _, t, m in after if t == "playback" and m.startswith("buffering at")),
        "underruns": count(r"audio underrun", "playback"),
        "preloadUsed": count(rf"playing {re.escape(video)} from the source held", "preload"),
    }


def verdict(row, budget_ms):
    started = row["soundMs"] if row["soundMs"] is not None else row["pictureMs"]
    problems = []
    if started is None:
        problems.append("never started")
    elif started > budget_ms:
        problems.append(f"slow start {started}ms")
    if row["watching"] and not row["videoDecoded"]:
        problems.append("no picture")
    for key in ("fatal403", "gaveUp", "fellBackToDisk", "stallsAfterStart", "underruns", "only360", "tvDirectAsk"):
        if row[key]:
            problems.append(f"{key}={row[key]}")
    return "PASS" if not problems else "FAIL: " + ", ".join(problems)


def cold_start(args):
    adb("shell", "am", "force-stop", PACKAGE)
    if not args.keep_lookups:
        adb("shell", "run-as", PACKAGE, "rm", "-rf", "cache/lookups")
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(args.warm_up)


def run(args):
    facts = preflight()
    print(f"device {facts['serial']} app {facts['version']} signedIn={facts['signedIn']} "
          f"other services={facts['otherServices'] or 'none'}", flush=True)
    rows = []
    plays = [(attempt, video) for attempt in range(1, args.repeat + 1) for video in args.videos]
    for n, (attempt, video) in enumerate(plays, 1):
        if video == args.videos[0]:
            print(f"{time.strftime('%T')} pass {attempt}: cold start (app stopped, saved lookups cleared)", flush=True)
            cold_start(args)
        print(f"{time.strftime('%T')} [{n}/{len(plays)}] {video} (pass {attempt}): playing the link", flush=True)
        play_link(video)
        wait_for_start(video, args.start_timeout)
        time.sleep(args.hold)
        row = measure(video, trail())
        row["attempt"] = attempt
        row["verdict"] = verdict(row, args.start_budget_ms)
        rows.append(row)
        print(f"{time.strftime('%T')}   -> {row['verdict']} sound={row['soundMs']} video={row['videoDecoded']} "
              f"quality={row['quality']} young403={row['young403Retries']}", flush=True)
    adb("shell", "cmd", "media_session", "dispatch", "pause")
    report = {"facts": facts, "label": args.label, "rows": rows, "at": time.strftime("%F %T")}
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(report, f, indent=1)
    print_table(report)
    return 0 if all(r["verdict"] == "PASS" for r in rows) else 1


def print_table(report):
    print(f"\n{report['label']} ({report['facts']['version']}, {report['at']})")
    print("video        verdict                                  start ms  quality   young403 route")
    for r in report["rows"]:
        print(f"{r['video']:<12} {r['verdict'][:40]:<40} {str(r['soundMs']):>8}  {str(r['quality']):<9} "
              f"{r['young403Retries']:>8} {(r['route'] or '')[:40]}")


def _height(quality):
    found = re.match(r"(\d+)p", quality or "")
    return int(found.group(1)) if found else 0


def _summary(rows):
    by_video = {}
    for r in rows:
        by_video.setdefault(r["video"], []).append(r)
    summary = {}
    for video, plays in by_video.items():
        starts = sorted(p["soundMs"] for p in plays if p["soundMs"] is not None)
        summary[video] = {
            "plays": len(plays),
            "failed": sum(1 for p in plays if p["verdict"] != "PASS"),
            "medianStartMs": starts[len(starts) // 2] if starts else None,
            "lowestQuality": min((_height(p["quality"]) for p in plays), default=0),
            "problems": sorted({p["verdict"] for p in plays if p["verdict"] != "PASS"}),
        }
    return summary


def compare(before_path, after_path):
    before = _summary(json.load(open(before_path))["rows"])
    after = _summary(json.load(open(after_path))["rows"])
    regressions = 0
    print(f"{'video':<12} {'failed':>9} {'median start ms':>17} {'lowest quality':>15}")
    for video, a in after.items():
        b = before.get(video)
        if not b:
            continue
        reasons = []
        if a["failed"] / a["plays"] > b["failed"] / b["plays"] + 0.34:
            reasons.append("fails more often")
        if b["medianStartMs"] and a["medianStartMs"] and a["medianStartMs"] > b["medianStartMs"] * 1.5 + 2000:
            reasons.append("starts slower")
        if a["lowestQuality"] < b["lowestQuality"]:
            reasons.append("lower quality")
        regressions += bool(reasons)
        print(f"{video:<12} {b['failed']}/{b['plays']} -> {a['failed']}/{a['plays']} "
              f"{str(b['medianStartMs']):>7} -> {str(a['medianStartMs']):<7} "
              f"{b['lowestQuality']:>5}p -> {a['lowestQuality']}p"
              + (f"  REGRESSION: {', '.join(reasons)}" if reasons else ""))
        for problem in a["problems"]:
            print(f"{'':<14}after: {problem}")
    print(f"\n{regressions} regression(s)")
    return 1 if regressions else 0


def main():
    parser = argparse.ArgumentParser(
        description="Plays a set of YouTube links on a device the way a share does, and records from the "
                    "app's own dewidebug lines whether each started, how fast, at what quality, and what "
                    "went wrong. Run it on the build before a change and on the change, then compare.")
    sub = parser.add_subparsers(dest="command", required=True)
    run_p = sub.add_parser("run", help="play every video and write a JSON report")
    run_p.add_argument("--label", required=True, help="what was installed, e.g. v0.1.575 or a5200d3f")
    run_p.add_argument("--out", required=True, help="where the JSON report goes")
    run_p.add_argument("--videos", nargs="+", default=DEFAULT_VIDEOS)
    run_p.add_argument("--hold", type=int, default=20, help="seconds to keep playing after the start")
    run_p.add_argument("--repeat", type=int, default=3,
                       help="passes over the list; one pass is too noisy to compare (0.8s vs 2.6s for the same video)")
    run_p.add_argument("--start-timeout", type=int, default=60)
    run_p.add_argument("--start-budget-ms", type=int, default=8000)
    run_p.add_argument("--warm-up", type=int, default=15,
                       help="seconds after a cold start, so the engine warm-up is not measured as a slow play")
    run_p.add_argument("--keep-lookups", action="store_true",
                       help="keep the app's saved lookups, which otherwise make a second run look faster")
    cmp_p = sub.add_parser("compare", help="diff two JSON reports; exits 1 on any regression")
    cmp_p.add_argument("before")
    cmp_p.add_argument("after")
    args = parser.parse_args()
    if args.command == "run":
        sys.exit(run(args))
    sys.exit(compare(args.before, args.after))


if __name__ == "__main__":
    main()
