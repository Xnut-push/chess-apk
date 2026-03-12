package com.chessanalyzer

import android.content.Context
import android.graphics.*
import android.view.View

class ArrowOverlayView(context: Context) : View(context) {

    private var moves = listOf<String>()
    private var region = Rect()

    private val colors = listOf(
        Color.parseColor("#4dde72"),
        Color.parseColor("#f0c030"),
        Color.parseColor("#5aa0f0")
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setMoves(moves: List<String>, region: Rect) {
        this.moves = moves
        this.region = region
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (region.isEmpty || moves.isEmpty()) return

        val sqW = region.width() / 8f
        val sqH = region.height() / 8f

        moves.take(3).forEachIndexed { i, uci ->
            if (uci.length < 4) return@forEachIndexed
            val color = colors.getOrElse(i) { Color.WHITE }

            val fromFile = uci[0] - 'a'
            val fromRank = uci[1] - '1'
            val toFile = uci[2] - 'a'
            val toRank = uci[3] - '1'

            val fromX = region.left + fromFile * sqW + sqW / 2
            val fromY = region.top + (7 - fromRank) * sqH + sqH / 2
            val toX = region.left + toFile * sqW + sqW / 2
            val toY = region.top + (7 - toRank) * sqH + sqH / 2

            val alpha = (220 - i * 30)
            paint.color = color; paint.alpha = alpha; paint.strokeWidth = 14f - i * 3f
            fillPaint.color = color; fillPaint.alpha = alpha

            // Line
            val dx = toX - fromX; val dy = toY - fromY
            val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val trim = 20f
            val ex = toX - dx / len * trim
            val ey = toY - dy / len * trim
            canvas.drawLine(fromX, fromY, ex, ey, paint)

            // Arrowhead
            val angle = Math.atan2(dy.toDouble(), dx.toDouble())
            val headLen = 28f; val headAngle = 0.5
            val path = Path().apply {
                moveTo(toX, toY)
                lineTo(
                    (toX - headLen * Math.cos(angle - headAngle)).toFloat(),
                    (toY - headLen * Math.sin(angle - headAngle)).toFloat()
                )
                lineTo(
                    (toX - headLen * Math.cos(angle + headAngle)).toFloat(),
                    (toY - headLen * Math.sin(angle + headAngle)).toFloat()
                )
                close()
            }
            canvas.drawPath(path, fillPaint)
        }
    }
}
