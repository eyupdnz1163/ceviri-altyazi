package com.ornek.altyazi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var spinner: Spinner
    private lateinit var status: TextView

    private val reqPermissions = 1
    private val reqOverlay = 2
    private val reqProjection = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        root.addView(TextView(this).apply {
            text = "Çeviri Altyazı"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Videodaki konuşmayı yakalar, Türkçe'ye çevirir ve ekranın altına altyazı olarak yazar."
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 32)
        })
        root.addView(TextView(this).apply {
            text = "Videodaki konuşma dili:"
            setTextColor(Color.WHITE)
        })

        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Languages.sources.map { it.name }
        )
        root.addView(spinner)

        val start = Button(this).apply { text = "Altyazıyı başlat" }
        val stop = Button(this).apply { text = "Durdur" }
        root.addView(start)
        root.addView(stop)

        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 0)
            gravity = Gravity.START
        }
        root.addView(status)
        setContentView(root)

        start.setOnClickListener { beginFlow() }
        stop.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            status.text = "Durduruldu."
        }
    }

    private fun beginFlow() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), reqPermissions)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Diğer uygulamaların üzerinde gösterme iznini aç", Toast.LENGTH_LONG).show()
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                reqOverlay
            )
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == reqPermissions) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                beginFlow()
            } else {
                status.text = "Ses izni verilmedi, altyazı çalışamaz."
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            reqOverlay -> beginFlow()
            reqProjection -> {
                if (resultCode == RESULT_OK && data != null) {
                    val lang = Languages.sources[spinner.selectedItemPosition]
                    val svc = Intent(this, CaptureService::class.java).apply {
                        putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(CaptureService.EXTRA_DATA, data)
                        putExtra(CaptureService.EXTRA_SPEECH_TAG, lang.speechTag)
                        putExtra(CaptureService.EXTRA_ML_CODE, lang.mlKitCode)
                    }
                    startForegroundService(svc)
                    status.text = "Başladı. Şimdi videoyu aç; altyazı ekranın altında görünecek.\n" +
                        "İlk seferde çeviri modeli indirilir (internet gerekir)."
                } else {
                    status.text = "Ses yakalama izni verilmedi."
                }
            }
        }
    }
}
