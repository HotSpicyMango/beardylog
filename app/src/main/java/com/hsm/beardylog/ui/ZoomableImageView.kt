package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

/**
 * Pinch-to-zoom + double-tap-to-zoom image view used by the memorial album full-screen viewer.
 *
 * At minimum scale (not zoomed in) a single-finger vertical drag has nothing to pan, so it's
 * reported to [dismissGestureListener] instead, letting the host screen drive a swipe-to-dismiss
 * gesture. While zoomed in, drags pan the image and the parent (a ViewPager2) is told not to
 * intercept so page-swiping doesn't fight with panning.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    interface OnDismissGestureListener {
        fun onDismissProgress(dy: Float)
        fun onDismissRelease(dy: Float, velocityY: Float)
    }

    private enum class Mode { NONE, PAN, DISMISS }

    var dismissGestureListener: OnDismissGestureListener? = null

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val maxFlingVelocity: Float = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val matrix: Matrix = Matrix()

    private var minScale: Float = 1f
    private var maxScale: Float = 1f
    private var scale: Float = 1f
    private var translateX: Float = 0f
    private var translateY: Float = 0f
    private var imageWidth: Float = 0f
    private var imageHeight: Float = 0f

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var mode: Mode = Mode.NONE
    private var dismissDy: Float = 0f
    private var velocityTracker: VelocityTracker? = null

    private val scaleListener: ScaleListener = ScaleListener()
    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(context, scaleListener)

    private val tapListener: TapListener = TapListener()
    private val gestureDetector: GestureDetector = GestureDetector(context, tapListener)

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = Mode.PAN
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale: Float = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor: Float = newScale / scale
            scale = newScale
            translateX = detector.focusX - (detector.focusX - translateX) * factor
            translateY = detector.focusY - (detector.focusY - translateY) * factor
            clampTranslation()
            applyMatrix()
            return true
        }
    }

    private inner class TapListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            toggleZoom(e.x, e.y)
            return true
        }
    }

    /** setImageDrawable은 ImageView 생성자에서도 불릴 수 있는 자리라(레이아웃 XML의 android:src 등),
     *  이 클래스의 필드 초기화가 끝나기 전에 resetZoom이 실행되는 걸 막는다. */
    private var readyForZoom: Boolean = false

    init {
        scaleType = ScaleType.MATRIX
        readyForZoom = true
    }

    // Glide는 setImageURI가 아니라 setImageDrawable로 그림을 넣는다. 모든 설정 경로가
    // 여기로 모이므로(setImageURI/setImageResource도 내부적으로 이걸 부른다) 줌 초기화를 여기에 건다.
    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        if (readyForZoom) resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        imageWidth = d.intrinsicWidth.toFloat()
        imageHeight = d.intrinsicHeight.toFloat()
        minScale = min(width / imageWidth, height / imageHeight)
        maxScale = minScale * MAX_SCALE_MULTIPLIER
        scale = minScale
        translateX = (width - imageWidth * scale) / 2f
        translateY = (height - imageHeight * scale) / 2f
        applyMatrix()
    }

    private fun toggleZoom(focusX: Float, focusY: Float) {
        if (imageWidth <= 0f || imageHeight <= 0f) return
        val target: Float = if (scale > minScale * 1.05f) minScale else min(minScale * 2.5f, maxScale)
        val factor: Float = target / scale
        scale = target
        translateX = focusX - (focusX - translateX) * factor
        translateY = focusY - (focusY - translateY) * factor
        clampTranslation()
        applyMatrix()
    }

    private fun clampTranslation() {
        val scaledWidth: Float = imageWidth * scale
        val scaledHeight: Float = imageHeight * scale
        translateX = if (scaledWidth <= width) (width - scaledWidth) / 2f
            else translateX.coerceIn(width - scaledWidth, 0f)
        translateY = if (scaledHeight <= height) (height - scaledHeight) / 2f
            else translateY.coerceIn(height - scaledHeight, 0f)
    }

    private fun applyMatrix() {
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(translateX, translateY)
        imageMatrix = matrix
    }

    private fun isZoomedIn(): Boolean = scale > minScale * 1.01f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                mode = if (isZoomedIn()) Mode.PAN else Mode.NONE
                dismissDy = 0f
                velocityTracker?.recycle()
                val tracker: VelocityTracker = VelocityTracker.obtain()
                tracker.addMovement(event)
                velocityTracker = tracker
                parent?.requestDisallowInterceptTouchEvent(isZoomedIn())
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor to the remaining finger so a pinch-to-pan handoff doesn't jump.
                val remainingIndex: Int = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                velocityTracker?.addMovement(event)
                val dx: Float = event.x - lastX
                val dy: Float = event.y - lastY
                when (mode) {
                    Mode.PAN -> {
                        translateX += dx
                        translateY += dy
                        clampTranslation()
                        applyMatrix()
                    }
                    Mode.NONE -> when {
                        abs(dy) > touchSlop && abs(dy) > abs(dx) -> {
                            mode = Mode.DISMISS
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        abs(dx) > touchSlop -> {
                            // Horizontal drag while not zoomed: let the ViewPager2 page-swipe handle it.
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    Mode.DISMISS -> {
                        dismissDy += dy
                        dismissGestureListener?.onDismissProgress(dismissDy)
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DISMISS) {
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity)
                    val releaseVelocity: Float = velocityTracker?.yVelocity ?: 0f
                    dismissGestureListener?.onDismissRelease(dismissDy, releaseVelocity)
                }
                mode = Mode.NONE
                dismissDy = 0f
                velocityTracker?.recycle()
                velocityTracker = null
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    companion object {
        private const val MAX_SCALE_MULTIPLIER = 4f
    }
}
