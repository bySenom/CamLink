package com.camlink.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.max

/** Full-bleed camera preview that centre-crops instead of ever stretching a video frame. */
class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {
    private var frameWidth = 0
    private var frameHeight = 0
    private var rotationDegrees = 0

    fun setAspectRatio(width: Int, height: Int, rotationDegrees: Int = this.rotationDegrees) {
        if (width <= 0 || height <= 0) return
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        if (frameWidth == width && frameHeight == height && this.rotationDegrees == normalizedRotation) return
        frameWidth = width
        frameHeight = height
        this.rotationDegrees = normalizedRotation
        applyCenterCrop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyCenterCrop()
    }

    private fun applyCenterCrop() {
        if (frameWidth == 0 || frameHeight == 0 || width == 0 || height == 0) return
        val swapsAxes = rotationDegrees % 180 != 0
        val rotatedWidth = if (swapsAxes) frameHeight else frameWidth
        val rotatedHeight = if (swapsAxes) frameWidth else frameHeight
        val scale = max(width.toFloat() / rotatedWidth, height.toFloat() / rotatedHeight)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postRotate(rotationDegrees.toFloat(), frameWidth * scale / 2f, frameHeight * scale / 2f)
            val bounds = RectF(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat())
            mapRect(bounds)
            postTranslate(width / 2f - bounds.centerX(), height / 2f - bounds.centerY())
        }
        setTransform(matrix)
    }
}
