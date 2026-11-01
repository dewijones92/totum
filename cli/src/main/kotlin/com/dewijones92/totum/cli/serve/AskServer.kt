package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.ai.AiServer
import com.dewijones92.totum.cli.serve.ai.ClaudeCodeCompleter
import com.dewijones92.totum.cli.serve.ai.RemoteCompleter
import com.dewijones92.totum.cli.serve.ai.UsageLog
import com.dewijones92.totum.cli.serve.filing.FileCheck
import com.dewijones92.totum.cli.serve.filing.Filing
import com.dewijones92.totum.cli.serve.filing.FolderLibrary
import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.cli.serve.filing.LibraryFiler
import com.dewijones92.totum.cli.serve.filing.Shelf
import com.dewijones92.totum.cli.serve.filing.SidecarMaker
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskJob
import com.dewijones92.totum.data.ask.AskJson
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.JobState
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.home.HomeProblem
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class AskServer(
    private val brain: Brain,
    private val scope: CoroutineScope,
    private val home: HomeRoutes? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    concurrent: Int = CONCURRENT,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(concurrent)

    private data class Job(val started: Long, val snapshot: AskJob)

    fun start(port: Int): HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        executor = Executors.newFixedThreadPool(THREADS)
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
        Diag.log("serve", "listening on $port")
    }

    private fun healthJson(): String {
        val homeJobs = home?.let { runBlocking { it.jobs() } }
        return buildJsonObject {
            put("ok", true)
            put("jobs", jobs.size)
            homeJobs?.let { h ->
                put("homeJobs", h.jobs.size)
                put("freeBytes", h.freeBytes)
                put("youTubeSignedIn", h.youTubeSignedIn)
                put("problems", AskJson.encodeToJsonElement(ListSerializer(HomeProblem.serializer()), h.problems))
            }
        }.toString()
    }

    internal fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.trimEnd('/')
        val method = exchange.requestMethod
        when {
            method == "GET" && path == "/health" -> exchange.reply(OK, healthJson())
            method == "POST" && path == "/ask" -> start(exchange, "ask", AskRequest.serializer()) { brain.suggest(it) }
            method == "POST" && path == "/vet" -> start(exchange, "vet", VetRequest.serializer()) { brain.vet(it) }
            method == "GET" && path.startsWith("/job/") -> job(exchange, path.removePrefix("/job/"))
            home != null && home.handles(path) -> home.handle(exchange, method, path)
            else -> exchange.reply(NOT_FOUND, """{"error":"no such route: $method $path"}""")
        }
    }

    private fun <T> start(
        exchange: HttpExchange,
        what: String,
        serializer: KSerializer<T>,
        work: suspend (T) -> Any,
    ) {
        val text = exchange.requestBody.bufferedReader().readText()
        val request = runCatching { AskJson.decodeFromString(serializer, text) }.getOrElse {
            Diag.warn("serve", "$what: unreadable request: ${text.take(LOGGED)}", it)
            exchange.reply(BAD_REQUEST, """{"error":"unreadable request"}""")
            return
        }
        prune()
        val id = newId()
        val started = clock()
        jobs[id] = Job(started, AskJob(id, JobState.RUNNING))
        Diag.log("serve", "$what job $id started: ${text.take(LOGGED)}")
        scope.launch {
            val done = permits.withPermit {
                runCatching { work(request) }.fold(
                    onSuccess = { answer ->
                        AskJob(
                            id,
                            JobState.DONE,
                            clock() - started,
                            ask = answer as? AskAnswer,
                            vet = answer as? VetAnswer,
                        )
                    },
                    onFailure = { AskJob(id, JobState.FAILED, clock() - started, error = it.message ?: "failed") },
                )
            }
            Diag.log("serve", "$what job $id ${done.state} in ${done.elapsedMs}ms${done.error?.let { ": $it" } ?: ""}")
            jobs[id] = Job(started, done)
        }
        exchange.reply(ACCEPTED, AskJson.encodeToString(AskJob.serializer(), AskJob(id, JobState.RUNNING)))
    }

    private fun job(exchange: HttpExchange, id: String) {
        val job = jobs[id] ?: return exchange.reply(NOT_FOUND, """{"error":"no such job"}""")
        val snapshot = job.snapshot.let {
            if (it.state == JobState.RUNNING) it.copy(elapsedMs = clock() - job.started) else it
        }
        exchange.reply(OK, AskJson.encodeToString(AskJob.serializer(), snapshot))
    }

    private fun prune() {
        val cutoff = clock() - KEEP_MS
        jobs.entries.removeIf { it.value.started < cutoff }
    }

    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val OK = 200
        const val ACCEPTED = 202
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONCURRENT = 2
        const val THREADS = 16
        const val KEEP_MS = 30 * 60_000L
        const val LOGGED = 300
    }
}

