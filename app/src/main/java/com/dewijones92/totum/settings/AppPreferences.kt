package com.dewijones92.totum.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.sponsorblock.SkipCategory
import com.dewijones92.totum.data.sponsorblock.SponsorBlockSegmentSource
import com.dewijones92.totum.domain.MediaFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * User settings, exposed as a [StateFlow] so the UI reacts to changes.
 *
 * The quality values are *caps* (a max height): playback picks the best stream
 * that doesn't exceed the cap for the current network, so mobile data is saved
 * without forcing a lower quality than needed on Wi-Fi. [UNCAPPED] means "best".
 */
/**
 * Whether videos play with the picture. Situational rather than per-item — wanting
 * audio is about what you're doing ("washing up"), not about a particular video — so
 * it's one global mode rather than a property remembered per video.
 */
enum class PlaybackMode {
    /** Video on Wi-Fi, audio on mobile data. The default, and why the data warning is rarely needed. */
    AUTO,

    /** Everything plays audio-only, preferring a downloaded copy. */
    AUDIO,

    /** Videos play with the picture. */
    VIDEO,
}

/**
 * Whether we are LISTENING right now — the one answer, for behaviour and for what a row says.
 *
 * It exists because the question was being asked three times and one answer was right. Playback asked
 * `AppContainer.audioPlaybackPreferred()`, which resolves [PlaybackMode.AUTO] against the network;
 * `MediaItemActions.audioMode` and `ShortsReelScreen` each hand-rolled `mode == AUDIO` instead. AUTO is
 * the shipped default, so on mobile data the app played audio while every row offered "Listen only" and
 * never offered the picture back — a label that says the opposite of what is happening, on defaults.
 *
 * Pure and named so it can be tested and so a fourth caller cannot get it wrong.
 */
public fun listeningIn(mode: PlaybackMode, onMeteredNetwork: Boolean): Boolean = when (mode) {
    PlaybackMode.AUDIO -> true
    PlaybackMode.VIDEO -> false
    PlaybackMode.AUTO -> onMeteredNetwork
}

// One method per preference, so the count tracks how many settings exist rather than any
// complexity. Splitting a settings interface to satisfy a counter would scatter one concept
// across several types for no reader's benefit.
@Suppress("TooManyFunctions")
interface AppPreferences {
    val settings: StateFlow<Settings>
    fun setWifiMaxHeight(height: Int)
    fun setCellularMaxHeight(height: Int)
    fun setAutoPlayNext(enabled: Boolean)

    /** Experimental: resolve and stream over SABR instead of extracting. Off by default. */
    fun setSabrPlayback(enabled: Boolean)
    fun setHomeServer(base: String, prowlarrApiKey: String)

    /** Stores the token a sign-in returned. Never logged. */
    fun setHomeServerToken(token: String)
    fun setAutoDownloadQueue(enabled: Boolean)
    fun setAutoDownloadWifiOnly(enabled: Boolean)
    fun setPlaybackMode(mode: PlaybackMode)
    fun setMediaFilter(filter: MediaFilter)

    /** Which SponsorBlock categories are skipped, in playback and in downloads alike. */
    fun setSkipCategories(categories: Set<SkipCategory>)

    data class Settings(
        val wifiMaxHeight: Int = DEFAULT_WIFI_MAX_HEIGHT,
        val cellularMaxHeight: Int = DEFAULT_CELLULAR_MAX_HEIGHT,
        /** Whether the queue advances when an item ends. On by default. */
        val autoPlayNext: Boolean = true,
        /**
         * Resolve and stream over YouTube's own SABR protocol instead of extracting with
         * yt-dlp. **Off by default and experimental.**
         *
         * The prize is real — a `/player` call answers in ~150ms where an extraction costs 2-4
         * seconds on a phone — but SABR is asked for a media TIME rather than a byte offset, so
         * this cannot seek yet. Off by default because a fast start that cannot scrub is a
         * worse app, and behind a switch because the only way to find the next problem is to
         * use it.
         */
        val sabrPlayback: Boolean = false,
        /**
         * The home server's domain, e.g. `333133333.xyz`, from which `prowlarr.` and
         * `torrserver.` are derived.
         *
         * Blank disables torrent search entirely rather than showing a broken feature: with no
         * server there is nothing to search, and an error message for something never set up is
         * worse than the thing simply not being there.
         */
        val homeServerBase: String = "",
        /** Prowlarr's API key. The gate protects the endpoint; this identifies the caller. */
        val prowlarrApiKey: String = "",
        /**
         * The token the home server issued after a Google sign-in, replayed on every request.
         *
         * Obtained by opening the server's sign-in page in a browser, which deep-links back into
         * the app carrying it — the app cannot hold an oauth2-proxy cookie, because Google
         * refuses sign-in in a WebView and a Custom Tab's cookies live in Chrome.
         */
        val homeServerToken: String = "",
        /** Whether queued items have their audio fetched for offline listening. */
        val autoDownloadQueue: Boolean = true,
        /**
         * Restricts automatic downloads to Wi-Fi. **Off by default** (Dewi, 2026-08-02).
         *
         * It defaulted ON, which quietly made "everything in the queue is available offline"
         * untrue exactly when it mattered — away from home, on mobile data, the downloader did
         * nothing and said nothing. A queue that is only ready when you did not need it is not
         * ready. The setting stays for whoever wants it, and the queue now states plainly when
         * it is what is holding things up.
         */
        val autoDownloadWifiOnly: Boolean = false,
        val playbackMode: PlaybackMode = PlaybackMode.AUTO,
        /**
         * Which items feeds show, by progress. Global rather than per-feed: "hide what I have
         * finished" is a preference about how you read, not about one subscription, and a
         * per-feed version would need a setting per feed for a choice nobody varies.
         */
        val mediaFilter: MediaFilter = MediaFilter.ALL,
        /** SponsorBlock categories to skip; see SponsorBlockSegmentSource.DEFAULT_CATEGORIES. */
        val skipCategories: Set<SkipCategory> = SponsorBlockSegmentSource.DEFAULT_CATEGORIES,
    )

