package com.surya.ai

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import java.util.Locale

class ListenService : Service() {

    companion object {
        var running = false
        private const val CHANNEL = "surya_listen"
        private const val NOTIF_ID = 42
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wantListening = false
    private var awaitingCommand = false

    private val wakeA = Regex("(rdx|आर\\s*डी\\s*एक्स|आरडीएक्स)\\s*(surya|सूर्या|सूर्य|सुर्या)?")
    private val wakeB = Regex("(hey|हे)\\s*(surya|सूर्या|सूर्य|सुर्या)")

    private val startRunnable = Runnable { startRecognizer() }

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
            .setContentText("फ़ोन अनलॉक होने पर RDX सूर्या सुन रहा है")
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
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
        }
        SuryaService.instance?.hideBox()
        super.onDestroy()
    }

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

    private fun scheduleRestart(delay: Long) {
        if (!wantListening) return
        handler.removeCallbacks(startRunnable)
        handler.postDelayed(startRunnable, delay)
    }

    private fun say(msg: String, ms: Long = 0L) {
        val s = SuryaService.instance
        if (s != null) s.showBox(msg, ms)
        else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun startRecognizer() {
        if (!wantListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "इस फ़ोन में आवाज़ पहचानने की सुविधा नहीं है", Toast.LENGTH_LONG).show()
            return
        }
        destroyRecognizer()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (awaitingCommand) {
                awaitingCommand = false
                SuryaService.instance?.hideBox()
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wantListening = false
                return
            }
            val delay = if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) 300L else 2500L
            scheduleRestart(delay)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            handleResults(list ?: arrayListOf())
        }
    }

    private fun handleResults(list: List<String>) {
        var command: String? = null

        if (awaitingCommand) {
            awaitingCommand = false
            command = list.firstOrNull()?.lowercase(Locale.getDefault())?.trim()
        } else {
            for (s in list) {
                val t = s.lowercase(Locale.getDefault())
                if (wakeA.containsMatchIn(t) || wakeB.containsMatchIn(t)) {
                    command = t.replace(wakeA, " ").replace(wakeB, " ").trim()
                    break
                }
            }
        }

        if (command == null) {
            scheduleRestart(200)
            return
        }

        if (command.isEmpty()) {
            awaitingCommand = true
            say("सुन रहा हूँ... बोलिए")
            scheduleRestart(100)
            return
        }

        say("सुन रहा हूँ: $command")
        val reply = try {
            SmartCommands.run(applicationContext, command)
        } catch (e: Exception) {
            "कुछ गड़बड़ हो गई"
        }
        say(reply, 3000)
        scheduleRestart(1200)
    }
}
