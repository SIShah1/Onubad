package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val originalFileName: String,
  val outputFileName: String,
  val savedPath: String,
  val contentUri: String,
  val localBackupPath: String,
  val mode: String,
  val titleMetadata: String,
  val totalBlocks: Int,
  val translatedBlocks: Int,
  val totalLines: Int,
  val fileSizeBytes: Long,
  val modelUsed: String,
  val status: String,
  val createdAtEpochMs: Long = System.currentTimeMillis(),
)
