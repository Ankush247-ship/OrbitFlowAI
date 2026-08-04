package com.orbitpixelstudio.orbitflowai.utils

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Adds a bounce scale animation and haptic feedback to a view on touch.
 */
fun View.setBounceClickListener(onClick: () -> Unit) {
    this.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start()
            }
            MotionEvent.ACTION_UP -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                v.performHapticFeedback(
                    HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                // If it's a valid click (inside bounds)
                if (event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height) {
                    onClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
        }
        true
    }
}

/**
 * Standardized light haptic feedback for continuous scrubbing or dragging.
 */
fun View.performHapticLight() {
    this.performHapticFeedback(
        HapticFeedbackConstants.CLOCK_TICK,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    )
}

/**
 * Standardized haptic feedback for selection or important clicks.
 */
fun View.performHapticClick() {
    this.performHapticFeedback(
        HapticFeedbackConstants.VIRTUAL_KEY,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    )
}
