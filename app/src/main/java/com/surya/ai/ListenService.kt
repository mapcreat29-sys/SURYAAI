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
        private const val CHANNEL = "surya_listen"
        private const val NOTIF_ID = 42
        private const val HI_MODEL = "vosk-model-small-hi-0.22"
        private const val EN_MODEL = "vosk-model-small-en-us-0.15"
        private const val MODEL_BASE = "https://alphacephei.com/vosk/models/"
        private const val SAMPLE_RATE = 16000
    }

    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    @Volatile private var models: List<Model> = emptyList()
    @Volatile private var modelLoading = false
    @Volatile private var voskRunning = false
    private var voskThread: Thread? = null

    @Volatile private var wantListening = false
    private var awaitingCommand = false

    private val heyWords = setOf("hey", "hay", "hi", "he", "हे", "हेय", "है", "हैय")
    private val rdxWords = setOf("rdx", "rdex", "आरडीएक्स", "आरडीक्स", "अरडीएक्स")

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

    override fun onCreate() {
        super.onCreate()
        running = true

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Surya AI", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("सूर्या AI चालू है")
            .setContentText("फ़ोन अनलॉक होने पर \"हे सूर्य\" / \"Hey Surya\" सुन रहा है")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

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

    // ---------------- वेक फ़्रेज़ पहचानना: "हे/hey/RDX" + "सूर्य/सूरज/surya" ----------------

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

    private fun isSuryaWord(w: String): Boolean {
        val sk = skeleton(w)
        return sk == "sry" || sk == "srj"
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
            say("माइक की इजाज़त चाहिए", 3000)
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
                        } else if (p.isNotBlank() && findWake(p) >= 0 && now - changedAt[i] > 900) {
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

    // ---------------- भाषा-फ़ाइलें (पहली बार डाउनलोड) ----------------

    private fun isModelReady(name: String): Boolean = File(File(filesDir, name), ".ok").exists()

    private fun prepareModel(name: String): Model {
        val dir = File(filesDir, name)
        if (!File(dir, ".ok").exists()) {
            dir.deleteRecursively()
            val zip = File(filesDir, "$name.zip")
            val conn = URL("$MODEL_BASE$name.zip").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.inputStream.use { input ->
                FileOutputStream(zip).use { out -> input.copyTo(out) }
            }
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
            val firstTime = !isModelReady(HI_MODEL) || !isModelReady(EN_MODEL)
            try {
                if (firstTime) {
                    sayFromAnyThread("पहली बार भाषा-फ़ाइलें (हिंदी + अंग्रेज़ी, लगभग 80 MB) डाउनलोड हो रही हैं, इंटरनेट चालू रखें…")
                }
                val list = mutableListOf<Model>()
                list.add(prepareModel(HI_MODEL))
                try {
                    list.add(prepareModel(EN_MODEL))
                } catch (e: Exception) {
                    sayFromAnyThread("अंग्रेज़ी फ़ाइल नहीं मिल पाई, अभी सिर्फ़ हिंदी चलेगी", 3000)
                }
                models = list
                handler.post {
                    modelLoading = false
                    if (firstTime) say("तैयार हूँ! बोलिए: हे सूर्य / Hey Surya", 3000)
                    startVosk()
                }
            } catch (e: Exception) {
                handler.post {
                    modelLoading = false
                    say("भाषा-फ़ाइल नहीं मिल पाई, इंटरनेट देखिए", 4000)
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
            "कुछ गड़बड़ हो गई"
        }
        if (allowRetry && (reply == "समझ नहीं आया" || reply.startsWith("ऐप नहीं मिला"))) {
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
            say("इस फ़ोन में आवाज़ पहचानने की सुविधा नहीं है", 3000)
            handler.postDelayed(startRunnable, 1500)
            return
        }
        awaitingCommand = true
        say("सुन रहा हूँ... बोलिए")
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(commandListener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN"))
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
                say("माइक की इजाज़त चाहिए", 3000)
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
