package dev.rwilco.notify

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.AlertSound
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Chime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app's own copy of a chosen sound.
 *
 * The whole point of it is what happens to somebody else's file afterwards: it can be deleted,
 * moved, or on a phone this vault was not written on, and none of that may end in an alarm that
 * makes no noise. So the copy is made when the sound is chosen, and everything that cannot be
 * opened settles back to the phone's own alarm.
 */
@RunWith(AndroidJUnit4::class)
class SoundStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val copies get() = File(context.filesDir, "sounds")

    /** A file of somebody else's, in a folder this app is about to stop needing. */
    private fun somebodyElsesFile(name: String = "tone.mp3"): Uri {
        val file = File(context.cacheDir, name).apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        return Uri.fromFile(file)
    }

    @Before
    fun clean() {
        copies.deleteRecursively()
        File(context.cacheDir, "tone.mp3").delete()
    }

    @Test
    fun theFileIsCopiedInAndOutlivesTheOriginal() {
        val source = somebodyElsesFile()
        val kept = SoundStore.keep(context, source, "Timbre")
        assertNotNull("a readable file is always worth keeping", kept)
        kept!!
        assertEquals("Timbre", kept.label)
        assertTrue("the copy is ours", SoundStore.isOurs(context, kept))
        assertNotEquals("and it is not the original", source.toString(), kept.uri)
        assertEquals(1, copies.listFiles()?.size)

        // The original goes, which is the whole reason any of this exists.
        File(source.path!!).delete()
        assertTrue("the copy still opens", Sounds.readable(context, Uri.parse(kept.uri)))
        assertEquals("and it is still what plays", kept.uri, Sounds.uri(context, kept).toString())
    }

    @Test
    fun theSystemIsAllowedToReadTheCopy() {
        // A notification channel's tone is played by another process. If that process cannot
        // open the file the channel is silent, which is the one failure worse than a wrong tone.
        val kept = SoundStore.keep(context, somebodyElsesFile(), "Timbre")!!
        val uri = Uri.parse(kept.uri)
        SoundStore.grantToSystem(context, uri)
        val uid = runCatching { context.packageManager.getPackageUid("com.android.systemui", 0) }.getOrNull()
        assertNotNull("no systemui on this image; the grant cannot be judged", uid)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(uri, -1, uid!!, Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    @Test
    fun aSoundThatCannotBeOpenedSettlesBackToThePhonesOwnAlarm() {
        val gone = AlertSound.Custom("content://dev.rwilco.sounds/sounds/sound-nothing.mp3", "Perdido")
        val settled = SoundStore.settle(
            context,
            AppSettings(alertSound = gone, insistentSound = gone),
        )
        assertEquals(AlertSound.System, settled.alertSound)
        assertEquals(AlertSound.System, settled.insistentSound)
    }

    @Test
    fun oneOfSomebodyElsesIsAdoptedWhileItCanStillBeRead() {
        // What a sound chosen before the app kept its own copies turns into on the next launch.
        val theirs = AlertSound.Custom(somebodyElsesFile().toString(), "Timbre")
        val settled = SoundStore.settle(context, AppSettings(alertSound = theirs))
        val now = settled.alertSound
        assertTrue("adopted", SoundStore.isOurs(context, now))
        assertEquals("and it keeps its name", "Timbre", (now as AlertSound.Custom).label)
    }

    @Test
    fun whatIsStillChosenSurvivesTheSweepAndNothingElseDoes() {
        val kept = SoundStore.keep(context, somebodyElsesFile(), "Timbre")!!
        val orphan = SoundStore.keep(context, somebodyElsesFile("otro.mp3"), "Otro")!!
        assertEquals(2, copies.listFiles()?.size)

        SoundStore.sweep(context, AppSettings(alertSound = kept))
        assertTrue("the chosen one stays", Sounds.readable(context, Uri.parse(kept.uri)))
        assertFalse("the one nobody points at goes", Sounds.readable(context, Uri.parse(orphan.uri)))
    }

    @Test
    fun theBundledAndSystemSoundsAreLeftAlone() {
        val chimed = AppSettings(alertSound = AlertSound.Bundled(Chime.LOW), insistentSound = AlertSound.System)
        assertEquals(chimed, SoundStore.settle(context, chimed))
    }
}
