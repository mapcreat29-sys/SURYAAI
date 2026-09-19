package com.surya.ai

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private val needAcc = "पहले नीचे वाला बटन दबाकर सूर्या को Accessibility में चालू करो"

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

    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spoken = list?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            input.setText(spoken)
            runCommand(spoken)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = "सूर्या AI"
        title.textSize = 28f
        title.gravity = Gravity.CENTER

        input = EditText(this)
        input.hint = "कमांड लिखो, जैसे: यूट्यूब खोलो"

        val sendBtn = Button(this)
        sendBtn.text = "भेजो"
        sendBtn.setOnClickListener { runCommand(input.text.toString()) }

        val micBtn = Button(this)
        micBtn.text = "🎤 बोलो"
        micBtn.setOnClickListener { listen() }

        val accBtn = Button(this)
        accBtn.text = "फ़ोन कंट्रोल चालू करो (Accessibility)"
        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        output = TextView(this)
        output.textSize = 16f
        output.setPadding(0, pad, 0, 0)

        root.addView(title)
        root.addView(input)
        root.addView(sendBtn)
        root.addView(micBtn)
        root.addView(accBtn)
        root.addView(output)
        setContentView(root)
    }

    private fun say(msg: String) {
        output.text = msg
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए...")
        try {
            voiceLauncher.launch(i)
        } catch (e: Exception) {
            say("इस फ़ोन में आवाज़ की सुविधा नहीं मिली")
        }
    }

    private fun runCommand(raw: String) {
        val t = raw.lowercase(Locale.getDefault()).trim()
        if (t.isEmpty()) return
        val svc = SuryaService.instance

        when {
            t.contains("unlock") || t.contains("अनलॉक") ->
                say("सुरक्षा की वजह से मैं फ़ोन अनलॉक नहीं कर सकता")

            t.contains("lock") || t.contains("लॉक") -> {
                if (svc == null) say(needAcc)
                else say(if (svc.lock()) "फ़ोन लॉक कर रहा हूँ" else "लॉक नहीं हो पाया")
            }

            t.contains("home") || t.contains("होम") -> {
                if (svc == null) say(needAcc) else { svc.home(); say("होम स्क्रीन") }
            }

            t.contains("back") || t.contains("पीछे") -> {
                if (svc == null) say(needAcc) else { svc.back(); say("पीछे गया") }
            }

            t.contains("wifi") || t.contains("वाईफाई") || t.contains("वाई-फाई") -> {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                say("वाई-फाई सेटिंग खोल रहा हूँ")
            }

            t.contains("bluetooth") || t.contains("ब्लूटूथ") -> {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                say("ब्लूटूथ सेटिंग खोल रहा हूँ")
            }

            t.contains("setting") || t.contains("सेटिंग") -> {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                say("सेटिंग खोल रहा हूँ")
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
                if (name.isNotEmpty() && openApp(name)) say("खोल रहा हूँ: $name")
                else say("ऐप नहीं मिला: $name")
            }

            else -> say("समझ नहीं आया। कोशिश करो: यूट्यूब खोलो, वाईफाई, होम, लॉक")
        }
    }

    private fun openApp(query: String): Boolean {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull {
            pm.getLaunchIntentForPackage(it.packageName) != null &&
                pm.getApplicationLabel(it).toString().lowercase().contains(query)
        } ?: return false
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }
}
