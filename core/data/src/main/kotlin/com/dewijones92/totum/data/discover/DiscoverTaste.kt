package com.dewijones92.totum.data.discover

public data class DiscoverArtist(val id: String, val name: String)

public data class TasteMark(val id: String?, val name: String?, val why: String)

public class DiscoverTaste(recent: List<TasteMark> = emptyList(), followed: List<TasteMark> = emptyList()) {
    private val recentById = recent.byId()
    private val recentByName = recent.byName()
    private val followedById = followed.byId()
    private val followedByName = followed.byName()

    public val recentCount: Int = recent.size
    public val followedCount: Int = followed.size

    public fun recentWhy(artist: DiscoverArtist): String? = whyOf(artist, recentById, recentByName)

    public fun followedWhy(artist: DiscoverArtist): String? = whyOf(artist, followedById, followedByName)

    public companion object {
        public val NOTHING: DiscoverTaste = DiscoverTaste()
    }
}

public fun String.artistNameKey(): String = trim().lowercase().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

private fun whyOf(artist: DiscoverArtist, byId: Map<String, String>, byName: Map<String, String>): String? =
    byId[artist.id] ?: byName[artist.name.artistNameKey()]

private fun List<TasteMark>.byId(): Map<String, String> =
    mapNotNull { mark -> mark.id?.let { it to mark.why } }.toMap()

private fun List<TasteMark>.byName(): Map<String, String> =
    mapNotNull { mark -> mark.name?.artistNameKey()?.takeIf { it.isNotEmpty() }?.let { it to mark.why } }.toMap()
