package com.hsm.beardylog

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.MemorialPhoto
import com.hsm.beardylog.data.Reptile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

/**
 * MainActivity의 "추억공간" 섹션(무지개다리를 건넌 개체 목록/상세/앨범) 전용 뷰 빌드와 상태를 담당한다.
 * MainActivity가 소유하며, 화면 전환/뒤로가기 같은 액티비티 공통 동작은 [activity]에 위임한다.
 */
internal class MemorialSection(private val activity: MainActivity) {

    private val memorialState = MemorialSectionState()
    private var memorialDetailId by memorialState::detailId
    private val memorialDateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

    // ---- MainActivity가 호출하는 진입점 ----

    fun leave() = memorialState.leave()

    fun closeDetail(): Boolean = memorialState.closeDetail()

    fun hasOpenDetail(): Boolean = memorialDetailId != null

    fun refresh() {
        if (activity.currentSection != MainActivity.MainSection.MEMORIAL) return
        activity.replaceTopContentKeepingScroll(createContentView())
    }

    fun createContentView(): View {
        val detailId = memorialDetailId
        val detailReptile = detailId?.let { id -> deceasedReptiles().firstOrNull { it.id == id } }
        if (detailId != null && detailReptile == null) memorialDetailId = null
        return if (detailReptile != null) createMemorialDetailView(detailReptile) else createMemorialListView()
    }

    private fun deceasedReptiles(): List<Reptile> =
        memorialState.deceasedProfiles(activity.allReptiles)

    private fun createMemorialListView(): View {
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(sectionHeader("추억공간", "무지개다리를 건넌 아이들을 기억해요"))
        val deceased = deceasedReptiles()
        if (deceased.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "아직 추억공간으로 보낸 아이가 없습니다"
                textSize = 14f
                setTextColor(resColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
            return scrollView
        }
        deceased.forEachIndexed { index, reptile ->
            content.addView(memorialListCard(reptile), LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = dp(if (index == 0) 16 else 12)
            })
        }
        return scrollView
    }

