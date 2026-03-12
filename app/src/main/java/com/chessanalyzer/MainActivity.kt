package com.chessanalyzer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQ = 1001
    private val CAPTURE_REQ = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val eloLabel = findViewById<TextView>(R.id.eloLabel)
        val eloSlider = findViewById<SeekBar>(R.id.eloSlider)
        val sideWhite = findViewById<android.widget.RadioButton>(R.id.sideWhite)
        val sideBlack = findViewById<android.widget.RadioButton>(R.id.sideBlack)
        val apiKeyInput = findViewById<android.widget.EditText>(R.id.apiKeyInput)
        val statusText = findViewById<TextView>(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)

        val prefs = getSharedPreferences("chess_prefs", Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("api_key", ""))

        val eloValues = listOf(600,800,1000,1200,1500,1800,2000,2200,2500,2800,3000,3200,3400)
        val eloNames = listOf("Principiante","Novato","Básico","Intermedio bajo","Intermedio",
            "Avanzado","Experto","Maestro","Maestro Int.","Gran Maestro","Elite","Super Elite","Magnus")

        eloSlider.max = eloValues.size - 1
        eloSlider.progress = 4
        eloLabel.text = "ELO: ${eloValues[4]} — ${eloNames[4]}"

        eloSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                eloLabel.text = "ELO: ${eloValues[p]} — ${eloNames[p]}"
                prefs.edit().putInt("elo", eloValues[p]).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        startBtn.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            if (apiKey.isEmpty()) {
                statusText.text = "⚠ Ingresa tu API key"
                return@setOnClickListener
            }
            val side = if (sideWhite.isChecked) "white" else "black"
            prefs.edit()
                .putString("api_key", apiKey)
                .putString("side", side)
                .apply()

            if (!Settings.canDrawOverlays(this)) {
                statusText.text = "⚠ Activa permiso de overlay"
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    OVERLAY_PERMISSION_REQ
                )
            } else {
                requestScreenCapture()
            }
        }
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), CAPTURE_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQ -> {
                if (Settings.canDrawOverlays(this)) requestScreenCapture()
            }
            CAPTURE_REQ -> {
                if (resultCode == RESULT_OK && data != null) {
                    // Start foreground service first, then pass projection
                    val serviceIntent = Intent(this, OverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    startForegroundService(serviceIntent)
                    finish()
                }
            }
        }
    }
}
