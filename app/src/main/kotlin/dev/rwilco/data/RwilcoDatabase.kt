package dev.rwilco.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ReminderEntity::class],
    version = RwilcoDatabase.VERSION,
    exportSchema = true,
)
abstract class RwilcoDatabase : RoomDatabase() {

    abstract fun reminders(): ReminderDao

    companion object {
        /** A named constant so MigrationChainTest can assert the chain reaches it. */
        const val VERSION = 6
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
