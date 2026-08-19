package com.dewijones92.totum.account

import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A saved sign-in is on DISK by the time save() returns.
 *
 * `prefs.edit { }` defaults to `apply()`, which writes to memory and schedules the disk write. So
 * `isSignedIn()` answers true immediately while nothing has been persisted, and a process that dies
 * before the flush loses the sign-in silently. That is not theoretical: it happened on 2026-08-19.
 * A device-code sign-in completed on the emulator -- `signedIn=true`, the flow's own `Succeeded` event,
 * tokens saved -- and once the process ended there was no `youtube_account.xml` at all. Dewi had
 * approved the code by hand, a device code is single-use, and the approval was simply thrown away.
 *
 * A token is a rare and expensive write: it costs a person walking to another device and typing a code.
 * `commit()` blocks, which is exactly what is wanted here, and this store is already on Dispatchers.IO.
 *
 * Asserted against the FILE rather than against `load()`, deliberately: SharedPreferences serves reads
 * from its in-memory map, so a `load()` after `save()` passes just as happily with the bug present. The
 * only thing that distinguishes durable from not is bytes on disk.
 */
class ASignInSurvivesTheProcessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir.parentFile, "shared_prefs/$PREFS.xml")
    private lateinit var store: SharedPrefsTokenStore

    @Before
    fun startClean() = runBlocking {
        store = SharedPrefsTokenStore(context, PREFS)
        store.clear()
        file.delete()
        Unit
    }

    @After
    fun leaveNothingBehind() = runBlocking { store.clear() }

    @Test
    fun aSavedTokenIsOnDiskBeforeSaveReturns() = runBlocking {
        store.save(OAuthTokens(AccessToken("access-value"), RefreshToken("refresh-value"), 1_800_000L))

        assertTrue(
            "save() returned with nothing on disk at ${file.absolutePath} — an interrupted process would " +
                "lose the sign-in, and a device code cannot be approved twice",
            file.exists() && file.readText().contains("refresh_token"),
        )
    }

    private companion object {
        /**
         * Its OWN file, never the real one. The first version of this test used the default and its
         * teardown `clear()` therefore signed the device out -- on 2026-08-19 that destroyed a sign-in
         * Dewi had approved by hand minutes earlier, which a single-use device code cannot repeat.
         * A test that can spend someone's time to run is a test that must not touch shared state.
         */
        const val PREFS = "youtube_account_durability_test"
    }
}
