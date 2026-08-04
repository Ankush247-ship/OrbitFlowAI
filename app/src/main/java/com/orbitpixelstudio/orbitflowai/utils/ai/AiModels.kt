package com.orbitpixelstudio.orbitflowai.utils.ai

/**
 * Common result type returned by every AI provider client (Gemini, OpenAI, and
 * whatever gets added next). Keeping this provider-agnostic is what lets
 * [AiTextGenerator] swap providers without callers caring which one ran.
 */
sealed class AiResult {
    data class Success(val text: String) : AiResult()
    data class Failure(
        val message: String,
        val type: AiErrorType = AiErrorType.UNKNOWN
    ) : AiResult()
}

/**
 * Classifies *why* an AI call failed so the UI can show a specific, actionable
 * message (e.g. "Invalid API key" vs "No internet") instead of one generic
 * "couldn't respond" string regardless of cause.
 */
enum class AiErrorType {
    MISSING_API_KEY,
    INVALID_API_KEY,
    NO_INTERNET,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    UNKNOWN
}

/**
 * Identifies a pluggable AI text-generation provider. Add a new entry here plus
 * a client that implements the same "prompt in, AiResult out" shape to support
 * another provider (Claude, Grok, DeepSeek, etc.) without touching call sites.
 */
enum class AiProviderId(val displayName: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-2.0-flash"),
    OPENAI("OpenAI", "gpt-4o-mini");

    companion object {
        fun fromStorageKey(key: String?): AiProviderId {
            return entries.firstOrNull { it.name == key } ?: GEMINI
        }
    }
}
