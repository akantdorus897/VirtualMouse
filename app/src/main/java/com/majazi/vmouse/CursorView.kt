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
        val palm = Path()
        palm.addCircle(17 * s, 23 * s, 8 * s, Path.Direction.CW)
        canvas.drawPath(palm, fill)
        canvas.drawPath(palm, outline)
        val finger = Path()
        finger.addRoundRect(RectF(14.5f * s, 2 * s, 19.5f * s, 25 * s), 2.5f * s, 2.5f * s, Path.Direction.CW)
        canvas.drawPath(finger, fill)
        canvas.drawPath(finger, outline)
        val thumb = Path()
        thumb.addCircle(26 * s, 21 * s, 3.5f * s, Path.Direction.CW)
        canvas.drawPath(thumb, fill)
        canvas.drawPath(thumb, outline)
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
