package com.orbitpixelstudio.orbitflowai.utils.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device person/background segmentation using ML Kit's Selfie Segmenter.
 *
 * Fully offline — the model ships in the APK (~4.5MB), there is no network call
 * and no per-use cost. This is what makes "Background Removal" feasible for a
 * privacy-focused, local-only editor: unlike CapCut's cloud AI effects, nothing
 * ever leaves the device.
 *
 * One instance is reused across every frame of a clip. SINGLE_IMAGE_MODE treats
 * each frame independently (no cross-frame smoothing) which is the right choice
 * here since we're batch-processing frames for export, not live camera preview.
 */
class BackgroundSegmenter {

    private val segmenter: Segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    /**
     * Returns a grayscale [Bitmap] the same size as [frame], where pixel brightness
     * encodes foreground confidence (255 = subject, 0 = background). Feed this
     * straight into FFmpeg's `alphamerge` filter as the alpha source for [frame].
     */
    suspend fun maskFor(frame: Bitmap): Bitmap {
        val input = InputImage.fromBitmap(frame, 0)
        val result = segmenter.process(input).await()
        val buffer = result.buffer
        val w = result.width
        val h = result.height
        buffer.rewind()

        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val confidence = buffer.float // 0f..1f
            val gray = (confidence * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(gray, gray, gray)
        }

        val rawMask = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (w == frame.width && h == frame.height) return rawMask

        // ML Kit's raw mask is often smaller than the source frame (e.g. 256x256) —
        // scale it back up so it lines up pixel-for-pixel with the frame in FFmpeg.
        return Bitmap.createScaledBitmap(rawMask, frame.width, frame.height, true).also {
            rawMask.recycle()
        }
    }

    fun close() = segmenter.close()
}
