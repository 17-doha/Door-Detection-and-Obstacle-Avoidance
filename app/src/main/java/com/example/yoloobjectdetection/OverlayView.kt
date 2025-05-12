package com.example.yoloobjectdetection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        invalidate()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        // Use ContextCompat to get the color safely without using `!!` on context
        boxPaint.color = ContextCompat.getColor(context, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw bounding boxes for each result
        results.forEach { boundingBox ->
            val left = boundingBox.x1 * width
            val top = boundingBox.y1 * height
            val right = boundingBox.x2 * width
            val bottom = boundingBox.y2 * height

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val drawableText = boundingBox.clsName

            // Calculate text bounds
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()

            // Draw background for text
            canvas.drawRect(
                left,
                top - textHeight - BOUNDING_RECT_TEXT_PADDING, // Text is above the bounding box
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top,
                textBackgroundPaint
            )

            // Draw the text
            canvas.drawText(drawableText, left, top - BOUNDING_RECT_TEXT_PADDING.toFloat(), textPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()  // Redraw the view with new bounding boxes
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}
