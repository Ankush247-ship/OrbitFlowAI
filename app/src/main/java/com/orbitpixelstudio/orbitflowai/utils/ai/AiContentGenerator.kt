package com.orbitpixelstudio.orbitflowai.utils.ai

import android.content.Context

/**
 * The four one-shot copywriting generators exposed from the "AI Write" tool:
 * video script, title options, description, and hashtags. Each builds a
 * purpose-tuned prompt and dispatches through [AiTextGenerator.generateCached]
 * so the provider/key/retry/caching plumbing is shared and only lives once.
 */
enum class AiContentType(val label: String, val hint: String) {
    SCRIPT(
        "Video Script",
        "e.g. 60-second product demo for a wireless earbud, energetic tone"
    ),
    TITLE(
        "Titles",
        "e.g. A relaxing morning coffee routine in my apartment"
    ),
    DESCRIPTION(
        "Description",
        "e.g. Behind-the-scenes of a street food stall in Mumbai at night"
    ),
    HASHTAGS(
        "Hashtags",
        "e.g. Home workout video for beginners, no equipment"
    );
}

object AiContentGenerator {

    suspend fun generate(
        context: Context,
        type: AiContentType,
        topic: String,
        forceRefresh: Boolean = false
    ): AiResult {
        val prompt = buildPrompt(type, topic)
        return AiTextGenerator.generateCached(context, prompt, forceRefresh)
    }

    private fun buildPrompt(type: AiContentType, topic: String): String {
        val safeTopic = topic.trim()
        return when (type) {
            AiContentType.SCRIPT -> """
                You are a short-form video scriptwriter. Write a shot-by-shot script for
                this video, formatted as a numbered list of beats. Each beat should have a
                short visual/action cue and a line of voiceover or on-screen text where
                relevant. Keep the whole script tight enough to fit the described length.
                No preamble, start directly at beat 1.

                Video concept: "$safeTopic"
            """.trimIndent()

            AiContentType.TITLE -> """
                Suggest 6 short, scroll-stopping titles for this video, optimized for
                social platforms (concise, no clickbait lies, no emoji spam - at most one
                emoji if it genuinely fits). Return them as a plain numbered list, one per
                line, nothing else.

                Video concept: "$safeTopic"
            """.trimIndent()

            AiContentType.DESCRIPTION -> """
                Write a single social media video description (2-4 sentences) for this
                video. Engaging but not over-the-top, no hashtags in it (those are
                generated separately), no markdown formatting.

                Video concept: "$safeTopic"
            """.trimIndent()

            AiContentType.HASHTAGS -> """
                Suggest 15 relevant hashtags for this video: a mix of a few broad/high-reach
                tags and mostly specific/niche tags. Return them space-separated on a single
                line, each starting with #, lowercase, no spaces inside a tag, nothing else
                in the response.

                Video concept: "$safeTopic"
            """.trimIndent()
        }
    }
}
