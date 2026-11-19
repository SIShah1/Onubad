package com.example.service

import com.example.data.local.HistoryRepository
import com.example.data.local.TranslationHistoryEntity
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiCallResult
import com.example.data.preferences.SecureKeyStorage
import com.example.data.preferences.SettingsRepository
import com.example.data.storage.SubtitleFileStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.DefaultConfig
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.domain.model.ProcessingLogEntry
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import com.example.srt.SrtEngine
import com.example.srt.ValidationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TranslationCoordinator(
  private val settingsRepository: SettingsRepository,
  private val keyStorage: SecureKeyStorage,
  private val historyRepository: HistoryRepository,
  private val fileStorage: SubtitleFileStorage,
  private val geminiApiClient: GeminiApiClient,
) {

  private val _sessionState = MutableStateFlow(TranslationSessionState())
  val sessionState: StateFlow<TranslationSessionState> = _sessionState.asStateFlow()

  private val logIdGen = AtomicLong(1L)
  private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

  @Volatile private var pendingJobRequest: TranslationJobRequest? = null
  @Volatile private var activeJob: Job? = null

  fun prepareJobRequest(request: TranslationJobRequest) {
    pendingJobRequest = request
  }

  fun clearLogs() {
    _sessionState.update { state ->
      if (state.isRunningOrCooldown) {
        state.copy(logs = emptyList())
      } else {
        TranslationSessionState()
      }
    }
  }

  fun startExecutionInScope(
    serviceScope: CoroutineScope,
    onFinished: () -> Unit,
  ) {
    val request = pendingJobRequest
    if (request == null || request.files.isEmpty()) {
      appendLog("No subtitle files queued for translation.", LogLevel.ERROR)
      _sessionState.update {
        it.copy(status = TranslationStatus.FAILED, activePhaseMessage = "No subtitle files selected")
      }
      onFinished()
      return
    }

    activeJob?.cancel()
    activeJob =
      serviceScope.launch {
        try {
          executeTranslationJob(request)
        } catch (_: CancellationException) {
          handleCancellationCleanup()
        } catch (e: Exception) {
          appendLog("Translation failed: ${e.message ?: "Unexpected error"}", LogLevel.ERROR)
          _sessionState.update {
            it.copy(
              status = TranslationStatus.FAILED,
              activePhaseMessage = "Failed: ${e.message ?: "Error"}",
            )
          }
        } finally {
          onFinished()
        }
      }
  }

  fun abortTranslation() {
    val wasRunning = _sessionState.value.isRunningOrCooldown
    activeJob?.cancel(CancellationException("Aborted by user"))
    activeJob = null
    if (wasRunning) {
      _sessionState.update {
        it.copy(
          status = TranslationStatus.ABORTED,
          cooldownRemainingSeconds = 0,
          activePhaseMessage = "Aborted by user",
        )
      }
    }
  }

  private fun handleCancellationCleanup() {
    appendLog("Translation aborted. Validated batches preserved.", LogLevel.WARNING)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.ABORTED,
        cooldownRemainingSeconds = 0,
        activePhaseMessage = "Translation aborted",
      )
    }
  }

  private suspend fun executeTranslationJob(request: TranslationJobRequest) {
    val settings = settingsRepository.getSettingsSnapshot()
    keyStorage.resetExhaustedAndCooldownKeys()
    val allKeys = keyStorage.getKeysSnapshot()

    if (allKeys.isEmpty()) {
      appendLog("No Gemini API keys configured. Add keys in API Key Manager.", LogLevel.ERROR)
      _sessionState.update {
        it.copy(
          status = TranslationStatus.FAILED,
          activePhaseMessage = "No Gemini API keys configured",
        )
      }
      return
    }

    val totalFiles = request.files.size
    val totalBlocksAllFiles = request.files.sumOf { it.totalBlocks }
    var completedBlocksAllFiles = 0

    _sessionState.value =
      TranslationSessionState(
        status = TranslationStatus.RUNNING,
        totalFiles = totalFiles,
        completedFiles = 0,
        currentFileIndex = 1,
        currentFileName = request.files.first().fileName,
        totalBlocksAllFiles = totalBlocksAllFiles,
        completedBlocksAllFiles = 0,
        currentKeyNumber = 1,
        currentKeyLabel = allKeys.first().label,
        currentAttempt = 1,
        maxAttempts = settings.effectiveMaxAttemptsPerKey,
        activePhaseMessage = "Starting translation...",
        logs = emptyList(),
      )

    appendLog(
      "Started ${request.inputMode.name.lowercase()} job: $totalFiles file(s), $totalBlocksAllFiles blocks (${settings.effectiveModel})",
      LogLevel.INFO,
    )

    // Key rotation state preserved across batches and files in this session
    var currentKeyIndex = 0
    val temporarilyExhaustedIndices = HashSet<Int>()
    val permanentlyInvalidIndices =
      allKeys.indices.filter { allKeys[it].status == ApiKeyStatus.INVALID }.toMutableSet()

    if (permanentlyInvalidIndices.size == allKeys.size) {
      // Reset invalid statuses once if user is retrying with edited/re-enabled keys
      keyStorage.resetAllKeyStatuses()
      permanentlyInvalidIndices.clear()
    }

    for ((fileIdx, file) in request.files.withIndex()) {
      currentCoroutineContext().ensureActive()
      val displayFileNum = fileIdx + 1
      val chunkSize = settings.effectiveChunkSize
      val batches = file.blocks.chunked(chunkSize)
      val totalBatches = batches.size.coerceAtLeast(1)

      appendLog("File $displayFileNum/$totalFiles – Processing (${file.fileName})", LogLevel.INFO)
      _sessionState.update {
        it.copy(
          currentFileIndex = displayFileNum,
          currentFileName = file.fileName,
          totalBatchesInCurrentFile = totalBatches,
          currentBatchNumber = 1,
          activePhaseMessage = "File $displayFileNum/$totalFiles – Processing",
        )
      }

      if (file.blocks.isEmpty()) {
        appendLog(
          "File $displayFileNum/$totalFiles – Skipped (no valid SRT blocks)",
          LogLevel.WARNING,
        )
        _sessionState.update { it.copy(completedFiles = displayFileNum) }
        continue
      }

      val translatedFileBlocks = ArrayList<SrtBlock>(file.blocks.size)

      try {
        for ((batchIdx, batchBlocks) in batches.withIndex()) {
          currentCoroutineContext().ensureActive()
          val displayBatchNum = batchIdx + 1
          _sessionState.update {
            it.copy(
              currentBatchNumber = displayBatchNum,
              activePhaseMessage = "Batch $displayBatchNum/$totalBatches – Processing",
            )
          }
          appendLog("Batch $displayBatchNum/$totalBatches – Processing", LogLevel.INFO)

          val rotationResult =
            translateBatchWithRotation(
              batchBlocks = batchBlocks,
              batchNum = displayBatchNum,
              totalBatches = totalBatches,
              fileNum = displayFileNum,
              totalFiles = totalFiles,
              fileName = file.fileName,
              request = request,
              settings = settings,
              allKeys = allKeys,
              startingKeyIndex = currentKeyIndex,
              exhaustedIndices = temporarilyExhaustedIndices,
              invalidIndices = permanentlyInvalidIndices,
            )

          currentKeyIndex = rotationResult.nextKeyIndex

          if (rotationResult.fatalError != null) {
            // Preserve any validated batches completed so far for this file
            savePartialIfNeeded(
              file = file,
              translatedBlocks = translatedFileBlocks,
              request = request,
              settings = settings,
            )
            appendLog(rotationResult.fatalError, LogLevel.ERROR)
            _sessionState.update {
              it.copy(
                status = TranslationStatus.FAILED,
                activePhaseMessage = rotationResult.fatalError,
              )
            }
            return
          }

          val validatedBlocks = rotationResult.validatedBlocks!!
          translatedFileBlocks.addAll(validatedBlocks)
          completedBlocksAllFiles += validatedBlocks.size

          appendLog("Batch $displayBatchNum/$totalBatches – Completed", LogLevel.SUCCESS)
          _sessionState.update {
            it.copy(
              completedBlocksAllFiles = completedBlocksAllFiles,
              activePhaseMessage = "Batch $displayBatchNum/$totalBatches – Completed",
            )
          }
        }
      } catch (cancel: CancellationException) {
        savePartialIfNeeded(
          file = file,
          translatedBlocks = translatedFileBlocks,
          request = request,
          settings = settings,
        )
        throw cancel
      }

      // All batches for this file completed & validated -> Save output file & Room history
      val formattedSrt = SrtEngine.formatBlocks(translatedFileBlocks)
      val outputName = SrtEngine.buildOutputFileName(file.fileName, settings)
      val savedOutput = fileStorage.saveTranslatedSrt(outputName, formattedSrt)

      val titleMeta = buildTitleMetadata(request)
      historyRepository.saveRecord(
        TranslationHistoryEntity(
          originalFileName = file.fileName,
          outputFileName = savedOutput.outputFileName,
          savedPath = savedOutput.displayPath,
          contentUri = savedOutput.contentUri,
          localBackupPath = savedOutput.localBackupPath,
          mode = request.inputMode.name,
          titleMetadata = titleMeta,
          totalBlocks = file.totalBlocks,
          translatedBlocks = translatedFileBlocks.size,
          totalLines = file.totalLines,
          fileSizeBytes = savedOutput.fileSizeBytes,
          modelUsed = settings.effectiveModel,
          status = "Completed",
        )
      )

      appendLog(
        "File $displayFileNum/$totalFiles – Saved ${savedOutput.outputFileName}",
        LogLevel.SUCCESS,
      )
      _sessionState.update {
        it.copy(
          completedFiles = displayFileNum,
          activePhaseMessage = "File $displayFileNum/$totalFiles – Completed",
        )
      }
    }

    appendLog("All $totalFiles file(s) translated and saved.", LogLevel.SUCCESS)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.COMPLETED,
        completedFiles = totalFiles,
        completedBlocksAllFiles = totalBlocksAllFiles,
        cooldownRemainingSeconds = 0,
        activePhaseMessage = "Translation completed",
      )
    }
  }

  private suspend fun savePartialIfNeeded(
    file: SelectedSubtitleFile,
    translatedBlocks: List<SrtBlock>,
    request: TranslationJobRequest,
    settings: AppSettings,
  ) {
    if (translatedBlocks.isEmpty()) return
    runCatching {
      val formattedSrt = SrtEngine.formatBlocks(translatedBlocks)
      val baseOutputName = SrtEngine.buildOutputFileName(file.fileName, settings)
      val partialName =
        if (baseOutputName.endsWith(".srt", ignoreCase = true)) {
          baseOutputName.dropLast(4) + "_partial.srt"
        } else {
          baseOutputName + "_partial.srt"
        }
      val savedOutput = fileStorage.saveTranslatedSrt(partialName, formattedSrt)
      historyRepository.saveRecord(
        TranslationHistoryEntity(
          originalFileName = file.fileName,
          outputFileName = savedOutput.outputFileName,
          savedPath = savedOutput.displayPath,
          contentUri = savedOutput.contentUri,
          localBackupPath = savedOutput.localBackupPath,
          mode = request.inputMode.name,
          titleMetadata = buildTitleMetadata(request),
          totalBlocks = file.totalBlocks,
          translatedBlocks = translatedBlocks.size,
          totalLines = file.totalLines,
          fileSizeBytes = savedOutput.fileSizeBytes,
          modelUsed = settings.effectiveModel,
          status = "Partial (${translatedBlocks.size}/${file.totalBlocks})",
        )
      )
      appendLog(
        "Preserved ${translatedBlocks.size} validated blocks in ${savedOutput.outputFileName}",
        LogLevel.INFO,
      )
    }
  }

  private data class BatchRotationOutcome(
    val validatedBlocks: List<SrtBlock>? = null,
    val nextKeyIndex: Int,
    val fatalError: String? = null,
  )

  private suspend fun translateBatchWithRotation(
    batchBlocks: List<SrtBlock>,
    batchNum: Int,
    totalBatches: Int,
    fileNum: Int,
    totalFiles: Int,
    fileName: String,
    request: TranslationJobRequest,
    settings: AppSettings,
    allKeys: List<ApiKeyEntry>,
    startingKeyIndex: Int,
    exhaustedIndices: MutableSet<Int>,
    invalidIndices: MutableSet<Int>,
  ): BatchRotationOutcome {
    val batchPayload = SrtEngine.formatBlocks(batchBlocks)
    val maxAttempts = settings.effectiveMaxAttemptsPerKey
    val retryIntervalSec = settings.effectiveRetryIntervalSeconds
    val cooldownMinutes = settings.effectiveCooldownMinutes

    var keyIdx = startingKeyIndex

    while (true) {
      currentCoroutineContext().ensureActive()

      if (invalidIndices.size >= allKeys.size) {
        return BatchRotationOutcome(
          nextKeyIndex = 0,
          fatalError = "All configured API keys are invalid. Please update your keys.",
        )
      }

      // Find next available key that is neither exhausted nor invalid
      val candidateIdx = findNextAvailableKeyIndex(allKeys.size, keyIdx, exhaustedIndices, invalidIndices)
      if (candidateIdx == null) {
        // All non-invalid keys are exhausted -> Enter 30-minute (or configured) cooldown
        runCooldownTimer(cooldownMinutes)
        exhaustedIndices.clear()
        keyStorage.resetExhaustedAndCooldownKeys()
        keyIdx = findNextAvailableKeyIndex(allKeys.size, 0, exhaustedIndices, invalidIndices) ?: 0
        appendLog("Cooldown ended – Resuming from Key #${keyIdx + 1}", LogLevel.INFO)
        continue
      }

      keyIdx = candidateIdx
      val activeKey = allKeys[keyIdx]
      val keyNum = keyIdx + 1

      var attempt = 1
      var switchToNextKey = false

      while (attempt <= maxAttempts && !switchToNextKey) {
        currentCoroutineContext().ensureActive()

        _sessionState.update {
          it.copy(
            status = TranslationStatus.RUNNING,
            currentKeyNumber = keyNum,
            currentKeyLabel = activeKey.label,
            currentAttempt = attempt,
            maxAttempts = maxAttempts,
            cooldownRemainingSeconds = 0,
            activePhaseMessage = "File $fileNum/$totalFiles • Batch $batchNum/$totalBatches",
          )
        }

        val apiResult =
          geminiApiClient.translateBatch(
            apiKey = activeKey.key,
            model = settings.effectiveModel,
            temperature = settings.effectiveTemperature,
            thinkingBudget = settings.effectiveThinkingBudget,
            systemPrompt = settings.effectivePrompt,
            fewShotExamples = settings.fewShotExamples,
            inputMode = request.inputMode,
            title = request.title,
            secondaryMeta = request.secondaryMeta,
            batchSrtPayload = batchPayload,
            batchIndex = batchNum,
            totalBatches = totalBatches,
            fileName = fileName,
          )

        when (apiResult) {
          is GeminiCallResult.Success -> {
            val parsedOutput = SrtEngine.parseModelOutput(apiResult.translatedText)
            when (val validation = SrtEngine.validateBatch(batchBlocks, parsedOutput)) {
              is ValidationResult.Valid -> {
                return BatchRotationOutcome(
                  validatedBlocks = validation.blocks,
                  nextKeyIndex = keyIdx,
                )
              }
              is ValidationResult.Invalid -> {
                appendLog(
                  "Batch $batchNum/$totalBatches – Validation failed: ${validation.reason}",
                  LogLevel.WARNING,
                )
                if (attempt < maxAttempts) {
                  appendLog(
                    "Retrying batch in ${retryIntervalSec}s (Attempt ${attempt + 1}/$maxAttempts)",
                    LogLevel.INFO,
                  )
                  delayCancellableSeconds(retryIntervalSec, "Retrying batch in")
                } else {
                  appendLog(
                    "Key #$keyNum – Validation failed $maxAttempts times, switching key",
                    LogLevel.WARNING,
                  )
                  exhaustedIndices.add(keyIdx)
                  switchToNextKey = true
                }
              }
            }
          }

          is GeminiCallResult.QuotaExhausted -> {
            appendLog("Key #$keyNum – Daily Limit Exceeded", LogLevel.ERROR)
            keyStorage.updateKeyStatus(
              activeKey.id,
              ApiKeyStatus.QUOTA_EXHAUSTED,
              apiResult.reason,
            )
            exhaustedIndices.add(keyIdx)
            switchToNextKey = true
          }

          is GeminiCallResult.InvalidKey -> {
            appendLog("Key #$keyNum – Invalid API Key", LogLevel.ERROR)
            keyStorage.updateKeyStatus(activeKey.id, ApiKeyStatus.INVALID, apiResult.reason)
            invalidIndices.add(keyIdx)
            switchToNextKey = true
          }

          is GeminiCallResult.RetryableError -> {
            val shortReason =
              if (apiResult.reason.contains("Server busy", ignoreCase = true) ||
                apiResult.httpCode == 503 ||
                apiResult.httpCode == 500
              ) {
                "Server Busy"
              } else {
                apiResult.reason.take(64)
              }
            appendLog("Key #$keyNum – $shortReason", LogLevel.WARNING)
            if (attempt < maxAttempts) {
              appendLog("Retrying in ${retryIntervalSec}s", LogLevel.INFO)
              delayCancellableSeconds(retryIntervalSec, "Retrying in")
            } else {
              appendLog(
                "Key #$keyNum – Max attempts ($maxAttempts) reached, switching key",
                LogLevel.WARNING,
              )
              exhaustedIndices.add(keyIdx)
              switchToNextKey = true
            }
          }
        }
        attempt++
      }

      // Move to next key index on next iteration
      keyIdx = (keyIdx + 1) % allKeys.size
    }
  }

  private fun findNextAvailableKeyIndex(
    totalKeys: Int,
    startIndex: Int,
    exhaustedIndices: Set<Int>,
    invalidIndices: Set<Int>,
  ): Int? {
    for (offset in 0 until totalKeys) {
      val idx = (startIndex + offset) % totalKeys
      if (idx !in exhaustedIndices && idx !in invalidIndices) {
        return idx
      }
    }
    return null
  }

  private suspend fun delayCancellableSeconds(seconds: Int, prefix: String) {
    for (remaining in seconds downTo 1) {
      currentCoroutineContext().ensureActive()
      _sessionState.update { it.copy(activePhaseMessage = "$prefix ${remaining}s") }
      delay(1000L)
    }
  }

  private suspend fun runCooldownTimer(cooldownMinutes: Int) {
    val totalSeconds = cooldownMinutes * 60
    appendLog("All Keys Exhausted – Cooldown ${cooldownMinutes}m", LogLevel.COOLDOWN)
    _sessionState.update {
      it.copy(
        status = TranslationStatus.COOLDOWN,
        cooldownRemainingSeconds = totalSeconds,
        activePhaseMessage = "All Keys Exhausted – Cooldown ${cooldownMinutes}m",
      )
    }

    for (remaining in totalSeconds downTo 1) {
      currentCoroutineContext().ensureActive()
      val mins = remaining / 60
      val secs = remaining % 60
      val formatted = String.format(Locale.US, "%02dm %02ds", mins, secs)
      _sessionState.update {
        it.copy(
          status = TranslationStatus.COOLDOWN,
          cooldownRemainingSeconds = remaining,
          activePhaseMessage = "Cooldown $formatted",
        )
      }
      delay(1000L)
    }
  }

  private fun buildTitleMetadata(request: TranslationJobRequest): String {
    val t = request.title.trim()
    val s = request.secondaryMeta.trim()
    return when {
      t.isNotBlank() && s.isNotBlank() ->
        if (request.inputMode == InputMode.MOVIE) "$t ($s)" else "$t • $s"
      t.isNotBlank() -> t
      s.isNotBlank() -> s
      else -> if (request.inputMode == InputMode.MOVIE) "Movie Subtitle" else "Series Subtitle"
    }
  }

  private fun appendLog(message: String, level: LogLevel) {
    val entry =
      ProcessingLogEntry(
        id = logIdGen.getAndIncrement(),
        timestampLabel = timeFormatter.format(Date()),
        message = message,
        level = level,
      )
    _sessionState.update { state ->
      val updated = (state.logs + entry).takeLast(400)
      state.copy(logs = updated)
    }
  }
}
