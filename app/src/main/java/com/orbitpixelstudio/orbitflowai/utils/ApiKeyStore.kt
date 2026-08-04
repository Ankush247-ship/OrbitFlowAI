package com.orbitpixelstudio.orbitflowai.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.orbitpixelstudio.orbitflowai.utils.ai.AiProviderId

/**
 * Stores the user's own AI provider API keys locally, encrypted at rest.
 *
 * Keys never leave the device except in direct calls the device makes to each
 * provider's own API (see GeminiClient / OpenAiClient) — OrbitFlow AI has no
 * backend that proxies or logs them.
 */
object ApiKeyStore {

    private const val PREFS_NAME = "orbitflowai_secure_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_SELECTED_PROVIDER = "selected_ai_provider"

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fall back to a plain, app-private prefs file if the keystore is unavailable
            // (e.g. some emulator images). Still not world-readable, just not encrypted.
            Log.e("ApiKeyStore", "Falling back to unencrypted prefs: ${e.message}", e)
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getGeminiApiKey(context: Context): String? {
        val key = prefs(context).getString(KEY_GEMINI_API_KEY, null)
        return if (key.isNullOrBlank()) null else key
    }

    fun setGeminiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun clearGeminiApiKey(context: Context) {
        prefs(context).edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    fun getOpenAiApiKey(context: Context): String? {
        val key = prefs(context).getString(KEY_OPENAI_API_KEY, null)
        return if (key.isNullOrBlank()) null else key
    }

    fun setOpenAiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_OPENAI_API_KEY, apiKey.trim()).apply()
    }

    fun clearOpenAiApiKey(context: Context) {
        prefs(context).edit().remove(KEY_OPENAI_API_KEY).apply()
    }

    fun getSelectedProvider(context: Context): AiProviderId {
        return AiProviderId.fromStorageKey(prefs(context).getString(KEY_SELECTED_PROVIDER, null))
    }

    fun setSelectedProvider(context: Context, provider: AiProviderId) {
        prefs(context).edit().putString(KEY_SELECTED_PROVIDER, provider.name).apply()
    }
}
