package dev.rwilco.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The APK arrives in pieces, and the pieces have to add up.
 *
 * Sixty megabytes does not fit in the ten minutes WorkManager gives a worker over a weak
 * connection, and it used to start at byte zero on every retry — so five attempts spent three
 * hundred megabytes of somebody's data and installed nothing. What makes the retries add up is
 * that each one asks for the rest, and what keeps a half-arrived one from being mistaken for an
 * update ready to install is that it is not called `update.apk` until it is whole.
 */
class DownloadResumeTest {

    @Test
    fun `a half-downloaded build is kept under its own name, never the staged one`() {
        assertEquals("update-169.part", partName(169))
        // The two names never collide, which is the whole of why a cut-short download can no
        // longer look like an update waiting to be installed (see Updater.stagedUpdate).
        assertFalse(partName(169) == Updater.APK_FILE)
    }

    @Test
    fun `how far along a download is counts the bytes already held, and says nothing it cannot know`() {
        // A fresh download: what has been read, of what the body says it is.
        assertEquals(25, downloadPercent(alreadyHave = 0, read = 15_000_000, bodyLength = 60_000_000))
        // A resumed one: the body is only the rest, and the part already on disk counts —
        // or the line would start again from nought on every retry of the same file.
        assertEquals(50, downloadPercent(alreadyHave = 20_000_000, read = 10_000_000, bodyLength = 40_000_000))
        assertEquals(100, downloadPercent(alreadyHave = 20_000_000, read = 40_000_000, bodyLength = 40_000_000))
        assertEquals(0, downloadPercent(alreadyHave = 0, read = 0, bodyLength = 60_000_000))
        // No length (a chunked body) is no percentage: a number made up is worse than none.
        assertNull(downloadPercent(alreadyHave = 0, read = 5_000_000, bodyLength = -1))
        assertNull(downloadPercent(alreadyHave = 0, read = 0, bodyLength = 0))
        // More than was promised is still not more than all of it.
        assertEquals(100, downloadPercent(alreadyHave = 0, read = 61_000_000, bodyLength = 60_000_000))
    }

    @Test
    fun `the leftovers of other builds are swept, and the one in hand is not`() {
        val cache = listOf(Updater.APK_FILE, "update-168.part", "update-169.part", "watch.json")
        assertEquals(listOf("update-168.part"), staleParts(cache, keep = "update-169.part"))
        // With nothing in hand — the check found nothing to do — every part is a leftover.
        assertEquals(listOf("update-168.part", "update-169.part"), staleParts(cache, keep = null))
        // And nothing else in the cache is ever touched: the staged APK has its own life, and
        // the place watch's blob shares this directory.
        assertTrue(staleParts(cache, null).none { it == Updater.APK_FILE || it == "watch.json" })
    }

    @Test
    fun `a range past the end is done, not a failure`() {
        // The trap this closes: the last attempt wrote the final byte and the process died
        // before the rename. Asking for a range past the end is refused, and refused again on
        // every retry after that, with a perfectly good sixty megabytes sitting on the disk.
        assertTrue(partIsWhole(416, alreadyHave = 60_000_000))
        assertFalse(partIsWhole(416, alreadyHave = 0), "nothing on disk is not a whole file")
        assertFalse(partIsWhole(200, alreadyHave = 60_000_000))
        assertFalse(partIsWhole(206, alreadyHave = 60_000_000))
        // The two answers never both hold, so the order they are asked in cannot matter.
        for (code in listOf(200, 206, 416)) {
            assertFalse(continuesPart(code, 1_000) && partIsWhole(code, 1_000), "code $code")
        }
    }

    @Test
    fun `only a 206 continues what is already on disk`() {
        assertTrue(continuesPart(206, alreadyHave = 1_000), "here is the rest")
        // A server that ignored the range is sending the file from the top: appending that would
        // splice a second copy onto the first, so the part is written over instead.
        assertFalse(continuesPart(200, alreadyHave = 1_000))
        // And with nothing on disk there is nothing to continue, whatever the code says.
        assertFalse(continuesPart(206, alreadyHave = 0))
        assertFalse(continuesPart(200, alreadyHave = 0))
    }
}
