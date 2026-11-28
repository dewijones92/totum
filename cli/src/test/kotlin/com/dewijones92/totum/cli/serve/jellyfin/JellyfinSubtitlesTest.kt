package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.filing.Jellyfin
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinSubtitlesTest {

    private val id = "94effbb4aa9c6ed543b035c8e835e170"
    private val asked = mutableListOf<String>()
    private var status = 200
    private var body = """
        {"Items":[{"Id":"$id","Name":"Spinal Tap II","Type":"Movie","MediaSources":[
          {"Id":"src0123","Container":"mkv","MediaStreams":[
            {"Type":"Video","Index":0,"Codec":"hevc"},
            {"Type":"Audio","Index":1,"Codec":"eac3","Language":"eng"},
            {"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"eng","DisplayTitle":"English - SubRip",
             "IsTextSubtitleStream":true,"IsExternal":false},
            {"Type":"Subtitle","Index":3,"Codec":"ass","Language":"cym","DisplayTitle":"Welsh - ASS - Forced",
             "IsTextSubtitleStream":true,"IsForced":true},
            {"Type":"Subtitle","Index":4,"Codec":"hdmv_pgs_subtitle","Language":"fra","DisplayTitle":"French - PGS",
             "IsTextSubtitleStream":false},
            {"Type":"Subtitle","Index":5,"Codec":"subrip","IsTextSubtitleStream":true}
          ]}
        ]}],"TotalRecordCount":1}
    """.trimIndent()

    private val fake = Interceptor { chain ->
        val request = chain.request()
        asked += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("m")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val browse = JellyfinBrowse(
        Jellyfin(
            OkHttpClient.Builder().addInterceptor(fake).build(),
            "http://jellyfin.invalid:8096",
            "k3y",
            emptyMap()
        ),
    )

    @Test
    fun `an item's text subtitle streams are offered, each as the WebVTT address Jellyfin itself serves`() {
        val found = browse.castSubtitles(id)

        assertEquals(
            listOf(
                "http://jellyfin.invalid:8096/Videos/$id/src0123/Subtitles/2/Stream.vtt?api_key=k3y",
                "http://jellyfin.invalid:8096/Videos/$id/src0123/Subtitles/3/Stream.vtt?api_key=k3y",
                "http://jellyfin.invalid:8096/Videos/$id/src0123/Subtitles/5/Stream.vtt?api_key=k3y",
            ),
            found.map { it.location },
        )
        assertEquals(listOf("en", "cy", null), found.map { it.language })
        assertEquals("English - SubRip", found[0].label)
        assertTrue(asked.single().startsWith("/Items?Ids=$id"))
        assertTrue(asked.single(), asked.single().contains("MediaSources"))
    }

    @Test
    fun `a picture-based subtitle stream is not offered, since Jellyfin cannot make WebVTT of it`() {
        val found = browse.castSubtitles(id)

        assertTrue(found.none { it.location.contains("/Subtitles/4/") })
    }

    @Test
    fun `an item with no streams, or Jellyfin not answering, offers none and does not throw`() {
        body = """{"Items":[{"Id":"$id","Name":"x","Type":"Movie"}],"TotalRecordCount":1}"""
        assertEquals(emptyList<Any>(), browse.castSubtitles(id))

        status = 500
        assertEquals(emptyList<Any>(), browse.castSubtitles(id))

        status = 200
        body = "not json"
        assertEquals(emptyList<Any>(), browse.castSubtitles(id))
    }

    @Test
    fun `only a real item id is looked up`() {
        assertEquals(emptyList<Any>(), browse.castSubtitles("x&api_key=other"))
        assertEquals(emptyList<String>(), asked)
    }
}
