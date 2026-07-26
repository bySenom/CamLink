package com.camlink.camera

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.roundToInt

/** A centre-cropped-free TextureView: the whole camera frame stays visible and never stretches. */
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
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (frameWidth == 0 || frameHeight == 0 || availableWidth == 0 || availableHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val frameRatio = frameWidth.toFloat() / frameHeight.toFloat()
        val availableRatio = availableWidth.toFloat() / availableHeight.toFloat()
        val measuredWidth: Int
        val measuredHeight: Int
        if (availableRatio > frameRatio) {
            measuredHeight = availableHeight
            measuredWidth = (measuredHeight * frameRatio).roundToInt()
        } else {
            measuredWidth = availableWidth
            measuredHeight = (measuredWidth / frameRatio).roundToInt()
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
