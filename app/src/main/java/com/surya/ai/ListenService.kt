package com.surya.ai

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class ListenService : Service() {

    companion object {
        var running = false

        // भाषा-फ़ाइल डाउनलोड की प्रगति (ऐप की स्क्रीन पर दिखाने के लिए)
        @Volatile var progress = ""

        // आख़िरी सुनी हुई बातें (ऐप की स्क्रीन पर जाँच के लिए)
        @Volatile var lastHeard = ""

        private const val CHANNEL = "surya_listen"
        private const val NOTIF_ID = 42
        private const val HI_MODEL = "vosk-model-small-hi-0.22"
        private const val EN_MODEL = "vosk-model-small-en-us-0.15"
        private const val MODEL_BASE = "https://alphacephei.com/vosk/models/"
        private const val SAMPLE_RATE = 16000
    }

    private class ModelInfo(
        val name: String,
        val hiName: String,
        val enName: String,
        val approxMb: Double
    )

    private val modelInfos = listOf(
        ModelInfo(HI_MODEL, "हिंदी", "Hindi", 42.0),
        ModelInfo(EN_MODEL, "अंग्रेज़ी", "English", 40.0)
    )

    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    @Volatile private var models: List<Model> = emptyList()
    @Volatile private var modelLoading = false
    @Volatile private var voskRunning = false
    @Volatile private var lastProgressAt = 0L
    private var voskThread: Thread? = null

    @Volatile private var wantListening = false
    private var awaitingCommand = false

    private val heardLog = ArrayList<String>()

    // ऐप की अपनी आवाज़ (TTS)
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsBusy = false
    @Volatile private var ttsBusySince = 0L
    @Volatile private var ttsDoneAt = 0L

    private val startRunnable = Runnable { startVosk() }

    private val commandTimeout = Runnable {
        if (awaitingCommand) {
            SuryaService.instance?.hideBox()
            finishCommandListening(500)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseListening()
                Intent.ACTION_USER_PRESENT -> resumeListening()
            }
        }
    }

    private fun tr(hi: String, en: String): String = Lang.t(this, hi, en)

    override fun onCreate() {
        super.onCreate()
        running = true

        tts = TextToSpeech(this) { st ->
            ttsReady = (st == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        ttsBusy = false
                        ttsDoneAt = System.currentTimeMillis()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ttsBusy = false
                        ttsDoneAt = System.currentTimeMillis()
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        ttsBusy = false
                        ttsDoneAt = System.currentTimeMillis()
                    }
                })
            }
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Surya AI", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(null, -1)

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenReceiver, f)

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive && !km.isKeyguardLocked) resumeListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        wantListening = false
        awaitingCommand = false
        progress = ""
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
        }
        try {
            voskThread?.join(600)
        } catch (e: Exception) {
        }
        if (voskThread?.isAlive != true) {
            for (m in models) {
                try {
                    m.close()
                } catch (e: Exception) {
                }
            }
            models = emptyList()
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        SuryaService.instance?.hideBox()
        super.onDestroy()
    }

    // ---------------- नोटिफ़िकेशन ----------------

    private fun buildNotification(text: String?, pct: Int): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(tr("सूर्या AI चालू है", "Surya AI is on"))
            .setContentText(
                text ?: tr(
                    "फ़ोन अनलॉक होने पर \"हे सूर्य\" सुन रहा है",
                    "Listening for \"Hey Surya\" while the phone is unlocked"
                )
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
        if (pct >= 0) b.setProgress(100, pct, false)
        return b.build()
    }

    private fun updateNotification(text: String?, pct: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, pct))
        } catch (e: Exception) {
        }
    }

    // ---------------- चालू / बंद ----------------

    private fun resumeListening() {
        wantListening = true
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, 400)
    }

    private fun pauseListening() {
        wantListening = false
        awaitingCommand = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        SuryaService.instance?.hideBox()
    }

    private fun say(msg: String, ms: Long = 0L) {
        val s = SuryaService.instance
        if (s != null) s.showBox(msg, ms)
        else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun sayFromAnyThread(msg: String, ms: Long = 0L) {
        handler.post { say(msg, ms) }
    }

    // ---------------- ऐप की आवाज़ ----------------

    private fun speak(text: String) {
        if (text.isBlank() || !ttsReady) {
            ttsBusy = false
            return
        }
        try {
            val t = tts ?: run {
                ttsBusy = false
                return
            }
            val loc = if (Lang.isHindi(this)) Locale("hi", "IN") else Locale("en", "IN")
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.US)
            }
            ttsBusySince = System.currentTimeMillis()
            ttsBusy = true
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "surya")
        } catch (e: Exception) {
            ttsBusy = false
        }
    }

    private fun spokenFor(reply: String, ok: Boolean): String {
        if (reply.isBlank()) return ""
        if (!ok) {
            val failOpen = reply.startsWith("ऐप नहीं मिला") || reply.startsWith("App not found") ||
                reply.startsWith("यह सेटिंग") || reply.startsWith("Couldn't open")
            return if (failOpen) tr("नहीं खुला", "It did not open") else reply
        }
        return when {
            reply.startsWith("खोल रहा हूँ: ") -> reply.removePrefix("खोल रहा हूँ: ") + " को खोल दिया गया"
            reply.startsWith("Opening: ") -> reply.removePrefix("Opening: ") + " was opened"
            reply.startsWith("कॉल लगा रहा हूँ: ") -> reply.removePrefix("कॉल लगा रहा हूँ: ") + " को कॉल लगा दिया गया"
            reply.startsWith("Calling: ") -> "Calling " + reply.removePrefix("Calling: ")
            else -> reply.replace("खोल रहा हूँ", "खोल दिया गया")
        }
    }

    private fun logHeard(tag: String, text: String) {
        if (text.isBlank()) return
        synchronized(heardLog) {
            heardLog.add(0, "$tag: $text")
            while (heardLog.size > 6) heardLog.removeAt(heardLog.size - 1)
            lastHeard = heardLog.joinToString("\n")
        }
    }

    // ---------------- वेक फ़्रेज़ पहचानना ----------------

    // आवाज़ की बनावट: keepH = true हो तो "ह/h" भी गिना जाता है
    private fun phon(s: String, keepH: Boolean): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.getDefault())) {
            val m: Char? = when (ch) {
                'ह', 'h' -> if (keepH) 'h' else null
                'क', 'ख', 'च', 'छ', 'c', 'q', 'k' -> 'k'
                'ग', 'घ', 'g' -> 'g'
                'ज', 'झ', 'j', 'z' -> 'j'
                'ट', 'ठ', 'ड', 'ढ', 'त', 'थ', 'द', 'ध', 't', 'd' -> 't'
                'न', 'ण', 'ं', 'ँ', 'n' -> 'n'
                'प', 'फ', 'p', 'f' -> 'p'
                'ब', 'भ', 'b' -> 'b'
                'म', 'm' -> 'm'
                'य', 'y' -> 'y'
                'र', 'r' -> 'r'
                'ल', 'l' -> 'l'
                'व', 'w', 'v' -> 'v'
                'श', 'ष', 'स', 's' -> 's'
                else -> null
            }
            if (m != null && (sb.isEmpty() || sb.last() != m)) sb.append(m)
        }
        return sb.toString()
    }

    private fun splitWords(text: String): List<String> =
        text.lowercase(Locale.getDefault()).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    // सूर्य: पहले आपकी सूची, फिर आवाज़ से मिलान
    private fun isSuryaWord(w: String): Boolean {
        if (w in WakeWords.surya) return true
        val sk = phon(w, false)
        if (sk == "sry" || sk == "srj") return true
        return sk.length in 3..4 && sk.startsWith("sr") && (sk.contains('y') || sk.contains('j'))
    }

    private fun suryaLen(words: List<String>, j: Int): Int {
        for (len in 1..3) {
            if (j + len > words.size) break
            if (isSuryaWord(words.subList(j, j + len).joinToString(""))) return len
        }
        return 0
    }

    // हे: पहले आपकी सूची, फिर आवाज़ से मिलान (हे, है, हाय, hey, hay ...)
    private fun isHeyWord(w: String): Boolean {
        if (w in WakeWords.hey) return true
        val sk = phon(w, true)
        return sk == "h" || sk == "hy"
    }

    // तेज़ बोलने पर जुड़ा हुआ रूप, जैसे "हेसूर्या"
    private val mergedWake = Regex("^hy?sr[yj]h?$")

    private val rdxSk = Regex("^rt(ks?)?$")

    private fun isRdx(joined: String): Boolean =
        joined in WakeWords.rdx || rdxSk.matches(phon(joined, true))

    // वेक फ़्रेज़ मिले तो उसके बाद वाले शब्द का नंबर लौटाता है, नहीं मिले तो -1
    private fun findWake(text: String): Int {
        val words = splitWords(text)
        for (i in words.indices) {
            for (len in 1..3) {
                if (i + len > words.size) break
                val joined = words.subList(i, i + len).joinToString("")
                if (mergedWake.matches(phon(joined, true))) return i + len
            }
            if (isHeyWord(words[i])) {
                val n = suryaLen(words, i + 1)
                if (n > 0) return i + 1 + n
            }
            for (len in 1..3) {
                if (i + len > words.size) break
                val joined = words.subList(i, i + len).joinToString("")
                if (isRdx(joined)) {
                    val n = suryaLen(words, i + len)
                    if (n > 0) return i + len + n
                }
            }
        }
        return -1
    }

    private fun extractCommand(text: String): String {
        val words = splitWords(text)
        val idx = findWake(text)
        if (idx < 0) return ""
        return words.drop(idx).joinToString(" ").trim()
    }

    // ---------------- कमांड चुनने में मदद ----------------

    private val cmdWords = listOf(
        "open", "ओपन", "खोल", "call", "dial", "कॉल", "लगाओ", "lock", "लॉक",
        "home", "होम", "back", "पीछे", "setting", "सेटिंग", "wifi", "वाईफाई",
        "bluetooth", "ब्लूटूथ", "brightness", "ब्राइटनेस", "volume", "वॉल्यूम"
    )

    private fun cmdScore(t: String): Int = cmdWords.count { t.contains(it) }

    private fun bestCandidate(c: List<String>): String =
        c.maxByOrNull { cmdScore(it) } ?: ""

    // ---------------- Vosk: चुपचाप सुनना (हिंदी + अंग्रेज़ी) ----------------

    private fun startVosk() {
        if (!wantListening || voskRunning || awaitingCommand) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            say(tr("माइक की इजाज़त चाहिए", "Microphone permission is needed"), 3000)
            return
        }

        val list = models
        if (list.isEmpty()) {
            loadModelAsync()
            return
        }

        voskRunning = true
        voskThread = thread(name = "surya-vosk") { voskLoop(list) }
    }

    // Vosk के नतीजे से सारे संभावित वाक्य निकालना (n-best)
    private fun resultTexts(json: String): List<String> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("alternatives")
        if (arr != null) {
            val out = ArrayList<String>()
            for (k in 0 until arr.length()) {
                out.add(arr.getJSONObject(k).optString("text"))
            }
            return out
        }
        return listOf(o.optString("text"))
    }

    // वेक फ़्रेज़ सुनते ही ऐप कहता है "हाँ बोलिए"
    private fun onCaptureStart() {
        if (!wantListening) {
            ttsBusy = false
            return
        }
        say(tr("हाँ बोलिए", "Yes, go ahead"))
        speak(tr("हाँ बोलिए", "Yes, tell me"))
    }

    private fun voskLoop(list: List<Model>) {
        var rec: AudioRecord? = null
        var recs: List<Recognizer> = emptyList()
        var heardCommand: String? = null
        var timedOut = false

        fun newRecs(): List<Recognizer> = list.map { m ->
            val r0 = Recognizer(m, SAMPLE_RATE.toFloat())
            r0.setMaxAlternatives(4)
            r0
        }

        fun closeRecs() {
            for (r in recs) {
                try {
                    r.close()
                } catch (e: Exception) {
                }
            }
        }

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, SAMPLE_RATE)
            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("mic busy")

            recs = newRecs()
            val lastPartial = Array(recs.size) { "" }
            val changedAt = LongArray(recs.size)

            rec.startRecording()

            val buf = ShortArray(1024)
            var chunk = 0
            var capture = false
            var captureDeadline = 0L
            var gatherUntil = 0L
            var needReset = false
            val cands = ArrayList<String>()

            while (wantListening && heardCommand == null) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                chunk++
                val now = System.currentTimeMillis()

                // ऐप अपनी आवाज़ बोल रहा हो तब सुनना बंद (ताकि अपनी ही आवाज़ न सुन ले)
                val busy = (ttsBusy && now - ttsBusySince < 6000) || now - ttsDoneAt < 300
                if (busy) {
                    needReset = true
                    continue
                }
                if (needReset) {
                    closeRecs()
                    recs = newRecs()
                    for (i in lastPartial.indices) {
                        lastPartial[i] = ""
                        changedAt[i] = 0L
                    }
                    needReset = false
                    if (capture) {
                        captureDeadline = now + 7000
                        gatherUntil = 0L
                        cands.clear()
                    }
                }

                var wakeText: String? = null

                for (i in recs.indices) {
                    val r = recs[i]
                    if (r.acceptWaveForm(buf, n)) {
                        lastPartial[i] = ""
                        val texts = resultTexts(r.result)
                        logHeard(if (i == 0) "हि" else "En", texts.firstOrNull() ?: "")
                        if (!capture) {
                            val hit = texts.firstOrNull { it.isNotBlank() && findWake(it) >= 0 }
                            if (hit != null && wakeText == null) wakeText = hit
                        } else {
                            val t = texts.firstOrNull { it.isNotBlank() } ?: ""
                            val rest = if (findWake(t) >= 0) extractCommand(t) else t.trim()
                            if (rest.isNotBlank()) {
                                cands.add(rest)
                                if (gatherUntil == 0L) gatherUntil = now + 300
                            }
                        }
                    } else if (!capture && (chunk and 1) == 0) {
                        val p = JSONObject(r.partialResult).optString("partial")
                        if (p != lastPartial[i]) {
                            lastPartial[i] = p
                            changedAt[i] = now
                        } else if (p.isNotBlank() && findWake(p) >= 0 &&
                            now - changedAt[i] > 300 && wakeText == null
                        ) {
                            wakeText = p
                        }
                    }
                }

                val wt = wakeText
                if (!capture && wt != null) {
                    val cmd = extractCommand(wt)
                    if (cmd.isNotBlank()) {
                        heardCommand = cmd
                    } else { capture = true
                        captureDeadline = now + 9000
                        gatherUntil = 0L
                        cands.clear()
                        ttsBusy = true
                        ttsBusySince = now
                        handler.post { onCaptureStart() }
                    }
                } else if (capture) {
                    if (gatherUntil != 0L && now >= gatherUntil) {
                        heardCommand = bestCandidate(cands)
                    } else if (now > captureDeadline) {
                        timedOut = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            // माइक व्यस्त है (जैसे कॉल के दौरान) - थोड़ी देर बाद फिर कोशिश होगी
        } finally {
            try {
                rec?.stop()
            } catch (e: Exception) {
            }
            try {
                rec?.release()
            } catch (e: Exception) {
            }
            closeRecs()
            voskRunning = false
        }

        val cmd = heardCommand
        if (cmd != null) {
            handler.post { if (wantListening) runCommand(cmd, allowRetry = true) }
        } else if (wantListening && !awaitingCommand) {
            if (timedOut) handler.post { SuryaService.instance?.hideBox() }
            handler.postDelayed(startRunnable, if (timedOut) 300L else 2000L)
        }
    }
     // ---------------- भाषा-फ़ाइलें (पहली बार डाउनलोड, प्रगति के साथ) ----------------

    private fun isModelReady(name: String): Boolean = File(File(filesDir, name), ".ok").exists()

    private fun showStatus(text: String, pct: Int) {
        progress = text
        handler.post {
            updateNotification(text, pct)
            if (SuryaService.instance != null) say(text)
        }
    }

    private fun publishProgress(
        idx: Int,
        info: ModelInfo,
        doneBytes: Long,
        totalBytes: Long,
        force: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAt < 400) return
        lastProgressAt = now
        val totalMb = if (totalBytes > 0) totalBytes / 1048576.0 else info.approxMb
        val doneMb = doneBytes / 1048576.0
        val pct = ((doneMb / totalMb) * 100).toInt().coerceIn(0, 100)
        val d = String.format(Locale.US, "%.1f", doneMb)
        val t = String.format(Locale.US, "%.1f", totalMb)
        val text = tr(
            "भाषा-फ़ाइल डाउनलोड (${idx + 1}/2) — ${info.hiName}: $d MB / $t MB ($pct%)",
            "Downloading language file (${idx + 1}/2) — ${info.enName}: $d MB / $t MB ($pct%)"
        )
        showStatus(text, pct)
    }

    private fun prepareModel(idx: Int): Model {
        val info = modelInfos[idx]
        val dir = File(filesDir, info.name)
        if (!File(dir, ".ok").exists()) {
            dir.deleteRecursively()
            val zip = File(filesDir, "${info.name}.zip")
            val conn = URL("$MODEL_BASE${info.name}.zip").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        publishProgress(idx, info, done, total, false)
                    }
                }
            }
            publishProgress(idx, info, done, total, true)
            showStatus(
                tr(
                    "${info.hiName} फ़ाइल खोली जा रही है… (${idx + 1}/2)",
                    "Unpacking ${info.enName} file… (${idx + 1}/2)"
                ),
                100
            )
            unzip(zip, filesDir)
            zip.delete()
            File(dir, ".ok").writeText("ok")
        }
        return Model(dir.absolutePath)
    }

    private fun loadModelAsync() {
        if (modelLoading) return
        modelLoading = true
        thread(name = "surya-model") {
            val firstTime = modelInfos.any { !isModelReady(it.name) }
            try {
                val list = mutableListOf<Model>()
                list.add(prepareModel(0))
                try {
                    list.add(prepareModel(1))
                } catch (e: Exception) {
                    sayFromAnyThread(
                        tr(
                            "अंग्रेज़ी फ़ाइल नहीं मिल पाई, अभी सिर्फ़ हिंदी चलेगी",
                            "English file couldn't be downloaded; only Hindi will work for now"
                        ),
                        3500
                    )
                }
                models = list
                progress = ""
                handler.post {
                    modelLoading = false
                    updateNotification(null, -1)
                    if (firstTime) say(tr("तैयार हूँ! बोलिए: हे सूर्य", "Ready! Say: Hey Surya"), 3000)
                    else SuryaService.instance?.hideBox()
                    startVosk()
                }
            } catch (e: Exception) {
                progress = ""
                handler.post {
                    modelLoading = false
                    updateNotification(null, -1)
                    say(
                        tr(
                            "भाषा-फ़ाइल नहीं मिल पाई, इंटरनेट देखिए",
                            "Language file couldn't be downloaded. Check your internet"
                        ),
                        4000
                    )
                    if (wantListening) handler.postDelayed(startRunnable, 30000)
                }
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(destPath + File.separator)) {
                    throw SecurityException("bad zip entry")
                }
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zin.copyTo(it) }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
    }

    // ---------------- कमांड चलाना ----------------

    private fun restartVoskSoon() {
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, 600)
    }

    private fun showAndSpeak(reply: String, ok: Boolean) {
        if (reply.isNotEmpty()) {
            say(reply, 2500)
            speak(spokenFor(reply, ok))
        } else {
            SuryaService.instance?.hideBox()
        }
        restartVoskSoon()
    }

    private fun runCommand(command: String, allowRetry: Boolean) {
        handler.removeCallbacks(commandTimeout)
        var ok = true
        val reply = try {
            SmartCommands.run(applicationContext, command)
        } catch (e: Exception) {
            Commands.retry = false
            ok = false
            tr("कुछ गड़बड़ हो गई", "Something went wrong")
        }
        if (ok) ok = Commands.ok

        if (Commands.retry) {
            // फ़ोन के अपने कमांड से बात नहीं बनी
            if (Gemini.hasKey(this)) {
                askAi(command)
                return
            }
            if (allowRetry) {
                // Android की आवाज़ पहचान से एक बार फिर सुनते हैं
                listenForCommand()
                return
            }
        }
        showAndSpeak(reply, ok)
    }

    // ---------------- AI (Gemini) ----------------

    private fun askAi(command: String) {
        say(tr("सोच रहा हूँ...", "Thinking..."))
        val appCtx = applicationContext
        thread(name = "surya-ai") {
            val r = Gemini.ask(appCtx, command)
            handler.post { onAiResult(r) }
        }
    }

    private fun onAiResult(r: Gemini.Result) {
        if (r.error != null) {
            val msg = Gemini.errorText(this, r)
            say(msg, 4000)
            speak(msg)
            restartVoskSoon()
            return
        }
        if (r.type == "command" && r.text.isNotBlank()) {
            var ok = true
            val reply = try {
                SmartCommands.run(applicationContext, r.text)
            } catch (e: Exception) {
                ok = false
                tr("कुछ गड़बड़ हो गई", "Something went wrong")
            }
            if (ok) ok = Commands.ok
            showAndSpeak(reply, ok)
        } else {
            val t = r.text.ifBlank { tr("समझ नहीं आया", "Didn't understand") }
            say(t, 7000)
            speak(t)
            restartVoskSoon()
        }
    }

    // ---------------- Android का SpeechRecognizer: सिर्फ़ तब, जब AI न हो और कमांड न समझ आए ----------------

    private fun listenForCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(
                tr(
                    "इस फ़ोन में आवाज़ पहचानने की सुविधा नहीं है",
                    "Speech recognition isn't available on this phone"
                ),
                3000
            )
            handler.postDelayed(startRunnable, 1500)
            return
        }
        awaitingCommand = true
        say(tr("सुन रहा हूँ... बोलिए", "Listening... go ahead"))
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(commandListener)
        val hindi = Lang.isHindi(this)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hindi) "hi-IN" else "en-IN")
        i.putExtra(
            "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
            arrayOf(if (hindi) "en-IN" else "hi-IN")
        )
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        recognizer = r
        r.startListening(i)
        handler.removeCallbacks(commandTimeout)
        handler.postDelayed(commandTimeout, 12000)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        recognizer = null
    }

    private fun finishCommandListening(delay: Long) {
        awaitingCommand = false
        handler.removeCallbacks(commandTimeout)
        destroyRecognizer()
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, delay)
    }

    private val commandListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            SuryaService.instance?.hideBox()
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wantListening = false
                awaitingCommand = false
                handler.removeCallbacks(commandTimeout)
                say(tr("माइक की इजाज़त चाहिए", "Microphone permission is needed"), 3000)
                return
            }
            finishCommandListening(1200)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val command = list?.firstOrNull()?.lowercase(Locale.getDefault())?.trim()
            awaitingCommand = false
            handler.removeCallbacks(commandTimeout)
            destroyRecognizer()
            if (command.isNullOrEmpty()) {
                SuryaService.instance?.hideBox()
                handler.postDelayed(startRunnable, 800)
            } else {
                runCommand(command, allowRetry = false)
            }
        }
    }
}
