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
            ?: return AiResult.Failure("No API key set for ${provider.displayName}")

        return when (provider) {
            AiProviderId.GEMINI -> GeminiClient.generateText(apiKey, prompt)
            AiProviderId.OPENAI -> OpenAiClient.generateText(apiKey, prompt)
        }
    }
}
