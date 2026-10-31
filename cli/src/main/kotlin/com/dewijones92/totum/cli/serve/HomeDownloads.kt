package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.download.DefaultDownloadManager
import com.dewijones92.totum.data.download.DownloadManager
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
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.HttpYouTubeAuth
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.player.HttpEmbedHostFlagsSource
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.HttpVisitorIdSource
import com.dewijones92.totum.innertube.player.MemoisedNSolver
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.SignatureSolver
import com.dewijones92.totum.video.EmbeddedPlayer
import com.dewijones92.totum.video.FileLookupStore
import com.dewijones92.totum.video.InnerTubePlayerStreams
import com.dewijones92.totum.video.PhoneCodecs
import com.dewijones92.totum.video.SabrRoute
import com.dewijones92.totum.video.SignedInTvPlayer
import com.dewijones92.totum.video.StreamStatus
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.video.solvingChallenges
import com.dewijones92.totum.video.videoAndPodcastDownloads
import com.dewijones92.totum.ytdlp.process.ProcessYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal sealed interface Started {
    data class Accepted(val id: String) : Started
    data class Refused(val why: String) : Started
}

internal interface Home {
    suspend fun start(request: HomeJobRequest): Started
    suspend fun jobs(): HomeJobs
    suspend fun cancel(id: String)
    suspend fun retry(id: String)
    suspend fun fileOf(id: String): File?
    suspend fun signIn(handover: AccountHandover)
    suspend fun signOut()
}

internal class HomeDownloads(
    private val dataDir: File,
    private val downloadDir: File,
    scope: CoroutineScope,
    private val rewrites: Rewrites = Rewrites(emptyList()),
    private val freeFloorBytes: Long = FREE_FLOOR_BYTES,
) : Home {
    private val http = OkHttpClient.Builder().readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS).build()
    private val engine = ProcessYtDlpEngine()
    private val tokens = FileTokenStore(File(dataDir, "youtube.json"))
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

    private val manager: DownloadManager = DefaultDownloadManager(
        downloadDir = downloadDir,
        store = JsonDownloadStore(File(dataDir, "downloads.json")),
        strategy = object : DownloadStrategy {
            override fun download(item: PlayableItem, target: File, audioOnly: Boolean) =
                strategyFor(item).download(item, target, audioOnly)

            override fun download(item: PlayableItem, target: File, audioOnly: Boolean, maxHeight: Int?) =
                strategyFor(item).download(item, target, audioOnly, maxHeight)
        },
        scope = scope,
    )

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
            ),
            preferredAudioLanguages = { listOf("en") },
            lookupStore = lookups,
            streamStatus = StreamStatus(http)::of,
        )
    }

    fun free(): Long = downloadDir.apply { mkdirs() }.usableSpace

    override suspend fun start(request: HomeJobRequest): Started {
        if (free() < freeFloorBytes) return Started.Refused("❌ Not enough space at home (${free() / GB} GB free)")
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
        return HomeJobs(records.map { it.item.toJob(it.state) }, free(), account.isSignedIn())
    }

    override suspend fun cancel(id: String): Unit = manager.delete(MediaItemId(id))

    override suspend fun retry(id: String): Unit = manager.retry(MediaItemId(id))

    override suspend fun fileOf(id: String): File? = manager.observeDownloaded().first()
        .firstOrNull { it.playable.item.id.value == id }?.localPath?.let(::File)?.takeIf { it.isFile }

    override suspend fun signIn(handover: AccountHandover) {
        tokens.save(
            OAuthTokens(
                AccessToken(handover.accessToken),
                RefreshToken(handover.refreshToken),
                handover.expiresAtEpochSeconds
            ),
        )
        Diag.log("home", "the phone handed over its YouTube sign-in; signed in now: ${account.isSignedIn()}")
    }

    override suspend fun signOut() {
        tokens.clear()
        Diag.log("home", "YouTube sign-in forgotten at the phone's request")
    }

    companion object {
        const val FREE_FLOOR_BYTES = 20L * 1_000_000_000L
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
