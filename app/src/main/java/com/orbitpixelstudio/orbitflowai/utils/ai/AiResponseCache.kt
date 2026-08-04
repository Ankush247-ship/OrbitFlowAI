package com.orbitpixelstudio.orbitflowai.utils.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Lightweight disk cache for AI text responses so repeated identical requests
 * (e.g. reopening the Script Generator with the same topic, or a user backing
 * out of a dialog and back in) don't burn another API call/quota unit.
 *
 * Not used for the Orbit AI Q&A assistant, since those answers are meant to
 * be contextual/regenerable per turn — only for the one-shot generators
 * (Script, Title, Description, Hashtags, Captions) where the same input
 * should deterministically be worth reusing for a short window.
 *
 * Cache entries expire after [DEFAULT_TTL_MS] (24h) since AI phrasing quality
 * and model versions can change, and stale cached copywriting silently going
 * stale forever would be a worse experience than one extra API call a day.
 */
object AiResponseCache {

    private const val TAG = "AiResponseCache"
    private const val DIR_NAME = "ai_response_cache"
    private const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Builds a stable cache key from everything that affects the output. */
    fun key(provider: AiProviderId, model: String, prompt: String): String {
        val raw = "${provider.name}|$model|$prompt"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context, key: String, ttlMs: Long = DEFAULT_TTL_MS): String? {
        return try {
            val file = File(cacheDir(context), "$key.json")
            if (!file.exists()) return null

            val json = JSONObject(file.readText(Charsets.UTF_8))
            val savedAt = json.optLong("savedAt", 0L)
            if (System.currentTimeMillis() - savedAt > ttlMs) {
                file.delete()
                return null
            }
            json.optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Cache read failed for $key: ${e.message}")
            null
        }
    }

    fun put(context: Context, key: String, text: String) {
        try {
            val file = File(cacheDir(context), "$key.json")
            val json = JSONObject().apply {
                put("text", text)
                put("savedAt", System.currentTimeMillis())
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Cache write failed for $key: ${e.message}")
        }
    }

    /** Clears all cached AI responses. Exposed for a future Settings > Clear AI cache action. */
    fun clear(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Best-effort prune of anything older than [ttlMs], for periodic housekeeping. */
    fun pruneExpired(context: Context, ttlMs: Long = DEFAULT_TTL_MS) {
        val now = System.currentTimeMillis()
        cacheDir(context).listFiles()?.forEach { file ->
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val savedAt = json.optLong("savedAt", 0L)
                if (now - savedAt > ttlMs) file.delete()
            } catch (e: Exception) {
                file.delete()
            }
        }
    }
}
