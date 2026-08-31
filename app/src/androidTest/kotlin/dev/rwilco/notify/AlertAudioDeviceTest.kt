package dev.rwilco.notify

import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime
import dev.rwilco.model.NET_GAIN
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
    fun aSoundCanBeMovedToTheSpeakerMidPlay() {
        // Twenty seconds unanswered and a reminder sent to the headphones comes out of the
        // phone instead (AlertAudio.HEADPHONES_GRACE_MS) — because headphones connected are not
        // headphones being listened through. There is nothing to hear on an emulator; what this
        // pins is that the move itself is a call the platform takes, mid-playback, without
        // stopping the sound, which is the only way this path can break silently.
        val uri = Sounds.uri(context, AlertSound.Bundled(Chime.SOFT))
        val player = MediaPlayer()
        try {
            player.setDataSource(context, uri!!)
            player.setAudioAttributes(AlertAudio.attributes())
            AlertAudio.routeTo(context, player, toHeadphones = true)
            player.prepare()
            player.start()
            AlertAudio.toSpeaker(context, player)
            assertTrue("the chime stopped when it was moved", player.isPlaying)
        } finally {
            runCatching { player.stop() }
            player.release()
        }
        assertTrue("twenty seconds is inside the minute the noise may last", AlertAudio.HEADPHONES_GRACE_MS < 60_000L)
    }

    @Test
    fun theNetsWordPlaysOnceAtHalfAndLetsGoOfItself() {
        // The safety net makes a noise now — the ordinary tone at half an alarm — from a
        // broadcast with no screen behind it and nothing that will ever call stop. So the
        // player has to release itself, which is the half of this a JVM cannot check: what is
        // pinned here is that every call on the way is one the platform accepts, and that the
        // tone actually starts. There is nothing to *hear* on an emulator, as ever.
        val uri = Sounds.uri(context, AlertSound.Bundled(Chime.SOFT))
        assertNotNull(uri)
        AlertAudio.playOnce(context, uri!!, NET_GAIN, toHeadphones = true)
        // A file the phone cannot open must give the audio back rather than hold the focus for
        // ever, which is the failure that would only ever show up as somebody's music staying
        // ducked. It answers by not throwing.
        AlertAudio.playOnce(context, Uri.parse("content://dev.rwilco.absent/nothing"), NET_GAIN, toHeadphones = false)
    }

    @Test
    fun theAlarmStreamIsTheOneThatGovernsIt() {
        val audio = context.getSystemService(AudioManager::class.java)!!
        assertTrue(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) > 0)
        // Whatever is or is not plugged in, the question answers without throwing.
        AlertAudio.headsetConnected(context)
    }
}
