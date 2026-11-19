package com.example

import com.example.data.network.GeminiApiClient
import com.example.data.network.GeminiCallResult
import com.example.data.preferences.SecureKeyStorage
import com.example.domain.model.AppSettings
import com.example.domain.model.NamingMode
import com.example.srt.SrtEngine
import com.example.srt.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `srt parser and validator verify exact serials and timestamps`() {
    val originalSrt =
      """
      1
      00:00:01,000 --> 00:00:03,500
      Hello world.
      How are you?

      2
      00:00:04,000 --> 00:00:06,200
      Let's go home.
      """.trimIndent()

    val translatedSrt =
      """
      ```srt
      1
      00:00:01,000 --> 00:00:03,500
      হ্যালো বিশ্ব।
      কেমন আছো?

      2
      00:00:04,000 --> 00:00:06,200
      চলো বাড়ি যাই।
      ```
      """.trimIndent()

    val originalBlocks = SrtEngine.parse(originalSrt)
    val parsedTranslated = SrtEngine.parseModelOutput(translatedSrt)
    val result = SrtEngine.validateBatch(originalBlocks, parsedTranslated)

    assertTrue(result is ValidationResult.Valid)
    val validBlocks = (result as ValidationResult.Valid).blocks
    assertEquals(2, validBlocks.size)
    assertEquals("হ্যালো বিশ্ব।", validBlocks[0].lines[0])
    assertEquals("00:00:04,000 --> 00:00:06,200", validBlocks[1].timestamp)
  }

  @Test
  fun `srt validator rejects missing blocks or mismatched timestamps`() {
    val original =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        Line one

        2
        00:00:03,000 --> 00:00:04,000
        Line two
        """.trimIndent()
      )

    val missingOne =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        লাইন এক
        """.trimIndent()
      )
    assertTrue(SrtEngine.validateBatch(original, missingOne) is ValidationResult.Invalid)

    val badTimestamp =
      SrtEngine.parse(
        """
        1
        00:00:01,000 --> 00:00:02,000
        লাইন এক

        2
        00:00:03,000 --> 00:00:05,000
        লাইন দুই
        """.trimIndent()
      )
    assertTrue(SrtEngine.validateBatch(original, badTimestamp) is ValidationResult.Invalid)
  }

  @Test
  fun `output naming convention builds default and custom filenames`() {
    val defaultSettings = AppSettings(namingMode = NamingMode.DEFAULT)
    assertEquals(
      "Andor_S02E01_BSub.srt",
      SrtEngine.buildOutputFileName("Andor_S02E01.srt", defaultSettings),
    )

    val customSettings =
      AppSettings(
        namingMode = NamingMode.CUSTOM,
        customPrefix = "Bengali_",
        customSuffix = "- BSub",
      )
    assertEquals(
      "Bengali_Andor_S02E01- BSub.srt",
      SrtEngine.buildOutputFileName("Andor_S02E01.srt", customSettings),
    )
  }

  @Test
  fun `bulk key parser parses 25 plus keys cleanly`() {
    val raw25Keys = (1..26).joinToString("\n") { "AIzaSyTestKeyNumber_${it.toString().padStart(2, '0')}_ABCDEF" }
    val parsed = SecureKeyStorage.parseBulkKeys(raw25Keys)
    assertEquals(26, parsed.size)
  }

  @Test
  fun `gemini client distinguishes daily quota exhaustion from server busy and invalid key`() {
    val client = GeminiApiClient()

    val quotaResult =
      client.classifyHttpFailure(
        429,
        """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for GenerateRequestsPerDay"}}""",
      )
    assertTrue(quotaResult is GeminiCallResult.QuotaExhausted)

    val busyResult =
      client.classifyHttpFailure(
        503,
        """{"error":{"status":"UNAVAILABLE","message":"The model is overloaded."}}""",
      )
    assertTrue(busyResult is GeminiCallResult.RetryableError)

    val invalidResult =
      client.classifyHttpFailure(
        400,
        """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}""",
      )
    assertTrue(invalidResult is GeminiCallResult.InvalidKey)
  }
}
