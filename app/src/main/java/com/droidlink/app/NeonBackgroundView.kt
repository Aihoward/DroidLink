package com.droidlink.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Lightweight, allocation-free decorative background for non-gameplay screens only. */
class NeonBackgroundView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 255, 55); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 255, 75); style = Paint.Style.FILL }
    private var running = false
    private val redraw = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 50L) // Decorative motion is intentionally capped at 20 FPS.
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        removeCallbacks(redraw)
        post(redraw)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(redraw)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(3, 6, 4))
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val phase = (SystemClock.elapsedRealtime() % 20_000L) / 20_000f

        linePaint.alpha = 24
        for (i in 0 until 5) {
            val y = ((i * 0.23f + phase * 0.18f) % 1.15f - 0.08f) * h
            canvas.drawLine(-0.08f * w, y, 1.08f * w, y - 0.22f * h, linePaint)
        }
        linePaint.alpha = 15
        for (i in 0 until 4) {
            val x = (0.12f + i * 0.27f) * w
            canvas.drawLine(x, 0f, x + 0.12f * w, h, linePaint)
        }

        for (i in 0 until 6) {
            val travel = (phase + i * 0.173f) % 1f
            val x = (0.08f + (i % 3) * 0.41f + 0.035f * sin((phase + i) * 6.283f)) * w
            val y = (1.08f - travel * 1.16f) * h
            val radius = (3f + (i % 3) * 2f) * resources.displayMetrics.density
            orbPaint.alpha = 28 + (i % 2) * 16
            canvas.drawCircle(x, y, radius, orbPaint)
        }
    }
}
