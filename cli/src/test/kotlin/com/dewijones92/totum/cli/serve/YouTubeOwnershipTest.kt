package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.targets.Requester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class YouTubeOwnershipTest {

    private val file = Files.createTempDirectory("owner").toFile().resolve("youtube-owner.json")
    private val ownership = YouTubeOwnership(file)
    private val dewi = Requester("dewi-id", "Pixel 7 (a3f9)")
    private val wife = Requester("wife-id", "Pixel 7 (77c1)")

    @Test
    fun `the first phone to hand a sign-in over becomes the owner, and keeps the right to refresh it`() {
        assertNull(ownership.owner())

        assertEquals(Admission.Accepted, ownership.admit(dewi))
        assertEquals(Admission.Accepted, ownership.admit(dewi))

        assertEquals(dewi, ownership.owner())
    }

    @Test
    fun `another phone cannot overwrite the sign-in, even one of the same model, and is told why`() {
        ownership.admit(dewi)

        val refused = ownership.admit(wife) as Admission.Refused

        assertTrue(refused.why, refused.why.startsWith("🔒") && refused.why.contains("Pixel 7 (a3f9)"))
        assertEquals(dewi, ownership.owner())
    }

    @Test
    fun `a phone that cannot say who it is is refused`() {
        assertTrue(ownership.admit(null) is Admission.Refused)
        assertNull(ownership.owner())
    }

    @Test
    fun `only the owner may sign the Pi out, and once cleared anyone may sign it in`() {
        ownership.admit(dewi)

        assertFalse(ownership.mayForget(wife.id))
        assertFalse(ownership.mayForget(null))
        assertTrue(ownership.mayForget(dewi.id))

        ownership.clear()

        assertTrue(ownership.mayForget(wife.id))
        assertEquals(Admission.Accepted, ownership.admit(wife))
        assertEquals(wife, ownership.owner())
    }
}
