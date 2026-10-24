package com.tejerfacil.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PatternEntity::class, DiagramEntity::class, RowEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patterns(): PatternDao
    abstract fun diagrams(): DiagramDao
    abstract fun rows(): RowDao
    abstract fun progress(): ProgressDao

    companion object {
        /** v2: copias editables de patrones y diagramas con símbolos editados. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE patterns ADD COLUMN copyOf INTEGER")
                db.execSQL("ALTER TABLE diagrams ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tejer_facil.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
