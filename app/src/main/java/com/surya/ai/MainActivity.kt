
    package com.surya.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var toggle: SwitchCompat
    private var ignoreToggle = false

    private val prefs by lazy { getSharedPreferences("surya", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasMic()) {
            prefs.edit().putBoolean("on", true).apply()
            startListening()
        } else {
            setToggle(false)
            prefs.edit().putBoolean("on", false).apply()
            output.text = "माइक की इजाज़त ज़रूरी है"
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
        title.text = "सूर्या AI"
        title.textSize = 28f
        title.gravity = Gravity.CENTER

        status = TextView(this)
        status.textSize = 15f
        status.setPadding(0, pad, 0, pad)

        toggle = SwitchCompat(this)
        toggle.text = "सूर्या सुनना: ON / OFF"
        toggle.textSize = 18f
        toggle.isChecked = prefs.getBoolean("on", false)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!ignoreToggle) {
                if (checked) turnOn() else turnOff()
            }
        }

        val hint = TextView(this)
        hint.text = "फ़ोन अनलॉक होने पर बोलो: RDX सूर्या, यूट्यूब खोलो / हे सूर्या, कॉल मम्मी"
        hint.textSize = 14f
        hint.setPadding(0, pad, 0, pad)

        val accBtn = Button(this)
        accBtn.text = "फ़ोन कंट्रोल चालू करो (Accessibility)"
        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val batBtn = Button(this)
        batBtn.text = "बैटरी: सूर्या को Unrestricted करो"
        batBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        input = EditText(this)
        input.hint = "टेस्ट के लिए कमांड लिखो, जैसे: यूट्यूब खोलो"

        val sendBtn = Button(this)
        sendBtn.text = "भेजो"
        sendBtn.setOnClickListener {
            output.text = SmartCommands.run(this, input.text.toString())
        }

        output = TextView(this)
        output.textSize = 16f
        output.setPadding(0, pad, 0, 0)

        root.addView(title)
        root.addView(status)
        root.addView(toggle)
        root.addView(hint)
        root.addView(accBtn)
        root.addView(batBtn)
        root.addView(input)
        root.addView(sendBtn)
        root.addView(output)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("on", false) && hasMic() && !ListenService.running) {
            startListening()
        }
        refresh()
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
        val s1 = if (on) "चालू ✅" else "बंद ⛔"
        val s2 = if (acc) "चालू ✅" else "बंद ⛔ (नीचे का बटन दबाकर चालू करो)"
        status.text = "सुनना: $s1\nफ़ोन कंट्रोल: $s2"
    }
}
