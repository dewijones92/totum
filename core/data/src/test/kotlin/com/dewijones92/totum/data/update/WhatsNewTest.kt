package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    private val v919 = AppVersion.parse("0.1.919")

    @Test
    fun `an update, or a first run of this feature, shows the card`() {
        assertEquals(WhatsNewVerdict.Show, whatsNewVerdict(v919, lastSeen = "0.1.900", freshInstall = false))
        assertEquals(WhatsNewVerdict.Show, whatsNewVerdict(v919, lastSeen = null, freshInstall = false))
    }

    @Test
    fun `a fresh install only records the version, and says nothing`() {
        assertEquals(WhatsNewVerdict.RecordOnly, whatsNewVerdict(v919, lastSeen = null, freshInstall = true))
    }

    @Test
    fun `a version already shown is not shown again`() {
        assertEquals(WhatsNewVerdict.Nothing, whatsNewVerdict(v919, lastSeen = "0.1.919", freshInstall = false))
        assertEquals(WhatsNewVerdict.Nothing, whatsNewVerdict(v919, lastSeen = "0.1.919", freshInstall = true))
    }

    @Test
    fun `a build with no version number says nothing, whatever was seen`() {
        assertEquals(WhatsNewVerdict.Nothing, whatsNewVerdict(null, lastSeen = null, freshInstall = false))
        assertEquals(WhatsNewVerdict.Nothing, whatsNewVerdict(null, lastSeen = "0.1.1", freshInstall = false))
    }

    @Test
    fun `a fresh install that has seen an older version is an update, not a fresh install`() {
        assertEquals(WhatsNewVerdict.Show, whatsNewVerdict(v919, lastSeen = "0.1.100", freshInstall = true))
    }

    @Test
    fun `commit subjects become readable lines with the emoji for their kind`() {
        val notes = """
            Signed APK.

            ## What's new
            - feat(cast): the TV volume is on the strip
            - fix(queue): a group's repeat one no longer loops the first track
            - perf(ui): rows redraw less
            - docs: the cast section
            - refactor(core): one codec ranking
            - test: more of them
            - chore(deps): bump
        """.trimIndent()

        assertEquals(
            listOf(
                "✨ Cast: the TV volume is on the strip",
                "🐛 Queue: a group's repeat one no longer loops the first track",
                "⚡ Ui: rows redraw less",
                "📝 the cast section",
                "🧹 Core: one codec ranking",
                "🧪 more of them",
                "🔧 Deps: bump",
            ),
            whatsNewLines(notes),
        )
    }

    @Test
    fun `a subject with no known kind, or none, is a plain bullet`() {
        val notes = "## What's new\n- wip(thing): half done\n- Merge branch main\n- feat!: breaking change"

        assertEquals(
            listOf("• wip(thing): half done", "• Merge branch main", "✨ breaking change"),
            whatsNewLines(notes),
        )
    }

    @Test
    fun `trailer lines that CI picked up from commit bodies are left out`() {
        val notes = """
            ## What's new
            - feat: one
            - Co-Authored-By: Claude <noreply@anthropic.com>
            - Claude-Session: https://claude.ai/code/session_x
            - Signed-off-by: Someone
            - co-authored-by: lower case too
            - feat: two
        """.trimIndent()

        assertEquals(listOf("✨ one", "✨ two"), whatsNewLines(notes))
    }

    @Test
    fun `the list stops at the next heading and starts after this one, however it is written`() {
        val notes = "## Notes\n- fix: not this\n## WHAT'S NEW  \n* feat: this\n\n## Assets\n- fix: nor this"

        assertEquals(listOf("✨ this"), whatsNewLines(notes))
    }

    @Test
    fun `the same line twice appears once`() {
        assertEquals(listOf("✨ same"), whatsNewLines("## What's new\n- feat: same\n- feat: same"))
    }

    @Test
    fun `no section, no notes, or an empty section is no lines`() {
        assertTrue(whatsNewLines(null).isEmpty())
        assertTrue(whatsNewLines("").isEmpty())
        assertTrue(whatsNewLines("Just words, no heading.").isEmpty())
        assertTrue(whatsNewLines("## What's new\n\n## Other\n- fix: x").isEmpty())
    }

    @Test
    fun `a long release of a thousand commits reads in order without losing any`() {
        val notes = "## What's new\n" + (1..1_000).joinToString("\n") { "- feat(area$it): change $it" }

        val lines = whatsNewLines(notes)

        assertEquals(1_000, lines.size)
        assertEquals("✨ Area1: change 1", lines.first())
        assertEquals("✨ Area1000: change 1000", lines.last())
    }

    @Test
    fun `GitHub's reply carries the APK asset, its size and the notes`() = runTest {
        val body = """
            {"tag_name": "v0.1.920", "html_url": "https://github.com/dewijones92/totum/releases/tag/v0.1.920",
             "published_at": "2026-11-13T10:00:00Z",
             "body": "## What's new\n- feat: one",
             "assets": [
               {"name": "totum.apk.sha256", "browser_download_url": "https://x.test/totum.apk.sha256", "size": 90},
               {"name": "totum.apk", "browser_download_url": "https://x.test/totum.apk", "size": 33000000}
             ]}
        """.trimIndent()

        val found = GitHubLatestRelease({ FetchResult.Success(body) }).latest() as ReleaseLookup.Found

        assertEquals(ReleaseApk(HttpUrl.of("https://x.test/totum.apk"), 33_000_000L), found.release.apk)
        assertEquals("## What's new\n- feat: one", found.release.notes)
    }

    @Test
    fun `a release with no totum apk asset, or one with no address, has no APK`() = runTest {
        val none = """{"tag_name":"v0.1.920","assets":[{"name":"o.zip","browser_download_url":"https://x.test/o"}]}"""
        val noAddress = """{"tag_name":"v0.1.920","assets":[{"name":"totum.apk","size":5}]}"""

        assertNull((GitHubLatestRelease({ FetchResult.Success(none) }).latest() as ReleaseLookup.Found).release.apk)
        val missing = GitHubLatestRelease({ FetchResult.Success(noAddress) }).latest() as ReleaseLookup.Found
        assertNull(missing.release.apk)
    }

    @Test
    fun `a release asked for by tag is fetched from its own address`() = runTest {
        var requested: HttpUrl? = null
        val source = GitHubLatestRelease({ url ->
            requested = url
            FetchResult.Success("""{"tag_name":"v0.1.919"}""")
        })

        val found = source.byTag("v0.1.919") as ReleaseLookup.Found

        assertEquals("https://api.github.com/repos/dewijones92/totum/releases/tags/v0.1.919", requested?.value)
        assertEquals("v0.1.919", found.release.tag)
    }

    @Test
    fun `a release with no page address gets the tag page`() = runTest {
        val found = GitHubLatestRelease({ FetchResult.Success("""{"tag_name":"v0.1.919"}""") }).latest()

        assertEquals(
            "https://github.com/dewijones92/totum/releases/tag/v0.1.919",
            (found as ReleaseLookup.Found).release.page.value,
        )
    }
}
