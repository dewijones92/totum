package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.LibraryFiler
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.YoungStreamUrl
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DefaultDownloadManager
import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.data.download.JsonDownloadStore
import com.dewijones92.totum.data.home.AccountHandover
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobRequest
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeJobs
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.data.net.OkHttpTextFetcher
import com.dewijones92.totum.data.sponsorblock.SponsorBlockSegmentSource
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.HttpYouTubeAuth
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.history.HttpYouTubeWatchHistory
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import com.dewijones92.totum.innertube.player.HttpEmbedHostFlagsSource
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.HttpVisitorIdSource
import com.dewijones92.totum.innertube.player.MemoisedNSolver
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.SignatureSolver
import com.dewijones92.totum.playback.cast.CastItem
import com.dewijones92.totum.playback.cast.CastRung
import com.dewijones92.totum.playback.cast.LocalManifests
import com.dewijones92.totum.polite.Politeness
import com.dewijones92.totum.video.EmbeddedPlayer
import com.dewijones92.totum.video.FileLookupStore
import com.dewijones92.totum.video.InnerTubePlayerStreams
import com.dewijones92.totum.video.LiveStreamManifest
import com.dewijones92.totum.video.PhoneCodecs
import com.dewijones92.totum.video.SabrRoute
import com.dewijones92.totum.video.SignedInTvPlayer
import com.dewijones92.totum.video.StreamStatus
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.video.solvingChallenges
import com.dewijones92.totum.video.videoAndPodcastDownloads
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.process.ProcessYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal sealed interface Started {
    data class Accepted(val id: String) : Started
    data class Refused(val why: String) : Started
}

internal sealed interface Received {
    data class Stored(val bytes: Long) : Received
    data class Refused(val code: Int, val why: String) : Received
}

internal interface Home {
    suspend fun start(request: HomeJobRequest): Started
    suspend fun jobs(): HomeJobs
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
    suspend fun fileOf(id: String): File?
    suspend fun receive(id: String, audioOnly: Boolean, declaredBytes: Long, body: InputStream): Received
    suspend fun signIn(handover: AccountHandover): Admission
    suspend fun signOut(by: String?): Admission
}

