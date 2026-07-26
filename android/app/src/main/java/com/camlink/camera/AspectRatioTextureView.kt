package com.camlink.camera

import android.content.Context
import android.graphics.Matrix
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

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (frameWidth == width && frameHeight == height)) return
        frameWidth = width
        frameHeight = height
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
        val scale = max(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val scaledWidth = frameWidth * scale
        val scaledHeight = frameHeight * scale
        setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - scaledWidth) / 2f, (height - scaledHeight) / 2f)
        })
    }
}
