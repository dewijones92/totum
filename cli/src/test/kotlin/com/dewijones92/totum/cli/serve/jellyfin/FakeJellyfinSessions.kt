package com.dewijones92.totum.cli.serve.jellyfin

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.Collections

internal class FakeJellyfinSessions : AutoCloseable {

    class Call(val method: String, val target: String, val authorization: String?, val body: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    @Volatile var sessions: String = "[]"

    @Volatile var commandAnswer: Int = NO_CONTENT

    @Volatile var sessionsAnswer: Int = OK

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                calls += Call(request.method.orEmpty(), target, request.headers["Authorization"], bodyOf(request))
                return when {
                    request.method == "GET" && request.url.encodedPath == "/Sessions" ->
                        MockResponse.Builder().code(sessionsAnswer).body(sessions).build()
                    request.method == "POST" && request.url.encodedPath.startsWith("/Sessions/") ->
                        MockResponse.Builder().code(commandAnswer).build()
                    else -> MockResponse.Builder().code(NOT_FOUND).build()
                }
            }
        }
        start()
    }

    val base: String get() = server.url("/").toString().trimEnd('/')

    fun posts(): List<Call> = calls.filter { it.method == "POST" }

    fun gets(): List<Call> = calls.filter { it.method == "GET" }

    fun lastPost(): Call = posts().last()

    override fun close() {
        server.close()
    }

    private fun bodyOf(request: RecordedRequest): String = request.body?.utf8().orEmpty()

    companion object {
        const val OK = 200
        const val NO_CONTENT = 204
        const val NOT_FOUND = 404

        fun session(
            deviceId: String = "dev-1",
            deviceName: String = "Test TV",
            sessionId: String = "0123456789abcdef0123456789abcdef",
            controllable: Boolean = true,
            nowPlaying: String? = null,
            nowPlayingName: String = "A Test Film",
            positionTicks: Long = 0,
            runTimeTicks: Long = 102_000_000_000,
            paused: Boolean = false,
            volume: Int? = null,
            lastActivity: String = "2026-01-01T00:00:00.0000000Z",
        ): String {
            val playing = nowPlaying?.let {
                """"NowPlayingItem":{"Id":"$it","Name":"$nowPlayingName","Type":"Movie",""" +
                    """"RunTimeTicks":$runTimeTicks},"""
            }.orEmpty()
            val level = volume?.let { """"VolumeLevel":$it,""" }.orEmpty()
            return """
                {"Id":"$sessionId","DeviceId":"$deviceId","DeviceName":"$deviceName","Client":"Jellyfin Android TV",
                 "LastActivityDate":"$lastActivity",$playing
                 "PlayState":{"PositionTicks":$positionTicks,"CanSeek":true,"IsPaused":$paused,$level"IsMuted":false},
                 "Capabilities":{"SupportsMediaControl":true},
                 "SupportsMediaControl":$controllable,"SupportsRemoteControl":$controllable}
            """.trimIndent()
        }

        fun list(vararg sessions: String): String = sessions.joinToString(",", "[", "]")
    }
}
