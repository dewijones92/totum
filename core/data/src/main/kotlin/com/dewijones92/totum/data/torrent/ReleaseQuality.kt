package com.dewijones92.totum.data.torrent

public object ReleaseQuality {
    public fun resolutionOf(title: String): String? {
        val words = ReleaseName(title).tail.map(String::lowercase)
        return RESOLUTIONS.firstOrNull { (names, _) -> words.any { it in names } }?.second
    }

    public fun qualityOf(title: String): String? {
        val words = ReleaseName(title).tail.map(String::lowercase)
        fun first(table: List<Pair<Set<String>, String>>) =
            table.firstOrNull { (names, _) -> words.any { it in names } }?.second
        val audio = listOfNotNull(
            first(listOf(setOf("atmos") to "Atmos")),
            CHANNELS.find(title)?.value?.let { if (it.startsWith("7")) "7.1" else "5.1" },
        )
        val parts = listOfNotNull(first(RESOLUTIONS), first(SOURCES), first(CODECS), first(HDR)) + audio
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private val CHANNELS = Regex("""(?<![0-9])[57][. ]1(?![0-9])""")
    private val RESOLUTIONS = listOf(
        setOf("2160p", "4k", "uhd") to "4K",
        setOf("1080p", "1080i") to "1080p",
        setOf("720p") to "720p",
        setOf("576p", "480p") to "SD",
    )
    private val SOURCES = listOf(
        setOf("remux") to "Remux",
        setOf("bluray", "blu", "bdrip", "brrip", "bdremux") to "BluRay",
        setOf("web", "webdl", "webrip", "amzn", "nf", "dsnp", "hmax", "atvp") to "WEB",
        setOf("hdtv") to "TV",
        setOf("dvdrip", "dvd") to "DVD",
    )
    private val CODECS = listOf(
        setOf("x265", "h265", "hevc") to "HEVC",
        setOf("av1") to "AV1",
        setOf("x264", "h264", "avc") to "H.264",
        setOf("xvid", "divx") to "XviD",
    )
    private val HDR = listOf(
        setOf("dv", "dovi", "dolbyvision") to "Dolby Vision",
        setOf("hdr", "hdr10", "hdr10+", "hdr10plus") to "HDR",
    )
}
