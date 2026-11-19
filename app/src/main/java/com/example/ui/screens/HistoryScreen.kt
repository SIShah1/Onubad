package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.TranslationHistoryEntity
import com.example.srt.SrtEngine
import com.example.ui.components.SectionTitleRow
import com.example.ui.components.StatusPill
import com.example.ui.components.StudioSectionCard
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.HistoryPreviewState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
  historyItems: List<TranslationHistoryEntity>,
  previewState: HistoryPreviewState?,
  onOpenPreview: (TranslationHistoryEntity) -> Unit,
  onClosePreview: () -> Unit,
  onReSaveToDownloads: (TranslationHistoryEntity) -> Unit,
  onExportToUri: (TranslationHistoryEntity, android.net.Uri?) -> Unit,
  onCreateShareIntent: (TranslationHistoryEntity) -> Intent?,
  onDeleteItem: (TranslationHistoryEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var pendingExportItem by remember { mutableStateOf<TranslationHistoryEntity?>(null) }
  var pendingDeleteItem by remember { mutableStateOf<TranslationHistoryEntity?>(null) }

  val exportDocumentLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
      val item = pendingExportItem
      pendingExportItem = null
      if (item != null) {
        onExportToUri(item, uri)
      }
    }

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
  ) {
    StudioSectionCard {
      SectionTitleRow(
        title = "Translated Files History",
        subtitle = "Saved to Downloads/BSub Translator/",
        icon = Icons.Rounded.History,
        trailing = {
          StatusPill(
            text = "${historyItems.size} file(s)",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
          )
        },
      )
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (historyItems.isEmpty()) {
      EmptyHistoryState(modifier = Modifier.fillMaxSize())
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(items = historyItems, key = { it.id }) { item ->
          HistoryItemCard(
            item = item,
            onPreview = { onOpenPreview(item) },
            onReDownload = { onReSaveToDownloads(item) },
            onExportAs = {
              pendingExportItem = item
              exportDocumentLauncher.launch(item.outputFileName)
            },
            onShare = {
              val intent = onCreateShareIntent(item)
              if (intent != null) {
                context.startActivity(Intent.createChooser(intent, "Share Translated Subtitle"))
              }
            },
            onDelete = { pendingDeleteItem = item },
          )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
      }
    }
  }

  previewState?.let { state ->
    AlertDialog(
      onDismissRequest = onClosePreview,
      title = {
        Text(
          text = state.item.outputFileName,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      text = {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().height(340.dp),
        ) {
          Text(
            text = state.contentPreview,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
          )
        }
      },
      confirmButton = {
        TextButton(onClick = onClosePreview) {
          Text("Close")
        }
      },
    )
  }

  pendingDeleteItem?.let { item ->
    AlertDialog(
      onDismissRequest = { pendingDeleteItem = null },
      title = { Text("Delete Saved File?") },
      text = {
        Text("Remove ${item.outputFileName} from translation history?")
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteItem(item)
            pendingDeleteItem = null
          }
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteItem = null }) {
          Text("Cancel")
        }
      },
    )
  }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_empty_archive_1795096561835),
      contentDescription = "No translated subtitles yet",
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(width = 240.dp, height = 180.dp).clip(MaterialTheme.shapes.large),
    )
    Spacer(modifier = Modifier.height(18.dp))
    Text(
      text = "No Translated Files Yet",
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
      text =
        "Completed movie and series translations are automatically saved to Downloads/BSub Translator/ and indexed here for preview, export, and sharing.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun HistoryItemCard(
  item: TranslationHistoryEntity,
  onPreview: () -> Unit,
  onReDownload: () -> Unit,
  onExportAs: () -> Unit,
  onShare: () -> Unit,
  onDelete: () -> Unit,
) {
  val dateText =
    remember(item.createdAtEpochMs) {
      SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date(item.createdAtEpochMs))
    }
  val isCompleted = item.status.equals("Completed", ignoreCase = true)

  StudioSectionCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Top,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.outputFileName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${item.titleMetadata} • ${item.mode}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      StatusPill(
        text = item.status,
        containerColor =
          if (isCompleted) MaterialTheme.colorScheme.tertiaryContainer
          else MaterialTheme.colorScheme.primaryContainer,
        contentColor =
          if (isCompleted) MaterialTheme.colorScheme.onTertiaryContainer
          else MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text =
        "${item.translatedBlocks}/${item.totalBlocks} blocks • ${item.totalLines} lines • ${SrtEngine.formatFileSize(item.fileSizeBytes)}",
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = "Path: ${item.savedPath}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = "${item.modelUsed} • $dateText",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(
        onClick = onPreview,
        modifier = Modifier.weight(1f).testTag("history_preview_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Preview,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("View", maxLines = 1)
      }

      OutlinedButton(
        onClick = onReDownload,
        modifier = Modifier.weight(1f).testTag("history_redownload_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Download,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Save", maxLines = 1)
      }

      IconButton(
        onClick = onExportAs,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("history_export_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.FileDownload,
          contentDescription = "Export file to custom folder",
        )
      }

      IconButton(
        onClick = onShare,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("history_share_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Share,
          contentDescription = "Share translated subtitle",
        )
      }

      IconButton(
        onClick = onDelete,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("history_delete_${item.id}"),
      ) {
        Icon(
          imageVector = Icons.Rounded.Delete,
          contentDescription = "Delete saved file",
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}
