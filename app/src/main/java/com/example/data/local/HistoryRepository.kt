package com.example.data.local

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: TranslationHistoryDao) {
  val historyItems: Flow<List<TranslationHistoryEntity>> = dao.getAllHistory()

  val totalCount: Flow<Int> = dao.observeTotalCount()

  fun observeByStatus(status: HistoryTranslationStatus): Flow<List<TranslationHistoryEntity>> =
    dao.getHistoryByStatus(status)

  fun searchHistory(query: String): Flow<List<TranslationHistoryEntity>> {
    val clean = query.trim()
    return if (clean.isEmpty()) dao.getAllHistory() else dao.searchHistory(clean)
  }

  fun observeCountByStatus(status: HistoryTranslationStatus): Flow<Int> =
    dao.observeCountByStatus(status)

  suspend fun getById(id: Long): TranslationHistoryEntity? = dao.getById(id)

  suspend fun saveRecord(record: TranslationHistoryEntity): Long {
    return dao.insertHistory(record)
  }

  suspend fun updateRecord(record: TranslationHistoryEntity) {
    dao.updateHistory(record)
  }

  suspend fun createProcessingRecord(
    originalFileName: String,
    expectedOutputFileName: String,
    mode: String,
    titleMetadata: String,
    totalBlocks: Int,
    totalLines: Int,
    modelUsed: String,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): Long {
    return dao.insertHistory(
      TranslationHistoryEntity(
        originalFileName = originalFileName,
        outputFileName = expectedOutputFileName,
        mode = mode,
        titleMetadata = titleMetadata,
        totalBlocks = totalBlocks,
        translatedBlocks = 0,
        totalLines = totalLines,
        fileSizeBytes = 0L,
        modelUsed = modelUsed,
        status = HistoryTranslationStatus.PROCESSING.label,
        translationStatus = HistoryTranslationStatus.PROCESSING,
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
      )
    )
  }

  suspend fun updateBatchProgress(
    historyId: Long,
    translatedBlocks: Int,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateProgress(
      id = historyId,
      translatedBlocks = translatedBlocks,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun markCompleted(
    historyId: Long,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    translatedBlocks: Int,
    fileSizeBytes: Long,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateOutcome(
      id = historyId,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      translatedBlocks = translatedBlocks,
      fileSizeBytes = fileSizeBytes,
      status = HistoryTranslationStatus.COMPLETED.label,
      translationStatus = HistoryTranslationStatus.COMPLETED,
      errorMessage = null,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun markPartial(
    historyId: Long,
    outputFileName: String,
    savedPath: String,
    contentUri: String,
    localBackupPath: String,
    translatedBlocks: Int,
    totalBlocks: Int,
    fileSizeBytes: Long,
    reason: String? = null,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    dao.updateOutcome(
      id = historyId,
      outputFileName = outputFileName,
      savedPath = savedPath,
      contentUri = contentUri,
      localBackupPath = localBackupPath,
      translatedBlocks = translatedBlocks,
      fileSizeBytes = fileSizeBytes,
      status = "Partial ($translatedBlocks/$totalBlocks)",
      translationStatus = HistoryTranslationStatus.PARTIAL,
      errorMessage = reason,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun markFailed(
    historyId: Long,
    translatedBlocks: Int = 0,
    errorMessage: String,
    status: HistoryTranslationStatus = HistoryTranslationStatus.FAILED,
    updatedAtEpochMs: Long = System.currentTimeMillis(),
  ) {
    val existing = dao.getById(historyId)
    dao.updateOutcome(
      id = historyId,
      outputFileName = existing?.outputFileName.orEmpty(),
      savedPath = existing?.savedPath.orEmpty(),
      contentUri = existing?.contentUri.orEmpty(),
      localBackupPath = existing?.localBackupPath.orEmpty(),
      translatedBlocks = translatedBlocks,
      fileSizeBytes = existing?.fileSizeBytes ?: 0L,
      status = status.label,
      translationStatus = status,
      errorMessage = errorMessage,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }

  suspend fun deleteRecord(id: Long) {
    dao.deleteById(id)
  }

  suspend fun clearAll() {
    dao.deleteAll()
  }
}
