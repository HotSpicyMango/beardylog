package com.hsm.beardylog.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils

class ShimmerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val shaderMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null
    private var shimmerOffset = 0f
    private var shimmerAnimator: ValueAnimator? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return

        val baseColor = currentTextColor
        val highlightColor = if (ColorUtils.calculateLuminance(baseColor) > 0.5) {
            ColorUtils.blendARGB(baseColor, Color.rgb(139, 201, 232), 0.8f)
        } else {
            ColorUtils.blendARGB(baseColor, Color.WHITE, 0.88f)
        }
        shimmerShader = LinearGradient(
            0f,
            height.toFloat(),
            width.toFloat(),
            0f,
            intArrayOf(baseColor, baseColor, highlightColor, baseColor, baseColor),
            floatArrayOf(0f, 0.36f, 0.5f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        startShimmer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            shimmerShader?.let { shader ->
                shaderMatrix.reset()
                shaderMatrix.setTranslate(shimmerOffset, 0f)
                shader.setLocalMatrix(shaderMatrix)
                paint.shader = shader
            }
        } else {
            paint.shader = null
        }
        super.onDraw(canvas)
    }

    private fun startShimmer() {
        if (!isAttachedToWindow || width <= 0 || !ValueAnimator.areAnimatorsEnabled()) return
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(-width.toFloat(), width.toFloat(), width.toFloat()).apply {
            duration = 3_000L
            startDelay = 500L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                shimmerOffset = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
