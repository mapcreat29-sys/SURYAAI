package com.surya.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var keyInput: EditText
    private lateinit var toggle: SwitchCompat
    private var ignoreToggle = false

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private val prefs by lazy { getSharedPreferences("surya", MODE_PRIVATE) }

    private fun tr(hi: String, en: String): String = Lang.t(this, hi, en)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasMic()) {
            prefs.edit().putBoolean("on", true).apply()
            startListening()
        } else {
            setToggle(false)
            prefs.edit().putBoolean("on", false).apply()
            output.text = tr("माइक की इजाज़त ज़रूरी है", "Microphone permission is required")
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this)
        title.text = tr("सूर्या AI", "Surya AI")
        title.textSize = 28f
        title.gravity = Gravity.CENTER

        val langLabel = TextView(this)
        langLabel.text = "भाषा / Language"
        langLabel.textSize = 15f
        langLabel.setPadding(0, pad, 0, 0)

        val langGroup = RadioGroup(this)
        langGroup.orientation = RadioGroup.HORIZONTAL
        val rbHi = RadioButton(this)
        rbHi.text = "हिंदी"
        rbHi.id = View.generateViewId()
        val rbEn = RadioButton(this)
        rbEn.text = "English"
        rbEn.id = View.generateViewId()
        langGroup.addView(rbHi)
        langGroup.addView(rbEn)
        if (Lang.isHindi(this)) rbHi.isChecked = true else rbEn.isChecked = true
        langGroup.setOnCheckedChangeListener { _, id ->
            val code = if (id == rbEn.id) "en" else "hi"
            val cur = if (Lang.isHindi(this)) "hi" else "en"
            if (code != cur) changeLanguage(code)
        }

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(0, pad, 0, pad)

        toggle = SwitchCompat(this)
        toggle.text = tr("सूर्या सुनना: ON / OFF", "Surya listening: ON / OFF")
        toggle.textSize = 18f
        toggle.isChecked = prefs.getBoolean("on", false)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!ignoreToggle) {
                if (checked) turnOn() else turnOff()
            }
        }

        val hint = TextView(this)
        hint.text = tr(
            "फ़ोन अनलॉक होने पर बोलो: हे सूर्या / RDX सूरज। ऐप कहेगा \"हाँ बोलिए\", फिर बोलो: यूट्यूब खोलो या कॉल मम्मी",
            "With the phone unlocked, say: Hey Surya / RDX Surya. The app says \"Yes, tell me\", then say: Open YouTube or Call Mom"
        )
        hint.textSize = 14f
        hint.setPadding(0, pad, 0, pad)

        val accBtn = Button(this)
        accBtn.text = tr(
            "फ़ोन कंट्रोल चालू करो (Accessibility)",
            "Turn on phone control (Accessibility)"
        )
        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val batBtn = Button(this)
        batBtn.text = tr(
            "बैटरी: सूर्या को Unrestricted करो",
            "Battery: set Surya to Unrestricted"
        )
        batBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        // ---------- AI (Gemini) की चाबी ----------
        val aiLabel = TextView(this)
        aiLabel.text = tr("AI (Gemini) की चाबी", "AI (Gemini) key")
        aiLabel.textSize = 15f
        aiLabel.setPadding(0, pad, 0, 0)

        keyInput = EditText(this)
        keyInput.hint = tr("यहाँ चाबी चिपकाएँ", "Paste the key here")
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyInput.setSingleLine(true)

        val saveKeyBtn = Button(this)
        saveKeyBtn.text = tr("चाबी सेव करो", "Save key")
        saveKeyBtn.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.length < 20) {
                output.text = tr(
                    "चाबी छोटी लग रही है, पूरी चाबी चिपकाएँ",
                    "The key looks too short, paste the full key"
                )
            } else {
                Gemini.saveKey(this, k)
                keyInput.setText("")
                output.text = tr("चाबी सेव हो गई ✅", "Key saved ✅")
                refresh()
            }
        }

        val testKeyBtn = Button(this)
        testKeyBtn.text = tr("AI जाँचो", "Test AI")
        testKeyBtn.setOnClickListener {
            output.text = tr("AI से पूछ रहा हूँ...", "Asking the AI...")
            val app = applicationContext
            thread {
                val r = Gemini.ask(app, "Say hello in one short sentence")
                runOnUiThread {
                    output.text = if (r.error == null) {
                        "AI ✅: " + r.text
                    } else {
                        "AI ⛔: " + Gemini.errorText(this, r) + "\n" + r.detail
                    }
                }
            }
        }

        val removeKeyBtn = Button(this)
        removeKeyBtn.text = tr("चाबी हटाओ", "Remove key")
        removeKeyBtn.setOnClickListener {
            Gemini.saveKey(this, "")
            output.text = tr("चाबी हटा दी गई", "Key removed")
            refresh()
        }

        input = EditText(this)
        input.hint = tr(
            "टेस्ट के लिए कमांड लिखो, जैसे: यूट्यूब खोलो",
            "Type a command to test, e.g. open YouTube"
        )

        val sendBtn = Button(this)
        sendBtn.text = tr("भेजो", "Send")
        sendBtn.setOnClickListener {
            output.text = SmartCommands.run(this, input.text.toString())
        }

        output = TextView(this)
        output.textSize = 16f
        output.setPadding(0, pad, 0, 0)

        root.addView(title)
        root.addView(langLabel)
        root.addView(langGroup)
        root.addView(status)
        root.addView(toggle)
        root.addView(hint)
        root.addView(accBtn)
        root.addView(batBtn)
        root.addView(aiLabel)
        root.addView(keyInput)
        root.addView(saveKeyBtn)
        root.addView(testKeyBtn)
        root.addView(removeKeyBtn)
        root.addView(input)
        root.addView(sendBtn)
        root.addView(output)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("on", false) && hasMic() && !ListenService.running) {
            startListening()
        }
        refresh()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 1000)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun changeLanguage(code: String) {
        Lang.set(this, code)
        val app = applicationContext
        if (ListenService.running) {
            stopService(Intent(app, ListenService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                val on = app.getSharedPreferences("surya", MODE_PRIVATE).getBoolean("on", false)
                if (on) {
                    ContextCompat.startForegroundService(app, Intent(app, ListenService::class.java))
                }
            }, 900)
        }
        recreate()
    }

    private fun hasMic(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun setToggle(v: Boolean) {
        ignoreToggle = true
        toggle.isChecked = v
        ignoreToggle = false
    }

    private fun turnOn() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            prefs.edit().putBoolean("on", true).apply()
            startListening()
            refresh()
        } else {
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun turnOff() {
        prefs.edit().putBoolean("on", false).apply()
        stopService(Intent(this, ListenService::class.java))
        SuryaService.instance?.hideBox()
        refresh()
    }

    private fun startListening() {
        if (!ListenService.running) {
            ContextCompat.startForegroundService(this, Intent(this, ListenService::class.java))
        }
    }

    private fun refresh() {
        val on = prefs.getBoolean("on", false)
        val acc = SuryaService.instance != null
        val onTxt = if (on) tr("चालू ✅", "On ✅") else tr("बंद ⛔", "Off ⛔")
        val accTxt = if (acc) tr("चालू ✅", "On ✅")
        else tr("बंद ⛔ (नीचे का बटन दबाकर चालू करो)", "Off ⛔ (tap the button below to turn it on)")
        val aiTxt = if (Gemini.hasKey(this)) {
            tr("चाबी सेव है ✅ (…", "Key saved ✅ (…") + Gemini.key(this).takeLast(4) + ")"
        } else {
            tr("चाबी नहीं है ⛔", "No key ⛔")
        }
        var text = tr(
            "सुनना: $onTxt\nफ़ोन कंट्रोल: $accTxt\nAI: $aiTxt",
            "Listening: $onTxt\nPhone control: $accTxt\nAI: $aiTxt"
        )
        val p = ListenService.progress
        if (p.isNotEmpty()) text += "\n\n$p"
        val hs = ListenService.lastHeard
        if (hs.isNotEmpty()) text += "\n\n" + tr("आख़िरी सुना:", "Last heard:") + "\n" + hs
        status.text = text
    }
}
