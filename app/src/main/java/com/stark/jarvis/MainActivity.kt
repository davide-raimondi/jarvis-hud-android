package com.stark.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var autoListenPending = false
    private var webLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableWakeOnLockScreen()
        requestRequiredPermissions()
        tts = TextToSpeech(this, this)
        setupWebView()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        JarvisWakeService.isPaused = true
    }

    override fun onPause() {
        super.onPause()
        stopNativeListening()
        JarvisWakeService.isPaused = false
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun enableWakeOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        setContentView(webView)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.databaseEnabled = true
        webView.addJavascriptInterface(JarvisNativeBridge(), "JarvisAndroid")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webLoaded = true
                if (autoListenPending) {
                    autoListenPending = false
                    triggerAutoListenInWeb()
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val fromAssist = intent.action == Intent.ACTION_ASSIST
        val fromWakeWord = intent.getBooleanExtra("AUTO_LISTEN", false)
        if (fromAssist || fromWakeWord) {
            if (webLoaded) triggerAutoListenInWeb() else autoListenPending = true
        }
    }

    private fun triggerAutoListenInWeb() {
        webView.postDelayed({ evalJs("window.jarvisOnWake && window.jarvisOnWake();") }, 550)
    }

    private fun evalJs(code: String) {
        runOnUiThread { webView.evaluateJavascript(code, null) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ITALIAN
            tts?.setPitch(0.92f)
            tts?.setSpeechRate(1.05f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { evalJs("window.jarvisOnSpeechStart && window.jarvisOnSpeechStart();") }
                override fun onDone(utteranceId: String?) { evalJs("window.jarvisOnSpeechEnd && window.jarvisOnSpeechEnd();") }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { evalJs("window.jarvisOnSpeechEnd && window.jarvisOnSpeechEnd();") }
            })
        }
    }

    private fun startNativeListening() {
        runOnUiThread {
            stopNativeListening()
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                evalJs("window.jarvisOnVoiceError && window.jarvisOnVoiceError('Riconoscimento non disponibile');")
                return@runOnUiThread
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { evalJs("window.jarvisOnListenReady && window.jarvisOnListenReady();") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { evalJs("window.jarvisOnVoiceLevel && window.jarvisOnVoiceLevel($rmsdB);") }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) { evalJs("window.jarvisOnVoiceError && window.jarvisOnVoiceError('Codice $error');") }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                        if (text.isNotEmpty()) {
                            evalJs("window.jarvisOnVoiceResult && window.jarvisOnVoiceResult(${JSONObject.quote(text)});")
                        } else {
                            evalJs("window.jarvisOnVoiceError && window.jarvisOnVoiceError('Vuoto');")
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrEmpty()) {
                            evalJs("window.jarvisOnVoicePartial && window.jarvisOnVoicePartial(${JSONObject.quote(partial)});")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun stopNativeListening() {
        runOnUiThread {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    inner class JarvisNativeBridge {
        @JavascriptInterface fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utt") }
        @JavascriptInterface fun stopSpeaking() { tts?.stop() }
        @JavascriptInterface fun startListening() { startNativeListening() }
        @JavascriptInterface fun stopListening() { stopNativeListening() }

        @JavascriptInterface
        fun setFlashlight(on: Boolean): Boolean {
            return try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: cm.cameraIdList.firstOrNull() ?: return false
                cm.setTorchMode(cameraId, on)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun getBatteryLevel(): Int {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }

        @JavascriptInterface
        fun setAlarm(hour: Int, minute: Int, message: String): Boolean {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface
        fun openApp(appName: String): Boolean {
            val query = appName.lowercase(Locale.ITALIAN).trim()
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val match = apps.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase(Locale.ITALIAN)
                label.contains(query) || info.activityInfo.packageName.lowercase().contains(query)
            } ?: return false
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            return true
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun setWakeServiceEnabled(enabled: Boolean): Boolean {
            return try {
                val svcIntent = Intent(this@MainActivity, JarvisWakeService::class.java)
                if (enabled) ContextCompat.startForegroundService(this@MainActivity, svcIntent) else stopService(svcIntent)
                true
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun isWakeServiceRunning(): Boolean = JarvisWakeService.isRunning

        @JavascriptInterface
        fun openAssistantSettings() {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
