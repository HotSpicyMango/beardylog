package com.hsm.beardylog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.ByteArrayInputStream
import kotlin.math.max
import kotlin.math.min

class SquareCropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f }
    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private var scale = 1f
    private var minScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(minScale, minScale * 5f)
            clampOffset()
            invalidate()
            return true
        }
    })

    fun setImage(uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        // 카메라 원본은 수천 px에 달해 무압축으로 그대로 디코드하면 메모리 스파이크/OOM 위험이 있어,
        // 크롭 화면에서 실제로 필요한 해상도 정도로만 다운샘플링해서 디코드한다.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIMENSION)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return
        val orientation = ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        bitmap?.recycle()
        bitmap = applyExifOrientation(decoded, orientation)
        resetImagePosition()
    }

    private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (rawWidth / (sampleSize * 2) >= maxDimension || rawHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        resetImagePosition()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        bitmap?.let { image ->
            drawMatrix.reset()
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(offsetX, offsetY)
            canvas.drawBitmap(image, drawMatrix, bitmapPaint)
        }
        val crop = cropRect()
        canvas.drawRect(0f, 0f, width.toFloat(), crop.top, overlayPaint)
        canvas.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, overlayPaint)
        canvas.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, overlayPaint)
        val thirdWidth = crop.width() / 3f
        val thirdHeight = crop.height() / 3f
        canvas.drawLine(crop.left + thirdWidth, crop.top, crop.left + thirdWidth, crop.bottom, guidePaint)
        canvas.drawLine(crop.left + thirdWidth * 2, crop.top, crop.left + thirdWidth * 2, crop.bottom, guidePaint)
        canvas.drawLine(crop.left, crop.top + thirdHeight, crop.right, crop.top + thirdHeight, guidePaint)
        canvas.drawLine(crop.left, crop.top + thirdHeight * 2, crop.right, crop.top + thirdHeight * 2, guidePaint)
        canvas.drawRect(crop, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; dragging = true }
            MotionEvent.ACTION_MOVE -> if (dragging && event.pointerCount == 1) {
                offsetX += event.x - lastX
                offsetY += event.y - lastY
                clampOffset()
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    fun croppedBitmap(): Bitmap? {
        val image = bitmap ?: return null
        val crop = cropRect()
        val left = ((crop.left - offsetX) / scale).toInt().coerceIn(0, image.width - 1)
        val top = ((crop.top - offsetY) / scale).toInt().coerceIn(0, image.height - 1)
        val size = (crop.width() / scale).toInt().coerceAtMost(min(image.width - left, image.height - top)).coerceAtLeast(1)
        return Bitmap.createBitmap(image, left, top, size, size)
    }

    private fun cropRect(): RectF {
        val size = min(width, height).toFloat()
        return RectF((width - size) / 2f, (height - size) / 2f, (width + size) / 2f, (height + size) / 2f)
    }

    private fun resetImagePosition() {
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        val crop = cropRect()
        minScale = max(crop.width() / image.width, crop.height() / image.height)
        scale = minScale
        offsetX = crop.centerX() - image.width * scale / 2f
        offsetY = crop.centerY() - image.height * scale / 2f
        invalidate()
    }

    private fun clampOffset() {
        val image = bitmap ?: return
        val crop = cropRect()
        val scaledWidth = image.width * scale
        val scaledHeight = image.height * scale
        offsetX = if (scaledWidth <= crop.width()) crop.centerX() - scaledWidth / 2f else offsetX.coerceIn(crop.right - scaledWidth, crop.left)
        offsetY = if (scaledHeight <= crop.height()) crop.centerY() - scaledHeight / 2f else offsetY.coerceIn(crop.bottom - scaledHeight, crop.top)
    }

    private companion object {
        // 크롭 UI가 화면에 보여주는 크기를 고려했을 때 이 이상 해상도는 크롭 품질에 도움이 안 되면서
        // 메모리만 잡아먹으므로, 디코드 단계에서 이 정도로 다운샘플링한다.
        const val MAX_DECODE_DIMENSION = 1600
    }
}