@Suppress("TooManyFunctions")
internal class HomeDownloads(
    val dataDir: File,
    private val downloadDir: File,
    scope: CoroutineScope,
    private val rewrites: Rewrites = Rewrites(emptyList()),
    private val freeFloorBytes: Long = FREE_FLOOR_BYTES,
    private val filer: LibraryFiler? = null,
    private val health: HomeHealth? = null,
    private val polite: Politeness,
    rawEngine: YtDlpEngine = ProcessYtDlpEngine(),
    transport: Interceptor? = null,
) : Home {
    private val http = polite.gated(OkHttpClient.Builder().readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS))
        .apply { transport?.let(::addInterceptor) }.build()
    private val engine: YtDlpEngine = polite.gated(rawEngine)
    private val tokens = FileTokenStore(File(dataDir, "youtube.json"))
    private val ownership = YouTubeOwnership(File(dataDir, "youtube-owner.json"))
    private val account = YouTubeAccount(HttpYouTubeAuth(http), tokens)
    private val innerTube = InnerTubeClient(http, accountToken = {
        (account.accessToken() as? AccessTokenResult.Available)?.token
    })
    private val stamps = HttpSignatureTimestampSource(http)
    private val nSolver = MemoisedNSolver(NSolver { challenges, playerUrl -> engine.solveN(challenges, playerUrl) })
    private val signatures = SignatureSolver(engine::solveSignatures)
    private val embedded = EmbeddedPlayer(innerTube, HttpVisitorIdSource(http), HttpEmbedHostFlagsSource(http), stamps)
    private val skipSegments = SponsorBlockSegmentSource(OkHttpTextFetcher(http)) { emptySet() }
    private val lookups = FileLookupStore(File(dataDir, "lookups"))
    private val decodersFile = File(dataDir, "decoders.json")
    private val decodersOf = ConcurrentHashMap<String, List<PhoneDecoder>>(loadDecoders(decodersFile))
    private val resolvers = ConcurrentHashMap<List<PhoneDecoder>, VideoResolver>()

    private val store = JsonDownloadStore(File(dataDir, "downloads.json"))

    private val manager: DefaultDownloadManager = DefaultDownloadManager(
        downloadDir = downloadDir,
        store = store,
        strategy = object : DownloadStrategy {
            override fun download(item: PlayableItem, target: File, audioOnly: Boolean) =
                strategyFor(item).download(item, target, audioOnly)

            override fun download(item: PlayableItem, target: File, audioOnly: Boolean, maxHeight: Int?) =
                strategyFor(item).download(item, target, audioOnly, maxHeight)
        },
        scope = scope,
    )

    init {
        HomeFiling(store, downloadDir, filer).start(scope, manager.observeRecords())
    }

    private fun strategyFor(item: PlayableItem): DownloadStrategy = videoAndPodcastDownloads(
        engine = engine,
        preferredAudioLanguages = { listOf("en") },
        resolver = resolverFor(decodersOf[item.item.id.value].orEmpty()),
        transferClient = http,
        sabr = SabrRoute.NONE,
    )

    private fun resolverFor(decoders: List<PhoneDecoder>): VideoResolver = resolvers.getOrPut(decoders) {
        Diag.log("home", "a resolver for ${PhoneCodecs(decoders).screen}")
        VideoResolver(
            engine = engine,
            skipSegments = skipSegments,
            codecSupport = PhoneCodecs(decoders),
            playerStreams = InnerTubePlayerStreams(
                innerTube,
                account = SignedInTvPlayer(account, stamps, innerTube, nSolver, signatures),
                solveN = solvingChallenges(stamps, nSolver, signatures),
                embedded = embedded::player,
                preferAccount = { true },
                botBackOff = polite.botBackOff,
                live = LiveStreamManifest(LiveStreamManifest.headsOver(http), LocalManifests::publish),
            ),
            preferredAudioLanguages = { listOf("en") },
            lookupStore = lookups,
            streamStatus = StreamStatus(http)::of,
        )
    }

    fun free(): Long = downloadDir.apply { mkdirs() }.usableSpace

    override suspend fun start(request: HomeJobRequest): Started {
        if (free() < freeFloorBytes) return Started.Refused("❌ Not enough space on the Pi (${free() / GB} GB free)")
        val item = request.item.copy(mediaUrl = request.item.mediaUrl?.let(rewrites::apply)).toPlayable()
            ?: return Started.Refused("❌ That item can't be downloaded")
        decodersOf[item.item.id.value] = request.decoders
        saveDecoders(decodersFile, decodersOf)
        Diag.log(
            "home",
            "job ${item.item.id.value} \"${item.item.title}\" audioOnly=${request.audioOnly} " +
                "maxHeight=${request.maxHeight} decoders=${request.decoders.size} free=${free() / GB}GB",
        )
        manager.download(item, request.audioOnly, request.maxHeight)
        return Started.Accepted(item.item.id.value)
    }

    override suspend fun jobs(): HomeJobs {
        val records = manager.observeRecords().first()
        val jobs = records.map { it.item.toJob(it.state) }
        val signedIn = account.isSignedIn()
        return HomeJobs(
            jobs,
            free(),
            signedIn,
            health?.problems(jobs, signedIn).orEmpty(),
            uploadLimit(),
            youTubeOwner = ownership.owner()?.label?.takeIf { signedIn },
        )
    }

    private fun uploadLimit(): Long = (free() - freeFloorBytes).coerceIn(0L, MAX_UPLOAD_BYTES)

    private val uploads = UploadReceiver(manager, ::uploadLimit, ::free)

    override suspend fun receive(id: String, audioOnly: Boolean, declaredBytes: Long, body: InputStream): Received =
        uploads.receive(id, audioOnly, declaredBytes, body)

    override suspend fun cancel(id: String): Unit = manager.delete(MediaItemId(id))

    override suspend fun retry(id: String): Unit = manager.retry(MediaItemId(id))

    override suspend fun fileOf(id: String): File? = manager.observeDownloaded().first()
        .firstOrNull { it.playable.item.id.value == id }?.localPath?.let(::File)?.takeIf { it.isFile }

    val watchHistory: YouTubeWatchHistory = HttpYouTubeWatchHistory(account, http, innerTube, stamps)

    suspend fun signedInToYouTube(): Boolean = account.isSignedIn()

    fun youTubeOwnerId(): String? = ownership.owner()?.id

    suspend fun castOf(item: PlayableItem, picture: Boolean): CastItem? {
        val id = item.item.id.value
        if (!picture || item.handle !is PlayHandle.Video || fileOf(id) != null) return null
        val resolved = resolverFor(emptyList()).resolve(item, "target-cast") ?: return null
        val pairs = resolved.qualities.filter { it.audioUrl != null }
        val chosen = pairs.firstOrNull()
        if (chosen == null) {
            Diag.log(
                "targets",
                "$id: no stream with its own audio to build a ladder from (${resolved.qualities.size} offered)"
            )
            return null
        }
        val rewrite = { url: String -> rewrites.apply(url) }
        val video = rewrite(chosen.videoUrl.value)
        val wait = YoungStreamUrl.retryDelayMs(video, System.currentTimeMillis(), errorCount = 1)
        Diag.log(
            "targets",
            "$id: a picture for a TV, ${chosen.label} with its own audio, ladder of ${pairs.size}, " +
                "young wait ${wait ?: 0}ms",
        )
        wait?.let { delay(it) }
        return CastItem(
            mediaId = id,
            uri = video,
            separateAudioUrl = chosen.audioUrl?.value?.let(rewrite),
            listening = false,
            ladder = pairs.map { CastRung(rewrite(it.videoUrl.value), it.height, it.codec) },
            durationMs = item.item.duration?.inWholeMilliseconds,
        )
    }

    suspend fun soundOf(item: PlayableItem, picture: Boolean = false): String? {
        fileOf(item.item.id.value)?.let { file ->
            Diag.log("targets", "${item.item.id.value}: the copy at home, ${file.name}")
            return file.path
        }
        val url = when (val handle = item.handle) {
            is PlayHandle.Video -> resolverFor(emptyList()).resolve(item, if (picture) "target-picture" else "speaker")
                ?.let { if (picture) it.item.mediaUrl else it.audioOnlyUrl ?: it.item.mediaUrl }
            is PlayHandle.Podcast -> handle.audioUrl ?: item.item.mediaUrl
            is PlayHandle.LocalVideo -> null
        }?.value?.let(rewrites::apply)
        val wait = url?.let { YoungStreamUrl.retryDelayMs(it, System.currentTimeMillis(), errorCount = 1) }
        Diag.log(
            "targets",
            "${item.item.id.value}: ${item.handle.label} picture=$picture -> " +
                "${if (url == null) "no stream" else "a stream"}, young wait ${wait ?: 0}ms",
        )
        wait?.let { delay(it) }
        return url
    }

    override suspend fun signIn(handover: AccountHandover): Admission {
        val admission = ownership.admit(handover.from)
        if (admission is Admission.Refused) return admission
        tokens.save(
            OAuthTokens(
                AccessToken(handover.accessToken),
                RefreshToken(handover.refreshToken),
                handover.expiresAtEpochSeconds
            ),
        )
        Diag.log(
            "home",
            "${handover.from?.label} handed over its YouTube sign-in; signed in now: ${account.isSignedIn()}",
        )
        return admission
    }

    override suspend fun signOut(by: String?): Admission {
        if (!ownership.mayForget(by)) {
            val owner = ownership.owner()?.label ?: "another phone"
            return Admission.Refused("🔒 Only the phone that signed the home server in ($owner) can sign it out")
        }
        tokens.clear()
        ownership.clear()
        Diag.log("home", "YouTube sign-in forgotten at the request of ${by ?: "an unidentified phone"}")
        return Admission.Accepted
    }

    companion object {
        const val FREE_FLOOR_BYTES = 20L * 1_000_000_000L
        const val MAX_UPLOAD_BYTES = 64L * 1_000_000_000L
        private const val GB = 1_000_000_000L
        private const val READ_TIMEOUT_S = 60L
    }
}

