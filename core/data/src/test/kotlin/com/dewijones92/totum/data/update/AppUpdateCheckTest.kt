package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckTest {

    private fun release(tag: String) = Release(
        version = AppVersion.parse(tag)!!,
        tag = tag,
        page = HttpUrl.of("https://github.com/dewijones92/totum/releases/tag/$tag"),
        publishedAt = "2026-10-12T10:00:00Z",
    )

    @Test
    fun `versions compare as numbers, not text`() {
        assertTrue(AppVersion.parse("0.1.99")!! < AppVersion.parse("0.1.100")!!)
        assertTrue(AppVersion.parse("v0.1.603")!! > AppVersion.parse("0.1.602")!!)
        assertEquals(0, AppVersion.parse("v0.1.603")!!.compareTo(AppVersion.parse("0.1.603")!!))
        assertEquals("0.1.603", AppVersion.parse("v0.1.603").toString())
    }

    @Test
    fun `a build CI did not number is not a version`() {
        assertNull(AppVersion.parse("0.1.0-dev"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun `an older install is out of date`() = runTest {
        val check = AppUpdateCheck("0.1.602", { ReleaseLookup.Found(release("v0.1.603")) })

        val status = check.checkNow("test")

        assertEquals(UpdateStatus.OutOfDate(AppVersion.parse("0.1.602")!!, release("v0.1.603")), status)
        assertEquals(status, check.status.value)
    }

    @Test
    fun `the latest install, or a newer one, is up to date`() = runTest {
        val latest = { ReleaseLookup.Found(release("v0.1.603")) }

        assertTrue(AppUpdateCheck("0.1.603", latest).checkNow("test") is UpdateStatus.UpToDate)
        assertTrue(AppUpdateCheck("0.1.604", latest).checkNow("test") is UpdateStatus.UpToDate)
    }

    @Test
    fun `a dev build never asks GitHub`() = runTest {
        var asked = 0
        val check = AppUpdateCheck("0.1.0-dev", {
            asked++
            ReleaseLookup.Found(release("v0.1.603"))
        })

        assertEquals(UpdateStatus.NotApplicable("0.1.0-dev"), check.checkNow("test"))
        assertEquals(0, asked)
    }

    @Test
    fun `a failed lookup keeps the last verdict`() = runTest {
        var answer: ReleaseLookup = ReleaseLookup.Found(release("v0.1.603"))
        val check = AppUpdateCheck("0.1.602", { answer })
        val outOfDate = check.checkNow("first")

        answer = ReleaseLookup.Unavailable("offline")

        assertEquals(outOfDate, check.checkNow("second"))
        assertEquals("offline", check.diagnostics()["update.lastProblem"])
    }

    @Test
    fun `a failed first lookup is unknown`() = runTest {
        val check = AppUpdateCheck("0.1.602", { ReleaseLookup.Unavailable("offline") })

        assertEquals(UpdateStatus.Unknown(AppVersion.parse("0.1.602")!!, "offline"), check.checkNow("test"))
    }

    @Test
    fun `the report says whether the build was out of date`() = runTest {
        val check = AppUpdateCheck("0.1.602", { ReleaseLookup.Found(release("v0.1.603")) }, now = { 42L })
        check.checkNow("test")

        val report = check.diagnostics()

        assertEquals("0.1.602", report["update.installed"])
        assertTrue(report["update.status"]!!.startsWith("OUT OF DATE: v0.1.603 is out"))
        assertEquals("42", report["update.checkedAtMs"])
    }

    @Test
    fun `reads the tag and page from GitHub's latest release`() = runTest {
        var requested: HttpUrl? = null
        val body = """
            {"tag_name": "v0.1.603",
             "html_url": "https://github.com/dewijones92/totum/releases/tag/v0.1.603",
             "published_at": "2026-10-12T10:00:00Z", "assets": []}
        """.trimIndent()
        val source = GitHubLatestRelease({ url ->
            requested = url
            FetchResult.Success(body)
        })

        assertEquals(ReleaseLookup.Found(release("v0.1.603")), source.latest())
        assertEquals("https://api.github.com/repos/dewijones92/totum/releases/latest", requested?.value)
    }

    @Test
    fun `a GitHub failure or an odd reply is unavailable`() = runTest {
        val failed = GitHubLatestRelease({ FetchResult.Failure("rate limited", 403) }).latest()
        val odd = GitHubLatestRelease({ FetchResult.Success("""{"tag_name":"nightly"}""") }).latest()

        assertTrue(failed is ReleaseLookup.Unavailable && failed.detail.contains("403"))
        assertTrue(odd is ReleaseLookup.Unavailable)
    }
}
