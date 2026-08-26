package dev.rwilco.notify

import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audio path against a real device: asking for the focus, routing, and playing.
 *
 * What it cannot do is *hear* it. There are no headphones on an emulator and nothing else is
 * playing, so ducking and routing have nothing to act on here — what this pins is that every
 * call on the way is one the platform accepts, which is where an alarm path breaks silently.
 */
@RunWith(AndroidJUnit4::class)
class AlertAudioDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFocusIsAskedForAndGivenBack() {
        val request = AlertAudio.duckOthers(context)
        assertNotNull("the phone would not take the request at all", request)
        AlertAudio.release(context, request)
        // And releasing something that was never asked for is not a crash.
        AlertAudio.release(context, null)
    }

    @Test
    fun aChimePlaysWithRoutingAskedFor() {
        val uri = Sounds.uri(context, AlertSound.Bundled(Chime.SOFT))
        assertNotNull(uri)
        val player = MediaPlayer()
        try {
            player.setDataSource(context, uri!!)
            player.setAudioAttributes(AlertAudio.attributes())
            // No headphones on an emulator: this is the "nothing connected" path, which must
            // leave the routing to the platform rather than throwing or muting.
            AlertAudio.routeTo(context, player, toHeadphones = true)
            player.prepare()
            player.start()
            assertTrue("the chime did not start", player.isPlaying)
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    @Test
    fun theAlarmStreamIsTheOneThatGovernsIt() {
        val audio = context.getSystemService(AudioManager::class.java)!!
        assertTrue(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) > 0)
        // Whatever is or is not plugged in, the question answers without throwing.
        AlertAudio.headsetConnected(context)
    }
}