private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
    try {
        block(this)
    } catch (e: java.io.IOException) {
        Diag.warn("serve", "request $requestMethod $requestURI failed", e)
    } finally {
        close()
    }
}

internal suspend fun serveAsk(port: Int, out: (String) -> Unit): Int = coroutineScope {
    val brain = brainFromEnv()
    val jellyfin = jellyfinFromEnv()
    val downloadDir = File(System.getenv("TOTUM_DOWNLOADS") ?: "${System.getProperty("user.home")}/totum-downloads")
    val health = HomeHealth(
        HomeHealth.Probes(
            freeBytes = { downloadDir.apply { mkdirs() }.usableSpace },
            ai = aiStateProbe(),
            jellyfin = jellyfin?.let { j -> { withContext(Dispatchers.IO) { j.reachable() } } },
        ),
    )
    val home = HomeDownloads(
        dataDir = File(System.getenv("TOTUM_DATA") ?: "${System.getProperty("user.home")}/.totum"),
        downloadDir = downloadDir,
        scope = this,
        rewrites = rewritesFrom(System.getenv("TOTUM_REWRITE")),
        filer = filerFromEnv(brain, jellyfin),
        health = health,
    )
    AskServer(brain, this, HomeRoutes(home)).start(port)
    out("totum serve: listening on $port")
    awaitCancellation()
}

internal suspend fun fileCheck(command: Command.FileCheck, out: (String) -> Unit): Int {
    val paths = FileCheck.listing(File(command.listing))
    val brain = brainFromEnv()
    val check = FileCheck(paths) { request, library ->
        if (command.rulesOnly) Filing.byRules(request.name, library, request.from) else brain.file(request)
    }
    if (command.tries.isNotEmpty()) {
        command.tries.forEach { tried ->
            val name = tried.substringBefore('|')
            val from = tried.substringAfter('|', "").ifBlank { null }
            val plan = check.plan(name, from)
            out("$name (from ${from ?: "-"}) -> ${plan.getOrNull() ?: "failed: ${plan.exceptionOrNull()?.message}"}")
        }
        return 0
    }
    val samples = check.samples(command.samples, command.seed)
    val by = if (command.rulesOnly) "by rules" else "with ${System.getenv("TOTUM_ASK_MODEL") ?: "haiku"}"
    out("filing ${samples.size} sample(s) $by")
    val results = check.run(samples)
    val report = FileCheck.report(if (command.rulesOnly) "rules" else "brain", results)
    command.out?.let { File(it).writeText(report) }
    out(FileCheck.summary(if (command.rulesOnly) "rules" else "brain", results))
    return 0
}

internal fun jellyfinFromEnv(env: (String) -> String? = System::getenv): Jellyfin? {
    val url = env("TOTUM_JELLYFIN_URL") ?: return null
    val key = env("TOTUM_JELLYFIN_KEY") ?: return null
    return Jellyfin(
        OkHttpClient(),
        url,
        key,
        mapOf(
            Shelf.MOVIES to (env("TOTUM_JELLYFIN_MOVIES") ?: "/data/movies"),
            Shelf.SERIES to (env("TOTUM_JELLYFIN_SERIES") ?: "/data/tvshows"),
        ),
    )
}

