package com.example.ircamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val spots = mutableListOf<Spot>()

    data class Spot(val x: Float, val y: Float, val intensity: Int)

    fun setSpots(list: List<Spot>) {
        spots.clear()
        spots.addAll(list)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (spot in spots) {
            // Нормализуем интенсивность (100..255) в диапазон 0..1
            val normalized = (spot.intensity.coerceIn(100, 255) - 100) / 155f
            val radius = 20f + (normalized * 50f) // от 20 до 70 пикселей
            val alpha = (100 + normalized * 155).toInt().coerceIn(0, 255)

            // Цвет: жёлтый (слабый) -> красный (сильный)
            val red = 255
            val green = (255 * (1 - normalized)).toInt()
            val blue = 0
            paint.color = Color.argb(alpha, red, green, blue)

            canvas.drawCircle(spot.x, spot.y, radius, paint)
        }
    }
}