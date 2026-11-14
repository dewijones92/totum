package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.PlayHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCheckTest {

    private val release = Release(
        version = AppVersion.parse("0.1.920")!!,
        tag = "v0.1.920",
        page = HttpUrl.of("https://github.com/dewijones92/totum/releases/tag/v0.1.920"),
        publishedAt = null,
        apk = ReleaseApk(HttpUrl.of("https://github.com/dewijones92/totum/releases/download/v0.1.920/a.apk"), 33_000),
    )

    private val installed = ApkFacts("com.dewijones92.totum", "0.1.919", setOf("aa11", "bb22"))

    private fun download(
        packageName: String? = "com.dewijones92.totum",
        versionName: String? = "0.1.920",
        signers: Set<String> = setOf("aa11", "bb22"),
    ) = ApkFacts(packageName, versionName, signers)

    private fun refusal(archive: ApkFacts?) = (judgeApk(archive, installed, release) as ApkVerdict.Refused).why

    @Test
    fun `the right app, signed with the same key, at the release's version is genuine`() {
        assertEquals(ApkVerdict.Genuine, judgeApk(download(), installed, release))
    }

    @Test
    fun `a file Android cannot read as an app is refused`() {
        assertTrue(refusal(null).contains("could not read"))
    }

    @Test
    fun `a different app is refused by name`() {
        val why = refusal(download(packageName = "com.evil.totum"))

        assertTrue(why, "com.evil.totum" in why && "com.dewijones92.totum" in why)
    }

    @Test
    fun `a file with no package name is refused as a different app`() {
        assertTrue(refusal(download(packageName = null)).contains("different app"))
    }

    @Test
    fun `an unsigned file is refused`() {
        assertEquals("the file is not signed", refusal(download(signers = emptySet())))
    }

    @Test
    fun `a file signed with another key is refused, since Android would refuse it`() {
        assertTrue(refusal(download(signers = setOf("cc33"))).contains("different key"))
    }

    @Test
    fun `a file signed with only some of the installed keys is refused`() {
        assertTrue(refusal(download(signers = setOf("aa11"))).contains("different key"))
        assertTrue(refusal(download(signers = setOf("aa11", "bb22", "cc33"))).contains("different key"))
    }

    @Test
    fun `a file of another version than the release says is refused with both versions`() {
        val why = refusal(download(versionName = "0.1.918"))

        assertTrue(why, "0.1.918" in why && "0.1.920" in why)
    }

    @Test
    fun `a file with no version is refused`() {
        assertTrue(refusal(download(versionName = null)).contains("version"))
    }

    @Test
    fun `the checks run in order, so the first thing wrong is the reason given`() {
        val why = refusal(download(packageName = "com.evil.totum", signers = emptySet(), versionName = "9"))

        assertTrue(why, "different app" in why)
    }

    @Test
    fun `the release's APK becomes a download item named for its version and address`() {
        val item = release.apkDownload()!!

        assertEquals("app-update:v0.1.920", item.item.id.value)
        assertEquals("Totum 0.1.920", item.item.title)
        assertEquals(release.apk!!.url, item.item.mediaUrl)
        assertTrue(item.handle is PlayHandle.Podcast)
    }

    @Test
    fun `a release with no APK has nothing to download`() {
        assertNull(release.copy(apk = null).apkDownload())
    }
}
