package com.surya.ai

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class SuryaService : AccessibilityService() {

    companion object {
        var instance: SuryaService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun lock(): Boolean {
        return if (Build.VERSION.SDK_INT >= 28) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }
}
