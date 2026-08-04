package com.orbitpixelstudio.orbitflowai.utils

import android.util.Log
import com.orbitpixelstudio.orbitflowai.utils.ai.AiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal direct-to-OpenAI client for chat completions, using the user's own
 * key from Settings → AI Studio. Same no-SDK approach as [GeminiClient] so the
 * two are trivial to compare and a third provider is trivial to add.
 */
object OpenAiClient {

    private const val TAG = "OpenAiClient"
    private const val DEFAULT_MODEL = "gpt-4o-mini"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    suspend fun generateText(
        apiKey: String,
        prompt: String,
        model: String = DEFAULT_MODEL
    ): AiResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(ENDPOINT)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                ))
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""

            if (responseCode !in 200..299) {
                Log.e(TAG, "OpenAI request failed ($responseCode): $responseText")
                val errMsg = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null }
                return@withContext AiResult.Failure(errMsg ?: "Request failed with code $responseCode")
            }

            val json = JSONObject(responseText)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext AiResult.Failure("No response generated")
            }

            val text = choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            if (text.isBlank()) {
                AiResult.Failure("Empty response from OpenAI")
            } else {
                AiResult.Success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI request error: ${e.message}", e)
            AiResult.Failure(e.message ?: "Unknown network error")
        } finally {
            connection?.disconnect()
        }
    }
}
