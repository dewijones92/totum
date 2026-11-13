package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.download.JsonDownloadStore
import com.dewijones92.totum.domain.isTorrent
import kotlinx.coroutines.CancellationException

internal class PosterRepair(
    private val store: JsonDownloadStore,
    private val posterFor: suspend (String) -> String?,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun repair(records: List<DownloadRecord>) {
        val missing = records.filter { it.item.item.isTorrent && it.item.item.thumbnailUrl == null }
        val byTorrent = missing.groupBy { it.item.item.author.orEmpty() }
        byTorrent.forEach { (name, rows) ->
            if (name.isBlank()) {
                Diag.log("home", "${rows.size} torrent job(s) have no poster and no torrent name to look one up by")
                return@forEach
            }
            val found = try {
                posterFor(name)?.let(HttpUrl::parse)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Diag.warn("home", "poster lookup for \"$name\" failed", e)
                null
            }
            Diag.log(
                "home",
                "poster for \"$name\" (${rows.size} job(s)): ${found ?: "none found by title and year"}",
            )
            found?.let { poster -> rows.forEach { store.learnFacts(it.item.item.copy(thumbnailUrl = poster)) } }
        }
    }
}