    private fun memorialListCard(reptile: Reptile): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resColor(R.color.forest_light)
            setCardBackgroundColor(resColor(R.color.surface_card))
            setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                memorialDetailId = reptile.id
                activity.replaceTopContent(createContentView())
            }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        row.addView(ImageView(activity).apply {
            background = activity.getDrawable(R.drawable.bg_profile_circle)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_lizard_placeholder)
            reptile.photoUri?.let { uri ->
                Glide.with(activity)
                    .load(android.net.Uri.parse(uri))
                    .override(dp(52), dp(52))
                    .centerCrop()
                    .into(this)
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(TextView(activity).apply {
            text = reptile.name
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
        })
        textColumn.addView(TextView(activity).apply {
            text = memorialPeriodLabel(reptile)
            textSize = 13f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(14) })
        card.addView(row, ViewGroup.LayoutParams(match, wrap))
        return card
    }

    private fun memorialPeriodLabel(reptile: Reptile): String {
        val deathDate = reptile.deathDate ?: return ""
        val startDate = reptile.hatchingDate ?: reptile.adoptionDate ?: reptile.referenceDate
        val together = Period.between(LocalDate.ofEpochDay(startDate), LocalDate.ofEpochDay(deathDate))
        return "함께한 시간 ${together.years}년 ${together.months}개월 · ${LocalDate.ofEpochDay(deathDate).format(memorialDateFormatter)}"
    }

    private fun createMemorialDetailView(reptile: Reptile): View {
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(TextView(activity).apply {
            text = "← 추억공간"
            textSize = 14f
            setTextColor(resColor(R.color.forest))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                memorialDetailId = null
                activity.replaceTopContent(createContentView())
            }
        })
        content.addView(ImageView(activity).apply {
            background = activity.getDrawable(R.drawable.bg_photo_placeholder)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_lizard_placeholder)
            reptile.photoUri?.let { uri ->
                Glide.with(activity)
                    .load(android.net.Uri.parse(uri))
                    .override(dp(128), dp(128))
                    .centerCrop()
                    .into(this)
            }
        }, LinearLayout.LayoutParams(dp(128), dp(128)).apply {
            topMargin = dp(20)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        content.addView(TextView(activity).apply {
            text = reptile.name
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
        content.addView(TextView(activity).apply {
            text = listOf(reptile.species, reptile.morph).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "" }
            textSize = 14f
            setTextColor(resColor(R.color.text_secondary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })

        val infoCard = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor(R.color.surface_card))
        }
        val infoColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val startDate = reptile.hatchingDate ?: reptile.adoptionDate ?: reptile.referenceDate
        val startLabel = if (reptile.hatchingDate != null) "해칭일" else if (reptile.adoptionDate != null) "입양일" else reptile.referenceDateType
        val deathDate = reptile.deathDate
        infoColumn.addView(TextView(activity).apply {
            text = "$startLabel: ${LocalDate.ofEpochDay(startDate).format(memorialDateFormatter)}"
            textSize = 14f
            setTextColor(resColor(R.color.text_primary))
        })
        infoColumn.addView(TextView(activity).apply {
            text = "떠난 날: ${deathDate?.let { LocalDate.ofEpochDay(it).format(memorialDateFormatter) } ?: "-"}"
            textSize = 14f
            setTextColor(resColor(R.color.text_primary))
            setPadding(0, dp(8), 0, 0)
        })
        infoColumn.addView(TextView(activity).apply {
            text = memorialPeriodLabel(reptile).substringBefore(" ·")
            textSize = 14f
            setTextColor(resColor(R.color.forest))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        infoCard.addView(infoColumn, ViewGroup.LayoutParams(match, wrap))
        content.addView(infoCard, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(24) })

        if (!reptile.memorialNote.isNullOrBlank()) {
            val noteCard = MaterialCardView(activity).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(resColor(R.color.surface_card))
            }
            val noteColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            noteColumn.addView(TextView(activity).apply {
                text = "추억 메모"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
            })
            noteColumn.addView(TextView(activity).apply {
                text = reptile.memorialNote
                textSize = 14f
                setTextColor(resColor(R.color.text_primary))
                setPadding(0, dp(10), 0, 0)
            })
            noteCard.addView(noteColumn, ViewGroup.LayoutParams(match, wrap))
            content.addView(noteCard, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
        }

        val albumSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(albumSection, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
        activity.lifecycleScope.launch {
            val albumPhotos = withContext(Dispatchers.IO) { activity.database.memorialPhotoDao().forReptile(reptile.id) }
            if (memorialDetailId == reptile.id) {
                albumSection.removeAllViews()
                albumSection.addView(memorialAlbumPreview(reptile, albumPhotos))
            }
        }

        content.addView(MaterialButton(activity).apply {
            text = "몸무게 기록 보기"
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
            setOnClickListener { view ->
                view.clickHaptic()
                activity.startActivity(Intent(activity, WeightHistoryActivity::class.java).putExtra(WeightHistoryActivity.EXTRA_REPTILE_ID, reptile.id))
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(24) })
        content.addView(TextView(activity).apply {
            text = "추억공간으로 전환하며 식단·캘린더 기록은 삭제되었어요"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(resColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        content.addView(MaterialButton(activity).apply {
            text = "추억 프로필 삭제"
            setTextColor(resColor(R.color.danger))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(resColor(R.color.danger))
            strokeWidth = dp(1)
            cornerRadius = dp(12)
            setIconResource(R.drawable.ic_delete)
            iconTint = ColorStateList.valueOf(resColor(R.color.danger))
            setOnClickListener { view ->
                view.clickHaptic()
                confirmDeleteMemorialProfile(reptile)
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(20) })

        return scrollView
    }

    private fun confirmDeleteMemorialProfile(reptile: Reptile) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("추억 프로필 삭제")
            .setMessage("${reptile.name}의 프로필과 사진·몸무게 기록·추억 앨범이 모두 영구히 삭제됩니다. 되돌릴 수 없어요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.deleteReptileFully(reptile.id) }
                    }.onSuccess {
                        memorialDetailId = null
                        // Don't wait on Room's LiveData round-trip to reflect the delete: the DB write
                        // already succeeded, so drop it from the in-memory list right away too, so the
                        // list re-renders correctly on this same call instead of still showing the
                        // deleted profile until some unrelated write later nudges the observer.
                        activity.allReptiles = activity.allReptiles.filterNot { it.id == reptile.id }
                        activity.replaceTopContent(createContentView())
                        activity.showBriefToast("삭제했습니다")
                    }.onFailure {
                        activity.showBriefToast("삭제하지 못했습니다")
                    }
                }
            }
            .show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(resColor(R.color.danger))
    }

    private fun memorialAlbumPreview(reptile: Reptile, albumPhotos: List<MemorialPhoto>): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor(R.color.surface_card))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        column.addView(TextView(activity).apply {
            text = if (albumPhotos.isEmpty()) "추억 앨범" else "추억 앨범 · ${albumPhotos.size}장"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.forest))
        })
        if (albumPhotos.isNotEmpty()) {
            val previewRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            albumPhotos.take(4).forEach { photo ->
                previewRow.addView(ImageView(activity).apply {
                    background = activity.getDrawable(R.drawable.bg_photo_placeholder)
                    clipToOutline = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(activity)
                        .load(android.net.Uri.parse(photo.photoUri))
                        .override(dp(64), dp(64))
                        .centerCrop()
                        .into(this)
                }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) })
            }
            column.addView(previewRow, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(12) })
        } else {
            column.addView(TextView(activity).apply {
                text = "사진을 추가해 이 아이만의 앨범을 만들어보세요"
                textSize = 13f
                setTextColor(resColor(R.color.text_secondary))
                setPadding(0, dp(6), 0, 0)
            })
        }
        column.addView(MaterialButton(activity).apply {
            text = if (albumPhotos.isEmpty()) "사진 추가하기" else "앨범 전체보기"
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
            setOnClickListener { view ->
                view.clickHaptic()
                activity.startActivity(Intent(activity, MemorialAlbumActivity::class.java).putExtra(MemorialAlbumActivity.EXTRA_REPTILE_ID, reptile.id))
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        card.addView(column, ViewGroup.LayoutParams(match, wrap))
        return card
    }

    // ---- 이 섹션 전용 소품 헬퍼 (MainActivity의 것과 동일한 구현을 그대로 둔다) ----

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun resColor(resId: Int): Int = activity.appColor(resId)
    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun sectionHeader(title: String, subtitle: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(TextView(context).apply {
            text = title
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
    }
}
