package com.nexora.browser.util

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.nexora.browser.NexoraApp

/**
 * Native touch feedback for app controls. The platform's normal click sound
 * is enabled per preference; haptics are requested only for completed taps.
 */
object UiFeedback {
    fun tap(context: Context, view: View) {
        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(NexoraApp.KEY_HAPTIC_FEEDBACK, true)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun install(root: View, includeHaptics: Boolean = true) {
        val context = root.context
        val prefs = context.getSharedPreferences(NexoraApp.PREFS_NAME, Context.MODE_PRIVATE)
        val sounds = prefs.getBoolean(NexoraApp.KEY_SOUND_EFFECTS, true)
        fun visit(view: View) {
            view.isSoundEffectsEnabled = sounds
            if (includeHaptics && view.isClickable && view !is WebView) {
                view.setOnTouchListener { target, event ->
                    if (event.action == MotionEvent.ACTION_UP && target.isEnabled &&
                        prefs.getBoolean(NexoraApp.KEY_HAPTIC_FEEDBACK, true)
                    ) {
                        target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    false
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
    }
}
