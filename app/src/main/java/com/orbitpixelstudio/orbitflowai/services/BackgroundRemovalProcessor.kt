package com.orbitpixelstudio.orbitflowai.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import com.orbitpixelstudio.orbitflowai.utils.ai.BackgroundSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns "remove the background from this clip" into an actual exported file.
 *
 * Pipeline:
 *   1. FFmpeg extracts the clip to a PNG frame sequence at a fixed fps (keeps the
 *      cost of step 2 bounded — segmenting 30fps of 4K video frame-by-frame on a
 *      phone is not realistic, so we downsample the frame rate for this pass).
 *   2. Each frame is run through [BackgroundSegmenter] on-device to get a matte.
 *   3. FFmpeg alpha-merges the mattes against the original frames and composites
 *      the result over the chosen background (solid color / blurred copy of the
 *      source / a picked image), re-muxing the original audio track untouched.
 *
 * This is a one-shot transform, not a live filter: the output is a normal .mp4
 * that can be dropped back into the timeline exactly like any imported clip
 * (see the integration note at the bottom of this file).
 *
 * Nothing here touches the network — matting is 100% on-device via ML Kit.
 */
class BackgroundRemovalProcessor(
    private val context: Context,
    private val renderEngine: FFmpegRenderEngine
) {
    private val TAG = "BackgroundRemoval"

    sealed class Background {
        data class Color(val hex: String = "#00B140") : Background()
        data class Image(val path: String) : Background()
        data class Blur(val radius: Int = 20) : Background()
    }

    data class Progress(val stage: String, val fraction: Float)

    suspend fun process(
        sourcePath: String,
        background: Background,
        fps: Int = 24,
        onProgress: ((Progress) -> Unit)? = null
    ): FFmpegRenderEngine.RenderResult = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "bg_removal_${System.nanoTime()}").apply { mkdirs() }
        val framesDir = File(workDir, "frames").apply { mkdirs() }
        val mattesDir = File(workDir, "mattes").apply { mkdirs() }
        val segmenter = BackgroundSegmenter()

        try {
            val (srcWidth, srcHeight) = readResolution(sourcePath)

            // 1. Extract source frames.
            onProgress?.invoke(Progress("Extracting frames", 0f))
            val extractCmd = "-y -i \"$sourcePath\" -vf fps=$fps \"${framesDir.absolutePath}/f_%05d.png\""
            val extractResult = renderEngine.executeCommand(extractCmd)
            if (extractResult is FFmpegRenderEngine.RenderResult.Failure) return@withContext extractResult

            // 2. Segment every frame on-device.
            val frameFiles = framesDir.listFiles { f -> f.extension == "png" }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (frameFiles.isEmpty()) {
                return@withContext FFmpegRenderEngine.RenderResult.Failure(
                    error = "No frames extracted from $sourcePath"
                )
            }

            frameFiles.forEachIndexed { i, frameFile ->
                val bmp = BitmapFactory.decodeFile(frameFile.absolutePath)
                if (bmp != null) {
                    val mask = segmenter.maskFor(bmp)
                    File(mattesDir, frameFile.name).outputStream().use { out ->
                        mask.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    mask.recycle()
                    bmp.recycle()
                }
                onProgress?.invoke(Progress("Segmenting frames", (i + 1f) / frameFiles.size))
            }

            // 3. Composite: frames + mattes (as alpha) over the chosen background.
            onProgress?.invoke(Progress("Compositing", 0.9f))
            val outputFile = File(context.cacheDir, "bg_removed_${System.nanoTime()}.mp4")
            val framePattern = "${framesDir.absolutePath}/f_%05d.png"
            val mattePattern = "${mattesDir.absolutePath}/f_%05d.png"

            // Input 0 = frames, Input 1 = mattes, Input 2 = background source,
            // Input 3 = original file (used only for its audio track).
            val bgInputArg: String
            val bgFilter: String
            when (background) {
                is Background.Color ->
                    """
                    -f lavfi -i "color=c=${background.hex}:s=${srcWidth}x${srcHeight}:r=$fps"
                    """.trimIndent().let {
                        bgInputArg = it
                        bgFilter = "[2:v]format=yuv420p[bg]"
                    }
                is Background.Image -> {
                    bgInputArg = "-loop 1 -i \"${background.path}\""
                    bgFilter = "[2:v]scale=$srcWidth:$srcHeight:force_original_aspect_ratio=increase," +
                        "crop=$srcWidth:$srcHeight[bg]"
                }
                is Background.Blur -> {
                    bgInputArg = "-framerate $fps -i \"$framePattern\""
                    bgFilter = "[2:v]gblur=sigma=${background.radius}[bg]"
                }
            }

            val cmd = buildString {
                append("-y ")
                append("-framerate $fps -i \"$framePattern\" ")
                append("-framerate $fps -i \"$mattePattern\" ")
                append("$bgInputArg ")
                append("-i \"$sourcePath\" ")
                append("-filter_complex \"")
                append("[0:v][1:v]alphamerge[fg];")
                append("$bgFilter;")
                append("[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,format=yuv420p[outv]")
                append("\" ")
                append("-map \"[outv]\" -map 3:a? -c:v h264_mediacodec -c:a copy ")
                append("\"${outputFile.absolutePath}\"")
            }

            val result = renderEngine.executeCommand(cmd)
            onProgress?.invoke(Progress("Done", 1f))
            result
        } catch (e: Exception) {
            Log.e(TAG, "Background removal failed: ${e.message}", e)
            FFmpegRenderEngine.RenderResult.Failure(error = e.message ?: "Background removal failed")
        } finally {
            segmenter.close()
            workDir.deleteRecursively()
        }
    }

    private fun readResolution(path: String): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            w to h
        } catch (e: Exception) {
            Log.w(TAG, "Could not read resolution, defaulting to 1080x1920: ${e.message}")
            1080 to 1920
        } finally {
            retriever.release()
        }
    }
}

/*
 * ── Integration note ──────────────────────────────────────────────────────
 * This is a standalone, one-shot transform — it does not touch the existing
 * FFmpegRenderEngine command builder in VideoEditingViewModel, so it can't
 * break any existing export path.
 *
 * Wire it up from wherever a clip is selected in the timeline, e.g. in
 * VideoEditingActivity:
 *
 *   lifecycleScope.launch {
 *       val processor = BackgroundRemovalProcessor(applicationContext, renderEngine)
 *       val sourcePath = resolveUriToPath(selectedClip.uri) // existing helper
 *       when (val result = processor.process(
 *           sourcePath = sourcePath,
 *           background = BackgroundRemovalProcessor.Background.Blur(20),
 *           onProgress = { p -> progressBar.setProgress((p.fraction * 100).toInt()) }
 *       )) {
 *           is FFmpegRenderEngine.RenderResult.Success ->
 *               viewModel.replaceClipUri(selectedClip.id, Uri.fromFile(File(result.outputPath)))
 *           is FFmpegRenderEngine.RenderResult.Failure ->
 *               showError(result.error)
 *           else -> {}
 *       }
 *   }
 *
 * `replaceClipUri` doesn't exist yet — swap in whatever your ViewModel already
 * uses to change a MergeItem/base clip's `uri` (the Merge/MergeItem model in
 * EditOperation.kt is the natural place). Once the clip's uri points at the
 * processed file, every existing filter (overlays, adjust, filters, transitions)
 * keeps working on it unmodified.
 */
