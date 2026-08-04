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
 * Minimal direct-to-Google client for Gemini text generation.
 *
 * No SDK / Retrofit dependency is added on purpose to keep this change small —
 * it's a single REST call using the same HttpURLConnection + org.json approach
 * already used elsewhere in this app (see MainActivity's update checker).
 *
 * The request goes straight from this device to generativelanguage.googleapis.com
 * using the key the user pasted into Settings → AI Studio. OrbitFlow AI has no
 * server in the middle. See [com.orbitpixelstudio.orbitflowai.utils.ai.AiTextGenerator]
 * for the provider-agnostic entry point callers should actually use.
 */
object GeminiClient {

    private const val TAG = "GeminiClient"
    private const val DEFAULT_MODEL = "gemini-2.0-flash"
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    suspend fun generateText(
        apiKey: String,
        prompt: String,
        model: String = DEFAULT_MODEL
    ): AiResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(String.format(ENDPOINT_TEMPLATE, model, apiKey))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 2048)
                })
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""

            if (responseCode !in 200..299) {
                Log.e(TAG, "Gemini request failed ($responseCode): $responseText")
                val errMsg = try {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                } catch (e: Exception) { null }
                return@withContext AiResult.Failure(errMsg ?: "Request failed with code $responseCode")
            }

            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext AiResult.Failure("No response generated")
            }

            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")

            val text = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    text.append(parts.getJSONObject(i).optString("text", ""))
                }
            }

            if (text.isBlank()) {
                AiResult.Failure("Empty response from Gemini")
            } else {
                AiResult.Success(text.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request error: ${e.message}", e)
            AiResult.Failure(e.message ?: "Unknown network error")
        } finally {
            connection?.disconnect()
        }
    }
}
