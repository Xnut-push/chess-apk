package com.chessanalyzer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("chess_prefs", MODE_PRIVATE)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val eloSlider = findViewById<SeekBar>(R.id.eloSlider)
        val eloLabel = findViewById<TextView>(R.id.eloLabel)
        val sideWhite = findViewById<RadioButton>(R.id.sideWhite)
        val sideBlack = findViewById<RadioButton>(R.id.sideBlack)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val statusText = findViewById<TextView>(R.id.statusText)

        // Load saved prefs
        apiKeyInput.setText(prefs.getString("api_key", ""))
        val savedElo = prefs.getInt("elo", 1500)
        eloSlider.progress = ((savedElo - 600) / 28).coerceIn(0, 100)
        eloLabel.text = "ELO: $savedElo"
        sideWhite.isChecked = prefs.getString("side", "white") == "white"
        sideBlack.isChecked = prefs.getString("side", "white") == "black"

        eloSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                val elo = 600 + (p * 28)
                eloLabel.text = "ELO: $elo — ${getEloRank(elo)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        startBtn.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            if (apiKey.isEmpty()) {
                statusText.text = "⚠ Ingresa tu API key de Anthropic"
                return@setOnClickListener
            }

            val elo = 600 + (eloSlider.progress * 28)
            val side = if (sideWhite.isChecked) "white" else "black"

            prefs.edit()
                .putString("api_key", apiKey)
                .putInt("elo", elo)
                .putString("side", side)
                .apply()

            if (!Settings.canDrawOverlays(this)) {
                statusText.text = "⚠ Activa el permiso de 'Mostrar sobre otras apps'"
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                launchOverlay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val statusText = findViewById<TextView>(R.id.statusText)
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "✅ Permiso activo — listo para analizar"
        } else {
            statusText.text = "⚠ Se necesita permiso de overlay"
        }
    }

    private fun launchOverlay() {
        val intent = Intent(this, CaptureRequestActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun getEloRank(elo: Int): String = when {
        elo < 800  -> "Principiante"
        elo < 1200 -> "Aficionado"
        elo < 1500 -> "Intermedio"
        elo < 1800 -> "Avanzado"
        elo < 2200 -> "Experto"
        elo < 2500 -> "Maestro"
        elo < 2800 -> "Gran Maestro"
        else       -> "Máximo Stockfish"
    }
}
