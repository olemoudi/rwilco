package dev.rwilco.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ReminderEntity::class],
    version = RwilcoDatabase.VERSION,
    exportSchema = true,
)
abstract class RwilcoDatabase : RoomDatabase() {

    abstract fun reminders(): ReminderDao

    companion object {
        /** A named constant so MigrationChainTest can assert the chain reaches it. */
        const val VERSION = 1
        private const val NAME = "rwilco.db"

        /** One entry per version step; `// vN: what it added` on each. */
        val MIGRATIONS: Array<Migration> = emptyArray()

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
