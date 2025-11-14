package org.jtb.fractaldreams

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.util.LinkedList
import java.util.Locale

class FpsDisplay {
    companion object {
        const val WINDOW_SIZE_MS = 10000L // 10 seconds
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        alpha = 200
    }

    private val strokePaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        alpha = 200
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
    }

    private val frameTimestamps = LinkedList<Long>()
    private var fps = 0.0f
    private var lastDisplayUpdateTime: Long = 0
    private val displayUpdateIntervalMs = 1000L // 1 second

    fun update() {
        val currentTime = System.currentTimeMillis()
        frameTimestamps.add(currentTime)

        // Remove timestamps older than our window
        while (frameTimestamps.isNotEmpty() && currentTime - frameTimestamps.first() > WINDOW_SIZE_MS) {
            frameTimestamps.removeFirst()
        }

        // Update the displayed FPS value every second
        if (currentTime - lastDisplayUpdateTime > displayUpdateIntervalMs) {
            val currentWindowSeconds = (currentTime - (frameTimestamps.firstOrNull() ?: currentTime)) / 1000.0f
            fps = if (currentWindowSeconds > 0) {
                frameTimestamps.size / currentWindowSeconds
            } else {
                0.0f
            }
            lastDisplayUpdateTime = currentTime
        }
    }

    fun getFps(): Float = fps

    fun draw(canvas: Canvas) {
        val fpsText = String.format(Locale.ROOT, "FPS: %.1f", fps)
        val textBounds = Rect()
        textPaint.getTextBounds(fpsText, 0, fpsText.length, textBounds)

        val padding = 16f
        val textWidth = textPaint.measureText(fpsText)
        val textHeight = textBounds.height().toFloat()

        val x = canvas.width - textWidth - padding
        val y = canvas.height - textHeight - padding

        canvas.drawRect(
            x - padding / 2,
            y - padding / 2,
            x + textWidth + padding / 2,
            y + textHeight + padding / 2,
            backgroundPaint
        )

        canvas.drawText(fpsText, x, y + textHeight, strokePaint)
        canvas.drawText(fpsText, x, y + textHeight, textPaint)
    }
}