    companion object {
        /** A cap meaning "no limit — pick the best". */
        const val UNCAPPED: Int = Int.MAX_VALUE
        const val DEFAULT_WIFI_MAX_HEIGHT: Int = 1080
        const val DEFAULT_CELLULAR_MAX_HEIGHT: Int = 480
    }
}

/** SharedPreferences-backed [AppPreferences]; settings are tiny, so reads are synchronous. */
// One setter per preference, so the count tracks the number of settings rather than any
// complexity — and the home-server pair is deliberately ONE method because an address without a
// key cannot search and a key without an address has nothing to search.
@Suppress("TooManyFunctions")
class SharedPrefsAppPreferences(context: Context) : AppPreferences {

    private val prefs = context.getSharedPreferences("totum_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppPreferences.Settings(
            wifiMaxHeight = prefs.getInt(KEY_WIFI, AppPreferences.DEFAULT_WIFI_MAX_HEIGHT),
            cellularMaxHeight = prefs.getInt(KEY_CELLULAR, AppPreferences.DEFAULT_CELLULAR_MAX_HEIGHT),
            autoPlayNext = prefs.getBoolean(KEY_AUTOPLAY, true),
            sabrPlayback = prefs.getBoolean(KEY_SABR, false),
            // Falls back to the host baked in at build time, so torrents need no setup at all:
            // install, sign in with Google, done. A value typed in Settings still wins, which is
            // what makes a different server testable without a rebuild.
            homeServerBase = prefs.getString(KEY_HOME_SERVER, "")
                .orEmpty().ifBlank { BuildConfig.HOME_SERVER },
            prowlarrApiKey = prefs.getString(KEY_PROWLARR_KEY, "").orEmpty(),
            homeServerToken = prefs.getString(KEY_HOME_TOKEN, "").orEmpty(),
            autoDownloadQueue = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true),
            autoDownloadWifiOnly = prefs.getBoolean(KEY_AUTO_DOWNLOAD_WIFI, false),
            playbackMode = prefs.getString(KEY_PLAYBACK_MODE, null)
                ?.let { name -> runCatching { PlaybackMode.valueOf(name) }.getOrNull() }
                ?: PlaybackMode.AUTO,
            mediaFilter = prefs.getString(KEY_MEDIA_FILTER, null)
                ?.let { name -> runCatching { MediaFilter.valueOf(name) }.getOrNull() }
                ?: MediaFilter.ALL,
            // An unknown id (a preference written by a newer build) is dropped rather
            // than crashing; absent entirely means "never chosen", so use the defaults.
            skipCategories = prefs.getStringSet(KEY_SKIP_CATEGORIES, null)
                ?.mapNotNullTo(mutableSetOf()) { SkipCategory.fromId(it) }
                ?: SponsorBlockSegmentSource.DEFAULT_CATEGORIES,
        ),
    )
    override val settings: StateFlow<AppPreferences.Settings> = _settings.asStateFlow()

    override fun setWifiMaxHeight(height: Int): Unit =
        change("wifiMaxHeight", height, { putInt(KEY_WIFI, height) }) { it.copy(wifiMaxHeight = height) }

    override fun setCellularMaxHeight(height: Int): Unit =
        change("cellularMaxHeight", height, { putInt(KEY_CELLULAR, height) }) {
            it.copy(cellularMaxHeight = height)
        }

    override fun setAutoPlayNext(enabled: Boolean): Unit =
        change("autoPlayNext", enabled, { putBoolean(KEY_AUTOPLAY, enabled) }) { it.copy(autoPlayNext = enabled) }

    override fun setSabrPlayback(enabled: Boolean): Unit =
        change("sabrPlayback", enabled, { putBoolean(KEY_SABR, enabled) }) { it.copy(sabrPlayback = enabled) }

    /**
     * Set together, because half of it is useless: an address without a key cannot search, and a
     * key without an address has nothing to search. The key is NOT logged.
     */
    override fun setHomeServer(base: String, prowlarrApiKey: String): Unit = change(
        "homeServer",
        base.ifBlank { "(none)" },
        {
            putString(KEY_HOME_SERVER, base.trim())
            putString(KEY_PROWLARR_KEY, prowlarrApiKey.trim())
        },
    ) { it.copy(homeServerBase = base.trim(), prowlarrApiKey = prowlarrApiKey.trim()) }

    override fun setHomeServerToken(token: String): Unit = change(
        // The VALUE is never logged; whether one exists is, because "signed in?" is the first
        // question when nothing works.
        "homeServerToken",
        if (token.isBlank()) "(cleared)" else "(set)",
        { putString(KEY_HOME_TOKEN, token.trim()) },
    ) { it.copy(homeServerToken = token.trim()) }

    override fun setAutoDownloadQueue(enabled: Boolean): Unit =
        change("autoDownloadQueue", enabled, { putBoolean(KEY_AUTO_DOWNLOAD, enabled) }) {
            it.copy(autoDownloadQueue = enabled)
        }

    override fun setAutoDownloadWifiOnly(enabled: Boolean): Unit =
        change("autoDownloadWifiOnly", enabled, { putBoolean(KEY_AUTO_DOWNLOAD_WIFI, enabled) }) {
            it.copy(autoDownloadWifiOnly = enabled)
        }

    override fun setSkipCategories(categories: Set<SkipCategory>): Unit =
        change("skipCategories", categories.map { it.id }.sorted(), {
            putStringSet(KEY_SKIP_CATEGORIES, categories.mapTo(mutableSetOf()) { it.id })
        }) { it.copy(skipCategories = categories) }

    override fun setPlaybackMode(mode: PlaybackMode): Unit =
        change("playbackMode", mode, { putString(KEY_PLAYBACK_MODE, mode.name) }) {
            it.copy(playbackMode = mode)
        }

    override fun setMediaFilter(filter: MediaFilter): Unit =
        change("mediaFilter", filter, { putString(KEY_MEDIA_FILTER, filter.name) }) {
            it.copy(mediaFilter = filter)
        }

    /**
     * One path for every setting: persist, publish, and record it. A settings change is
     * often the answer to "it started behaving differently" — a report that lists the
     * current values cannot say *when* one of them changed, or that it changed at all.
     */
    private inline fun change(
        name: String,
        value: Any,
        write: SharedPreferences.Editor.() -> Unit,
        update: (AppPreferences.Settings) -> AppPreferences.Settings,
    ) {
        prefs.edit { write() }
        _settings.update(update)
        Diag.log("settings", "$name -> $value")
    }

    private companion object {
        const val KEY_WIFI = "wifi_max_height"
        const val KEY_CELLULAR = "cellular_max_height"
        const val KEY_AUTOPLAY = "auto_play_next"
        const val KEY_SABR = "sabr_playback"
        const val KEY_HOME_SERVER = "home_server_base"
        const val KEY_PROWLARR_KEY = "prowlarr_api_key"
        const val KEY_HOME_TOKEN = "home_server_token"
        const val KEY_AUTO_DOWNLOAD = "auto_download_queue"
        const val KEY_AUTO_DOWNLOAD_WIFI = "auto_download_wifi_only"
        const val KEY_PLAYBACK_MODE = "playback_mode"
        const val KEY_MEDIA_FILTER = "media_filter"
        const val KEY_SKIP_CATEGORIES = "skip_categories"
    }
}

