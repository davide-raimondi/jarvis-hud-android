package com.stark.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

class JarvisWakeService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_wake_channel"
        const val NOTIF_ID = 4201
        @Volatile var isPaused: Boolean = false
        @Volatile var isRunning: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        isRunning = true
        scheduleListen(800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "J.A.R.V.I.S. Ascolto Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("AUTO_LISTEN", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Online")
            .setContentText("In ascolto in background. Tocca o pronuncia 'Jarvis'.")
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun scheduleListen(delayMs: Long) {
        if (!isRunning) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            if (isPaused) scheduleListen(1500) else startWakeListen()
        }, delayMs)
    }

    private fun startWakeListen() {
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onError(error: Int) { scheduleListen(1200) }
                    override fun onPartialResults(partialResults: Bundle?) { checkHotword(partialResults) }
                    override fun onResults(results: Bundle?) { if (!checkHotword(results)) scheduleListen(700) }
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleListen(2000)
        }
    }

    private fun checkHotword(bundle: Bundle?): Boolean {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        val joined = list.joinToString(" ").lowercase(Locale.ITALIAN)
        if (joined.contains("jarvis") || joined.contains("giarvis")) {
            isPaused = true
            recognizer?.cancel()
            val launch = Intent(this, MainActivity::class.java).apply {
                putExtra("AUTO_LISTEN", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
            scheduleListen(3500)
            return true
        }
        return false
    }
}
