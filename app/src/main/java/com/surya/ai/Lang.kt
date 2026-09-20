package com.surya.ai

import android.content.Context

object Lang {

    fun isHindi(ctx: Context): Boolean =
        ctx.getSharedPreferences("surya", Context.MODE_PRIVATE)
            .getString("lang", "hi") != "en"

    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences("surya", Context.MODE_PRIVATE)
            .edit().putString("lang", code).apply()
    }

    fun t(ctx: Context, hi: String, en: String): String = if (isHindi(ctx)) hi else en
}
