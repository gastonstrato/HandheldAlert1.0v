package com.gaston.handheldalert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vista simple para que el usuario dibuje con el dedo el rectángulo donde
 * aparece el ícono (error.gif / tilde verde) en la captura de referencia,
 * durante la calibración.
 */
class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private var startX = 0f
    private var startY = 0f
    var selection: RectF = RectF()
        private set

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                selection = RectF(startX, startY, startX, startY)
            }
            MotionEvent.ACTION_MOVE -> {
                selection = RectF(
                    minOf(startX, event.x), minOf(startY, event.y),
                    maxOf(startX, event.x), maxOf(startY, event.y)
                )
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(selection, paint)
    }

    /** Selección normalizada (0f..1f) relativa al tamaño de esta vista. */
    fun normalizedSelection(): RectF {
        if (width == 0 || height == 0) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            selection.left / width,
            selection.top / height,
            selection.right / width,
            selection.bottom / height
        )
    }
}
