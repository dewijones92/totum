package com.dewijones92.totum.data.torrent

import java.time.Duration
import java.time.Instant

public data class TorrentTrust(
    public val uploader: String,
    public val group: String?,
    public val health: String,
    public val flags: List<String>,
) {
    public val flagged: Boolean get() = flags.isNotEmpty()

    public val chips: List<String> get() = flags + listOfNotNull(uploader, group, health)

    public companion object {
        public fun of(result: TorrentResult, now: Instant = Instant.now()): TorrentTrust = TorrentTrust(
            uploader = uploaderChip(result),
            group = groupOf(result.title)?.let { "🏷️ $it release" },
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

        internal fun groupOf(title: String): String? {
            val words = title.split(SEPARATORS).filter { it.isNotEmpty() }
            val tail = Regex("""-([A-Za-z0-9]+)\W*$""").find(title.trim())?.groupValues?.get(1)
            return (listOfNotNull(tail) + words.reversed()).firstNotNullOfOrNull { word ->
                KNOWN_GROUPS.firstOrNull { it.equals(word, ignoreCase = true) }
            }
        }

        private val SEPARATORS = Regex("""[\s.\-_\[\]()]+""")
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
