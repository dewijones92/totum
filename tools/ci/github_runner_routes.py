#!/usr/bin/env python3
import ipaddress
import json
import sys

SERVICES = ("actions",)
SHORTEST_PREFIX = 8


def ipv4_cidrs(meta: dict) -> list[str]:
    found: set[ipaddress.IPv4Network] = set()
    for service in SERVICES:
        for text in meta.get(service, []):
            try:
                network = ipaddress.ip_network(text, strict=False)
            except ValueError:
                continue
            if network.version == 4 and network.prefixlen >= SHORTEST_PREFIX:
                found.add(network)
    return [str(network) for network in sorted(found)]


if __name__ == "__main__":
    for cidr in ipv4_cidrs(json.load(sys.stdin)):
        print(cidr)
