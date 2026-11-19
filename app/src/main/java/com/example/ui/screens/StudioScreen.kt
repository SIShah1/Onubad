package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.model.ApiKeyEntry
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.InputMode
import com.example.domain.model.LogLevel
import com.example.domain.model.ProcessingLogEntry
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.TranslationSessionState
import com.example.domain.model.TranslationStatus
import com.example.srt.SrtEngine
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.StudioFormState
import java.util.Locale
import kotlin.math.ceil

@Composable
fun StudioScreen(
  formState: StudioFormState,
  settings: AppSettings,
  apiKeys: List<ApiKeyEntry>,
  sessionState: TranslationSessionState,
  onInputModeChange: (InputMode) -> Unit,
  onMovieNameChange: (String) -> Unit,
  onReleaseYearChange: (String) -> Unit,
  onSeriesNameChange: (String) -> Unit,
  onSeasonChange: (String) -> Unit,
  onSingleSrtPicked: (android.net.Uri?) -> Unit,
  onMultipleSrtPicked: (List<android.net.Uri>) -> Unit,
  onRemoveFile: (String) -> Unit,
  onMoveSeriesFile: (Int, Int) -> Unit,
  onClearFiles: () -> Unit,
  onStartTranslation: () -> Unit,
  onAbortTranslation: () -> Unit,
  onClearLogs: () -> Unit,
  onNavigateToKeys: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  val singleSrtLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      onSingleSrtPicked(uri)
    }

  val multiSrtLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      onMultipleSrtPicked(uris)
    }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      onStartTranslation()
    }

  val isRunning = sessionState.isRunningOrCooldown
  val activeKeysCount = apiKeys.count { it.status == ApiKeyStatus.ACTIVE }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Studio Atmospheric Banner Header
    StudioHeroBanner(
      modelName = settings.effectiveModel,
      chunkSize = settings.effectiveChunkSize,
      totalKeysCount = apiKeys.size,
      activeKeysCount = activeKeysCount,
      onNavigateToKeys = onNavigateToKeys,
    )

    // 2. Mode Switcher (Movie / Series) + Metadata + File Picker
    StudioSectionCard {
      SectionTitleRow(
        title = "Subtitle Source",
        subtitle = "English .srt to Bengali studio",
        icon = Icons.Rounded.Subtitles,
      )

      Spacer(modifier = Modifier.height(14.dp))

      SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
          selected = formState.inputMode == InputMode.MOVIE,
          onClick = { if (!isRunning) onInputModeChange(InputMode.MOVIE) },
          enabled = !isRunning,
          shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
          icon = {
            Icon(
              imageVector = Icons.Rounded.Movie,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          modifier = Modifier.testTag("movie_mode_tab"),
        ) {
          Text("Movie")
        }

        SegmentedButton(
          selected = formState.inputMode == InputMode.SERIES,
          onClick = { if (!isRunning) onInputModeChange(InputMode.SERIES) },
          enabled = !isRunning,
          shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
          icon = {
            Icon(
              imageVector = Icons.Rounded.Tv,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          modifier = Modifier.testTag("series_mode_tab"),
        ) {
          Text("Series")
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      if (formState.inputMode == InputMode.MOVIE) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = formState.movieName,
            onValueChange = onMovieNameChange,
            label = { Text("Movie Name") },
            placeholder = { Text("e.g., Interstellar") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(2f).testTag("movie_name_input"),
          )
          OutlinedTextField(
            value = formState.releaseYear,
            onValueChange = onReleaseYearChange,
            label = { Text("Year") },
            placeholder = { Text("2014") },
            singleLine = true,
            enabled = !isRunning,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("release_year_input"),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
          onClick = { singleSrtLauncher.launch(arrayOf("*/*")) },
          enabled = !isRunning && !formState.isAnalyzingFiles,
          modifier =
            Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("pick_single_srt_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            if (formState.movieFiles.isEmpty()) "Select .srt Subtitle File"
            else "Change .srt Subtitle File"
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          OutlinedTextField(
            value = formState.seriesName,
            onValueChange = onSeriesNameChange,
            label = { Text("Series Name") },
            placeholder = { Text("e.g., Andor") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(2f).testTag("series_name_input"),
          )
          OutlinedTextField(
            value = formState.season,
            onValueChange = onSeasonChange,
            label = { Text("Season") },
            placeholder = { Text("S02") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.weight(1f).testTag("series_season_input"),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
          onClick = { multiSrtLauncher.launch(arrayOf("*/*")) },
          enabled = !isRunning && !formState.isAnalyzingFiles,
          modifier =
            Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("pick_multi_srt_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            if (formState.seriesFiles.isEmpty()) "Select Multiple .srt Files"
            else "Add More .srt Files (${formState.seriesFiles.size} queued)"
          )
        }
      }

      if (formState.isAnalyzingFiles) {
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Analyzing subtitle blocks and lines...",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // 3. Analyzed Subtitle Counter & File Queue
    AnimatedVisibility(visible = formState.activeFiles.isNotEmpty()) {
      SubtitleAnalysisCard(
        formState = formState,
        chunkSize = settings.effectiveChunkSize,
        isRunning = isRunning,
        onRemoveFile = onRemoveFile,
        onMoveSeriesFile = onMoveSeriesFile,
        onClearFiles = onClearFiles,
      )
    }

    // 4. API Key Warning Banner if empty
    if (apiKeys.isEmpty()) {
      Surface(
        modifier =
          Modifier.fillMaxWidth().clickable(onClick = onNavigateToKeys).testTag("missing_keys_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
      ) {
        Row(
          modifier = Modifier.padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "No Gemini API Keys Saved",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              text = "Tap to open API Key Manager and paste your keys.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
          }
        }
      }
    }

    // 5. Primary Start / Abort Action Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!isRunning) {
        Button(
          onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              val granted =
                ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
              if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
              } else {
                onStartTranslation()
              }
            } else {
              onStartTranslation()
            }
          },
          enabled = formState.activeFiles.isNotEmpty() && !formState.isAnalyzingFiles,
          modifier =
            Modifier.weight(1f)
              .height(54.dp)
              .testTag("start_translation_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Start Translation", style = MaterialTheme.typography.titleMedium)
        }
      } else {
        Button(
          onClick = onAbortTranslation,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
          modifier =
            Modifier.weight(1f)
              .height(54.dp)
              .testTag("abort_translation_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = "Abort Translation",
            modifier = Modifier.size(22.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Abort Translation", style = MaterialTheme.typography.titleMedium)
        }
      }
    }

    // 6. Live Processing Status & Persistent Logs
    LiveProcessingSection(
      sessionState = sessionState,
      onClearLogs = onClearLogs,
    )

    Spacer(modifier = Modifier.height(12.dp))
  }
}

@Composable
private fun StudioHeroBanner(
  modelName: String,
  chunkSize: Int,
  totalKeysCount: Int,
  activeKeysCount: Int,
  onNavigateToKeys: () -> Unit,
) {
  Box(
    modifier =
      Modifier.fillMaxWidth()
        .height(132.dp)
        .clip(MaterialTheme.shapes.large),
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_studio_banner_1795096561719),
      contentDescription = "BSub Translator Studio Banner",
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.horizontalGradient(
              colors =
                listOf(
                  Color(0xEE0A0F1A),
                  Color(0xCC0D1321),
                  Color(0x990D1321),
                )
            )
          ),
    )

    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "BSub Translator",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
          )
          Text(
            text = "Automated English -> Bengali SRT Studio",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
          )
        }

        Surface(
          onClick = onNavigateToKeys,
          shape = RoundedCornerShape(50),
          color = Color(0xFF1E293B).copy(alpha = 0.88f),
          border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f)),
          modifier = Modifier.testTag("hero_keys_chip"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Key,
              contentDescription = "API Keys",
              tint = Color(0xFFFBBF24),
              modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text =
                if (totalKeysCount == 0) "0 Keys"
                else "$activeKeysCount/$totalKeysCount Keys",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
            )
          }
        }
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        StatusPill(
          text = modelName,
          containerColor = Color(0xFF22D3EE).copy(alpha = 0.18f),
          contentColor = Color(0xFF67E8F9),
        )
        StatusPill(
          text = "$chunkSize blocks/batch",
          containerColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
          contentColor = Color(0xFFFDE68A),
        )
      }
    }
  }
}

