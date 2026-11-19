package com.example.data.network

import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.logging.ErrorCategory
import com.example.logging.TranslationLogger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class KeyRotationState(
  val totalKeys: Int = 0,
  val activeKeyIndex: Int = 0,
  val activeKeyLabel: String = "",
  val exhaustedCount: Int = 0,
  val invalidCount: Int = 0,
  val inCooldown: Boolean = false,
)

class KeyRotationSession internal constructor(
  val keys: List<ApiKeyEntry>,
  initialIndex: Int,
) {
  var currentIndex: Int =
    if (keys.isEmpty()) 0 else initialIndex.coerceIn(0, keys.lastIndex)
    internal set

  val exhaustedIndices: MutableSet<Int> = HashSet()
  val invalidIndices: MutableSet<Int> =
    keys.indices.filterTo(HashSet()) { keys[it].status == ApiKeyStatus.INVALID }

  fun findNextAvailableIndex(startFromIndex: Int = currentIndex): Int? {
    if (keys.isEmpty()) return null
    val start = startFromIndex.coerceIn(0, keys.lastIndex)
    for (offset in keys.indices) {
      val idx = (start + offset) % keys.size
      if (idx !in exhaustedIndices && idx !in invalidIndices) {
        return idx
      }
    }
    return null
  }

  fun allKeysPermanentlyInvalid(): Boolean =
    keys.isNotEmpty() && invalidIndices.size >= keys.size
}

sealed interface BatchValidationOutcome<out T> {
  data class Valid<T>(val value: T) : BatchValidationOutcome<T>
  data class Invalid(val reason: String) : BatchValidationOutcome<Nothing>
}

sealed interface RotationExecutionResult<out T> {
  data class Success<T>(
    val value: T,
    val usedKeyNumber: Int,
    val usedKeyLabel: String,
  ) : RotationExecutionResult<T>

  data class Failure(val fatalError: String) : RotationExecutionResult<Nothing>
}

