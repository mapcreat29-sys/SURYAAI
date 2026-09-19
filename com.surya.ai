package com.surya.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Locale

object Commands {

    private const val NEED_ACC = "पहले Accessibility में सूर्या को चालू करो"

    private class Page(val words: List<String>, val label: String, val action: String)

    private val pages = listOf(
        Page(listOf("wifi", "वाईफाई", "वाई-फाई", "वाई फाई"), "वाई-फाई", Settings.ACTION_WIFI_SETTINGS),
        Page(listOf("bluetooth", "ब्लूटूथ"), "ब्लूटूथ", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page(listOf("display over", "ओवरले"), "दूसरे ऐप के ऊपर दिखाना", Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Page(listOf("display", "डिस्प्ले", "brightness", "ब्राइटनेस"), "डिस्प्ले", Settings.ACTION_DISPLAY_SETTINGS),
        Page(listOf("sound", "साउंड", "volume", "वॉल्यूम", "आवाज़"), "साउंड", Settings.ACTION_SOUND_SETTINGS),
        Page(listOf("battery saver", "बैटरी सेवर"), "बैटरी सेवर", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Page(listOf("battery", "बैटरी"), "बैटरी", Intent.ACTION_POWER_USAGE_SUMMARY),
        Page(listOf("location", "लोकेशन"), "लोकेशन", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page(listOf("developer", "डेवलपर"), "डेवलपर ऑप्शन", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page(listOf("about phone", "build number", "बिल्ड नंबर", "फोन के बारे", "फ़ोन के बारे"), "फ़ोन के बारे में", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page(listOf("storage", "स्टोरेज"), "स्टोरेज", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page(listOf("all apps", "सभी ऐप"), "सभी ऐप्स", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Page(listOf("app settings", "ऐप सेटिंग", "एप्लीकेशन"), "ऐप्स", Settings.ACTION_APPLICATION_SETTINGS),
        Page(listOf("तारीख", "date and time"), "तारीख और समय", Settings.ACTION_DATE_SETTINGS),
        Page(listOf("language", "भाषा", "लैंग्वेज"), "भाषा", Settings.ACTION_LOCALE_SETTINGS),
        Page(listOf("keyboard", "कीबोर्ड"), "कीबोर्ड", Settings.ACTION_INPUT_METHOD_SETTINGS),
        Page(listOf("security", "सिक्योरिटी"), "सिक्योरिटी", Settings.ACTION_SECURITY_SETTINGS),
        Page(listOf("privacy", "प्राइवेसी"), "प्राइवेसी", Settings.ACTION_PRIVACY_SETTINGS),
        Page(listOf("accessibility", "एक्सेसिबिलिटी"), "एक्सेसिबिलिटी", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page(listOf("nfc", "एनएफसी"), "NFC", Settings.ACTION_NFC_SETTINGS),
        Page(listOf("airplane", "एयरप्लेन", "फ्लाइट मोड"), "एयरप्लेन मोड", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Page(listOf("network", "नेटवर्क"), "नेटवर्क", Settings.ACTION_WIRELESS_SETTINGS),
        Page(listOf("cast", "कास्ट"), "कास्ट", Settings.ACTION_CAST_SETTINGS),
        Page(listOf("account", "अकाउंट"), "अकाउंट", Settings.ACTION_SYNC_SETTINGS)
    )

    private val aliases = mapOf(
        "यूट्यूब" to "youtube",
        "इंस्टाग्राम" to "instagram",
        "व्हाट्सएप" to "whatsapp",
        "व्हाट्सऐप" to "whatsapp",
        "वॉट्सएप" to "whatsapp",
        "क्रोम" to "chrome",
        "कैमरा" to "camera",
        "गैलरी" to "gallery",
        "फोन" to "phone"
    )

    private fun start(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    private fun openPage(c: Context, p: Page): String {
        return try {
            start(c, Intent(p.action))
            "खोल रहा हूँ: ${p.label}"
        } catch (e: Exception) {
            "यह सेटिंग इस फ़ोन में सीधे नहीं खुल पाई: ${p.label}"
        }
    }

    fun run(ctx: Context, raw: String): String {
        val t = raw.lowercase(Locale.getDefault()).trim()
        if (t.isEmpty()) return ""
        val svc = SuryaService.instance
        val c: Context = svc ?: ctx
        val page = pages.firstOrNull { p -> p.words.any { w -> t.contains(w) } }

        return when {
            t.contains("unlock") || t.contains("अनलॉक") ->
                "सुरक्षा की वजह से मैं फ़ोन अनलॉक नहीं कर सकता"

            t.contains("lock") || t.contains("लॉक") ->
                if (svc == null) NEED_ACC
                else if (svc.lock()) "फ़ोन लॉक कर रहा हूँ" else "लॉक नहीं हो पाया"

            t.contains("home") || t.contains("होम") ->
                if (svc == null) NEED_ACC else { svc.home(); "होम स्क्रीन" }

            t.contains("back") || t.contains("पीछे") ->
                if (svc == null) NEED_ACC else { svc.back(); "पीछे गया" }

            page != null -> openPage(c, page)

            t.contains("setting") || t.contains("सेटिंग") -> {
                start(c, Intent(Settings.ACTION_SETTINGS))
                "सेटिंग खोल रहा हूँ"
            }

            t.contains("open") || t.contains("ओपन") || t.contains("खोल") -> {
                var name = t
                for (w in listOf("open", "ओपन", "खोलिए", "खोलो", "खोल", "करो", "please")) {
                    name = name.replace(w, " ")
                }
                name = name.trim()
                for ((h, e) in aliases) {
                    if (name.contains(h)) name = e
                }
                if (name.isNotEmpty() && openApp(c, name)) "खोल रहा हूँ: $name"
                else "ऐप नहीं मिला: $name"
            }

            else -> "समझ नहीं आया"
        }
    }

    private fun openApp(ctx: Context, query: String): Boolean {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getLaunchIntentForPackage(it.packageName) != null &&
                pm.getApplicationLabel(it).toString().lowercase().contains(query)
        } ?: return false
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        start(ctx, intent)
        return true
    }
}
