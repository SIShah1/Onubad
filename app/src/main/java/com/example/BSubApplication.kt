package com.example

import android.app.Application
import com.example.data.local.BSubDatabase
import com.example.data.local.HistoryRepository
import com.example.data.network.GeminiApiClient
import com.example.data.preferences.SecureKeyStorage
import com.example.data.preferences.SettingsRepository
import com.example.data.storage.SubtitleFileStorage
import com.example.service.TranslationCoordinator

class BSubApplication : Application() {

  val database: BSubDatabase by lazy { BSubDatabase.getInstance(this) }
  val historyRepository: HistoryRepository by lazy {
    HistoryRepository(database.translationHistoryDao())
  }
  val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
  val secureKeyStorage: SecureKeyStorage by lazy { SecureKeyStorage(this) }
  val subtitleFileStorage: SubtitleFileStorage by lazy { SubtitleFileStorage(this) }
  val geminiApiClient: GeminiApiClient by lazy { GeminiApiClient() }

  val translationCoordinator: TranslationCoordinator by lazy {
    TranslationCoordinator(
      settingsRepository = settingsRepository,
      keyStorage = secureKeyStorage,
      historyRepository = historyRepository,
      fileStorage = subtitleFileStorage,
      geminiApiClient = geminiApiClient,
    )
  }
}
