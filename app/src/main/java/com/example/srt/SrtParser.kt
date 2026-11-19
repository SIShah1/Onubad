package com.example.srt

import com.example.domain.model.SrtBlock
import java.io.InputStream

data class SrtItem(
  val index: Int,
  val timestamp: String,
  val content: String,
) {
  val lines: List<String>
    get() = if (content.isEmpty()) emptyList() else content.split('\n')

  fun toSrtBlock(): SrtBlock =
    SrtBlock(
      index = index,
      timestamp = timestamp,
      lines = lines,
    )
}

object SrtParser {

  private val BLANK_LINE_BLOCK_REGEX = Regex("""\n\s*\n+""")

  fun parse(inputStream: InputStream): List<SrtItem> {
    val rawText = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    return parse(rawText)
  }

  fun parse(rawContent: String): List<SrtItem> {
    val normalized =
      rawContent
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()

    if (normalized.isEmpty()) return emptyList()

    val rawChunks = normalized.split(BLANK_LINE_BLOCK_REGEX)
    val items = ArrayList<SrtItem>(rawChunks.size)

    for (chunk in rawChunks) {
      val lines = chunk.lines()
      if (lines.isEmpty()) continue

      val timestampLineIdx = lines.indexOfFirst { it.contains("-->") }
      if (timestampLineIdx <= 0) continue

      val indexCandidate = lines[timestampLineIdx - 1].trim().removePrefix("\uFEFF")
      val serial = indexCandidate.toIntOrNull() ?: continue
      val timestamp = lines[timestampLineIdx].trim()
      val content =
        if (timestampLineIdx + 1 < lines.size) {
          lines
            .subList(timestampLineIdx + 1, lines.size)
            .joinToString("\n") { it.trimEnd() }
        } else {
          ""
        }

      items.add(
        SrtItem(
          index = serial,
          timestamp = timestamp,
          content = content,
        )
      )
    }

    return items
  }

  fun parseBlocks(rawContent: String): List<SrtBlock> {
    return parse(rawContent).map { it.toSrtBlock() }
  }

  fun format(items: List<SrtItem>): String {
    if (items.isEmpty()) return ""
    return items.joinToString("\n\n") { item ->
      buildString {
        append(item.index).append('\n')
        append(item.timestamp)
        if (item.content.isNotEmpty()) {
          append('\n').append(item.content)
        }
      }
    } + "\n"
  }
}
