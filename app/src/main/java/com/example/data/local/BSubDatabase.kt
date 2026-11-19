package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TranslationHistoryEntity::class], version = 1, exportSchema = false)
abstract class BSubDatabase : RoomDatabase() {
  abstract fun translationHistoryDao(): TranslationHistoryDao

  companion object {
    @Volatile private var instance: BSubDatabase? = null

    fun getInstance(context: Context): BSubDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                BSubDatabase::class.java,
                "bsub_translator.db",
              )
              .build()
              .also { instance = it }
        }
    }
  }
}
