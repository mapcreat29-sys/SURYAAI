package com.surya.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import java.util.Locale

object Commands {

    // काम हो पाया या नहीं
    @Volatile var ok = true

    // आवाज़ दोबारा सुनकर कोशिश करनी चाहिए या नहीं
    @Volatile var retry = false

    private class Page(val words: List<String>, val hi: String, val en: String, val action: String)

    private val pages = listOf(
        Page(listOf("wifi", "wi-fi", "wi fi", "वाईफाई", "वाई-फाई", "वाई फाई"), "वाई-फाई", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        Page(listOf("bluetooth", "ब्लूटूथ"), "ब्लूटूथ", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page(listOf("display over", "ओवरले"), "दूसरे ऐप के ऊपर दिखाना", "Display over other apps", Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Page(listOf("display", "डिस्प्ले", "brightness", "ब्राइटनेस"), "डिस्प्ले", "Display", Settings.ACTION_DISPLAY_SETTINGS),
        Page(listOf("sound", "साउंड", "volume", "वॉल्यूम", "आवाज़"), "साउंड", "Sound", Settings.ACTION_SOUND_SETTINGS),
        Page(listOf("battery saver", "बैटरी सेवर"), "बैटरी सेवर", "Battery saver", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Page(listOf("battery", "बैटरी"), "बैटरी", "Battery", Intent.ACTION_POWER_USAGE_SUMMARY),
        Page(listOf("location", "लोकेशन"), "लोकेशन", "Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page(listOf("developer", "डेवलपर"), "डेवलपर ऑप्शन", "Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Page(listOf("about phone", "build number", "बिल्ड नंबर", "फोन के बारे", "फ़ोन के बारे"), "फ़ोन के बारे में", "About phone", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page(listOf("storage", "स्टोरेज"), "स्टोरेज", "Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page(listOf("all apps", "सभी ऐप"), "सभी ऐप्स", "All apps", Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Page(listOf("app settings", "ऐप सेटिंग", "एप्लीकेशन"), "ऐप्स", "Apps", Settings.ACTION_APPLICATION_SETTINGS),
        Page(listOf("तारीख", "date and time"), "तारीख और समय", "Date and time", Settings.ACTION_DATE_SETTINGS),
        Page(listOf("language", "भाषा", "लैंग्वेज"), "भाषा", "Language", Settings.ACTION_LOCALE_SETTINGS),
        Page(listOf("keyboard", "कीबोर्ड"), "कीबोर्ड", "Keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS),
        Page(listOf("security", "सिक्योरिटी"), "सिक्योरिटी", "Security", Settings.ACTION_SECURITY_SETTINGS),
        Page(listOf("privacy", "प्राइवेसी"), "प्राइवेसी", "Privacy", Settings.ACTION_PRIVACY_SETTINGS),
        Page(listOf("accessibility", "एक्सेसिबिलिटी"), "एक्सेसिबिलिटी", "Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page(listOf("nfc", "एनएफसी"), "NFC", "NFC", Settings.ACTION_NFC_SETTINGS),
        Page(listOf("airplane", "एयरप्लेन", "फ्लाइट मोड"), "एयरप्लेन मोड", "Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Page(listOf("network", "नेटवर्क"), "नेटवर्क", "Network", Settings.ACTION_WIRELESS_SETTINGS),
        Page(listOf("cast", "कास्ट"), "कास्ट", "Cast", Settings.ACTION_CAST_SETTINGS),
        Page(listOf("account", "अकाउंट"), "अकाउंट", "Accounts", Settings.ACTION_SYNC_SETTINGS)
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

    private fun hasWord(t: String, w: String): Boolean =
        Regex("(^|[^a-z])$w([^a-z]|$)").containsMatchIn(t)

    private fun openPage(c: Context, ctx: Context, p: Page): String {
        val label = Lang.t(ctx, p.hi, p.en)
        return try {
            start(c, Intent(p.action))
            Lang.t(ctx, "खोल रहा हूँ: $label", "Opening: $label")
        } catch (e: Exception) {
            ok = false
            Lang.t(ctx, "यह सेटिंग इस फ़ोन में सीधे नहीं खुल पाई: $label", "Couldn't open this setting directly: $label")
        }
    }

    fun run(ctx: Context, raw: String): String {
        ok = true
        retry = false
        val t = raw.lowercase(Locale.getDefault()).trim()
        if (t.isEmpty()) return ""
        val svc = SuryaService.instance
        val c: Context = svc ?: ctx
        val page = pages.firstOrNull { p -> p.words.any { w -> t.contains(w) } }
        val needAcc = Lang.t(
            ctx,
            "पहले Accessibility में सूर्या को चालू करो",
            "First turn on Surya in Accessibility settings"
        )

        return when {
            hasWord(t, "unlock") || t.contains("अनलॉक") -> {
                ok = false
                Lang.t(ctx, "सुरक्षा की वजह से मैं फ़ोन अनलॉक नहीं कर सकता", "For safety, I can't unlock the phone")
            }

            hasWord(t, "lock") || t.contains("लॉक") ->
                if (svc == null) {
                    ok = false
                    needAcc
                } else if (svc.lock()) {
                    Lang.t(ctx, "फ़ोन लॉक कर रहा हूँ", "Locking the phone")
                } else {
                    ok = false
                    Lang.t(ctx, "लॉक नहीं हो पाया", "Couldn't lock the phone")
                }

            hasWord(t, "home") || t.contains("होम") ->
                if (svc == null) {
                    ok = false
                    needAcc
                } else {
                    svc.home()
                    Lang.t(ctx, "होम स्क्रीन", "Home screen")
                }

            hasWord(t, "back") || t.contains("पीछे") ->
                if (svc == null) {
                    ok = false
                    needAcc
                } else {
                    svc.back()
                    Lang.t(ctx, "पीछे गया", "Went back")
                }

            page != null -> openPage(c, ctx, page)

            t.contains("setting") || t.contains("सेटिंग") -> {
                start(c, Intent(Settings.ACTION_SETTINGS))
                Lang.t(ctx, "सेटिंग खोल रहा हूँ", "Opening Settings")
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
                if (name.isNotEmpty() && openApp(c, name)) {
                    Lang.t(ctx, "खोल रहा हूँ: $name", "Opening: $name")
                } else {
                    ok = false
                    retry = true
                    Lang.t(ctx, "ऐप नहीं मिला: $name", "App not found: $name")
                }
            }

            else -> {
                ok = false
                retry = true
                Lang.t(ctx, "समझ नहीं आया", "Didn't understand")
            }
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
