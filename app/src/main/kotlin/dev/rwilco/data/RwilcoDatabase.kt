package dev.rwilco.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReminderEntity::class, FiringEventEntity::class],
    version = RwilcoDatabase.VERSION,
    exportSchema = true,
)
abstract class RwilcoDatabase : RoomDatabase() {

    abstract fun reminders(): ReminderDao

    abstract fun events(): FiringEventDao

    companion object {
        /** A named constant so MigrationChainTest can assert the chain reaches it. */
        const val VERSION = 10
        private const val NAME = "rwilco.db"

        /** One entry per version step; `// vN: what it added` on each. */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // v2: the firing state. Nullable epoch millis, so every reminder written by v1 comes
            // through as "never snoozed, never rang, nothing armed" — which is what it was.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN snoozedUntil INTEGER")
                    db.execSQL("ALTER TABLE reminder ADD COLUMN lastFiredAt INTEGER")
                    db.execSQL("ALTER TABLE reminder ADD COLUMN armedFor INTEGER")
                }
            },
            // v3: rules that combine. Everything written before this is an ANY reminder with
            // nothing ticked off and no rule behind its armed moment — which is what it was.
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN ruleMatch TEXT NOT NULL DEFAULT 'ANY'")
                    db.execSQL("ALTER TABLE reminder ADD COLUMN armedRule INTEGER")
                    db.execSQL("ALTER TABLE reminder ADD COLUMN firedRules TEXT NOT NULL DEFAULT ''")
                }
            },
            // v4: recurrence asked for rather than assumed. Everything written before this
            // rang again whenever its trigger could, so the reminders that keep going are the
            // ones whose trigger IS a recurrence — a repeating time or a random window. A place
            // or a date was one-shot in intent all along, and only behaved otherwise because
            // the app never asked. The discriminators are frozen, so matching the JSON is safe.
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN repeats INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        "UPDATE reminder SET repeats = 1 WHERE triggers LIKE '%\"at_time\"%' OR triggers LIKE '%\"random\"%'",
                    )
                }
            },
            // v5: recurrence became a shape rather than a yes/no. Everything that repeated did so
            // by its triggers, which is exactly what "by_trigger" means, so that is what those
            // rows become; the rest keep the "done is done" they already had. The old boolean
            // goes with them — SQLite cannot drop a column here, so the table is rebuilt.
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE reminder_v5 (" +
                            "id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, tags TEXT NOT NULL, " +
                            "triggers TEXT NOT NULL, actions TEXT NOT NULL, status TEXT NOT NULL, " +
                            "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, doneAt INTEGER, " +
                            "snoozedUntil INTEGER, lastFiredAt INTEGER, armedFor INTEGER, " +
                            "ruleMatch TEXT NOT NULL DEFAULT 'ANY', armedRule INTEGER, " +
                            "firedRules TEXT NOT NULL DEFAULT '', " +
                            "recurrence TEXT NOT NULL DEFAULT '" + NO_RECURRENCE + "', lastDealtAt INTEGER)",
                    )
                    db.execSQL(
                        "INSERT INTO reminder_v5 SELECT id, text, tags, triggers, actions, status, " +
                            "createdAt, updatedAt, doneAt, snoozedUntil, lastFiredAt, armedFor, " +
                            "ruleMatch, armedRule, firedRules, " +
                            "CASE repeats WHEN 1 THEN '" + BY_TRIGGER_RECURRENCE + "' ELSE '" + NO_RECURRENCE + "' END, " +
                            "NULL FROM reminder",
                    )
                    db.execSQL("DROP TABLE reminder")
                    db.execSQL("ALTER TABLE reminder_v5 RENAME TO reminder")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_status ON reminder (status)")
                }
            },
            // v6: the safety net — whether a firing nobody answers gets one quiet word about it,
            // and when that word was last said. Two columns added, nothing rewritten: off is
            // what every reminder already written meant, since nobody asked for it.
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN safetyNet INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE reminder ADD COLUMN nudgedAt INTEGER")
                }
            },
            // v7: the moment a "hecho" spends when nothing has rung yet. Null everywhere it did
            // not exist, which is what every row already meant: nothing had been dealt with
            // ahead of time, because there was no way to.
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN dealtThrough INTEGER")
                }
            },
            // Which rule the last ring was for. Null on every existing row, which reads as "not
            // known" — a place held to a ring it cannot attribute is read as it always was.
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN lastFiredRule INTEGER")
                }
            },
            // v9: what happened to a reminder, per reminder (FiringEventEntity). A new table
            // and nothing on the old one: every existing row simply has no history yet.
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `firing_event` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reminderId` TEXT NOT NULL, `at` INTEGER NOT NULL, " +
                            "`kind` TEXT NOT NULL, `ruleIndex` INTEGER, `detail` TEXT, " +
                            "FOREIGN KEY(`reminderId`) REFERENCES `reminder`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_firing_event_reminderId_at` ON `firing_event` (`reminderId`, `at`)")
                }
            },
            // v10: the place a snooze waits at ("cuando llegue a casa"), a trigger's JSON. Null
            // on every existing row, which reads as no such snooze.
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminder ADD COLUMN snoozedToPlace TEXT")
                }
            },
        )

        /** What "it repeated" meant before v5 had words for anything else. */
        private const val BY_TRIGGER_RECURRENCE = "{\"type\":\"by_trigger\"}"

        @Volatile
        private var instance: RwilcoDatabase? = null

        fun get(context: Context): RwilcoDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): RwilcoDatabase =
            Room.databaseBuilder(context, RwilcoDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                // Only on a downgrade, which a sideloaded app can only reach by hand. An upgrade
                // without its migration must fail loudly (and MigrationChainTest fails first).
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