class ApiKeyRotationManager(
  private val keyStorage: SecureKeyStorage,
  private val geminiApiClient: GeminiApiClient,
) {

  private val rotationMutex = Mutex()

  private val _rotationState = MutableStateFlow(KeyRotationState())
  val rotationState: StateFlow<KeyRotationState> = _rotationState.asStateFlow()

  suspend fun prepareSession(): KeyRotationSession =
    rotationMutex.withLock {
      keyStorage.resetExhaustedAndCooldownKeys()
      var keys = keyStorage.getKeysSnapshot()
      if (keys.isNotEmpty() && keys.all { it.status == ApiKeyStatus.INVALID }) {
        // Allow retry if user edited or re-enabled previously invalid keys
        keyStorage.resetAllKeyStatuses()
        keys = keyStorage.getKeysSnapshot()
      }

      val persistedIdx =
        if (keys.isEmpty()) 0 else keyStorage.getActiveKeyIndex() % keys.size
      val session = KeyRotationSession(keys = keys, initialIndex = persistedIdx)

      val activeIdx = session.findNextAvailableIndex(persistedIdx) ?: 0
      session.currentIndex = activeIdx
      if (keys.isNotEmpty()) {
        keyStorage.setActiveKeyIndex(activeIdx)
      }

      publishState(session, inCooldown = false)
      session
    }

  suspend fun getCurrentAvailableKey(session: KeyRotationSession): Pair<Int, ApiKeyEntry>? =
    rotationMutex.withLock {
      val idx = session.findNextAvailableIndex(session.currentIndex) ?: return@withLock null
      session.currentIndex = idx
      keyStorage.setActiveKeyIndex(idx)
      publishState(session, inCooldown = false)
      idx to session.keys[idx]
    }

  suspend fun rotateToNextAvailableKey(
    session: KeyRotationSession,
    markCurrentExhausted: Boolean = false,
    markCurrentInvalid: Boolean = false,
    errorReason: String? = null,
  ): Pair<Int, ApiKeyEntry>? =
    rotationMutex.withLock {
      if (session.keys.isEmpty()) return@withLock null
      val failedIdx = session.currentIndex.coerceIn(0, session.keys.lastIndex)
      val failedKey = session.keys[failedIdx]

      when {
        markCurrentInvalid -> {
          session.invalidIndices.add(failedIdx)
          keyStorage.updateKeyStatus(failedKey.id, ApiKeyStatus.INVALID, errorReason)
        }
        markCurrentExhausted -> {
          session.exhaustedIndices.add(failedIdx)
          keyStorage.updateKeyStatus(failedKey.id, ApiKeyStatus.QUOTA_EXHAUSTED, errorReason)
        }
      }

      val nextStart = (failedIdx + 1) % session.keys.size
      val nextIdx = session.findNextAvailableIndex(nextStart)
      if (nextIdx != null) {
        session.currentIndex = nextIdx
        keyStorage.setActiveKeyIndex(nextIdx)
        publishState(session, inCooldown = false)
        nextIdx to session.keys[nextIdx]
      } else {
        publishState(session, inCooldown = !session.allKeysPermanentlyInvalid())
        null
      }
    }

  suspend fun markExhaustedKeysCooldownInStorage(session: KeyRotationSession) {
    rotationMutex.withLock {
      publishState(session, inCooldown = true)
      for (idx in session.exhaustedIndices) {
        val entry = session.keys.getOrNull(idx) ?: continue
        if (idx !in session.invalidIndices) {
          keyStorage.updateKeyStatus(
            entry.id,
            ApiKeyStatus.COOLDOWN,
            "Temporarily rate-limited or quota-exhausted; waiting for cooldown",
          )
        }
      }
    }
  }

  suspend fun resetExhaustedAfterCooldown(session: KeyRotationSession): Int =
    rotationMutex.withLock {
      session.exhaustedIndices.clear()
      keyStorage.resetExhaustedAndCooldownKeys()
      val nextIdx = session.findNextAvailableIndex(0) ?: 0
      session.currentIndex = nextIdx
      if (session.keys.isNotEmpty()) {
        keyStorage.setActiveKeyIndex(nextIdx)
      }
      publishState(session, inCooldown = false)
      nextIdx
    }

  suspend fun <T> executeBatchWithRotation(
    session: KeyRotationSession,
    model: String,
    temperature: Float,
    thinkingBudget: Int,
    systemPrompt: String,
    fewShotExamples: List<FewShotExample>,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int,
    totalBatches: Int,
    fileName: String,
    maxAttemptsPerKey: Int,
    retryIntervalSeconds: Int,
    cooldownMinutes: Int,
    workId: String? = null,
    validateOutput: (String) -> BatchValidationOutcome<T>,
    onAttemptStarted: suspend (keyNumber: Int, keyLabel: String, attempt: Int, maxAttempts: Int) -> Unit = { _, _, _, _ -> },
    onStatusLog: suspend (message: String, level: LogLevel) -> Unit = { _, _ -> },
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit = { seconds, _ ->
      for (remaining in seconds downTo 1) {
        currentCoroutineContext().ensureActive()
        delay(1000L)
      }
    },
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit = { minutes ->
      delay(minutes * 60_000L)
    },
  ): RotationExecutionResult<T> {
    if (session.keys.isEmpty()) {
      return RotationExecutionResult.Failure(
        "No Gemini API keys configured. Add keys in API Key Manager."
      )
    }

    val safeMaxAttempts = maxAttemptsPerKey.coerceAtLeast(1)
    val safeRetrySeconds = retryIntervalSeconds.coerceAtLeast(1)

    while (true) {
      currentCoroutineContext().ensureActive()

      if (session.allKeysPermanentlyInvalid()) {
        return RotationExecutionResult.Failure(
          "All configured API keys are invalid. Please update your keys."
        )
      }

      val selected = getCurrentAvailableKey(session)
      if (selected == null) {
        TranslationLogger.logCooldownEntered(
          cooldownMinutes = cooldownMinutes,
          reason =
            "All ${session.keys.size} keys exhausted or rate-limited during $fileName batch $batchIndex/$totalBatches",
          workId = workId,
        )
        markExhaustedKeysCooldownInStorage(session)
        onCooldownWait(cooldownMinutes)
        val resumedIdx = resetExhaustedAfterCooldown(session)
        onStatusLog("Cooldown ended – Resuming from Key #${resumedIdx + 1}", LogLevel.INFO)
        continue
      }

      val (keyIdx, activeKey) = selected
      val keyNum = keyIdx + 1
      var attempt = 1
      var switchToNextKey = false

      while (attempt <= safeMaxAttempts && !switchToNextKey) {
        currentCoroutineContext().ensureActive()
        onAttemptStarted(keyNum, activeKey.label, attempt, safeMaxAttempts)

        val apiResult =
          geminiApiClient.translateBatch(
            apiKey = activeKey.key,
            model = model,
            temperature = temperature,
            thinkingBudget = thinkingBudget,
            systemPrompt = systemPrompt,
            fewShotExamples = fewShotExamples,
            inputMode = inputMode,
            title = title,
            secondaryMeta = secondaryMeta,
            batchSrtPayload = batchSrtPayload,
            batchIndex = batchIndex,
            totalBatches = totalBatches,
            fileName = fileName,
          )

        when (apiResult) {
          is GeminiCallResult.Success -> {
            when (val validation = validateOutput(apiResult.translatedText)) {
              is BatchValidationOutcome.Valid -> {
                return RotationExecutionResult.Success(
                  value = validation.value,
                  usedKeyNumber = keyNum,
                  usedKeyLabel = activeKey.label,
                )
              }
              is BatchValidationOutcome.Invalid -> {
                TranslationLogger.logValidationError(
                  fileName = fileName,
                  batchNumber = batchIndex,
                  totalBatches = totalBatches,
                  reason = validation.reason,
                  keyLabel = "Key #$keyNum (${activeKey.label})",
                  workId = workId,
                )
                onStatusLog(
                  "Batch $batchIndex/$totalBatches – Validation failed: ${validation.reason}",
                  LogLevel.WARNING,
                )
                if (attempt < safeMaxAttempts) {
                  onStatusLog(
                    "Retrying batch in ${safeRetrySeconds}s (Attempt ${attempt + 1}/$safeMaxAttempts)",
                    LogLevel.INFO,
                  )
                  onRetryDelay(safeRetrySeconds, "Retrying batch in")
                } else {
                  onStatusLog(
                    "Key #$keyNum – Validation failed $safeMaxAttempts times, switching key",
                    LogLevel.WARNING,
                  )
                  rotateToNextAvailableKey(
                    session = session,
                    markCurrentExhausted = true,
                    errorReason = "Validation failed $safeMaxAttempts times: ${validation.reason}",
                  )
                  switchToNextKey = true
                }
              }
            }
          }

          is GeminiCallResult.QuotaExhausted -> {
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_QUOTA_EXHAUSTED,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = 429,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog("Key #$keyNum – Daily Limit Exceeded", LogLevel.ERROR)
            rotateToNextAvailableKey(
              session = session,
              markCurrentExhausted = true,
              errorReason = apiResult.reason,
            )
            switchToNextKey = true
          }

          is GeminiCallResult.InvalidKey -> {
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_INVALID_KEY,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = 400,
              reason = apiResult.reason,
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            onStatusLog("Key #$keyNum – Invalid API Key", LogLevel.ERROR)
            rotateToNextAvailableKey(
              session = session,
              markCurrentInvalid = true,
              errorReason = apiResult.reason,
            )
            switchToNextKey = true
          }

          is GeminiCallResult.RetryableError -> {
            TranslationLogger.logApiFailure(
              category = ErrorCategory.API_RETRYABLE_ERROR,
              keyLabel = "Key #$keyNum (${activeKey.label})",
              httpCode = apiResult.httpCode,
              reason = "${apiResult.reason} (attempt $attempt/$safeMaxAttempts)",
              fileName = fileName,
              batchNumber = batchIndex,
              workId = workId,
            )
            val shortReason =
              if (apiResult.reason.contains("Server busy", ignoreCase = true) ||
                apiResult.httpCode == 503 ||
                apiResult.httpCode == 500
              ) {
                "Server Busy"
              } else {
                apiResult.reason.take(64)
              }
            onStatusLog("Key #$keyNum – $shortReason", LogLevel.WARNING)

            val isRateLimit = apiResult.httpCode == 429
            if (!isRateLimit && attempt < safeMaxAttempts) {
              onStatusLog("Retrying in ${safeRetrySeconds}s", LogLevel.INFO)
              onRetryDelay(safeRetrySeconds, "Retrying in")
            } else {
              val reasonMsg =
                if (isRateLimit) {
                  "Rate limit hit (HTTP 429), switching to next key"
                } else {
                  "Max attempts ($safeMaxAttempts) reached, switching key"
                }
              onStatusLog("Key #$keyNum – $reasonMsg", LogLevel.WARNING)
              rotateToNextAvailableKey(
                session = session,
                markCurrentExhausted = true,
                errorReason = apiResult.reason,
              )
              switchToNextKey = true
            }
          }
        }
        attempt++
      }
    }
  }

  /**
   * Convenience entry point for direct callers wanting Retrofit + DataStore key rotation
   * using the current AppSettings snapshot.
   */
  suspend fun translateBatchWithAutoRotation(
    settings: AppSettings,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int = 1,
    totalBatches: Int = 1,
    fileName: String = "subtitle.srt",
    workId: String? = null,
    onRetryDelay: suspend (seconds: Int, prefix: String) -> Unit = { seconds, _ ->
      for (remaining in seconds downTo 1) {
        currentCoroutineContext().ensureActive()
        delay(1000L)
      }
    },
    onCooldownWait: suspend (cooldownMinutes: Int) -> Unit = { minutes ->
      delay(minutes * 60_000L)
    },
  ): RotationExecutionResult<String> {
    val session = prepareSession()
    return executeBatchWithRotation(
      session = session,
      model = settings.effectiveModel,
      temperature = settings.effectiveTemperature,
      thinkingBudget = settings.effectiveThinkingBudget,
      systemPrompt = settings.effectivePrompt,
      fewShotExamples = settings.fewShotExamples,
      inputMode = inputMode,
      title = title,
      secondaryMeta = secondaryMeta,
      batchSrtPayload = batchSrtPayload,
      batchIndex = batchIndex,
      totalBatches = totalBatches,
      fileName = fileName,
      maxAttemptsPerKey = settings.effectiveMaxAttemptsPerKey,
      retryIntervalSeconds = settings.effectiveRetryIntervalSeconds,
      cooldownMinutes = settings.effectiveCooldownMinutes,
      workId = workId,
      validateOutput = { raw ->
        if (raw.isBlank()) BatchValidationOutcome.Invalid("Empty response from Gemini")
        else BatchValidationOutcome.Valid(raw)
      },
      onRetryDelay = onRetryDelay,
      onCooldownWait = onCooldownWait,
    )
  }

  private fun publishState(session: KeyRotationSession, inCooldown: Boolean) {
    val current = session.keys.getOrNull(session.currentIndex)
    _rotationState.value =
      KeyRotationState(
        totalKeys = session.keys.size,
        activeKeyIndex = session.currentIndex,
        activeKeyLabel = current?.label.orEmpty(),
        exhaustedCount = session.exhaustedIndices.size,
        invalidCount = session.invalidIndices.size,
        inCooldown = inCooldown,
      )
  }
}