internal fun filerFromEnv(brain: Brain, jellyfin: Jellyfin?, env: (String) -> String? = System::getenv): LibraryFiler? {
    val movies = env("TOTUM_LIBRARY_MOVIES") ?: return null
    val series = env("TOTUM_LIBRARY_SERIES") ?: return null
    val told = if (jellyfin != null) "will be told" else "not configured"
    Diag.log("filing", "filing finished torrents into $movies and $series; Jellyfin $told")
    return LibraryFiler(
        FolderLibrary(mapOf(Shelf.MOVIES to File(movies), Shelf.SERIES to File(series))),
        jellyfin,
        brain::file,
        refused = { why -> brain.refused("file", why) },
        sidecars = SidecarMaker()::make,
    )
}

internal fun aiStateProbe(env: (String) -> String? = System::getenv): suspend () -> HomeHealth.AiState {
    val remote = env("TOTUM_AI_URL") ?: return { HomeHealth.AiState.LOCAL }
    val http = OkHttpClient.Builder().callTimeout(AI_HEALTH_TIMEOUT_S, TimeUnit.SECONDS).build()
    return {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(remote.trimEnd('/') + "/health").build()).execute().use { response ->
                    val signed = AskJson.parseToJsonElement(response.body.string()).jsonObject["signedIn"]
                        ?.jsonPrimitive?.booleanOrNull
                    when (signed) {
                        true -> HomeHealth.AiState.SIGNED_IN
                        false -> HomeHealth.AiState.NOT_SIGNED_IN
                        null -> HomeHealth.AiState.UNKNOWN
                    }
                }
            }.getOrElse { HomeHealth.AiState.UNREACHABLE }
        }
    }
}

internal fun brainFromEnv(env: (String) -> String? = System::getenv): Brain {
    val remote = env("TOTUM_AI_URL")
    Diag.log(
        "ask",
        if (remote != null) "thinking through the AI service at $remote" else "thinking with the local claude"
    )
    return if (remote != null) {
        PromptBrain(
            RemoteCompleter(remote, OkHttpClient.Builder().readTimeout(AI_READ_TIMEOUT_S, TimeUnit.SECONDS).build())
        )
    } else {
        claudeCodeBrain(claude = env("TOTUM_CLAUDE") ?: "claude", model = env("TOTUM_ASK_MODEL") ?: "haiku")
    }
}

internal suspend fun aiServe(port: Int, out: (String) -> Unit): Int {
    val model = System.getenv("TOTUM_AI_MODEL") ?: "haiku"
    val usage =
        UsageLog(File(System.getenv("TOTUM_DATA") ?: "${System.getProperty("user.home")}/.totum", "ai-usage.jsonl"))
    val completer = ClaudeCodeCompleter(System.getenv("TOTUM_CLAUDE") ?: "claude", model, record = usage::record)
    val token = !System.getenv("CLAUDE_CODE_OAUTH_TOKEN").isNullOrBlank()
    val stored = File(System.getProperty("user.home"), ".claude/.credentials.json").isFile
    if (!token && !stored) {
        Diag.warn(
            "ai",
            "no Claude sign-in configured (CLAUDE_CODE_OAUTH_TOKEN is empty); saying so on /health"
        )
    }
    AiServer(completer, usage, model, hasCredentials = token || stored).start(port)
    out("totum ai-serve: $model listening on $port")
    awaitCancellation()
}

private const val AI_READ_TIMEOUT_S = 330L
private const val AI_HEALTH_TIMEOUT_S = 5L

internal fun sidecars(files: List<String>, out: (String) -> Unit): Int {
    val maker = SidecarMaker()
    files.map(::File).filter { it.isFile }.forEach { video ->
        val made = maker.make(video)
        out("${video.name}: ${made.joinToString { it.name }.ifEmpty { "no new sidecars" }}")
    }
    return 0
}
