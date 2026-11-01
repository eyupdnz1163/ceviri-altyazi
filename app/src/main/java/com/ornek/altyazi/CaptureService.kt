package com.ornek.altyazi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.OutputStream

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SPEECH_TAG = "speech_tag"
        const val EXTRA_ML_CODE = "ml_code"
        private const val CHANNEL_ID = "altyazi"
        private const val SAMPLE_RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var sink: OutputStream? = null

    private var recognizer: SpeechRecognizer? = null
    private var speechTag = "en-US"
    private var translator: Translator? = null
    private var needsTranslation = true
    private var modelReady = false

    private var windowManager: WindowManager? = null
    private var captionView: TextView? = null
    private var lastRequested = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent == null || running) return START_NOT_STICKY

        startAsForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        speechTag = intent.getStringExtra(EXTRA_SPEECH_TAG) ?: "en-US"
        val mlCode = intent.getStringExtra(EXTRA_ML_CODE) ?: TranslateLanguage.ENGLISH
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)

        setupOverlay()
        setupTranslator(mlCode)
        startAudioCapture()
        startRecognizing()
        running = true
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Altyazı", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Çeviri Altyazı çalışıyor")
            .setContentText("Durdurmak için uygulamayı aç")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            text = "Dinleniyor…"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 140
        }
        windowManager?.addView(tv, params)
        captionView = tv
    }

    private fun showCaption(text: String) {
        main.post { captionView?.text = text }
    }

    private fun setupTranslator(sourceCode: String) {
        needsTranslation = sourceCode != TranslateLanguage.TURKISH
        if (!needsTranslation) { modelReady = true; return }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options).also { t ->
            showCaption("Çeviri modeli indiriliyor…")
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { modelReady = true; showCaption("Dinleniyor…") }
                .addOnFailureListener { showCaption("Çeviri modeli indirilemedi. İnterneti kontrol et.") }
        }
    }

    private fun translateAndShow(text: String) {
        if (text.isBlank()) return
        if (!needsTranslation) { showCaption(text); return }
        if (!modelReady) return
        lastRequested = text
        translator?.translate(text)?.addOnSuccessListener { tr ->
            if (text == lastRequested) showCaption(tr)
        }
    }

    private fun startAudioCapture() {
        val proj = projection ?: return
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Exception) {
            showCaption("Ses yakalanamadı: ${e.message}")
            return
        }
        audioRecord = rec
        rec.startRecording()
        running = true

        captureThread = Thread {
            val buf = ByteArray(3200)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    try {
                        sink?.write(buf, 0, n)
                    } catch (e: Exception) {
                        sink = null
                    }
                }
            }
        }.also { it.start() }
    }

    private fun startRecognizing() {
        if (!running && audioRecord == null) return
        if (recognizer == null) {
            recognizer = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            recognizer?.setRecognitionListener(listener)
        }

        val pipe = ParcelFileDescriptor.createPipe()
        sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        }
        recognizer?.startListening(intent)
    }

    private fun restartSoon() {
        if (!running) return
        main.postDelayed({ if (running) startRecognizing() }, 150)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) translateAndShow(t)
        }

        override fun onResults(results: android.os.Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) translateAndShow(t)
            restartSoon()
        }

        override fun onError(error: Int) {
            restartSoon()
        }

        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { sink?.close() } catch (_: Exception) {}
        try { translator?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        captionView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
