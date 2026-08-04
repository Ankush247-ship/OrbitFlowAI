package com.orbitpixelstudio.orbitflowai.utils.ai

import android.content.Context
import com.orbitpixelstudio.orbitflowai.utils.ApiKeyStore
import com.orbitpixelstudio.orbitflowai.utils.GeminiClient
import com.orbitpixelstudio.orbitflowai.utils.OpenAiClient

/**
 * Single entry point every feature (AI captions, Orbit AI assistant, future
 * script/hashtag/thumbnail generators, etc.) should call instead of talking to
 * GeminiClient or OpenAiClient directly. Reads the provider the user picked in
 * Settings → AI Studio and dispatches to that provider's key + client.
 *
 * To add a new provider (Claude, Grok, DeepSeek...):
 *  1. Add an entry to [AiProviderId].
 *  2. Add its key getter/setter alongside the others in [ApiKeyStore].
 *  3. Add a `<Provider>Client.generateText(apiKey, prompt): AiResult` object.
 *  4. Add one branch below.
 * No caller of [generate] needs to change.
 */
object AiTextGenerator {

    fun isConfigured(context: Context): Boolean {
        return activeApiKey(context) != null
    }

    fun activeProvider(context: Context): AiProviderId {
        return ApiKeyStore.getSelectedProvider(context)
    }

    fun activeApiKey(context: Context): String? {
        return when (activeProvider(context)) {
            AiProviderId.GEMINI -> ApiKeyStore.getGeminiApiKey(context)
            AiProviderId.OPENAI -> ApiKeyStore.getOpenAiApiKey(context)
        }
    }

    suspend fun generate(context: Context, prompt: String): AiResult {
        val provider = activeProvider(context)
        val apiKey = activeApiKey(context)
            ?: return AiResult.Failure(
                "No API key set for ${provider.displayName}",
                AiErrorType.MISSING_API_KEY
            )

        if (!com.orbitpixelstudio.orbitflowai.utils.NetworkUtils.isOnline(context)) {
            return AiResult.Failure(
                "No internet connection",
                AiErrorType.NO_INTERNET
            )
        }

        return when (provider) {
            AiProviderId.GEMINI -> GeminiClient.generateText(apiKey, prompt)
            AiProviderId.OPENAI -> OpenAiClient.generateText(apiKey, prompt)
        }
    }

    /**
     * Same as [generate], but checks/populates [AiResponseCache] first so
     * one-shot generators (script/title/description/hashtags/captions) don't
     * re-spend API quota re-generating an identical prompt within the cache
     * TTL. Pass [forceRefresh] = true for an explicit "Regenerate" action to
     * bypass the cache for that one call.
     */
    suspend fun generateCached(
        context: Context,
        prompt: String,
        forceRefresh: Boolean = false
    ): AiResult {
        val provider = activeProvider(context)
        val model = provider.defaultModel
        val cacheKey = AiResponseCache.key(provider, model, prompt)

        if (!forceRefresh) {
            AiResponseCache.get(context, cacheKey)?.let { cached ->
                return AiResult.Success(cached)
            }
        }

        val result = generate(context, prompt)
        if (result is AiResult.Success) {
            AiResponseCache.put(context, cacheKey, result.text)
        }
        return result
    }
}
