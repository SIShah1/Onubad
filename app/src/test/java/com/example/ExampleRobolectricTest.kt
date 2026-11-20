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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.example.data.local.BSubDatabase
import com.example.data.local.FileTranslationProgress
import com.example.data.local.HistoryRepository
import com.example.data.local.HistoryTranslationStatus
import com.example.data.local.RoomTranslationProgress
import com.example.data.local.SubtitleTranslation
import com.example.data.local.SubtitleTranslationRepository
import com.example.data.local.SubtitleTranslationStatus
import com.example.data.local.TranslationHistoryEntity
import com.example.ui.screens.HistoryScreen
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
import com.example.service.SubtitleTranslationService
import com.example.service.SubtitleTranslationServiceResult
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.BSubTranslatorTheme
import com.example.ui.theme.CloverCoralTint
import com.example.ui.theme.CloverEmerald
import com.example.ui.theme.CloverMintAqua
import com.example.ui.theme.CloverYellow
import com.example.ui.theme.CloverYellowTint
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
    var observedPrimaryColor = Color.Unspecified
    var observedSecondaryColor = Color.Unspecified
    var observedSecondaryContainer = Color.Unspecified
    var observedErrorContainer = Color.Unspecified
    var observedIsDark = true

    composeTestRule.setContent {
      var mode by remember { mutableStateOf(ThemeMode.DARK) }
      ThemeProvider(
        themeMode = mode,
        onThemeModeChange = { mode = it },
        animateTransitions = false,
      ) {
        observedBgColor = MaterialTheme.colorScheme.background
        observedPrimaryColor = MaterialTheme.colorScheme.primary
        observedSecondaryColor = MaterialTheme.colorScheme.secondary
        observedSecondaryContainer = MaterialTheme.colorScheme.secondaryContainer
        observedErrorContainer = MaterialTheme.colorScheme.errorContainer
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
    assertEquals(CloverMintAqua, observedPrimaryColor)
    assertEquals(CloverYellow, observedSecondaryColor)
    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").assertTextContains("Dark")

    composeTestRule.onNodeWithTag("hero_theme_toggle_chip").performClick()
    composeTestRule.waitForIdle()

    assertEquals(false, observedIsDark)
    assertEquals(LightCanvas, observedBgColor)
    assertEquals(CloverEmerald, observedPrimaryColor)
    assertEquals(CloverYellowTint, observedSecondaryContainer)
    assertEquals(CloverCoralTint, observedErrorContainer)
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

  @Test
  fun `subtitle translation service parses segments prepares gemini requests and rotates keys`() =
    runBlocking {
      app.secureKeyStorage.clearAllKeys()
      app.settingsRepository.setChunkSize(ParamMode.MANUAL, 2)
      app.settingsRepository.setMaxAttemptsPerKey(ParamMode.MANUAL, 1)
      app.settingsRepository.setRetryInterval(ParamMode.MANUAL, 1)

      val keyA = "AIzaSyServiceKeyA_1234567890"
      val keyB = "AIzaSyServiceKeyB_1234567890"
      app.secureKeyStorage.addSingleKey(keyA, "Primary")
      app.secureKeyStorage.addSingleKey(keyB, "Backup")
      app.secureKeyStorage.setActiveKeyIndex(0)

      val rawSrt =
        """
        1
        00:00:01,000 --> 00:00:02,500
        Good morning.

        2
        00:00:03,000 --> 00:00:04,500
        How is everything?

        3
        00:00:05,000 --> 00:00:06,500
        All is ready.
        """.trimIndent()

      val seenRequests = mutableListOf<Pair<String, String>>()
      val mockGeminiService =
        object : GeminiApiService {
          override suspend fun generateContent(
            model: String,
            apiKey: String,
            request: GeminiGenerateContentRequest,
          ): Response<GeminiGenerateContentResponse> {
            val promptText = request.contents.last().parts.first().text.orEmpty()
            seenRequests.add(apiKey to promptText)

            if (apiKey == keyA) {
              return Response.error(
                429,
                """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Rate limit hit"}}"""
                  .toResponseBody("application/json".toMediaType()),
              )
            }

            val responseSrt =
              if (promptText.contains("Good morning.")) {
                """
                1
                00:00:01,000 --> 00:00:02,500
                শুভ সকাল।

                2
                00:00:03,000 --> 00:00:04,500
                সবকিছু কেমন চলছে?
                """.trimIndent()
              } else {
                """
                3
                00:00:05,000 --> 00:00:06,500
                সবকিছু প্রস্তুত।
                """.trimIndent()
              }

            return Response.success(
              GeminiGenerateContentResponse(
                candidates =
                  listOf(
                    GeminiCandidate(
                      content = GeminiContent(parts = listOf(GeminiPart(text = responseSrt))),
                      finishReason = "STOP",
                    )
                  )
              )
            )
          }
        }

      val apiClient = GeminiApiClient(customService = mockGeminiService)
      val rotationManager =
        ApiKeyRotationManager(
          keyStorage = app.secureKeyStorage,
          geminiApiClient = apiClient,
        )
      val service =
        SubtitleTranslationService(
          settingsRepository = app.settingsRepository,
          fileStorage = app.subtitleFileStorage,
          geminiApiClient = apiClient,
          keyRotationManager = rotationManager,
        )

      val settingsSnapshot = app.settingsRepository.getSettingsSnapshot()
      val plan = service.parseAndSegment(rawSrt, chunkSize = 2, fileName = "demo.srt")
      assertEquals(3, plan.totalBlocks)
      assertEquals(2, plan.segments.size)
      assertEquals(2, plan.segments[0].blocks.size)
      assertEquals(1, plan.segments[1].blocks.size)

      val prepared =
        service.prepareTranslationRequest(
          segment = plan.segments[0],
          settings = settingsSnapshot,
          inputMode = InputMode.MOVIE,
          title = "Demo Movie",
          secondaryMeta = "2026",
          fileName = "demo.srt",
        )
      assertTrue(
        prepared.requestBody.contents.last().parts.first().text.orEmpty().contains("Good morning.")
      )

      val serviceResult =
        service.translateSrtContentWithRotation(
          rawSrtContent = rawSrt,
          fileName = "demo.srt",
          inputMode = InputMode.MOVIE,
          title = "Demo Movie",
          secondaryMeta = "2026",
          chunkSizeOverride = 2,
          onRetryDelay = { _, _ -> },
          onCooldownWait = { minutes ->
            throw AssertionError("Unexpected cooldown ($minutes min). Seen requests: $seenRequests")
          },
        )

      assertTrue(serviceResult is SubtitleTranslationServiceResult.Success)
      val success = serviceResult as SubtitleTranslationServiceResult.Success
      assertEquals(2, success.segmentsProcessed)
      assertEquals(3, success.translatedBlocks.size)
      assertTrue(success.reconstructedBengaliSrt.contains("00:00:01,000 --> 00:00:02,500\nশুভ সকাল।"))
      assertTrue(success.reconstructedBengaliSrt.contains("00:00:05,000 --> 00:00:06,500\nসবকিছু প্রস্তুত।"))
      assertEquals(keyA, seenRequests[0].first)
      assertEquals(keyB, seenRequests[1].first)
      assertEquals(keyB, seenRequests[2].first)
    }

  @Test
  fun `room history repository tracks processing completed failed records with filenames and timestamps`() =
    runBlocking {
      val repo = HistoryRepository(db.translationHistoryDao())

      val processingId =
        repo.createProcessingRecord(
          originalFileName = "Inception.2010.English.srt",
          expectedOutputFileName = "Inception.2010.English_BSub.srt",
          mode = InputMode.MOVIE.name,
          titleMetadata = "Inception (2010)",
          totalBlocks = 1200,
          totalLines = 3100,
          modelUsed = "gemini-2.5-flash",
          nowEpochMs = 1_700_000_000_000L,
        )

      val initial = repo.getById(processingId)!!
      assertEquals("Inception.2010.English.srt", initial.originalFileName)
      assertEquals(HistoryTranslationStatus.PROCESSING, initial.translationStatus)
      assertEquals(1_700_000_000_000L, initial.createdAtEpochMs)
      assertEquals(1_700_000_000_000L, initial.updatedAtEpochMs)

      repo.updateBatchProgress(
        historyId = processingId,
        translatedBlocks = 300,
        updatedAtEpochMs = 1_700_000_030_000L,
      )
      assertEquals(300, repo.getById(processingId)!!.translatedBlocks)

      repo.markCompleted(
        historyId = processingId,
        outputFileName = "Inception.2010.English_BSub.srt",
        savedPath = "Downloads/BSub Translator/Inception.2010.English_BSub.srt",
        contentUri = "content://downloads/1",
        localBackupPath = "/tmp/inception_bsub.srt",
        translatedBlocks = 1200,
        fileSizeBytes = 42_000L,
        updatedAtEpochMs = 1_700_000_060_000L,
      )

      val failedId =
        repo.createProcessingRecord(
          originalFileName = "Andor_S02E01.srt",
          expectedOutputFileName = "Andor_S02E01_BSub.srt",
          mode = InputMode.SERIES.name,
          titleMetadata = "Andor • S02",
          totalBlocks = 500,
          totalLines = 1200,
          modelUsed = "gemini-2.5-flash",
          nowEpochMs = 1_700_000_100_000L,
        )
      repo.markFailed(
        historyId = failedId,
        translatedBlocks = 0,
        errorMessage = "Quota exhausted on all keys",
        updatedAtEpochMs = 1_700_000_120_000L,
      )

      assertEquals(2, repo.totalCount.first())
      assertEquals(1, repo.observeCountByStatus(HistoryTranslationStatus.COMPLETED).first())
      assertEquals(1, repo.observeCountByStatus(HistoryTranslationStatus.FAILED).first())

      val completedList = repo.observeByStatus(HistoryTranslationStatus.COMPLETED).first()
      assertEquals(1, completedList.size)
      assertEquals("Inception.2010.English.srt", completedList[0].originalFileName)
      assertEquals(1_700_000_060_000L, completedList[0].updatedAtEpochMs)

      val searchMatches = repo.searchHistory("Andor").first()
      assertEquals(1, searchMatches.size)
      assertEquals(HistoryTranslationStatus.FAILED, searchMatches[0].translationStatus)
      assertEquals("Quota exhausted on all keys", searchMatches[0].errorMessage)

      repo.deleteRecord(failedId)
      assertEquals(1, repo.totalCount.first())
    }

  @Test
  fun `bsub database migration 2 to 3 preserves existing history and populates status and timestamp`() =
    runBlocking {
      val dbName = "migration_2_3_test.db"
      app.deleteDatabase(dbName)
      val sqlite = app.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
      sqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `translation_history` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `originalFileName` TEXT NOT NULL,
          `outputFileName` TEXT NOT NULL,
          `savedPath` TEXT NOT NULL,
          `contentUri` TEXT NOT NULL,
          `localBackupPath` TEXT NOT NULL,
          `mode` TEXT NOT NULL,
          `titleMetadata` TEXT NOT NULL,
          `totalBlocks` INTEGER NOT NULL,
          `translatedBlocks` INTEGER NOT NULL,
          `totalLines` INTEGER NOT NULL,
          `fileSizeBytes` INTEGER NOT NULL,
          `modelUsed` TEXT NOT NULL,
          `status` TEXT NOT NULL,
          `createdAtEpochMs` INTEGER NOT NULL
        )
        """.trimIndent()
      )
      sqlite.execSQL(
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
      sqlite.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_subtitle_translations_jobId_blockIndex` ON `subtitle_translations` (`jobId`, `blockIndex`)"
      )
      sqlite.execSQL(
        """
        INSERT INTO `translation_history` VALUES
        (1, 'movie.srt', 'movie_BSub.srt', 'Downloads/movie_BSub.srt', '', '/tmp/a.srt', 'MOVIE', 'Movie', 100, 100, 250, 4096, 'gemini-2.5-flash', 'Completed', 1700000000000),
        (2, 'ep1.srt', 'ep1_partial.srt', 'Downloads/ep1_partial.srt', '', '/tmp/b.srt', 'SERIES', 'Series', 200, 80, 500, 2048, 'gemini-2.5-flash', 'Partial (80/200)', 1700000050000)
        """.trimIndent()
      )
      sqlite.version = 2
      sqlite.close()

      val migratedDb =
        Room.databaseBuilder(app, BSubDatabase::class.java, dbName)
          .addMigrations(BSubDatabase.MIGRATION_1_2, BSubDatabase.MIGRATION_2_3)
          .allowMainThreadQueries()
          .build()

      try {
        val items = migratedDb.translationHistoryDao().getAllHistory().first()
        assertEquals(2, items.size)
        val partial = items.first { it.id == 2L }
        val completed = items.first { it.id == 1L }
        assertEquals(HistoryTranslationStatus.PARTIAL, partial.translationStatus)
        assertEquals(1_700_000_050_000L, partial.updatedAtEpochMs)
        assertEquals(HistoryTranslationStatus.COMPLETED, completed.translationStatus)
        assertEquals(1_700_000_000_000L, completed.updatedAtEpochMs)
      } finally {
        migratedDb.close()
        app.deleteDatabase(dbName)
      }
    }

  @Test
  fun `history screen displays original filenames timestamps and filters by status`() {
    val sampleItems =
      listOf(
        TranslationHistoryEntity(
          id = 1L,
          originalFileName = "Dune.Part.One.srt",
          outputFileName = "Dune.Part.One_BSub.srt",
          savedPath = "Downloads/BSub Translator/Dune.Part.One_BSub.srt",
          localBackupPath = "/tmp/dune.srt",
          mode = "MOVIE",
          titleMetadata = "Dune (2021)",
          totalBlocks = 100,
          translatedBlocks = 100,
          totalLines = 220,
          fileSizeBytes = 5000L,
          modelUsed = "gemini-2.5-flash",
          status = "Completed",
          translationStatus = HistoryTranslationStatus.COMPLETED,
          createdAtEpochMs = 1_700_000_000_000L,
          updatedAtEpochMs = 1_700_000_050_000L,
        ),
        TranslationHistoryEntity(
          id = 2L,
          originalFileName = "Broken_File.srt",
          outputFileName = "",
          mode = "MOVIE",
          titleMetadata = "Broken Movie",
          totalBlocks = 50,
          translatedBlocks = 0,
          totalLines = 100,
          fileSizeBytes = 0L,
          modelUsed = "gemini-2.5-flash",
          status = "Failed",
          translationStatus = HistoryTranslationStatus.FAILED,
          errorMessage = "Network timeout",
          createdAtEpochMs = 1_700_000_100_000L,
          updatedAtEpochMs = 1_700_000_110_000L,
        ),
      )

    composeTestRule.setContent {
      BSubTranslatorTheme {
        HistoryScreen(
          historyItems = sampleItems,
          previewState = null,
          onOpenPreview = {},
          onClosePreview = {},
          onReSaveToDownloads = {},
          onExportToUri = { _, _ -> },
          onCreateShareIntent = { null },
          onDeleteItem = {},
        )
      }
    }

    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertIsDisplayed()

    composeTestRule.onNodeWithTag("history_filter_failed").performClick()
    composeTestRule.onNodeWithText("Source: Broken_File.srt").assertIsDisplayed()
    composeTestRule.onNodeWithText("Network timeout").assertIsDisplayed()
    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertDoesNotExist()

    composeTestRule.onNodeWithTag("history_filter_completed").performClick()
    composeTestRule.onNodeWithText("Source: Dune.Part.One.srt").assertIsDisplayed()
    composeTestRule.onNodeWithText("Source: Broken_File.srt").assertDoesNotExist()
  }
}
