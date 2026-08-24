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
        const val VERSION = 3
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
        )

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
