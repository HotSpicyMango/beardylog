package com.hsm.beardylog

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.PhotoStore
import com.hsm.beardylog.databinding.ActivityCropBinding
import com.hsm.beardylog.ui.SquareCropView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        intent.getStringExtra(EXTRA_URI)?.let(::loadImage)
        binding.saveCropButton.setOnClickListener { view ->
            view.clickHaptic()
            saveCrop()
        }
    }

    /** 원본 디코드는 수십 MB짜리 작업이라 IO 스레드에서. 권한 만료/손상 사진은 여기서 걸린다. */
    private fun loadImage(uriValue: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { SquareCropView.decode(this@CropActivity, Uri.parse(uriValue)) }.getOrNull()
            }
            if (bitmap == null) {
                showBriefToast("사진을 불러오지 못했습니다")
                finish()
                return@launch
            }
            binding.cropView.setImage(bitmap)
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
        binding.saveCropButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val photoDirectory = File(filesDir, PhotoStore.PROFILE_DIRECTORY).apply { mkdirs() }
                    check(photoDirectory.isDirectory) { "프로필 사진을 저장할 공간을 만들지 못했습니다" }
                    File(photoDirectory, "profile_${System.currentTimeMillis()}.jpg").also { output ->
                        FileOutputStream(output).use { stream ->
                            cropped.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                        }
                    }
                }
            }.onSuccess { output ->
                cropped.recycle()
                binding.saveCropButton.confirmHaptic()
                setResult(RESULT_OK, intent.putExtra(EXTRA_RESULT_URI, Uri.fromFile(output).toString()))
                finish()
            }.onFailure {
                cropped.recycle()
                binding.saveCropButton.isEnabled = true
                binding.saveCropButton.rejectHaptic()
                showBriefToast("사진을 저장하지 못했습니다")
            }
        }
    }

    companion object {
        const val EXTRA_URI = "crop_uri"
        const val EXTRA_RESULT_URI = "crop_result_uri"
    }
}
