package com.example.srt

import com.example.domain.model.AppSettings
import com.example.domain.model.NamingMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock

sealed class ValidationResult {
  data class Valid(val blocks: List<SrtBlock>) : ValidationResult()

  data class Invalid(val reason: String) : ValidationResult()
}

object SrtEngine {

  private val TIMESTAMP_ARROW_REGEX = Regex("""\s*-->\s*""")
  private val CODE_FENCE_OPEN_REGEX = Regex("""^```[a-zA-Z0-9_-]*\s*""")
  private val CODE_FENCE_CLOSE_REGEX = Regex("""\s*```$""")

  fun analyzeFile(
    fileName: String,
    fileSizeBytes: Long,
    uriString: String,
    rawContent: String,
  ): SelectedSubtitleFile {
    val blocks = parse(rawContent)
    val totalLines = blocks.sumOf { block -> block.lines.count { it.isNotBlank() } }
    return SelectedSubtitleFile(
      uriString = uriString,
      fileName = fileName,
      fileSizeBytes = fileSizeBytes,
      totalBlocks = blocks.size,
      totalLines = totalLines,
      blocks = blocks,
    )
  }

  fun parse(rawContent: String): List<SrtBlock> {
    val normalized =
      rawContent
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()

    if (normalized.isEmpty()) return emptyList()

    val rawChunks = normalized.split(Regex("""\n\s*\n+"""))
    val blocks = ArrayList<SrtBlock>(rawChunks.size)

    for (chunk in rawChunks) {
      val lines = chunk.lines()
      if (lines.isEmpty()) continue

      // Find the timestamp line ("-->") to be resilient against blank/extra header lines
      val timestampLineIdx = lines.indexOfFirst { it.contains("-->") }
      if (timestampLineIdx <= 0) continue

      val indexCandidate = lines[timestampLineIdx - 1].trim().removePrefix("\uFEFF")
      val serial = indexCandidate.toIntOrNull() ?: continue
      val timestamp = lines[timestampLineIdx].trim()
      val textLines =
        if (timestampLineIdx + 1 < lines.size) {
          lines.subList(timestampLineIdx + 1, lines.size).map { it.trimEnd() }
        } else {
          emptyList()
        }

      blocks.add(
        SrtBlock(
          index = serial,
          timestamp = timestamp,
          lines = textLines,
        )
      )
    }

    return blocks
  }

  fun parseModelOutput(rawResponse: String): List<SrtBlock> {
    var cleaned = rawResponse.trim().removePrefix("\uFEFF")
    // Strip markdown code fences if the model wrapped the SRT response
    if (cleaned.startsWith("```")) {
      cleaned = cleaned.replaceFirst(CODE_FENCE_OPEN_REGEX, "").trim()
    }
    if (cleaned.endsWith("```")) {
      cleaned = cleaned.replaceFirst(CODE_FENCE_CLOSE_REGEX, "").trim()
    }
    return parse(cleaned)
  }

  fun validateBatch(
    originalBatch: List<SrtBlock>,
    translatedBlocks: List<SrtBlock>,
  ): ValidationResult {
    if (translatedBlocks.isEmpty()) {
      return ValidationResult.Invalid("Empty or unparseable SRT output from AI")
    }

    if (translatedBlocks.size != originalBatch.size) {
      return ValidationResult.Invalid(
        "Block count mismatch: expected ${originalBatch.size}, received ${translatedBlocks.size}"
      )
    }

    val seenIndices = HashSet<Int>(translatedBlocks.size)
    val sanitizedBlocks = ArrayList<SrtBlock>(originalBatch.size)

    for (i in originalBatch.indices) {
      val original = originalBatch[i]
      val translated = translatedBlocks[i]

      if (!seenIndices.add(translated.index)) {
        return ValidationResult.Invalid("Duplicate serial number #${translated.index} detected")
      }

      if (translated.index != original.index) {
        return ValidationResult.Invalid(
          "Serial mismatch at position ${i + 1}: expected #${original.index}, got #${translated.index}"
        )
      }

      if (normalizeTimestamp(translated.timestamp) != normalizeTimestamp(original.timestamp)) {
        return ValidationResult.Invalid(
          "Timestamp mismatch at block #${original.index}"
        )
      }

      val hasOriginalText = original.lines.any { it.isNotBlank() }
      val hasTranslatedText = translated.lines.any { it.isNotBlank() }
      if (hasOriginalText && !hasTranslatedText) {
        return ValidationResult.Invalid(
          "Missing translated text at block #${original.index}"
        )
      }

      // Preserve the exact original timestamp string verbatim to guarantee zero drift
      sanitizedBlocks.add(
        SrtBlock(
          index = original.index,
          timestamp = original.timestamp,
          lines = translated.lines,
        )
      )
    }

    return ValidationResult.Valid(sanitizedBlocks)
  }

  fun formatBlocks(blocks: List<SrtBlock>): String {
    if (blocks.isEmpty()) return ""
    val sb = StringBuilder(blocks.size * 64)
    blocks.forEachIndexed { idx, block ->
      sb.append(block.index).append('\n')
      sb.append(block.timestamp).append('\n')
      if (block.lines.isEmpty()) {
        sb.append('\n')
      } else {
        block.lines.forEach { line ->
          sb.append(line).append('\n')
        }
      }
      if (idx < blocks.lastIndex) {
        sb.append('\n')
      }
    }
    return sb.toString()
  }

  fun buildOutputFileName(originalFileName: String, settings: AppSettings): String {
    val cleanName = originalFileName.trim().ifBlank { "subtitle.srt" }
    val baseName =
      if (cleanName.endsWith(".srt", ignoreCase = true)) {
        cleanName.dropLast(4)
      } else {
        cleanName
      }

    val rawResult =
      when (settings.namingMode) {
        NamingMode.DEFAULT -> "${baseName}_BSub.srt"
        NamingMode.CUSTOM -> {
          val prefix = sanitizeFileSegment(settings.customPrefix)
          val suffix = sanitizeFileSegment(settings.customSuffix)
          val combined = "${prefix}${baseName}${suffix}"
          if (combined.isBlank()) "${baseName}_BSub.srt" else "$combined.srt"
        }
      }

    return sanitizeFileSegment(rawResult).ifBlank { "${baseName}_BSub.srt" }
  }

  private fun normalizeTimestamp(timestamp: String): String {
    return timestamp
      .trim()
      .replace(TIMESTAMP_ARROW_REGEX, " --> ")
      .replace('.', ',')
  }

  private fun sanitizeFileSegment(input: String): String {
    return input.replace(Regex("""[\\/:*?"<>|]"""), "_")
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1.0) return "$bytes B"
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
      String.format(java.util.Locale.US, "%.2f MB", mb)
    } else {
      String.format(java.util.Locale.US, "%.1f KB", kb)
    }
  }
}
