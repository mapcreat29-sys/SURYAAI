package com.surya.ai

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class SuryaService : AccessibilityService() {

    companion object {
        var instance: SuryaService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var box: TextView? = null
    private val hideRunnable = Runnable { hideBox() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideBox()
        instance = null
        super.onDestroy()
    }

    fun showBox(text: String, autoHideMs: Long = 0L) {
        handler.post {
            handler.removeCallbacks(hideRunnable)
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            var tv = box
            if (tv == null) {
                val t = TextView(this)
                val d = resources.displayMetrics.density
                t.setTextColor(Color.WHITE)
                t.textSize = 20f
                t.gravity = Gravity.CENTER
                val p = (20 * d).toInt()
                t.setPadding(p, p, p, p)
                val bg = GradientDrawable()
                bg.setColor(Color.parseColor("#E6202124"))
                bg.cornerRadius = 28 * d
                t.background = bg
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.CENTER
                wm.addView(t, lp)
                box = t
                tv = t
            }
            tv.text = text
            if (autoHideMs > 0) handler.postDelayed(hideRunnable, autoHideMs)
        }
    }

    fun hideBox() {
        handler.post {
            handler.removeCallbacks(hideRunnable)
            val tv = box
            if (tv != null) {
                try {
                    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                    wm.removeView(tv)
                } catch (e: Exception) {
                }
                box = null
            }
        }
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
