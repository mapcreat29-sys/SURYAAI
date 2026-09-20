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
        private const val MODEL_NAME = "vosk-model-small-hi-0.22"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val SAMPLE_RATE = 16000
        private const val DEBUG = true
    }

    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    @Volatile private var model: Model? = null
    @Volatile private var modelLoading = false
    @Volatile private var voskRunning = false
    private var voskThread: Thread? = null

    @Volatile private var wantListening = false
    private var awaitingCommand = false

    private val wakeA = Regex("(rdx|आर\\s*डी\\s*एक्स|आरडीएक्स)\\s*(surya|सूर्या|सूर्य|सुर्या|सुर्य)?")

    private val startRunnable = Runnable { startVosk() }

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
            .setContentText("फ़ोन अनलॉक होने पर \"हे सूर्य\" सुन रहा है")
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
            try {
                model?.close()
            } catch (e: Exception) {
            }
            model = null
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

    // ---------------- "सूर्य" जैसे शब्द पहचानना ----------------

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

    private fun suryaIndex(words: List<String>): Int {
        for (i in words.indices) {
            val sk = skeleton(words[i])
            if (sk == "sry" || sk == "srj") return i
        }
        return -1
    }

    private fun isWake(text: String): Boolean {
        val t = text.lowercase(Locale.getDefault())
        return suryaIndex(splitWords(t)) >= 0 || wakeA.containsMatchIn(t)
    }

    private fun extractCommand(text: String): String {
        val t = text.lowercase(Locale.getDefault()).trim()
        val words = splitWords(t)
        val idx = suryaIndex(words)
        if (idx >= 0) return words.drop(idx + 1).joinToString(" ").trim()
        return t.replace(wakeA, " ").trim()
    }

    // ---------------- Vosk: चुपचाप "हे सूर्य" सुनना ----------------

    private fun startVosk() {
        if (!wantListening || voskRunning || awaitingCommand) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            say("माइक की इजाज़त चाहिए", 3000)
            return
        }

        val m = model
        if (m == null) {
            loadModelAsync()
            return
        }

        voskRunning = true
        voskThread = thread(name = "surya-vosk") { voskLoop(m) }
    }

    private fun voskLoop(m: Model) {
        var rec: AudioRecord? = null
        var rc: Recognizer? = null
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

            rc = Recognizer(m, SAMPLE_RATE.toFloat())
            rec.startRecording()

            val buf = ShortArray(2048)
            var wakeAt = 0L
            while (wantListening) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                if (rc.acceptWaveForm(buf, n)) {
                    val text = JSONObject(rc.result).optString("text")
                    if (DEBUG && text.isNotBlank()) sayFromAnyThread("सुना: $text", 2500)
                    if (text.isNotBlank() && isWake(text)) {
                        heard = text
                        break
                    }
                    wakeAt = 0L
                } else {
                    val p = JSONObject(rc.partialResult).optString("partial")
                    if (p.isNotBlank() && isWake(p)) {
                        val now = System.currentTimeMillis()
                        if (wakeAt == 0L) wakeAt = now
                        if (now - wakeAt > 1200) {
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
            try {
                rc?.close()
            } catch (e: Exception) {
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

    // ---------------- भाषा-फ़ाइल (पहली बार डाउनलोड) ----------------

    private fun loadModelAsync() {
        if (modelLoading) return
        modelLoading = true
        thread(name = "surya-model") {
            try {
                val dir = File(filesDir, MODEL_NAME)
                val okFile = File(dir, ".ok")
                if (!okFile.exists()) {
                    sayFromAnyThread("पहली बार हिंदी भाषा-फ़ाइल (लगभग 42 MB) डाउनलोड हो रही है, इंटरनेट चालू रखें…")
                    dir.deleteRecursively()
                    val zip = File(filesDir, "$MODEL_NAME.zip")
                    val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.inputStream.use { input ->
                        FileOutputStream(zip).use { out -> input.copyTo(out) }
                    }
                    unzip(zip, filesDir)
                    zip.delete()
                    File(dir, ".ok").writeText("ok")
                }
                model = Model(dir.absolutePath)
                handler.post {
                    modelLoading = false
                    say("तैयार हूँ! बोलिए: हे सूर्य", 3000)
                    startVosk()
                }
            } catch (e: Exception) {
                modelLoading = false
                sayFromAnyThread("भाषा-फ़ाइल नहीं मिल पाई, इंटरनेट देखिए", 4000)
                handler.post {
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

    // ---------------- "हे सूर्य" सुनने के बाद ----------------

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
        say("सुन रहा हूँ: $command")
        val reply = try {
            SmartCommands.run(applicationContext, command)
        } catch (e: Exception) {
            "कुछ गड़बड़ हो गई"
        }
        if (allowRetry && (reply == "समझ नहीं आया" || reply.startsWith("ऐप नहीं मिला"))) {
            listenForCommand()
            return
        }
        say(reply, 3000)
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
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        recognizer = r
        r.startListening(i)
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
                say("माइक की इजाज़त चाहिए", 3000)
                return
            }
            say("सुनाई नहीं दिया", 2000)
            finishCommandListening(1200)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val command = list?.firstOrNull()?.lowercase(Locale.getDefault())?.trim()
            awaitingCommand = false
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
