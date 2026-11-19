package com.example.data.network

import com.example.domain.model.FewShotExample
import com.example.domain.model.InputMode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

sealed class GeminiCallResult {
  data class Success(val translatedText: String) : GeminiCallResult()

  data class RetryableError(val reason: String, val httpCode: Int? = null) : GeminiCallResult()

  data class QuotaExhausted(val reason: String) : GeminiCallResult()

  data class InvalidKey(val reason: String) : GeminiCallResult()
}

class GeminiApiClient {

  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(240, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .callTimeout(260, TimeUnit.SECONDS)
      .build()

  suspend fun translateBatch(
    apiKey: String,
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
  ): GeminiCallResult =
    withContext(Dispatchers.IO) {
      val cleanModel = model.trim().removePrefix("models/")
      val url =
        "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

      val requestJson =
        buildRequestBody(
          systemPrompt = systemPrompt,
          fewShotExamples = fewShotExamples,
          inputMode = inputMode,
          title = title,
          secondaryMeta = secondaryMeta,
          batchSrtPayload = batchSrtPayload,
          batchIndex = batchIndex,
          totalBatches = totalBatches,
          fileName = fileName,
          temperature = temperature,
          thinkingBudget = thinkingBudget,
        )

      val request =
        Request.Builder()
          .url(url)
          .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
          .header("Content-Type", "application/json")
          .build()

      try {
        val response = client.newCall(request).awaitCancellable()
        response.use { resp ->
          val bodyString = resp.body?.string().orEmpty()
          if (resp.isSuccessful) {
            val extracted = extractCandidateText(bodyString)
            if (extracted.isNullOrBlank()) {
              GeminiCallResult.RetryableError(
                reason = "Empty response returned by Gemini API",
                httpCode = resp.code,
              )
            } else {
              GeminiCallResult.Success(extracted)
            }
          } else {
            classifyHttpFailure(resp.code, bodyString)
          }
        }
      } catch (e: SocketTimeoutException) {
        GeminiCallResult.RetryableError("Network timeout: ${e.message ?: "Request timed out"}")
      } catch (e: UnknownHostException) {
        GeminiCallResult.RetryableError("Network error: Unable to reach Gemini API")
      } catch (e: IOException) {
        GeminiCallResult.RetryableError("Network error: ${e.message ?: "Connection failed"}")
      } catch (e: Exception) {
        GeminiCallResult.RetryableError("Unexpected error: ${e.message ?: "Unknown failure"}")
      }
    }

  private fun buildRequestBody(
    systemPrompt: String,
    fewShotExamples: List<FewShotExample>,
    inputMode: InputMode,
    title: String,
    secondaryMeta: String,
    batchSrtPayload: String,
    batchIndex: Int,
    totalBatches: Int,
    fileName: String,
    temperature: Float,
    thinkingBudget: Int,
  ): JSONObject {
    val contextHeader =
      buildString {
        appendLine(systemPrompt.trim())
        appendLine()
        appendLine("MEDIA CONTEXT:")
        when (inputMode) {
          InputMode.MOVIE -> {
            appendLine("- Type: Movie")
            if (title.isNotBlank()) appendLine("- Movie Name: ${title.trim()}")
            if (secondaryMeta.isNotBlank()) appendLine("- Release Year: ${secondaryMeta.trim()}")
          }
          InputMode.SERIES -> {
            appendLine("- Type: TV Series")
            if (title.isNotBlank()) appendLine("- Series Name: ${title.trim()}")
            if (secondaryMeta.isNotBlank()) appendLine("- Season: ${secondaryMeta.trim()}")
          }
        }
        appendLine("- Source File: $fileName (Batch $batchIndex of $totalBatches)")
      }.trim()

    val contentsArray = JSONArray()

    // Add configured few-shot examples as alternating user/model demonstration turns
    fewShotExamples
      .filter { it.englishText.isNotBlank() && it.bengaliTranslation.isNotBlank() }
      .forEachIndexed { idx, ex ->
        val notePrefix = if (ex.note.isNotBlank()) "[Style note: ${ex.note.trim()}]\n" else ""
        val userTurnText =
          "Example ${idx + 1} - Translate into Bengali following the exact style and SRT structure:\n$notePrefix${ex.englishText.trim()}"
        contentsArray.put(
          JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userTurnText)))
          }
        )
        contentsArray.put(
          JSONObject().apply {
            put("role", "model")
            put("parts", JSONArray().put(JSONObject().put("text", ex.bengaliTranslation.trim())))
          }
        )
      }

    // Final user turn with the actual SRT batch to translate
    val batchInstruction =
      """
Translate every subtitle block below from English to Bengali.
Return ONLY the translated .srt blocks with identical serial numbers and identical timestamps.

$batchSrtPayload
      """.trimIndent()

    contentsArray.put(
      JSONObject().apply {
        put("role", "user")
        put("parts", JSONArray().put(JSONObject().put("text", batchInstruction)))
      }
    )

    val generationConfig =
      JSONObject().apply {
        put("temperature", temperature.toDouble())
        if (thinkingBudget > 0) {
          put(
            "thinkingConfig",
            JSONObject().apply { put("thinkingBudget", thinkingBudget) },
          )
        }
      }

    return JSONObject().apply {
      put(
        "systemInstruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", contextHeader))),
      )
      put("contents", contentsArray)
      put("generationConfig", generationConfig)
    }
  }

  private fun extractCandidateText(rawJson: String): String? {
    return try {
      val root = JSONObject(rawJson)
      val candidates = root.optJSONArray("candidates") ?: return null
      if (candidates.length() == 0) return null
      val firstCandidate = candidates.getJSONObject(0)
      val content = firstCandidate.optJSONObject("content") ?: return null
      val parts = content.optJSONArray("parts") ?: return null
      val sb = StringBuilder()
      for (i in 0 until parts.length()) {
        val part = parts.getJSONObject(i)
        // Skip internal thinking parts if marked thought=true
        if (part.optBoolean("thought", false)) continue
        val text = part.optString("text", "")
        if (text.isNotEmpty()) {
          if (sb.isNotEmpty()) sb.append('\n')
          sb.append(text)
        }
      }
      sb.toString().trim()
    } catch (_: Exception) {
      null
    }
  }

  internal fun classifyHttpFailure(code: Int, rawBody: String): GeminiCallResult {
    val parsedMsg =
      try {
        JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
      } catch (_: Exception) {
        ""
      }
    val parsedStatus =
      try {
        JSONObject(rawBody).optJSONObject("error")?.optString("status").orEmpty()
      } catch (_: Exception) {
        ""
      }

    val combinedLower = "$parsedStatus $parsedMsg $rawBody".lowercase()

    // Invalid / unauthorized API key
    if (
      code == 401 ||
        combinedLower.contains("api_key_invalid") ||
        combinedLower.contains("api key not valid") ||
        combinedLower.contains("invalid api key") ||
        combinedLower.contains("permission_denied") ||
        (code == 400 && combinedLower.contains("api key"))
    ) {
      return GeminiCallResult.InvalidKey(
        parsedMsg.ifBlank { "Invalid API key (HTTP $code)" }
      )
    }

    // Daily quota exhaustion (non-retryable for the current key)
    if (
      code == 429 &&
        (combinedLower.contains("quota") ||
          combinedLower.contains("resource_exhausted") ||
          combinedLower.contains("perday") ||
          combinedLower.contains("daily") ||
          combinedLower.contains("limit"))
    ) {
      return GeminiCallResult.QuotaExhausted(
        parsedMsg.ifBlank { "Daily API quota exhausted (HTTP 429)" }
      )
    }

    if (
      code == 403 &&
        (combinedLower.contains("quota") ||
          combinedLower.contains("billing") ||
          combinedLower.contains("limit"))
    ) {
      return GeminiCallResult.QuotaExhausted(
        parsedMsg.ifBlank { "API quota or billing limit exceeded (HTTP 403)" }
      )
    }

    // Server busy / rate limit transient / 5xx
    return when (code) {
      429 -> GeminiCallResult.RetryableError("Rate limit / Server busy (HTTP 429)", code)
      500, 502, 503, 504, 408 ->
        GeminiCallResult.RetryableError(
          "Server busy (HTTP $code): ${parsedMsg.take(120)}".trim(),
          code,
        )
      else ->
        GeminiCallResult.RetryableError(
          "API error (HTTP $code): ${parsedMsg.ifBlank { rawBody.take(120) }}",
          code,
        )
    }
  }

  private suspend fun Call.awaitCancellable(): Response =
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) {
              cont.resumeWith(Result.failure(e))
            }
          }

          override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
              cont.resume(response)
            } else {
              response.close()
            }
          }
        }
      )
    }

  companion object {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  }
}
