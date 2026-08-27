package dev.rwilco.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Condition
import dev.rwilco.model.Fix
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * "En casa, y a la vez entre las siete y las nueve y media" — ringing in the street.
 *
 * The reported evening, put back together. Under "a la vez" the window's moment is judged with
 * the place folded in as a state, and that judgement used to be a fresh measurement of the last
 * raw fix against the circle. On a fifty-metre circle — the tightest the app allows, and smaller
 * than an ordinary network fix is accurate — the measurement resolves to "yes" wherever the
 * phone is. So the window opened at seven, the fence said yes to a question it could not answer,
 * and the reminder rang twenty minutes after the phone's own geofences had recorded the phone
 * leaving. The watch knew. Nobody asked it.
 */
@RunWith(AndroidJUnit4::class)
class TogetherPlaceFiringTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val id = "together-place"
    private val lat = 40.4169
    private val lng = -3.7035
    private val radius = 50

    private val home = Trigger.Location(lat, lng, radius, Presence.INSIDE, "Casa")

    @Before
    fun empty() = runBlocking {
        app.repository.deleteAll()
        app.placeWatch.write(PlaceWatchState())
    }

    /**
     * A window opening in two seconds, and armed the way the app arms one on every change:
     * far enough ahead to be armed, close enough that the alarm is arriving now. Arming is what
     * makes a firing legitimate, so a test that skips it is testing a door nobody comes through.
     * Returns the window's rule index.
     */
    private suspend fun saveAndArm(): Int {
        val now = app.clock.instant()
        val opens = now.plusSeconds(2).atZone(app.clock.zone).toLocalTime()
        app.repository.save(
            Reminder(
                id = id,
                text = "Limpiar el termo del café",
                rules = listOf(
                    TriggerRule(home),
                    TriggerRule(Trigger.Interval(opens, opens.plusHours(2))),
                ),
                ruleMatch = RuleMatch.TOGETHER,
                createdAt = now.minus(Duration.ofDays(1)),
                updatedAt = now.minus(Duration.ofDays(1)),
            ),
        )
        app.scheduler.rearmAll()
        return 1
    }

    /** A fix too vague to settle a fifty-metre circle: exactly what a network fix is like. */
    private fun vagueFix() = Fix(lat, lng, accuracyM = 80.0, at = app.clock.instant())

    @Test
    fun theWindowDoesNotRingWhileTheWatchSaysThePhoneIsAway() = runBlocking {
        val window = saveAndArm()
        // What the geofences recorded when the phone left, plus a fix too vague to overrule it.
        app.placeWatch.write(
            PlaceWatchState(lastFix = vagueFix(), inside = mapOf(GeofenceIds.encode(id, 0, home) to false)),
        )
        app.firing.fire(id, ruleIndex = window)
        assertNull("it rang in the street", app.repository.get(id)!!.lastFiredAt)
    }

    @Test
    fun theWindowRingsWhenTheWatchSaysThePhoneIsThere() = runBlocking {
        val window = saveAndArm()
        app.placeWatch.write(
            PlaceWatchState(lastFix = vagueFix(), inside = mapOf(GeofenceIds.encode(id, 0, home) to true)),
        )
        app.firing.fire(id, ruleIndex = window)
        assertNotNull("it stayed quiet at home", app.repository.get(id)!!.lastFiredAt)
    }

    @Test
    fun aCircleTheWatchHasNeverJudgedStillGetsTheBenefitOfTheDoubt() = runBlocking {
        // The house rule, untouched: what nobody can vouch for holds, because the failure
        // somebody notices is the one that never arrives.
        val window = saveAndArm()
        app.placeWatch.write(PlaceWatchState(lastFix = vagueFix()))
        app.firing.fire(id, ruleIndex = window)
        assertNotNull("silence from a circle nobody has judged", app.repository.get(id)!!.lastFiredAt)
    }

    @Test
    fun aMemoryNoFixSpeaksForAnyMoreIsOldNews() = runBlocking {
        // Past the speed memory the watch's word is as stale as everything else, and the house
        // rule takes over rather than a two-hour-old "you are out" silencing the evening.
        val window = saveAndArm()
        val old = app.clock.instant().minus(Duration.ofHours(3))
        app.placeWatch.write(
            PlaceWatchState(
                lastFix = Fix(lat, lng, accuracyM = 20.0, at = old),
                inside = mapOf(GeofenceIds.encode(id, 0, home) to false),
            ),
        )
        app.firing.fire(id, ruleIndex = window)
        assertNotNull("a stale outside silenced it", app.repository.get(id)!!.lastFiredAt)
    }

    @Test
    fun theSameCircleIsOneQuestionHoweverManyRulesNameIt() {
        // Guards the geometry match: the watch keys circles by rule, and a condition carries
        // the place with no id to look it up by.
        val other = Trigger.Location(lat, lng, radius, Presence.OUTSIDE, "Casa")
        assertEquals(
            GeofenceIds.circleKey(lat, lng, radius),
            GeofenceIds.circleKey(other.lat, other.lng, other.radiusM),
        )
    }
}
