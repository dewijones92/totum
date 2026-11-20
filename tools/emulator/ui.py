#!/usr/bin/env python3
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL = "emulator-5554"


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, check=False).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/ui.xml")
    return list(ET.fromstring(xml[xml.index("<"):]).iter("node"))


def centre(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(fragment):
    for node in nodes():
        if fragment in (node.get("text") or "") or fragment in (node.get("content-desc") or ""):
            return node
    return None


def tap(fragment, tries=6, scroll=False):
    for attempt in range(tries):
        node = find(fragment)
        if node is not None:
            x, y = centre(node)
            adb("shell", "input", "tap", str(x), str(y))
            print(f"tapped {fragment!r} at {x},{y}")
            return True
        if scroll:
            adb("shell", "input", "swipe", "540", "1800", "540", "700", "300")
        time.sleep(1)
    print(f"NOT FOUND {fragment!r}")
    return False


if __name__ == "__main__":
    command, *rest = sys.argv[1:]
    if command == "tap":
        sys.exit(0 if tap(rest[0], scroll="--scroll" in rest) else 1)
    if command == "has":
        sys.exit(0 if find(rest[0]) is not None else 1)
