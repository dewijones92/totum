---
title: What the Pi stores for Totum, and what it should
kind: todo
status: investigated 2026-10-22
area: pi/infra
priority: medium
requested: 2026-10-22
updated: 2026-10-22
---

# What the Pi stores for Totum, and what it should

Investigate and document what the Raspberry Pi keeps for Totum (TorrServer RAM cache, Prowlarr, crashlog data, totum-audio, downloads) and recommend; the Pi's disk is 92% full.

## Findings (2026-10-22, read-only over ssh)

The root disk is 1.8 TB, 92% used, 156 GB free. Totum's own share is tiny:

| Store | On disk | In memory | Notes |
|---|---|---|---|
| TorrServer (`/var/lib/torrserver`) | 660 KB | ~87 MB | its piece cache is RAM-only; config, log and torrent list on disk |
| Crash-log reports (`/home/pi/crashlog-data`) | 89 MB | ~60 MB | three months of reports with screenshots |
| Prowlarr (`/var/lib/prowlarr`) | 12 MB | ~190 MB | indexer config |
| FlareSolverr | a Docker volume | ~170 MB | browser profile |
| totum-audio | none | ~17 MB | no volume |

What fills the disk is not Totum: the media library (`/home/pi/ext_files`, 1.5 TB) and qBittorrent's downloads
(`/root/torrentDownloads`, 40 GB). Docker's images and build cache are 13 GB, about 1.7 GB of it reclaimable.

Recommendations, none urgent: a retention rule for crash-log reports (say, drop screenshots from reports older than
90 days whose findings are all judged), `docker builder prune` now and then (350 MB), and a look at the 40 GB of
finished qBittorrent downloads, which is Dewi's call. Nothing in Totum needs to change to free space.

