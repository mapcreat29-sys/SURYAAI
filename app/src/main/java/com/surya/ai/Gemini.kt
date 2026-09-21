package com.surya.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Gemini {

    class Result(val type: String, val text: String, val error: String?, val detail: String = "")

    // पहला मॉडल न मिले तो अगला आज़माया जाता है (मॉडल के नाम कभी बदल भी जाते हैं)
    private val models = listOf(
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
        "gemini-2.5-flash-lite"
    )

    private const val SYSTEM = "You are Surya, a voice assistant inside an Android phone app. " +
        "The user's text comes from noisy speech recognition in Hindi and/or English and may be misspelled. " +
        "Reply with ONLY a JSON object. " +
        "If the user wants the phone to do something, reply {\"type\":\"command\",\"text\":\"<command>\"} " +
        "where <command> is exactly one of: \"open <app name in English>\", \"call <contact name or number>\", " +
        "\"lock\", \"home\", \"back\", \"<setting name> settings\" (for example wifi, bluetooth, display, sound, battery, location). " +
        "Otherwise answer the question with {\"type\":\"answer\",\"text\":\"<answer>\"}. " +
        "Answers must be at most 2 short sentences, plain text, no markdown, no symbols, " +
        "in the same language as the user's request (Hindi in Devanagari script, or English)."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("surya", Context.MODE_PRIVATE)

    fun key(ctx: Context): String = prefs(ctx).getString("gemini_key", "")?.trim() ?: ""

    fun hasKey(ctx: Context): Boolean = key(ctx).length >= 20

    fun saveKey(ctx: Context, k: String) {
        prefs(ctx).edit().putString("gemini_key", k.trim()).apply()
    }

    fun errorText(ctx: Context, r: Result): String = when (r.error) {
        "nokey" -> Lang.t(ctx, "AI की चाबी नहीं डाली गई", "No AI key has been added")
        "key" -> Lang.t(ctx, "AI की चाबी सही नहीं है", "The AI key is not valid")
        "limit" -> Lang.t(ctx, "AI की सीमा पूरी हो गई, थोड़ी देर बाद कोशिश करें", "AI limit reached, try again later")
        "net" -> Lang.t(ctx, "इंटरनेट नहीं है", "No internet connection")
        else -> Lang.t(ctx, "AI से जवाब नहीं मिला", "No answer from AI")
    }

    private fun buildBody(userText: String, thinkingOff: Boolean): String {
        val o = JSONObject()
        o.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
        )
        o.put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userText)))
            )
        )
        val g = JSONObject()
        g.put("temperature", 0.3)
        g.put("maxOutputTokens", 400)
        g.put("responseMimeType", "application/json")
        if (thinkingOff) g.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        o.put("generationConfig", g)
        return o.toString()
    }

    private fun call(model: String, key: String, body: String): Pair<Int, String> {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", key)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return Pair(code, text)
    }

    private fun parse(resp: String): Result {
        return try {
            val o = JSONObject(resp)
            val parts = o.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            var t = sb.toString().trim()
            t = t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            try {
                val j = JSONObject(t)
                Result(j.optString("type", "answer"), j.optString("text").trim(), null)
            } catch (e: Exception) {
                Result("answer", t, null)
            }
        } catch (e: Exception) {
            Result("error", "", "other", "parse")
        }
    }

    // यह नेटवर्क का काम है, इसे मुख्य धागे पर न चलाएँ
    fun ask(ctx: Context, userText: String): Result {
        val k = key(ctx)
        if (k.length < 20) return Result("error", "", "nokey")
        var lastDetail = ""
        for (model in models) {
            var next = false
            for (thinkingOff in listOf(true, false)) {
                if (next) break
                try {
                    val (code, resp) = call(model, k, buildBody(userText, thinkingOff))
                    lastDetail = "$model: HTTP $code"
                    if (code in 200..299) {
                        return parse(resp)
                    } else if (code == 429) {
                        return Result("error", "", "limit", lastDetail)
                    } else if (code == 401 || code == 403) {
                        return Result("error", "", "key", lastDetail)
                    } else if (code == 400 &&
                        (resp.contains("API_KEY_INVALID") || resp.contains("API key not valid"))
                    ) {
                        return Result("error", "", "key", lastDetail)
                    } else if (code == 404) {
                        next = true
                    } else if (code == 400) {
                        // शायद यह मॉडल thinking बंद करने को नहीं मानता, बिना उसके फिर कोशिश होगी
                    } else {
                        return Result("error", "", "server", lastDetail)
                    }
                } catch (e: IOException) {
                    return Result("error", "", "net", lastDetail)
                } catch (e: Exception) {
                    return Result("error", "", "other", lastDetail)
                }
            }
        }
        return Result("error", "", "other", lastDetail)
    }
}
