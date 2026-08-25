package dev.rwilco.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, run against real SQLite instead of trusted. A JVM test can check that
 * [RwilcoDatabase.MIGRATIONS] chains 1 → VERSION; it cannot catch a migration whose SQL is
 * wrong. What this asserts is the promise every update makes: the reminders survive it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RwilcoDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private companion object {
        const val DB = "migration-test.db"
    }

    @Test
    fun a_reminder_saved_on_the_first_schema_survives_the_upgrade() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO reminder (id, text, tags, triggers, actions, status, createdAt, updatedAt, doneAt) " +
                    "VALUES ('r1', 'Water the plants', '[\"casa\"]', " +
                    "'[{\"type\":\"on_date\",\"date\":\"2026-09-01\"}]', '[\"NOTIFICATION\"]', 'ACTIVE', 1, 2, NULL)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, RwilcoDatabase.VERSION, true, *RwilcoDatabase.MIGRATIONS)

        db.query("SELECT text, triggers, snoozedUntil, lastFiredAt, armedFor FROM reminder WHERE id = 'r1'").use { cursor ->
            assertTrue("the reminder did not survive the upgrade", cursor.moveToFirst())
            assertEquals("Water the plants", cursor.getString(0))
            assertEquals("[{\"type\":\"on_date\",\"date\":\"2026-09-01\"}]", cursor.getString(1))
            assertTrue("a reminder from v1 has never been snoozed", cursor.isNull(2))
            assertTrue("nor rung", cursor.isNull(3))
            assertTrue("nor armed", cursor.isNull(4))
        }
    }

    /**
     * v4 asked a question the app had been answering for people: does this keep going after you
     * deal with it? Everything written before it behaved as "yes, whenever the trigger can",
     * which for a place meant ringing again the next time you walked through your own door. The
     * migration keeps that behaviour only where the trigger IS a recurrence.
     */
    @Test
    fun the_upgrade_keeps_repeating_triggers_repeating_and_stops_the_rest() {
        helper.createDatabase(DB, 1).use { db ->
            fun insert(id: String, trigger: String) = db.execSQL(
                "INSERT INTO reminder (id, text, tags, triggers, actions, status, createdAt, updatedAt, doneAt) " +
                    "VALUES ('$id', '$id', '[]', '$trigger', '[\"NOTIFICATION\"]', 'ACTIVE', 1, 2, NULL)",
            )
            insert("place", "[{\"type\":\"location\",\"lat\":40.4,\"lng\":-3.7,\"radiusM\":200,\"transition\":\"ENTER\",\"label\":\"Casa\"}]")
            insert("date", "[{\"type\":\"on_date\",\"date\":\"2026-09-01\"}]")
            insert("weekly", "[{\"type\":\"at_time\",\"time\":\"09:00\",\"days\":[\"MONDAY\"]}]")
            insert("random", "[{\"type\":\"random\",\"timesPer\":1,\"period\":\"DAY\",\"from\":\"10:00\",\"to\":\"20:00\",\"days\":[]}]")
        }

        val db = helper.runMigrationsAndValidate(DB, RwilcoDatabase.VERSION, true, *RwilcoDatabase.MIGRATIONS)

        db.query("SELECT id, repeats FROM reminder ORDER BY id").use { cursor ->
            val repeats = buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
            assertEquals("a place was one-shot in intent all along", 0, repeats["place"])
            assertEquals(0, repeats["date"])
            assertEquals("a repeating time is a recurrence by definition", 1, repeats["weekly"])
            assertEquals(1, repeats["random"])
        }
    }

    @Test
    fun every_step_of_the_chain_lands_on_a_schema_Room_recognises() {
        // Walking one version at a time proves each migration individually — a chain that only
        // works end to end still breaks whoever updates twice in a row.
        for (from in 1 until RwilcoDatabase.VERSION) {
            val name = "step-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, from + 1, true, *RwilcoDatabase.MIGRATIONS).close()
        }
    }
}
