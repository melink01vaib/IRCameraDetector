package com.example.ircamera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class IRSpotAnalyzer(
    private var minBrightness: Int,
    private val onResult: (List<Spot>) -> Unit
) : ImageAnalysis.Analyzer {

    data class Spot(val x: Int, val y: Int, val intensity: Int)

    private val contrastThreshold = 70
    private val step = 6
    private val ringRadius = 6
    private val maxSpots = 20

    fun updateSensitivity(newValue: Int) {
        minBrightness = newValue
    }

    override fun analyze(image: ImageProxy) {
        val spots = mutableListOf<Spot>()
        try {
            val yPlane = image.planes[0]
            val buffer: ByteBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            val width = image.width
            val height = image.height

            for (y in ringRadius until height - ringRadius step step) {
                for (x in ringRadius until width - ringRadius step step) {
                    val idx = y * rowStride + x * pixelStride
                    if (idx >= buffer.capacity()) continue
                    val brightness = buffer.get(idx).toInt() and 0xFF
                    if (brightness < minBrightness) continue
                    val avg = getSurroundingAverage(
                        buffer, width, height, x, y, rowStride, pixelStride
                    )
                    if (brightness - avg > contrastThreshold) {
                        spots.add(Spot(x, y, brightness))
                        if (spots.size >= maxSpots) break
                    }
                }
                if (spots.size >= maxSpots) break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
        onResult(spots)
    }

    private fun getSurroundingAverage(
        buffer: ByteBuffer, width: Int, height: Int,
        cx: Int, cy: Int, rowStride: Int, pixelStride: Int
    ): Int {
        var sum = 0
        var count = 0
        for (dy in -ringRadius..ringRadius step ringRadius) {
            for (dx in -ringRadius..ringRadius step ringRadius) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until width && ny in 0 until height) {
                    val idx = ny * rowStride + nx * pixelStride
                    if (idx < buffer.capacity()) {
                        sum += buffer.get(idx).toInt() and 0xFF
                        count++
                    }
                }
            }
        }
        return if (count > 0) sum / count else 0
    }
}