package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.data.local.BSubDatabase
import com.example.data.local.FileTranslationProgress
import com.example.data.local.RoomTranslationProgress
import com.example.data.local.SubtitleTranslation
import com.example.data.local.SubtitleTranslationRepository
import com.example.data.local.SubtitleTranslationStatus
import com.example.data.network.ApiKeyRotationManager
import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiApiService
import com.example.data.network.GeminiCandidate
import com.example.data.network.GeminiContent
import com.example.data.network.GeminiGenerateContentRequest
import com.example.data.network.GeminiGenerateContentResponse
import com.example.data.network.GeminiPart
import com.example.data.network.RotationExecutionResult
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.InputMode
import com.example.domain.model.ParamMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.domain.model.ThemeMode
import com.example.domain.model.TranslationJobRequest
import com.example.domain.model.TranslationSessionState
import com.example.logging.ErrorCategory
import com.example.logging.TranslationLogger
import com.example.logging.WorkManagerDiagnosticTree
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.BSubTranslatorTheme
import com.example.ui.theme.LightCanvas
import com.example.ui.theme.LocalThemeController
import com.example.ui.theme.StudioMidnight
import com.example.ui.theme.ThemeProvider
import com.example.ui.viewmodel.StudioFormState
import com.example.worker.SubtitleTranslationWorker
import com.example.worker.TranslationWorkScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var db: BSubDatabase
  private lateinit var app: BSubApplication

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    app = context as BSubApplication
    db = Room.inMemoryDatabaseBuilder(context, BSubDatabase::class.java).allowMainThreadQueries().build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
  }

  @After
  fun tearDown() {
    app.overrideGeminiServiceForTesting(null)
    TranslationLogger.resetForTesting(app)
    db.close()
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("BSub Translator", appName)
  }

  @Test
  fun `subtitle translation entity persists pending and transitions to translated`() = runBlocking {
    val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
    val block = SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:03,000", lines = listOf("Hello!"))

    repo.seedPendingBlocks(jobId = "job_1", fileName = "movie.srt", blocks = listOf(block))

    val initialRows: List<SubtitleTranslation> = repo.observeJobTranslations("job_1").first()
    assertEquals(1, initialRows.size)
    assertEquals("Hello!", initialRows[0].originalEnglishText)
    assertEquals(null, initialRows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.PENDING, initialRows[0].status)

    val translatedBlock = block.copy(lines = listOf("হ্যালো!"))
    repo.markBatchTranslated(jobId = "job_1", translatedBlocks = listOf(translatedBlock))

    val updatedRows = repo.observeJobTranslations("job_1").first()
    assertEquals(1, updatedRows.size)
    assertEquals("হ্যালো!", updatedRows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, updatedRows[0].status)
  }

  @Test
  fun `subtitle translation repository inserts queries by status and updates by id`() = runBlocking {
    val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
    val id =
      repo.insertTranslation(
        jobId = "job_2",
        fileName = "episode1.srt",
        blockIndex = 1,
        timestamp = "00:00:05,000 --> 00:00:07,000",
        originalEnglishText = "Where are we going?",
      )

    val pending = repo.observeByStatus(SubtitleTranslationStatus.PENDING).first()
    assertEquals(1, pending.size)
    assertEquals(id, pending[0].id)

    repo.markAsTranslated(id, "আমরা কোথায় যাচ্ছি?")

    val translated = repo.observeByStatus(SubtitleTranslationStatus.TRANSLATED).first()
    assertEquals(1, translated.size)
    assertEquals("আমরা কোথায় যাচ্ছি?", translated[0].translatedBengaliText)
  }

  @Test
  fun `workmanager subtitle translation worker parses srt seeds room and translates batch`() = runBlocking {
    app.subtitleTranslationRepository.clearAll()
    app.secureKeyStorage.clearAllKeys()
    app.secureKeyStorage.addSingleKey("AIzaSyDummyTestKeyForWorkManager12345", "Test Key #1")

    val fakeGemini =
      object : GeminiApiService {
        override suspend fun generateContent(
          model: String,
          apiKey: String,
          request: GeminiGenerateContentRequest,
        ): Response<GeminiGenerateContentResponse> {
          val translatedSrt =
            """
            1
            00:00:01,000 --> 00:00:03,000
            কেমন আছেন?

            2
            00:00:04,000 --> 00:00:06,500
            সবকিছু ঠিক আছে।
            """.trimIndent()
          return Response.success(
            GeminiGenerateContentResponse(
              candidates =
                listOf(
                  GeminiCandidate(
                    content = GeminiContent(parts = listOf(GeminiPart(text = translatedSrt))),
                    finishReason = "STOP",
                  )
                )
            )
          )
        }
      }

    app.overrideGeminiServiceForTesting(fakeGemini)

    val sampleSrt =
      """
      1
      00:00:01,000 --> 00:00:03,000
      How are you?

      2
      00:00:04,000 --> 00:00:06,500
      Everything is fine.
      """.trimIndent()

    val workerJobId = "wm_test_job_1"
    val worker =
      TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
        .setInputData(
          workDataOf(
            SubtitleTranslationWorker.KEY_SRT_CONTENT to sampleSrt,
            SubtitleTranslationWorker.KEY_FILE_NAME to "test_movie.srt",
            SubtitleTranslationWorker.KEY_INPUT_MODE to InputMode.MOVIE.name,
            SubtitleTranslationWorker.KEY_TITLE to "Test Film",
            SubtitleTranslationWorker.KEY_SECONDARY_META to "2026",
            SubtitleTranslationWorker.KEY_ROOM_JOB_ID to workerJobId,
          )
        )
        .build()

    val result = worker.doWork()
    assertTrue("Expected WorkManager Result.Success, got $result", result is ListenableWorker.Result.Success)

    val rows = app.subtitleTranslationRepository.observeJobTranslations(workerJobId).first()
    assertEquals(2, rows.size)
    assertEquals("How are you?", rows[0].originalEnglishText)
    assertEquals("কেমন আছেন?", rows[0].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, rows[0].status)
    assertEquals("সবকিছু ঠিক আছে।", rows[1].translatedBengaliText)
    assertEquals(SubtitleTranslationStatus.TRANSLATED, rows[1].status)
  }

  @Test
  fun `translation work scheduler enqueues unique work with network constraint`() = runBlocking {
    val sampleBlocks =
      listOf(
        SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:02,000", lines = listOf("Hello"))
      )
    val request =
      TranslationJobRequest(
        inputMode = InputMode.MOVIE,
        title = "Inception",
        secondaryMeta = "2010",
        files =
          listOf(
            SelectedSubtitleFile(
              uriString = "",
              fileName = "inception.srt",
              fileSizeBytes = 64L,
              totalBlocks = 1,
              totalLines = 1,
              blocks = sampleBlocks,
            )
          ),
      )

    val workId = TranslationWorkScheduler.enqueueTranslation(app, request)
    val info = WorkManager.getInstance(app).getWorkInfoById(workId).get()
    assertEquals(WorkInfo.State.ENQUEUED, info?.state)
    assertTrue(info?.tags?.contains(TranslationWorkScheduler.TAG_SUBTITLE_TRANSLATION) == true)
  }

  @Test
  fun `room progress queries and studio progress indicator update from translation status counts`() =
    runBlocking {
      val repo = SubtitleTranslationRepository(db.subtitleTranslationDao())
      val blocks =
        listOf(
          SrtBlock(index = 1, timestamp = "00:00:01,000 --> 00:00:02,000", lines = listOf("One")),
          SrtBlock(index = 2, timestamp = "00:00:03,000 --> 00:00:04,000", lines = listOf("Two")),
        )
      repo.seedPendingBlocks(jobId = "progress_job", fileName = "sample.srt", blocks = blocks)

      val initial = repo.observeOverallProgress().first()
      assertEquals(2, initial.totalBlocks)
      assertEquals(0, initial.translatedBlocks)
      assertEquals(2, initial.pendingBlocks)
      assertEquals(0f, initial.progressFraction)

      composeTestRule.setContent {
        val roomProgress by repo.observeOverallProgress().collectAsState(initial = RoomTranslationProgress())
        val fileProgress by
          repo.observeFileProgressList().collectAsState(initial = emptyList<FileTranslationProgress>())
        BSubTranslatorTheme {
          StudioScreen(
            formState = StudioFormState(),
            settings = AppSettings(),
            apiKeys = emptyList(),
            sessionState = TranslationSessionState(),
            roomProgress = roomProgress,
            fileProgressList = fileProgress,
            onInputModeChange = {},
            onMovieNameChange = {},
            onReleaseYearChange = {},
            onSeriesNameChange = {},
            onSeasonChange = {},
            onSingleSrtPicked = {},
            onMultipleSrtPicked = {},
            onRemoveFile = {},
            onMoveSeriesFile = { _, _ -> },
            onClearFiles = {},
            onStartTranslation = {},
            onAbortTranslation = {},
            onClearLogs = {},
            onNavigateToKeys = {},
          )
        }
      }

      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("room_progress_counts").assertTextEquals("0/2 blocks processed")
      composeTestRule.onNodeWithTag("room_progress_percent").assertTextEquals("0%")

      repo.markBatchTranslated(
        jobId = "progress_job",
        translatedBlocks = listOf(blocks[0].copy(lines = listOf("এক"))),
      )

      val halfOverall = repo.observeOverallProgress().first { it.translatedBlocks == 1 }
      assertEquals(1, halfOverall.pendingBlocks)

      composeTestRule.waitUntil(timeoutMillis = 5000L) {
        try {
          composeTestRule.onNodeWithTag("room_progress_counts").assertTextEquals("1/2 blocks processed")
          true
        } catch (_: AssertionError) {
          false
        }
      }
      composeTestRule.onNodeWithTag("room_progress_percent").assertTextEquals("50%")

      val halfFileProgress = repo.observeFileProgressList().first()
      assertEquals(1, halfFileProgress.size)
      assertEquals(1, halfFileProgress[0].translatedBlocks)
      assertEquals(1, halfFileProgress[0].pendingBlocks)
      assertEquals(50, halfFileProgress[0].progressPercent)
    }

  @Test
  fun `theme provider dynamically toggles light and dark color schemes across ui segments`() {
    var observedBgColor = Color.Unspecified
    var observedIsDark = true

    composeTestRule.setContent {
      var mode by remember { mutableStateOf(ThemeMode.DARK) }
      ThemeProvider(
        themeMode = mode,
        onThemeModeChange = { mode = it },
        animateTransitions = false,
      ) {
        observedBgColor = MaterialTheme.colorScheme.background
        observedIsDark = LocalThemeController.current.isDarkTheme
        StudioScreen(
          formState = StudioFormState(),
          settings = AppSettings(themeMode = mode),
          apiKeys = emptyList(),
          sessionState = TranslationSessionState(),
          onInputModeChange = {},
          onMovieNameChange = {},
          onReleaseYearChange = {},
          onSeriesNameChange = {},
          onSeasonChange = {},
          onSingleSrtPicked = {},
          onMultipleSrtPicked = {},
          onRemoveFile = {},
          onMoveSeriesFile = { _, _ -> },
          onClearFiles = {},
          onStartTranslation = {},
          onAbortTranslation = {},
          onClearLogs = {},
          onNavigateToKeys = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    assertTrue(observedIsDark)
    assertEquals(StudioMidnight, observedBgColor)
    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").assertTextContains("Dark")

    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").performClick()
    composeTestRule.waitForIdle()

    assertEquals(false, observedIsDark)
    assertEquals(LightCanvas, observedBgColor)
    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").assertTextContains("Light")
  }

  @Test
  fun `timber diagnostic tree tracks workmanager api failures redacts keys and persists log file`() =
    runBlocking {
      assertTrue(
        "Expected BSubApplication.onCreate() to plant WorkManagerDiagnosticTree",
        Timber.forest().any { it is WorkManagerDiagnosticTree },
      )

      TranslationLogger.clearDiagnosticLogs(app)
      app.subtitleTranslationRepository.clearAll()
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)
      app.settingsRepository.setRetryInterval(ParamMode.MANUAL, 1)

      val keyAlpha = "AIzaSySecretAlphaKeyValue1234567890"
      val keyBravo = "AIzaSySecretBravoKeyValue1234567890"
      val keyCharlie = "AIzaSySecretCharlieKeyValue12345678"
      val keyDelta = "AIzaSySecretDeltaKeyValue1234567890"

      app.secureKeyStorage.addSingleKey(keyAlpha, "Alpha")
      app.secureKeyStorage.addSingleKey(keyBravo, "Bravo")
      app.secureKeyStorage.addSingleKey(keyCharlie, "Charlie")
      app.secureKeyStorage.addSingleKey(keyDelta, "Delta")

      val jsonMedia = "application/json".toMediaType()
      val fakeGemini =
        object : GeminiApiService {
          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            return when (apiKey) {
              keyAlpha ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for ?key=$keyAlpha"}}"""
                    .toResponseBody(jsonMedia),
                )
              keyBravo ->
                Response.error(
                  503,
                  """{"error":{"status":"UNAVAILABLE","message":"Server busy for $keyBravo"}}"""
                    .toResponseBody(jsonMedia),
                )
              keyCharlie ->
                Response.error(
                  400,
                  """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid: $keyCharlie"}}"""
                    .toResponseBody(jsonMedia),
                )
              else ->
                Response.success(
                  GeminiGenerateContentResponse(
                    candidates =
                      listOf(
                        GeminiCandidate(
                          content =
                            GeminiContent(
                              parts =
                                listOf(
                                  GeminiPart(
                                    text =
                                      """
                                      1
                                      00:00:01,000 --> 00:00:03,000
                                      হ্যালো পৃথিবী।
                                      """.trimIndent()
                                  )
                                )
                            ),
                          finishReason = "STOP",
                        )
                      )
                  )
                )
            }
          }
        }

      app.overrideGeminiServiceForTesting(fakeGemini)

      val sampleSrt =
        """
        1
        00:00:01,000 --> 00:00:03,000
        Hello world.
        """.trimIndent()

      val worker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to sampleSrt,
              SubtitleTranslationWorker.KEY_FILE_NAME to "diagnostic_test.srt",
              SubtitleTranslationWorker.KEY_ROOM_JOB_ID to "timber_test_job",
            )
          )
          .build()

      val result = worker.doWork()
      assertTrue("Expected worker success after key rotation, got $result", result is ListenableWorker.Result.Success)

      val events = TranslationLogger.errorEvents.value
      assertTrue(events.any { it.category == ErrorCategory.API_QUOTA_EXHAUSTED && it.httpCode == 429 })
      assertTrue(events.any { it.category == ErrorCategory.API_RETRYABLE_ERROR && it.httpCode == 503 })
      assertTrue(events.any { it.category == ErrorCategory.API_INVALID_KEY && it.httpCode == 400 })
      assertTrue(events.all { it.fileName == "diagnostic_test.srt" && !it.workId.isNullOrBlank() })

      val persistedLog = TranslationLogger.readPersistedErrorLog(app)
      assertTrue(persistedLog.contains("API_QUOTA_EXHAUSTED"))
      assertTrue(persistedLog.contains("API_RETRYABLE_ERROR"))
      assertTrue(persistedLog.contains("API_INVALID_KEY"))
      assertFalse("Raw API key Alpha leaked into persisted log!", persistedLog.contains(keyAlpha))
      assertFalse("Raw API key Bravo leaked into persisted log!", persistedLog.contains(keyBravo))
      assertFalse("Raw API key Charlie leaked into persisted log!", persistedLog.contains(keyCharlie))
    }

  @Test
  fun `timber diagnostic tree captures workmanager srt parse and execution failures`() =
    runBlocking {
      TranslationLogger.clearDiagnosticLogs(app)

      val worker =
        TestListenableWorkerBuilder<SubtitleTranslationWorker>(app)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_CONTENT to "not a valid subtitle file",
              SubtitleTranslationWorker.KEY_FILE_NAME to "broken_input.srt",
            )
          )
          .build()

      val result = worker.doWork()
      assertTrue("Expected Result.Failure for invalid SRT", result is ListenableWorker.Result.Failure)

      val events = TranslationLogger.errorEvents.value
      assertTrue(
        "Expected SRT_PARSE_ERROR event, got $events",
        events.any {
          it.category == ErrorCategory.SRT_PARSE_ERROR && it.fileName == "broken_input.srt"
        },
      )
      assertTrue(
        "Expected WORKER_EXECUTION_ERROR event, got $events",
        events.any { it.category == ErrorCategory.WORKER_EXECUTION_ERROR },
      )
    }

  @Test
  fun `api key rotation manager rotates datastore keys on retrofit rate limit and persists active cursor`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()

      val key1 = "AIzaSyRateLimitedKeyOne1234567890"
      val key2 = "AIzaSyInvalidKeyTwo12345678901234"
      val key3 = "AIzaSyHealthyKeyThree123456789012"

      app.secureKeyStorage.addSingleKey(key1, "Primary")
      app.secureKeyStorage.addSingleKey(key2, "Backup")
      app.secureKeyStorage.addSingleKey(key3, "Reserve")

      val attemptedKeys = mutableListOf<String>()
      val jsonMedia = "application/json".toMediaType()
      val retrofitService =
        object : GeminiApiService {
          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            attemptedKeys.add(apiKey)
            return when (apiKey) {
              key1 ->
                Response.error(
                  429,
                  """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Rate limit exceeded"}}"""
                    .toResponseBody(jsonMedia),
                )
              key2 ->
                Response.error(
                  401,
                  """{"error":{"status":"PERMISSION_DENIED","message":"Invalid API key"}}"""
                    .toResponseBody(jsonMedia),
                )
              else ->
                Response.success(
                  GeminiGenerateContentResponse(
                    candidates =
                      listOf(
                        GeminiCandidate(
                          content =
                            GeminiContent(
                              parts =
                                listOf(
                                  GeminiPart(
                                    text = "1\n00:00:01,000 --> 00:00:02,000\nসব ঠিক আছে।"
                                  )
                                )
                            ),
                          finishReason = "STOP",
                        )
                      )
                  )
                )
            }
          }
        }

      val rotationManager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = GeminiApiClient(customService = retrofitService),
        )

      val result =
        rotationManager.translateBatchWithAutoRotation(
          settings = AppSettings(),
          inputMode = InputMode.MOVIE,
          title = "Arrival",
          secondaryMeta = "2016",
          batchSrtPayload = "1\n00:00:01,000 --> 00:00:02,000\nEverything is fine.",
          onRetryDelay = { _, _ -> },
        )

      assertTrue("Expected Success after rotating past key1 and key2, got $result", result is RotationExecutionResult.Success)
      val success = result as RotationExecutionResult.Success
      assertEquals(3, success.usedKeyNumber)
      assertEquals("Reserve", success.usedKeyLabel)
      assertEquals(listOf(key1, key2, key3), attemptedKeys)

      // Verify DataStore persisted updated key statuses and active cursor index = 2 (Reserve)
      assertEquals(2, app.secureKeyStorage.getActiveKeyIndex())
      val storedKeys = app.secureKeyStorage.getKeysSnapshot()
      assertEquals(ApiKeyStatus.QUOTA_EXHAUSTED, storedKeys[0].status)
      assertEquals(ApiKeyStatus.INVALID, storedKeys[1].status)
      assertEquals(ApiKeyStatus.ACTIVE, storedKeys[2].status)

      // Subsequent session starts from persisted active cursor (index 2 -> Reserve) without retrying key2 (INVALID)
      val nextSession = rotationManager.prepareSession()
      assertEquals(2, nextSession.currentIndex)
      assertTrue(1 in nextSession.invalidIndices)
    }

  @Test
  fun `notification foreground info uses monochrome clover app icon drawable`() = runBlocking {
    val drawable = ContextCompat.getDrawable(app, R.drawable.ic_notification_app_icon)
    assertTrue("Expected ic_notification_app_icon drawable to load", drawable != null)

    val worker = TestListenableWorkerBuilder<SubtitleTranslationWorker>(app).build()
    val foregroundInfo = worker.getForegroundInfo()
    assertEquals(
      R.drawable.ic_notification_app_icon,
      foregroundInfo.notification.smallIcon.resId,
    )
    assertEquals(0xFF006D5B.toInt(), foregroundInfo.notification.color)
  }
}
