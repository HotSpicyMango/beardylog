package com.hsm.beardylog

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.MemorialPhoto
import com.hsm.beardylog.data.PhotoScaler
import com.hsm.beardylog.data.PhotoStore
import com.hsm.beardylog.databinding.ActivityMemorialAlbumBinding
import com.hsm.beardylog.ui.ZoomableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class MemorialAlbumActivity : AppBaseActivity() {
    private lateinit var binding: ActivityMemorialAlbumBinding
    private lateinit var database: AppDatabase
    private var reptileId = -1L
    private var photos: List<MemorialPhoto> = emptyList()

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_PICK)) { uris ->
        if (uris.isNotEmpty()) savePhotos(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemorialAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reptileId = intent.getLongExtra(EXTRA_REPTILE_ID, -1L)
        if (reptileId <= 0) { finish(); return }
        database = AppDatabase.getInstance(applicationContext)
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { DrawableCompat.setTint(it, appColor(R.color.text_primary)) }
        binding.toolbar.setNavigationOnClickListener { view ->
            view.clickHaptic()
            finish()
        }
        database.reptileDao().observeById(reptileId).observe(this) { reptile ->
            binding.toolbar.title = reptile?.let { "${it.name}의 추억 앨범" } ?: "추억 앨범"
        }
        binding.addPhotoButton.setOnClickListener { view ->
            view.clickHaptic()
            pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        database.memorialPhotoDao().observeForReptile(reptileId).observe(this) { list ->
            photos = list
            renderGrid()
        }
    }

    private fun savePhotos(uris: List<Uri>) {
        binding.addPhotoButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val photoDirectory = File(filesDir, PhotoStore.MEMORIAL_DIRECTORY).apply { mkdirs() }
                    uris.forEachIndexed { index, uri ->
                        val source = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@forEachIndexed
                        val scaled = PhotoScaler.scaledJpeg(source) ?: return@forEachIndexed
                        val output = File(photoDirectory, "memorial_${reptileId}_${System.currentTimeMillis()}_$index.jpg")
                        output.writeBytes(scaled)
                        database.memorialPhotoDao().insert(
                            MemorialPhoto(0, reptileId, Uri.fromFile(output).toString(), System.currentTimeMillis())
                        )
                    }
                }
            }.onSuccess {
                binding.addPhotoButton.isEnabled = true
                binding.root.confirmHaptic()
            }.onFailure {
                binding.addPhotoButton.isEnabled = true
                showBriefToast("사진을 추가하지 못했습니다")
            }
        }
    }

    private fun renderGrid() {
        binding.photoGrid.removeAllViews()
        binding.emptyText.visibility = if (photos.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val columns = 3
        binding.photoGrid.columnCount = columns
        val cellSize = (resources.displayMetrics.widthPixels - dp(40) - dp(8) * (columns - 1)) / columns
        photos.forEachIndexed { index, photo ->
            val cell = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = getDrawable(R.drawable.bg_photo_placeholder)
                clipToOutline = true
                Glide.with(this@MemorialAlbumActivity)
                    .load(Uri.parse(photo.photoUri))
                    .override(cellSize, cellSize)
                    .centerCrop()
                    .into(this)
                setOnClickListener { view ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    showFullScreen(index)
                }
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns)).apply {
                width = cellSize
                height = cellSize
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            binding.photoGrid.addView(cell, params)
        }
    }

    private fun showFullScreen(startIndex: Int) {
        val viewerPhotos = photos
        if (viewerPhotos.isEmpty()) return
        val dialog: Dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var currentPosition: Int = startIndex.coerceIn(0, viewerPhotos.size - 1)

        val root: FrameLayout = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val pagerAdapter: MemorialPhotoPagerAdapter = MemorialPhotoPagerAdapter(
            viewerPhotos,
            notifyDismissProgress = { dy -> applyDismissProgress(root, dy) },
            notifyDismissRelease = { dy, velocityY -> handleDismissRelease(dialog, root, dy, velocityY) }
        )
        val pageChangeCallback: ViewPager2.OnPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
            }
        }
        val pager: ViewPager2 = ViewPager2(this)
        pager.adapter = pagerAdapter
        pager.registerOnPageChangeCallback(pageChangeCallback)
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val closeButton: MaterialButton = MaterialButton(this)
        closeButton.text = "닫기"
        closeButton.setOnClickListener { view -> view.clickHaptic(); dialog.dismiss() }
        val closeButtonParams: FrameLayout.LayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        closeButtonParams.gravity = Gravity.TOP or Gravity.START
        closeButtonParams.leftMargin = dp(16)
        closeButtonParams.topMargin = dp(16)
        root.addView(closeButton, closeButtonParams)

        val deleteButton: MaterialButton = MaterialButton(this)
        deleteButton.text = "삭제"
        deleteButton.setTextColor(Color.WHITE)
        deleteButton.backgroundTintList = ColorStateList.valueOf(resColor(R.color.danger))
        deleteButton.setOnClickListener { view ->
            view.clickHaptic()
            val photo = viewerPhotos.getOrNull(currentPosition)
            if (photo != null) {
                confirmDeletePhoto(photo) { dialog.dismiss() }
            }
        }
        val deleteButtonParams: FrameLayout.LayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        deleteButtonParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        deleteButtonParams.bottomMargin = dp(24)
        root.addView(deleteButton, deleteButtonParams)

        dialog.setContentView(root)
        pager.setCurrentItem(currentPosition, false)

        root.alpha = 0f
        root.scaleX = 0.96f
        root.scaleY = 0.96f
        dialog.show()
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ENTRANCE_ANIMATION_MS).start()
    }

    private fun applyDismissProgress(root: View, dy: Float) {
        root.translationY = dy
        val fadeDistance = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val ratio = (1f - abs(dy) / (fadeDistance * 0.7f)).coerceIn(0.25f, 1f)
        root.alpha = ratio
    }

    private fun handleDismissRelease(dialog: Dialog, root: View, dy: Float, velocityY: Float) {
        val shouldDismiss = abs(dy) > dp(DISMISS_DISTANCE_DP) || abs(velocityY) > DISMISS_FLING_VELOCITY
        if (shouldDismiss) {
            val target = if (dy < 0) -root.height.toFloat() - dp(40) else root.height.toFloat() + dp(40)
            root.animate().translationY(target).alpha(0f).setDuration(DISMISS_ANIMATION_MS).withEndAction { dialog.dismiss() }.start()
        } else {
            root.animate().translationY(0f).alpha(1f).setDuration(SPRING_BACK_ANIMATION_MS).start()
        }
    }

    private class MemorialPhotoPagerAdapter(
        private val photos: List<MemorialPhoto>,
        private val notifyDismissProgress: (Float) -> Unit,
        private val notifyDismissRelease: (Float, Float) -> Unit
    ) : RecyclerView.Adapter<MemorialPhotoPagerAdapter.ViewHolder>() {

        class ViewHolder(val imageView: ZoomableImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val imageView: ZoomableImageView = ZoomableImageView(parent.context)
            imageView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return ViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // photos[position] is read immediately here, not captured for a later callback,
            // so a stale index after the list changes isn't a concern.
            val photo = photos.getOrNull(position) ?: return
            holder.imageView.dismissGestureListener = object : ZoomableImageView.OnDismissGestureListener {
                override fun onDismissProgress(dy: Float) = notifyDismissProgress(dy)
                override fun onDismissRelease(dy: Float, velocityY: Float) = notifyDismissRelease(dy, velocityY)
            }
            // setImageURI는 메인 스레드에서 원본을 통째로 디코딩해 OOM을 낸다. 목록과 동일하게 Glide에 맡긴다.
            Glide.with(holder.imageView)
                .load(Uri.parse(photo.photoUri))
                .override(PhotoScaler.MAX_DIMENSION, PhotoScaler.MAX_DIMENSION)
                .fitCenter()
                .into(holder.imageView)
        }

        override fun getItemCount() = photos.size
    }

    private fun confirmDeletePhoto(photo: MemorialPhoto, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("사진 삭제")
            .setMessage("이 사진을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            database.memorialPhotoDao().deleteById(photo.id)
                            runCatching { Uri.parse(photo.photoUri).path?.let { File(it).delete() } }
                        }
                    }.onSuccess {
                        onDeleted()
                    }.onFailure {
                        showBriefToast("사진을 삭제하지 못했습니다")
                    }
                }
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun resColor(resId: Int): Int = appColor(resId)

    companion object {
        const val EXTRA_REPTILE_ID = "reptile_id"
        private const val MAX_PHOTOS_PER_PICK = 20
        private const val ENTRANCE_ANIMATION_MS = 220L
        private const val DISMISS_ANIMATION_MS = 200L
        private const val SPRING_BACK_ANIMATION_MS = 220L
        private const val DISMISS_DISTANCE_DP = 120
        private const val DISMISS_FLING_VELOCITY = 1400f
    }
}
