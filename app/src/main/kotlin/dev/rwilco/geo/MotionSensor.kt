package dev.rwilco.geo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Has this phone gone anywhere since I last asked?", answered for free.
 *
 * `TYPE_SIGNIFICANT_MOTION` is a one-shot trigger sensor: it is evaluated by the sensor hub, not
 * by the CPU, it wakes the phone only when it fires, and it fires when the accelerometer says
 * the *user's location* has changed — walking off, getting into a car — rather than when the
 * phone is picked up. It costs no permission, needs no Play Services, and works while the app
 * is asleep, which is the only time this watch is running. That is why it is this and not the
 * Activity Recognition API, which classifies better (walking, in a vehicle, with a confidence)
 * at the price of a runtime permission dialog and a Google dependency in an offline-first app.
 *
 * What it is worth is asymmetric, and [PlaceWatcher] treats it that way. Firing means the phone
 * moved: good evidence, taken at face value. Not firing is a hint — a phone flat on a train
 * table is not moving as far as its accelerometer is concerned — so it is only ever believed
 * alongside a pair of fixes that say the same thing.
 *
 * The listener lives in this process. If the process is killed between two checks the
 * registration goes with it, and the honest answer to "did it move?" becomes *I was not
 * listening*: [consume] returns null, and the watch plans as it did before there was a sensor.
 *
 * [onMotion] is called the moment it fires: it is there so a watch that had settled on a long
 * wait can pull its next look forward when the phone finally goes somewhere. It runs off the
 * main thread — the sensor hub delivers a trigger on the main looper, and what the watch does
 * with one is two binder calls, which do not belong there.
 */
open class MotionSensor(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val significant: Sensor? = runCatching {
        sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }.getOrNull()

    /** Whether the registration below was made by this process and is still standing. */
    @Volatile
    private var listening = false

    @Volatile
    private var moved = false

    /** Called off the main thread when the phone goes somewhere. */
    var onMotion: suspend () -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // One-shot: the sensor has already unregistered itself. One firing is the whole
            // answer, so it is not re-armed until the next check consumes it — which also caps
            // what onMotion can cost at one early look per check, however much the phone moves.
            moved = true
            scope.launch { runCatching { onMotion() }.onFailure { Log.w(TAG, "the stir went nowhere", it) } }
        }
    }

    /**
     * What the sensor saw since [watch] was last called, and start the next window. Null when
     * there is nothing to say: no such sensor, or this process was not the one that armed it.
     */
    open fun consume(): Boolean? {
        val answer = if (listening) moved else null
        watch()
        return answer
    }

    /** Listen from now until the next [consume]. Cheap enough to call on every check. */
    fun watch() {
        val sensor = significant ?: return
        val manager = sensors ?: return
        runCatching {
            manager.cancelTriggerSensor(listener, sensor)
            moved = false
            listening = manager.requestTriggerSensor(listener, sensor)
        }.onFailure {
            Log.w(TAG, "could not listen for motion", it)
            listening = false
        }
    }

    /** Stop listening: there is nothing left to watch for. */
    fun stop() {
        val sensor = significant ?: return
        runCatching { sensors?.cancelTriggerSensor(listener, sensor) }
        listening = false
        moved = false
    }

    private companion object {
        const val TAG = "RwilcoGeo"
    }
}
