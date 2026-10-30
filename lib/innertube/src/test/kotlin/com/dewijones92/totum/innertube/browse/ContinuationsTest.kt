package com.dewijones92.totum.innertube.browse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinuationsTest {

    private fun find(body: String) = Continuations.find(Json.parseToJsonElement(body))

    /** The shape the TV and WEB clients use today. */
    @Test
    fun `finds a continuationCommand token`() {
        val token = find(
            """
            {"contents":{"items":[
              {"tileRenderer":{}},
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"CBQ%3D"}}}}
            ]}}
            """.trimIndent(),
        )

        assertEquals("CBQ%3D", token?.value)
    }

    /**
     * YouTube has shipped at least three continuation shapes and still serves the older
     * ones on some feeds. Keying on one path means silently ceasing to paginate, which
     * looks exactly like reaching the end.
     */
    @Test
    fun `finds the older nextContinuationData shape`() {
        val token = find("""{"continuations":[{"nextContinuationData":{"continuation":"legacy-token"}}]}""")

        assertEquals("legacy-token", token?.value)
    }

    @Test
    fun `finds a reloadContinuationData shape`() {
        val token = find("""{"continuations":[{"reloadContinuationData":{"continuation":"reload-token"}}]}""")

        assertEquals("reload-token", token?.value)
    }

    @Test
    fun `no token means the last page`() {
        assertNull(find("""{"contents":{"items":[{"tileRenderer":{"contentId":"abc"}}]}}"""))
    }

    /**
     * A shelf inside a feed carries its own continuation ("more from this channel"),
     * which would page the shelf instead of the feed. The feed's own token comes last.
     */
    @Test
    fun `takes the last token, so a nested shelf does not win`() {
        val token = find(
            """
            {"contents":{"shelves":[
              {"shelf":{"continuations":[{"nextContinuationData":{"continuation":"shelf-token"}}]}}
            ],"footer":{"continuationItemRenderer":
              {"continuationEndpoint":{"continuationCommand":{"token":"feed-token"}}}}}}
            """.trimIndent(),
        )

        assertEquals("feed-token", token?.value)
    }

    /**
     * A real search response, in miniature. YouTube puts six filter chips ("Shorts",
     * "Live", …) *after* the results list, each holding a continuation of its own — so
     * "take the last token" picked a filter, and following it returned a result set with
     * no videos in it. Search paginated once, to nothing, and stopped. Verified against
     * the live endpoint: the chip token yields 0 videoRenderers, the real one yields 21.
     */
    @Test
    fun `a filter chip is not mistaken for the next page`() {
        val token = find(
            """
            {"contents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[{"videoRenderer":{"videoId":"abc"}}]}},
              {"continuationItemRenderer":
                {"continuationEndpoint":{"continuationCommand":{"token":"next-page"}}}}
            ],"subMenu":{"searchSubMenuRenderer":{"chipCloud":{"chipCloudRenderer":{"chips":[
              {"chipCloudChipRenderer":{"navigationEndpoint":
                {"continuationCommand":{"token":"filter-shorts"}}}},
              {"chipCloudChipRenderer":{"navigationEndpoint":
                {"continuationCommand":{"token":"filter-live"}}}}
            ]}}}}}}}
            """.trimIndent(),
        )

        assertEquals("next-page", token?.value)
    }

    @Test
    fun `a channel tab pages its grid, not its sort chips or its About panel`() {
        val body = checkNotNull(
            javaClass.getResourceAsStream("/channel_tab_continuations_web_sample.json")
        ) { "missing" }
            .bufferedReader().readText()

        val token = find(body)

        assertEquals("GRID", token?.value)
    }

    @Test
    fun `a TV subscriptions response pages its All tab, not the last channel's tab`() {
        val body = checkNotNull(javaClass.getResourceAsStream("/subscriptions_tv_sample.json")) { "missing" }
            .bufferedReader().readText()

        assertEquals("ALL-NEXT", find(body)?.value)
    }

    @Test
    fun `a next page wins over a reload of the same page, wherever it sits`() {
        val token = find(
            """
            {"content":{"list":{"continuations":[{"nextContinuationData":{"continuation":"next"}}]}},
             "continuation":{"reloadContinuationData":{"continuation":"reload"}}}
            """.trimIndent(),
        )

        assertEquals("next", token?.value)
    }

    @Test
    fun `a blank token is treated as absent rather than crashing`() {
        val body = """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":""}}}}"""

        assertNull(find(body))
    }
}
