package com.example.data.local

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: TranslationHistoryDao) {
  val historyItems: Flow<List<TranslationHistoryEntity>> = dao.getAllHistory()

  suspend fun saveRecord(record: TranslationHistoryEntity): Long {
    return dao.insertHistory(record)
  }

  suspend fun deleteRecord(id: Long) {
    dao.deleteById(id)
  }

  suspend fun clearAll() {
    dao.deleteAll()
  }
}
