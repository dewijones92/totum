package com.dewijones92.totum.playback

public object TeeVerdict {

    public fun why(route: String?, teeable: Boolean): String = when {
        teeable ->
            "its audio streams through the playback cache, so its download is made from those bytes [route=$route]"
        route == null -> "no play route is recorded for it, so nothing is known about what streams"
        route == "hls" ->
            "it plays as an HLS playlist (video and audio cut into segments the cache never stores, not the " +
                "audio file a download is), so there is no audio stream to copy [route=hls]"
        route == "sabr" ->
            "it plays over SABR, which bypasses the playback cache, so there are no cached bytes to copy [route=sabr]"
        route == "a live manifest Totum built" ->
            "it is a live stream, which is never cached, so there is nothing to copy [route=$route]"
        route == "the torrent server" ->
            "it streams from the torrent server on loopback, which is never cached [route=$route]"
        route == "a local file" -> "it plays from a local file, not a stream [route=$route]"
        else ->
            "no audio stream of it has opened through the playback cache (a muxed stream, a podcast enclosure " +
                "whose cache key lives for one launch, or an audio stream not opened yet) [route=$route]"
    }
}
