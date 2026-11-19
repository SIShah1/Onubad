package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [TranslationHistoryEntity::class, SubtitleTranslation::class],
  version = 2,
  exportSchema = false,
)
@TypeConverters(SubtitleTranslationConverters::class)
abstract class BSubDatabase : RoomDatabase() {
  abstract fun translationHistoryDao(): TranslationHistoryDao

  abstract fun subtitleTranslationDao(): SubtitleTranslationDao

  companion object {
    @Volatile private var instance: BSubDatabase? = null

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subtitle_translations` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `jobId` TEXT NOT NULL,
              `fileName` TEXT NOT NULL,
              `blockIndex` INTEGER NOT NULL,
              `timestamp` TEXT NOT NULL,
              `originalEnglishText` TEXT NOT NULL,
              `translatedBengaliText` TEXT,
              `status` TEXT NOT NULL,
              `updatedAtEpochMs` INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_subtitle_translations_jobId_blockIndex` ON `subtitle_translations` (`jobId`, `blockIndex`)"
          )
        }
      }

    fun getInstance(context: Context): BSubDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                BSubDatabase::class.java,
                "bsub_translator.db",
              )
              .addMigrations(MIGRATION_1_2)
              .build()
              .also { instance = it }
        }
    }
  }
}
