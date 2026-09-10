package com.majazi.vmouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Neshan-e cursor ba 4 shekl va rang-e ghabel-e taghir */
class CursorView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MouseConfig.color
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLACK
    }

    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
    }

    var shape: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var color: Int = 0
        set(value) {
            field = value
            fill.color = value
            invalidate()
        }

    init {
        shape = MouseConfig.shape
        color = MouseConfig.color
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (shape) {
            SHAPE_HAND -> drawHand(canvas, w)
            SHAPE_CENTER -> drawCenter(canvas, w, h)
            SHAPE_CIRCLE -> drawCircle(canvas, w)
            else -> drawArrow(canvas, w)
        }
    }

    private fun drawArrow(canvas: Canvas, w: Float) {
        val s = w / 34f
        val path = Path()
        path.moveTo(4 * s, 2 * s)
        path.lineTo(4 * s, 30 * s)
        path.lineTo(11 * s, 23 * s)
        path.lineTo(15 * s, 31 * s)
        path.lineTo(19 * s, 29 * s)
        path.lineTo(15 * s, 21 * s)
        path.lineTo(24 * s, 21 * s)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }

    private fun drawHand(canvas: Canvas, w: Float) {
        val s = w / 34f
        // Noke-ye engosht-e eshare: (17s, 2s) -> CLICK HAMOONJA MISHEH
        // Kaf + 4 engosht-e past (gooshe-gerd) + engosht-e eshare-e boland
        val fillP = Path()
        // kaf (palm) - moraba-e gooshe-gerd
        fillP.addRoundRect(RectF(8f * s, 15f * s, 26f * s, 31f * s), 5f * s, 5f * s, Path.Direction.CW)
        // 4 engosht-e past (bexesvi chandan-e normal-e dast)
        fillP.addRoundRect(RectF(8.5f * s, 16f * s, 12.5f * s, 27f * s), 2f * s, 2f * s, Path.Direction.CW)
        fillP.addRoundRect(RectF(13f * s, 14f * s, 16.5f * s, 26f * s), 2f * s, 2f * s, Path.Direction.CW)
        fillP.addRoundRect(RectF(17f * s, 13f * s, 20.5f * s, 26f * s), 2f * s, 2f * s, Path.Direction.CW)
        fillP.addRoundRect(RectF(21f * s, 15f * s, 24.5f * s, 28f * s), 2f * s, 2f * s, Path.Direction.CW)
        canvas.drawPath(fillP, fill)
        canvas.drawPath(fillP, outline)
        // engosht-e eshare - dorough-e noke baraye click daghigh
        val pointer = Path()
        pointer.addRoundRect(RectF(14.8f * s, 2f * s, 19.2f * s, 20f * s), 2.2f * s, 2.2f * s, Path.Direction.CW)
        canvas.drawPath(pointer, fill)
        canvas.drawPath(pointer, outline)
        // mehak (esme dast) baraye jelvehe
        val wrist = Path()
        wrist.addRoundRect(RectF(11f * s, 28f * s, 23f * s, 32f * s), 3f * s, 3f * s, Path.Direction.CW)
        canvas.drawPath(wrist, fill)
        canvas.drawPath(wrist, outline)
    }

    private fun drawCenter(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawLine(0f, cy, w, cy, cross)
        canvas.drawLine(cx, 0f, cx, h, cross)
        canvas.drawCircle(cx, cy, w / 7f, fill)
        canvas.drawCircle(cx, cy, w / 7f, outline)
    }

    private fun drawCircle(canvas: Canvas, w: Float) {
        val cx = w / 2f
        val cy = w / 2f
        canvas.drawCircle(cx, cy, w / 3.2f, fill)
        canvas.drawCircle(cx, cy, w / 3.2f, outline)
    }

    companion object {
        const val SHAPE_ARROW = 0
        const val SHAPE_HAND = 1
        const val SHAPE_CENTER = 2
        const val SHAPE_CIRCLE = 3
    }
}
