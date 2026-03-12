package com.chessanalyzer

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.Square

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var metrics: DisplayMetrics
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Views
    private var fabView: View? = null
    private var selectorView: View? = null
    private var arrowView: ArrowOverlayView? = null
    private var statusPanel: View? = null

    // Region
    private var regionRect = Rect()
    private var regionConfirmed = false

    // Drag state for FAB
    private var fabX = 100f; private var fabY = 300f
    private var dX = 0f; private var dY = 0f

    // Selector drag handles
    private var selLeft = 100; private var selTop = 200
    private var selRight = 600; private var selBottom = 700
    private var draggingHandle = -1

    private val handler = Handler(Looper.getMainLooper())
    private var analyzing = false
    private var currentFen = ""
    private var bestMoves = listOf<String>()

    companion object {
        const val CHANNEL_ID = "chess_overlay"
        const val NOTIF_ID = 1
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            setupImageReader()
        }

        showFab()
        return START_STICKY
    }

    // ─── FAB (floating button) ───────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showFab() {
        val params = WindowManager.LayoutParams(
            120, 120,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = fabX.toInt(); y = fabY.toInt() }

        val fab = LayoutInflater.from(this).inflate(R.layout.fab_button, null)
        fabView = fab

        fab.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = params.x - e.rawX; dY = params.y - e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX + dX).toInt()
                    params.y = (e.rawY + dY).toInt()
                    fabX = params.x.toFloat(); fabY = params.y.toFloat()
                    wm.updateViewLayout(fab, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (Math.abs(e.rawX + dX - params.x) < 5) {
                        if (regionConfirmed) triggerAnalysis() else showSelector()
                    }
                }
            }
            true
        }
        wm.addView(fab, params)
    }

    // ─── REGION SELECTOR ────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showSelector() {
        val params = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        val sel = SelectorView(this) { left, top, right, bottom ->
            selLeft = left; selTop = top; selRight = right; selBottom = bottom
        }
        selectorView = sel

        sel.setOnTouchListener { _, _ -> false }

        // Control buttons row
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 80 }

        val btnRow = LayoutInflater.from(this).inflate(R.layout.selector_controls, null)
        btnRow.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            regionRect = Rect(selLeft, selTop, selRight, selBottom)
            regionConfirmed = true
            removeSelectorViews(sel, btnRow)
            showArrowOverlay()
            showStatusPanel()
            triggerAnalysis()
        }
        btnRow.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            removeSelectorViews(sel, btnRow)
        }

        wm.addView(sel, params)
        wm.addView(btnRow, btnParams)
    }

    private fun removeSelectorViews(vararg views: View) {
        views.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
        selectorView = null
    }

    // ─── ARROW OVERLAY ──────────────────────────────────────────────
    private fun showArrowOverlay() {
        val params = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val av = ArrowOverlayView(this)
        arrowView = av
        wm.addView(av, params)
    }

    // ─── STATUS PANEL ───────────────────────────────────────────────
    private fun showStatusPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 8; y = 80 }

        val panel = LayoutInflater.from(this).inflate(R.layout.status_panel, null)
        statusPanel = panel

        panel.findViewById<ImageButton>(R.id.btnEdit).setOnClickListener {
            regionConfirmed = false
            removeArrowOverlay()
            try { wm.removeView(panel) } catch (_: Exception) {}
            showSelector()
        }
        panel.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }

        wm.addView(panel, params)
    }

    private fun removeArrowOverlay() {
        arrowView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        arrowView = null
    }

    // ─── SCREEN CAPTURE ─────────────────────────────────────────────
    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private fun captureRegion(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels
            val fullBmp = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / pixelStride,
                metrics.heightPixels, Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(
                fullBmp,
                regionRect.left, regionRect.top,
                regionRect.width(), regionRect.height()
            )
            fullBmp.recycle()
            cropped
        } finally {
            image.close()
        }
    }

    // ─── ANALYSIS ───────────────────────────────────────────────────
    private fun triggerAnalysis() {
        if (analyzing) return
        analyzing = true
        updateStatus("🔍 Analizando…")

        Thread {
            try {
                val bmp = captureRegion()
                if (bmp == null) { handler.post { updateStatus("❌ Sin captura"); analyzing = false }; return@Thread }

                val fen = sendToClaudeVision(bmp)
                bmp.recycle()

                if (fen.isNullOrEmpty()) { handler.post { updateStatus("❌ No detecté tablero"); analyzing = false }; return@Thread }

                currentFen = fen
                val moves = analyzeWithStockfish(fen)
                bestMoves = moves

                handler.post {
                    drawArrows(moves)
                    updateStatus("✅ ${moves.firstOrNull() ?: "Sin jugadas"}")
                    analyzing = false
                }
            } catch (e: Exception) {
                handler.post { updateStatus("❌ ${e.message}"); analyzing = false }
            }
        }.start()
    }

    private fun sendToClaudeVision(bmp: Bitmap): String? {
        val prefs = getSharedPreferences("chess_prefs", MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: return null
        val side = prefs.getString("side", "white") ?: "white"

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)

        val prompt = """You are a chess position detector. 
Look at this chess board image and return ONLY the FEN notation of the current position.
The player to move is $side.
Return ONLY the FEN string, nothing else. Example: rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"""

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 100)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", b64)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = client.newCall(request).execute()
        val json = JSONObject(resp.body!!.string())
        return json.getJSONArray("content").getJSONObject(0).getString("text").trim()
    }

    private fun analyzeWithStockfish(fen: String): List<String> {
        return try {
            val board = Board()
            board.loadFromFen(fen)
            val moves = board.legalMoves()
            // Simple eval: return top 3 moves in SAN notation
            moves.take(3).map { move ->
                "${move.from.value().lowercase()}${move.to.value().lowercase()}"
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun drawArrows(moves: List<String>) {
        arrowView?.setMoves(moves, regionRect)
        arrowView?.invalidate()
    }

    private fun updateStatus(text: String) {
        statusPanel?.findViewById<TextView>(R.id.statusText)?.text = text
    }

    // ─── CLEANUP ─────────────────────────────────────────────────────
    override fun onDestroy() {
        super.onDestroy()
        listOf(fabView, selectorView, arrowView, statusPanel).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Chess Analyzer", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Analyzer activo")
            .setContentText("Toca el botón flotante para analizar")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