@Composable
private fun SubtitleAnalysisCard(
  formState: StudioFormState,
  chunkSize: Int,
  isRunning: Boolean,
  onRemoveFile: (String) -> Unit,
  onMoveSeriesFile: (Int, Int) -> Unit,
  onClearFiles: () -> Unit,
) {
  val files = formState.activeFiles
  val totalBatches =
    files.sumOf { file ->
      if (file.totalBlocks == 0) 0 else ceil(file.totalBlocks.toDouble() / chunkSize).toInt()
    }

  StudioSectionCard {
    SectionTitleRow(
      title = "Subtitle Analysis",
      subtitle =
        if (files.size == 1) "Ready for batch translation"
        else "${files.size} files queued sequentially",
      icon = Icons.Rounded.Description,
      trailing = {
        if (!isRunning) {
          TextButton(onClick = onClearFiles, modifier = Modifier.testTag("clear_files_button")) {
            Text("Clear")
          }
        }
      },
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Aggregate Counters
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MetricTile(
        label = "Blocks",
        value = formState.totalBlocks.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Text Lines",
        value = formState.totalLines.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Batches",
        value = totalBatches.toString(),
        modifier = Modifier.weight(1f),
      )
      MetricTile(
        label = "Size",
        value = SrtEngine.formatFileSize(formState.totalBytes),
        modifier = Modifier.weight(1f),
      )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(10.dp))

    files.forEachIndexed { idx, file ->
      FileQueueRow(
        index = idx,
        totalCount = files.size,
        file = file,
        chunkSize = chunkSize,
        isSeriesMode = formState.inputMode == InputMode.SERIES,
        isRunning = isRunning,
        onMoveUp = { onMoveSeriesFile(idx, -1) },
        onMoveDown = { onMoveSeriesFile(idx, 1) },
        onRemove = { onRemoveFile(file.id) },
      )
      if (idx < files.lastIndex) {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

@Composable
private fun MetricTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun FileQueueRow(
  index: Int,
  totalCount: Int,
  file: SelectedSubtitleFile,
  chunkSize: Int,
  isSeriesMode: Boolean,
  isRunning: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onRemove: () -> Unit,
) {
  val batches = if (file.totalBlocks == 0) 0 else ceil(file.totalBlocks.toDouble() / chunkSize).toInt()
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isSeriesMode) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
        ) {
          Text(
            text = "#${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Spacer(modifier = Modifier.width(10.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = file.fileName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            "${file.totalBlocks} blocks • ${file.totalLines} lines • $batches batch(es) • ${SrtEngine.formatFileSize(file.fileSizeBytes)}",
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      if (!isRunning) {
        if (isSeriesMode && totalCount > 1) {
          IconButton(
            onClick = onMoveUp,
            enabled = index > 0,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Rounded.ArrowUpward,
              contentDescription = "Move file up",
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = onMoveDown,
            enabled = index < totalCount - 1,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Rounded.ArrowDownward,
              contentDescription = "Move file down",
              modifier = Modifier.size(18.dp),
            )
          }
        }
        IconButton(
          onClick = onRemove,
          modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Remove file",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun LiveProcessingSection(
  sessionState: TranslationSessionState,
  onClearLogs: () -> Unit,
) {
  val animatedProgress by
    animateFloatAsState(
      targetValue = sessionState.overallProgressFraction,
      label = "translation_progress",
    )
  val percentInt = (animatedProgress * 100f).toInt().coerceIn(0, 100)
  val logListState = rememberLazyListState()

  LaunchedEffect(sessionState.logs.size) {
    if (sessionState.logs.isNotEmpty()) {
      logListState.animateScrollToItem(sessionState.logs.lastIndex)
    }
  }

  StudioSectionCard(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    borderColor =
      when (sessionState.status) {
        TranslationStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        TranslationStatus.FAILED,
        TranslationStatus.ABORTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        TranslationStatus.IDLE -> MaterialTheme.colorScheme.outlineVariant
      },
  ) {
    SectionTitleRow(
      title = "Processing Status & Logs",
      subtitle = sessionState.activePhaseMessage,
      icon = Icons.Rounded.Terminal,
      trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          StatusPill(
            text = sessionState.status.name,
            containerColor =
              when (sessionState.status) {
                TranslationStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.secondaryContainer
                TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                TranslationStatus.ABORTED,
                TranslationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                TranslationStatus.IDLE -> MaterialTheme.colorScheme.surfaceContainerHigh
              },
            contentColor =
              when (sessionState.status) {
                TranslationStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
                TranslationStatus.COOLDOWN -> MaterialTheme.colorScheme.onSecondaryContainer
                TranslationStatus.COMPLETED -> MaterialTheme.colorScheme.onTertiaryContainer
                TranslationStatus.ABORTED,
                TranslationStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                TranslationStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
          if (sessionState.logs.isNotEmpty()) {
            IconButton(
              onClick = onClearLogs,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_logs_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = "Clear processing logs",
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      },
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Progress Bar + Percentage
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text =
          if (sessionState.totalFiles > 0) {
            "Files: ${sessionState.completedFiles}/${sessionState.totalFiles} completed"
          } else {
            "No active job"
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = "$percentInt%",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )
    }

    Spacer(modifier = Modifier.height(6.dp))
    LinearProgressIndicator(
      progress = { animatedProgress },
      modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
    )

    if (sessionState.totalFiles > 0) {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        MetricTile(
          label = "File",
          value = "${sessionState.currentFileIndex}/${sessionState.totalFiles}",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = "Batch",
          value = "${sessionState.currentBatchNumber}/${sessionState.totalBatchesInCurrentFile.coerceAtLeast(1)}",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = "API Key",
          value = if (sessionState.currentKeyNumber > 0) "#${sessionState.currentKeyNumber}" else "-",
          modifier = Modifier.weight(1f),
        )
        MetricTile(
          label = if (sessionState.status == TranslationStatus.COOLDOWN) "Cooldown" else "Attempt",
          value =
            if (sessionState.status == TranslationStatus.COOLDOWN) {
              val m = sessionState.cooldownRemainingSeconds / 60
              val s = sessionState.cooldownRemainingSeconds % 60
              String.format(Locale.US, "%02d:%02d", m, s)
            } else {
              "${sessionState.currentAttempt}/${sessionState.maxAttempts}"
            },
          modifier = Modifier.weight(1f),
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Persistent Console Log Window
    Surface(
      modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      if (sessionState.logs.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Processing logs will appear here during translation.",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyColumn(
          state = logListState,
          modifier = Modifier.fillMaxWidth().padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(items = sessionState.logs, key = { it.id }) { log ->
            LogLineRow(log = log)
          }
        }
      }
    }
  }
}

@Composable
private fun LogLineRow(log: ProcessingLogEntry) {
  val accentColor =
    when (log.level) {
      LogLevel.SUCCESS -> MaterialTheme.colorScheme.tertiary
      LogLevel.WARNING -> MaterialTheme.colorScheme.primary
      LogLevel.ERROR -> MaterialTheme.colorScheme.error
      LogLevel.COOLDOWN -> MaterialTheme.colorScheme.secondary
      LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "[${log.timestampLabel}]",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = log.message,
      style = MaterialTheme.typography.labelSmall,
      color = accentColor,
      modifier = Modifier.weight(1f),
    )
  }
}
