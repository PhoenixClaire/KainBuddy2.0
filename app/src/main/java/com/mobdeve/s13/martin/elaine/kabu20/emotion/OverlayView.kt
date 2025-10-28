package com.mobdeve.s13.martin.elaine.kabu20.emotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val faceBounds = mutableListOf<RectF>()
    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun updateFaces(faces: List<RectF>) {
        faceBounds.clear()
        faceBounds.addAll(faces)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (rect in faceBounds) {
            canvas.drawRect(rect, paint)
        }
    }
}