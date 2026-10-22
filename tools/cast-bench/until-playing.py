#!/usr/bin/env python3
"""Time how long a Cast receiver takes to really play, from its own media status.

    until-playing.py <tv-ip> wait            from now until the TV's clock moves
    until-playing.py <tv-ip> seek <seconds>  seek, then the same

"Really playing" is PLAYING with current_time at least 0.5 s past where it first said PLAYING,
because a receiver reports PLAYING before its buffer has anything in it.
Runs on the Pi with the catt venv's python (it has pychromecast).
"""
import sys
import time

import pychromecast

host, mode = sys.argv[1], sys.argv[2]
cast = pychromecast.get_chromecast_from_host((host, 8009, None, None, None), tries=2, retry_wait=3, timeout=8)
cast.wait()
media = cast.media_controller
media.update_status()
time.sleep(0.5)

began = time.monotonic()
if mode == "seek":
    target = float(sys.argv[3])
    media.seek(target)
first_playing = None
last = None
while time.monotonic() - began < 60:
    media.update_status()
    time.sleep(0.25)
    s = media.status
    now = time.monotonic() - began
    state = (s.player_state, round(s.current_time or 0, 1))
    if state != last:
        print(f"{now:6.2f}s {state[0]} at {state[1]}s", flush=True)
        last = state
    if s.player_state == "PLAYING":
        if first_playing is None:
            first_playing = s.current_time or 0
        elif (s.current_time or 0) >= first_playing + 0.5:
            print(f"PLAYING after {now:.2f}s", flush=True)
            sys.exit(0)
print("never played within 60s", flush=True)
sys.exit(1)
