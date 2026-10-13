package dev.rwilco.ui.alert

import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Hands a locked phone back to its lock screen once an alert has gone a minute untouched
 * (0.193.0, the owner's call: "nunca más de 1 minuto"), and the lock screen puts the display out
 * within seconds, as it does after any wake.
 *
 * An activity shown over the keyguard covers it, and the keyguard's own short timeout with it:
 * from then on the display stays up for the phone's screen timeout, whatever that is — reported
 * from the phone as an alert lit for minutes on a phone nobody had picked up, which on an OLED is
 * the same words on the same pixels. An app cannot turn a display off; it can step out of the
 * way. Nothing is answered by it: the card is still in the shade, and a tap on it brings the
 * screen back.
 *
 * "Untouched" counts a touch or a key on the screen, and anything put over it — a sheet, a
 * dialog, the shade, the bouncer — for as long as it is up: those are windows of their own,
 * whose touches the activity never sees, so the minute waits for them to close and starts over.
 *
 * Only on a locked phone. An unlocked one is somebody's phone in use, and its timeout is theirs.
 */
class LockScreenReturn(
    private val activity: ComponentActivity,
    private val beforeLeaving: () -> Unit = {},
) : DefaultLifecycleObserver {

    private var countdown: Job? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    /** Shown, touched, or uncovered: the minute starts over. */
    fun restart() {
        countdown?.cancel()
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        countdown = activity.lifecycleScope.launch {
            delay(UNTOUCHED.toMillis())
            if (activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) return@launch
            beforeLeaving()
            activity.moveTaskToBack(true)
        }
    }

    /** Out of sight, or covered by a window of its own: nothing to count. */
    fun pause() {
        countdown?.cancel()
        countdown = null
    }

    /** Focus comes and goes with whatever is put over the screen. */
    fun focusChanged(hasFocus: Boolean) = if (hasFocus) restart() else pause()

    override fun onStart(owner: LifecycleOwner) = restart()

    override fun onStop(owner: LifecycleOwner) = pause()

    private companion object {
        val UNTOUCHED: Duration = Duration.ofMinutes(1)
    }
}