/** In-memory [AppPreferences] for previews and tests. */
@Suppress("TooManyFunctions")
class InMemoryAppPreferences : AppPreferences {
    private val _settings = MutableStateFlow(AppPreferences.Settings())
    override val settings: StateFlow<AppPreferences.Settings> = _settings.asStateFlow()
    override fun setWifiMaxHeight(height: Int) = _settings.update { it.copy(wifiMaxHeight = height) }
    override fun setCellularMaxHeight(height: Int) = _settings.update { it.copy(cellularMaxHeight = height) }
    override fun setAutoPlayNext(enabled: Boolean) = _settings.update { it.copy(autoPlayNext = enabled) }

    override fun setSabrPlayback(enabled: Boolean) = _settings.update { it.copy(sabrPlayback = enabled) }
    override fun setHomeServer(base: String, prowlarrApiKey: String) =
        _settings.update { it.copy(homeServerBase = base, prowlarrApiKey = prowlarrApiKey) }
    override fun setHomeServerToken(token: String) = _settings.update { it.copy(homeServerToken = token) }
    override fun setAutoDownloadQueue(enabled: Boolean) = _settings.update { it.copy(autoDownloadQueue = enabled) }
    override fun setAutoDownloadWifiOnly(enabled: Boolean) =
        _settings.update { it.copy(autoDownloadWifiOnly = enabled) }
    override fun setPlaybackMode(mode: PlaybackMode) = _settings.update { it.copy(playbackMode = mode) }
    override fun setSkipCategories(categories: Set<SkipCategory>) =
        _settings.update { it.copy(skipCategories = categories) }

    override fun setMediaFilter(filter: MediaFilter) = _settings.update { it.copy(mediaFilter = filter) }
}
