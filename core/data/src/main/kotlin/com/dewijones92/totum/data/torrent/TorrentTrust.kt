package com.dewijones92.totum.data.torrent

import java.time.Duration
import java.time.Instant

public data class TorrentTrust(
    public val uploader: String,
    public val group: String?,
    public val quality: String? = null,
    public val health: String,
    public val flags: List<String>,
) {
    public val flagged: Boolean get() = flags.isNotEmpty()

    public val chips: List<String> get() = flags + listOfNotNull(uploader, quality, group, health)

    public companion object {
        public fun of(result: TorrentResult, now: Instant = Instant.now()): TorrentTrust = TorrentTrust(
            uploader = uploaderChip(result),
            group = groupOf(result.title)?.let { "🏷️ $it release" },
            quality = qualityOf(result.title)?.let { "🎞️ $it" },
            health = healthChip(result, now),
            flags = flagsOf(result),
        )

        internal fun standing(result: TorrentResult): Uploader = when {
            result.uploader != Uploader.UNKNOWN -> result.uploader
            OFFICIAL_SITES.any { site -> result.indexer.orEmpty().split('·').any { it.trim().equals(site, true) } } ->
                Uploader.OFFICIAL
            else -> Uploader.UNKNOWN
        }

        private fun uploaderChip(result: TorrentResult): String {
            val by = result.uploaderName?.let { " ($it)" }.orEmpty()
            return when (standing(result)) {
                Uploader.VIP -> "💚 VIP uploader$by"
                Uploader.TRUSTED -> "💗 Trusted uploader$by"
                Uploader.OFFICIAL -> "💚 Official ${result.indexer?.substringAfterLast('·')?.trim()} release"
                Uploader.MEMBER -> "👤 Ordinary uploader$by"
                Uploader.UNKNOWN -> "❔ Uploader unknown"
            }
        }

        private fun healthChip(result: TorrentResult, now: Instant): String {
            val leeching = result.leechers?.let { ", $it leeching" }.orEmpty()
            val age = result.publishedAt?.let { " · ${ageOf(Duration.between(it, now))}" }.orEmpty()
            val state = when {
                result.seeders >= WELL_SEEDED -> "🌱 Well seeded"
                result.seeders >= SOME_SEEDERS -> "🌿 Some seeders"
                result.seeders > 0 -> "🥀 Few seeders"
                else -> "🥀 No seeders"
            }
            return "$state (${result.seeders} seeding$leeching)$age"
        }

        private fun ageOf(age: Duration): String {
            val days = age.toDays().coerceAtLeast(0)
            return when {
                days >= DAYS_A_YEAR -> "${days / DAYS_A_YEAR} year(s) old"
                days >= DAYS_A_MONTH -> "${days / DAYS_A_MONTH} month(s) old"
                days >= 1 -> "$days day(s) old"
                else -> "new today"
            }
        }

        public fun filesWarning(files: List<TorrentFile>): String? {
            val programs = files.filter { it.name.substringAfterLast('.', "").lowercase() in PROGRAMS }
            if (programs.isEmpty()) return null
            val named = programs.take(PROGRAMS_NAMED).joinToString { it.name }
            return "⚠️ It also holds ${programs.size} program(s) ($named); only its video is played"
        }

        internal fun flagsOf(result: TorrentResult): List<String> = buildList {
            val words = result.title.split(SEPARATORS).filter { it.isNotEmpty() }
            words.firstOrNull { it.uppercase() in CAMERA_COPIES }?.let {
                add("⚠️ Looks like a cinema recording ($it)")
            }
            words.firstOrNull { it.lowercase() in PROGRAMS }?.let { add("⚠️ Its name mentions a program (.$it)") }
            if (PASSWORD.containsMatchIn(result.title)) add("⚠️ Its name mentions a password")
            tooSmall(result)?.let(::add)
        }

        private fun tooSmall(result: TorrentResult): String? {
            if (result.sizeBytes <= 0) return null
            val (quality, floor) = SMALLEST.entries.firstOrNull { (quality, _) ->
                Regex("""\b$quality\b""", RegexOption.IGNORE_CASE).containsMatchIn(result.title)
            }?.toPair() ?: return null
            if (result.sizeBytes >= floor) return null
            return "⚠️ Looks fake: ${result.sizeBytes / BYTES_PER_MB} MB for $quality"
        }

        internal fun qualityOf(title: String): String? {
            val words = title.split(SEPARATORS).filter { it.isNotEmpty() }.map { it.lowercase() }
            fun first(table: List<Pair<Set<String>, String>>) = table.firstOrNull { (names, _) ->
                words.any {
                    it in names
                }
            }?.second
            val audio = listOfNotNull(
                first(listOf(setOf("atmos") to "Atmos")),
                CHANNELS.find(title)?.value?.let { if (it.startsWith("7")) "7.1" else "5.1" },
            )
            val parts = listOfNotNull(first(RESOLUTIONS), first(SOURCES), first(CODECS), first(HDR)) + audio
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        internal fun groupOf(title: String): String? {
            val words = title.split(SEPARATORS).filter { it.isNotEmpty() }
            val tail = Regex("""-([A-Za-z0-9]+)\W*$""").find(title.trim())?.groupValues?.get(1)
            return (listOfNotNull(tail) + words.reversed()).firstNotNullOfOrNull { word ->
                KNOWN_GROUPS.firstOrNull { it.equals(word, ignoreCase = true) }
            }
        }

        private val SEPARATORS = Regex("""[\s.\-_\[\]()]+""")
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
        private val OFFICIAL_SITES = listOf("YTS")
        private val CAMERA_COPIES = setOf("CAM", "CAMRIP", "HDCAM", "TS", "HDTS", "TELESYNC", "TELECINE", "HDTC", "TC")
        private val PROGRAMS = setOf("exe", "scr", "msi", "bat", "cmd", "lnk", "apk")
        private val PASSWORD = Regex("""\bpass(word)?\b""", RegexOption.IGNORE_CASE)
        private const val BYTES_PER_MB = 1_000_000L
        private val SMALLEST = linkedMapOf(
            "2160p" to 1_000 * BYTES_PER_MB,
            "4K" to 1_000 * BYTES_PER_MB,
            "1080p" to 300 * BYTES_PER_MB,
            "720p" to 150 * BYTES_PER_MB,
        )
        private val KNOWN_GROUPS = listOf(
            "YTS", "YIFY", "RARBG", "EZTV", "EZTVx", "TGx", "SPARKS", "GECKOS", "AMIABLE", "FGT", "NTb", "NTG",
            "FLUX", "CMRG", "TEPES", "SMURF", "KOGi", "PSA", "QxR", "Tigole", "UTR", "ETHEL", "SuccessfulCrab",
            "MeGusta", "x0r", "PHOENiX", "GalaxyRG", "BONE",
        )
        private const val WELL_SEEDED = 20
        private const val PROGRAMS_NAMED = 2
        private const val SOME_SEEDERS = 5
        private const val DAYS_A_MONTH = 30
        private const val DAYS_A_YEAR = 365
    }
}
