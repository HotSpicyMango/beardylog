package com.hsm.beardylog.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** 갤러리 원본은 수천만 화소라 그대로 두면 앨범 뷰어와 Drive 백업 양쪽에서 OOM을 낸다.
 *  저장 시점과 백업 시점에서 같은 기준으로 한 번씩 줄인다. */
internal object PhotoScaler {
    const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 90

    /** 긴 변이 [maxDimension]px 이하가 되도록 줄인 JPEG 바이트.
     *  이미 충분히 작으면 [bytes]를 그대로(동일 참조로) 돌려주고, 디코드에 실패하면 null. */
    fun scaledJpeg(bytes: ByteArray, maxDimension: Int = MAX_DIMENSION): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth <= maxDimension && bounds.outHeight <= maxDimension) return bytes

        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
        ) ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** 긴 변이 [maxDimension] 이하가 되는 가장 작은 2의 거듭제곱. BitmapFactory의 inSampleSize 규약. */
    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