private fun PlayableItem.toJob(state: DownloadState): HomeJob {
    val base = HomeJob(item.id.value, toBackup(), HomeJobState.WAITING, audioOnly = false)
    return when (state) {
        is DownloadState.Downloading -> base.copy(
            state = HomeJobState.DOWNLOADING,
            bytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
        )
        is DownloadState.Downloaded -> base.copy(
            state = HomeJobState.DONE,
            audioOnly = state.audioOnly,
            bytes = File(state.localPath).length(),
            totalBytes = File(state.localPath).length(),
        )
        is DownloadState.Failed -> base.copy(state = HomeJobState.FAILED, error = state.reason)
        DownloadState.NotDownloaded -> base
    }
}

internal class Rewrites(private val pairs: List<Pair<String, String>>) {
    fun apply(url: String): String {
        val (from, to) = pairs.firstOrNull { url.startsWith(it.first) } ?: return url
        return to + url.removePrefix(from)
    }
}

internal fun rewritesFrom(spec: String?): Rewrites = Rewrites(
    spec.orEmpty().split(';').mapNotNull { pair ->
        pair.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] }
    },
)

private val DECODERS = MapSerializer(String.serializer(), ListSerializer(PhoneDecoder.serializer()))

private fun loadDecoders(file: File): Map<String, List<PhoneDecoder>> =
    runCatching { AskJson.decodeFromString(DECODERS, file.readText()) }.getOrDefault(emptyMap())

private fun saveDecoders(file: File, all: Map<String, List<PhoneDecoder>>) {
    runCatching {
        file.parentFile?.mkdirs()
        file.writeText(AskJson.encodeToString(DECODERS, all.toMap()))
    }
        .onFailure { Diag.warn("home", "decoders not saved", it) }
}
