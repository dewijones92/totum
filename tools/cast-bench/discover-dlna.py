#!/usr/bin/env python3
import re
import socket
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

MULTICAST = ("239.255.255.250", 1900)
RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"


def search(target=RENDERER, unicast=None, seconds=4.0):
    message = (
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: %s\r\n\r\n" % target
    ).encode()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    sock.settimeout(0.5)
    sock.sendto(message, (unicast, 1900) if unicast else MULTICAST)
    found = {}
    end = time.time() + seconds
    while time.time() < end:
        try:
            data, sender = sock.recvfrom(4096)
        except socket.timeout:
            continue
        location = locations(data.decode("latin-1")).get("location")
        if location:
            found[location] = sender[0]
    return list(found)


def locations(reply):
    return {
        key.strip().lower(): value.strip()
        for key, _, value in (line.partition(":") for line in reply.splitlines()[1:])
        if value
    }


def friendly_name(description):
    root = ET.fromstring(description)
    for element in root.iter():
        if element.tag.endswith("friendlyName") and element.text:
            return element.text.strip()
    return None


def entry(name, location):
    return "%s=%s" % (re.sub(r"[;=]", " ", name), location)


def main(argv):
    unicast = argv[0] if argv else None
    for location in search(unicast=unicast):
        try:
            with urllib.request.urlopen(location, timeout=5) as reply:
                name = friendly_name(reply.read().decode("utf-8", "replace")) or location
        except OSError as problem:
            print("# %s did not answer: %s" % (location, problem), file=sys.stderr)
            continue
        print(entry(name, location))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
