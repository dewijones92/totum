#!/bin/bash
# TV (192.168.0.219/.13) -> Pi 192.168.0.19:18765 -> ssh -R -> WSL localhost:8765 (-> adb forward -> emulator)
exec ssh -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -R 192.168.0.19:18765:localhost:8765 pi@333133333.xyz 'bash -s' < "$(dirname "$0")/pi-hop-remote.sh"
