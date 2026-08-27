package com.hsm.beardylog

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.databinding.ActivityCropBinding
import java.io.File
import java.io.FileOutputStream

class CropActivity : AppBaseActivity() {
    private lateinit var binding: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { DrawableCompat.setTint(it, appColor(R.color.text_primary)) }
        binding.toolbar.setNavigationOnClickListener { view ->
            view.clickHaptic()
            requestClose()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestClose()
            }
        })
        intent.getStringExtra(EXTRA_URI)?.let { binding.cropView.setImage(Uri.parse(it)) }
        binding.saveCropButton.setOnClickListener { view ->
            view.clickHaptic()
            saveCrop()
        }
    }

    private fun requestClose() {
        if (intent.getStringExtra(EXTRA_URI).isNullOrBlank()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("사진 자르기 취소")
            .setMessage("완료하지 않은 사진 편집이 있습니다. 나가시겠습니까?")
            .setNegativeButton("계속 편집", null)
            .setPositiveButton("나가기") { _, _ -> finish() }
            .show()
    }

    private fun saveCrop() {
        val cropped = binding.cropView.croppedBitmap() ?: run {
            binding.saveCropButton.rejectHaptic()
            return
        }
        val photoDirectory = File(filesDir, "profile_photos").apply { mkdirs() }
        if (!photoDirectory.isDirectory) {
            cropped.recycle()
            binding.saveCropButton.rejectHaptic()
            showBriefToast("프로필 사진을 저장할 공간을 만들지 못했습니다")
            return
        }
        val output = File(photoDirectory, "profile_${System.currentTimeMillis()}.jpg")
        FileOutputStream(output).use { stream -> cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream) }
        cropped.recycle()
        binding.saveCropButton.confirmHaptic()
        setResult(RESULT_OK, intent.putExtra(EXTRA_RESULT_URI, Uri.fromFile(output).toString()))
        finish()
    }

    companion object {
        const val EXTRA_URI = "crop_uri"
        const val EXTRA_RESULT_URI = "crop_result_uri"
    }
}
