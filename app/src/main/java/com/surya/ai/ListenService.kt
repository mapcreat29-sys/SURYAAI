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

    // वेक वर्ड: पहले "हे / hey / RDX", फिर सूर्य से मिलता कोई भी रूप
    private val heyWords = setOf(
        "hey", "hay", "hi", "he", "hei", "hai", "hy",
        "हे", "हेय", "है", "हैय", "हाय", "हेई", "हेइ"
    )
    private val rdxWords = setOf(
        "rdx", "rdex", "आरडीएक्स", "आरडीक्स", "अरडीएक्स", "आरडीऐक्स", "आरडेक्स"
    )

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

    // ---------------- वेक फ़्रेज़ पहचानना ----------------

    private fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.getDefault())) {
            val m: Char? = when (ch) {
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

    // सूर्य, सूर्या, सूरज, सूरिया, सुर्य, शौर्य, surya, suraj, sooriya ...
    private fun isSuryaWord(w: String): Boolean {
        val sk = skeleton(w)
        if (sk == "sry" || sk == "srj") return true
        return sk.length in 3..4 && sk.startsWith("sr") && (sk.contains('y') || sk.contains('j'))
    }

    private fun suryaLen(words: List<String>, j: Int): Int {
        if (j >= words.size) return 0
        if (isSuryaWord(words[j])) return 1
        if (j + 1 < words.size && isSuryaWord(words[j] + words[j + 1])) return 2
        return 0
    }

    // वेक फ़्रेज़ मिले तो उसके बाद वाले शब्द का नंबर लौटाता है, नहीं मिले तो -1
    private fun findWake(text: String): Int {
        val words = splitWords(text)
        for (i in words.indices) {
            if (words[i] in heyWords) {
                val n = suryaLen(words, i + 1)
                if (n > 0) return i + 1 + n
            }
            for (len in 1..3) {
                if (i + len > words.size) break
                val joined = words.subList(i, i + len).joinToString("")
                if (joined in rdxWords) {
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

    private fun voskLoop(list: List<Model>) {
        var rec: AudioRecord? = null
        var recs: List<Recognizer> = emptyList()
        var heard: String? = null
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

            recs = list.map { Recognizer(it, SAMPLE_RATE.toFloat()) }
            val lastPartial = Array(recs.size) { "" }
            val changedAt = LongArray(recs.size)

            rec.startRecording()

            val buf = ShortArray(2048)
            while (wantListening && heard == null) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val now = System.currentTimeMillis()
                for (i in recs.indices) {
                    val r = recs[i]
                    if (r.acceptWaveForm(buf, n)) {
                        val text = JSONObject(r.result).optString("text")
                        lastPartial[i] = ""
                        if (text.isNotBlank() && findWake(text) >= 0) {
                            heard = text
                            break
                        }
                    } else {
                        val p = JSONObject(r.partialResult).optString("partial")
                        if (p != lastPartial[i]) {
                            lastPartial[i] = p
                            changedAt[i] = now
                        } else if (p.isNotBlank() && findWake(p) >= 0 && now - changedAt[i] > 700) {
                            heard = p
                            break
                        }
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
            for (r in recs) {
                try {
                    r.close()
                } catch (e: Exception) {
                }
            }
            voskRunning = false
        }

        val h = heard
        if (h != null) {
            handler.post { onWake(h) }
        } else if (wantListening && !awaitingCommand) {
            handler.postDelayed(startRunnable, 2000)
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

    // ---------------- वेक फ़्रेज़ सुनने के बाद ----------------

    private fun onWake(text: String) {
        if (!wantListening) return
        val command = extractCommand(text)
        if (command.isEmpty()) {
            listenForCommand()
        } else {
            runCommand(command, allowRetry = true)
        }
    }

    private fun runCommand(command: String, allowRetry: Boolean) {
        handler.removeCallbacks(commandTimeout)
        val reply = try {
            SmartCommands.run(applicationContext, command)
        } catch (e: Exception) {
            Commands.retry = false
            tr("कुछ गड़बड़ हो गई", "Something went wrong")
        }
        if (allowRetry && Commands.retry) {
            // शायद कमांड ग़लत सुना गया - Android की आवाज़ पहचान से एक बार फिर सुनते हैं
            listenForCommand()
            return
        }
        if (reply.isNotEmpty()) say(reply, 2500) else SuryaService.instance?.hideBox()
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, 1500)
    }

    // ---------------- Android का SpeechRecognizer: सिर्फ़ एक बार, कमांड के लिए ----------------

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
