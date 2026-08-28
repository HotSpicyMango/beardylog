package com.hsm.beardylog

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.BreedingPair
import com.hsm.beardylog.data.Clutch
import com.hsm.beardylog.data.Hatchling
import com.hsm.beardylog.data.Reptile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * MainActivity의 "브리딩" 섹션(짝 등록, 메이팅 날짜, 클러치, 해츨링 관리) 전용 뷰 빌드와 상태를 담당한다.
 * MemorialSection과 동일한 패턴: MainActivity가 소유하며 화면 전환/뒤로가기는 activity에 위임한다.
 */
internal class BreedingSection(private val activity: MainActivity) {

    private var pairs: List<BreedingPair> = emptyList()
    private var clutches: List<Clutch> = emptyList()
    private var hatchlings: List<Hatchling> = emptyList()
    private var detailPairId: Long? = null
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M/d")

    // ---- MainActivity가 호출하는 진입점 ----

    fun setup() {
        activity.database.breedingPairDao().observeAll().observe(activity) {
            pairs = it
            refresh()
        }
        activity.database.clutchDao().observeAll().observe(activity) {
            clutches = it
            refresh()
        }
        activity.database.hatchlingDao().observeAll().observe(activity) {
            hatchlings = it
            refresh()
        }
    }

    fun leave() {
        detailPairId = null
    }

    fun closeDetail(): Boolean {
        if (detailPairId == null) return false
        detailPairId = null
        return true
    }

    fun hasOpenDetail(): Boolean = detailPairId != null

    fun refresh() {
        if (activity.currentSection != MainActivity.MainSection.BREEDING) return
        activity.replaceTopContentKeepingScroll(createContentView())
    }

    fun createContentView(): View {
        val id = detailPairId
        val pair = id?.let { i -> pairs.firstOrNull { it.id == i } }
        if (id != null && pair == null) detailPairId = null
        return if (pair != null) createDetailView(pair) else createListView()
    }

    // ---- 목록 화면 ----

    private fun createListView(): View {
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(sectionHeader("브리딩", "짝짓기부터 해칭까지 기록해요"))

        if (pairs.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "아직 등록된 브리딩 기록이 없습니다"
                textSize = 14f
                setTextColor(resColor(R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(24))
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
            content.addView(MaterialButton(activity).apply {
                text = "브리딩 기록 추가"
                setTextColor(resColor(R.color.forest))
                backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
                setOnClickListener { view ->
                    view.clickHaptic()
                    showAddPairDialog()
                }
            })
            return scrollView
        }

        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "브리딩 기록 추가"
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(resColor(R.color.forest))
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.surface_card))
            setOnClickListener { view ->
                view.clickHaptic()
                showAddPairDialog()
            }
        }, LinearLayout.LayoutParams(wrap, wrap).apply {
            gravity = Gravity.END
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        pairs.sortedWith(compareBy<BreedingPair> { it.sortOrder }.thenBy { it.id }).forEachIndexed { index, pair ->
            content.addView(pairListCard(pair), LinearLayout.LayoutParams(match, wrap).apply {
                topMargin = dp(if (index == 0) 8 else 8)
            })
        }
        return scrollView
    }

    private fun pairListCard(pair: BreedingPair): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resColor(R.color.forest_light)
            setCardBackgroundColor(resColor(R.color.surface_card))
            setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                detailPairId = pair.id
                activity.replaceTopContent(createContentView())
            }
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(sortOrderInput(pair), LinearLayout.LayoutParams(dp(38), dp(30)))
        topRow.addView(TextView(activity).apply {
            text = "번"
            textSize = 12f
            setTextColor(resColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(3) })
        topRow.addView(TextView(activity).apply {
            text = "${maleDisplayName(pair)} × ${femaleDisplayName(pair)}"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(8) })
        topRow.addView(TextView(activity).apply {
            text = formatDDay(pair.matingDate)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.forest))
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(6) })
        column.addView(topRow)

        val metaRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val pairClutches = clutchesFor(pair.id)
        metaRow.addView(TextView(activity).apply {
            text = buildString {
                append("메이팅 ${LocalDate.ofEpochDay(pair.matingDate).format(shortDateFormatter)}")
                pairClutches.sortedBy { it.clutchNumber }.forEach { clutch ->
                    append(" · ${clutch.clutchNumber}차 ${formatDDay(clutch.layingDate)}")
                }
            }
            textSize = 12f
            setTextColor(resColor(R.color.text_secondary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        if (pairClutches.size < MAX_CLUTCHES) {
            metaRow.addView(TextView(activity).apply {
                text = "+ 클러치"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
                setPadding(dp(8), dp(2), 0, dp(2))
                setOnClickListener { view ->
                    view.clickHaptic()
                    showAddClutchDialog(pair, nextClutchNumber(pairClutches))
                }
            })
        }
        column.addView(metaRow)

        card.addView(column, ViewGroup.LayoutParams(match, wrap))
        return card
    }

    private fun sortOrderInput(pair: BreedingPair): EditText = EditText(activity).apply {
        setText(pair.sortOrder.toString())
        textSize = 14f
        gravity = Gravity.CENTER
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(MAX_COUNT_INPUT_LENGTH))
        setTextColor(resColor(R.color.text_primary))
        backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitSortOrder(pair, this)
                clearFocus()
                true
            } else {
                false
            }
        }
        setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) commitSortOrder(pair, view as EditText)
        }
    }

    private fun commitSortOrder(pair: BreedingPair, input: EditText) {
        val newOrder = input.text?.toString()?.trim()?.toIntOrNull() ?: pair.sortOrder
        if (newOrder == pair.sortOrder) return
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    activity.database.breedingPairDao().update(pair.also { it.sortOrder = newOrder })
                }
            }.onFailure {
                activity.showBriefToast("순번을 저장하지 못했습니다")
            }
        }
    }

    // ---- 상세 화면 ----

    private fun createDetailView(pair: BreedingPair): View {
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
            text = "← 브리딩"
            textSize = 14f
            setTextColor(resColor(R.color.forest))
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                detailPairId = null
                activity.replaceTopContent(createContentView())
            }
        })

        val parentsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        parentsRow.addView(parentChip(pair.maleReptileId, maleDisplayName(pair)), LinearLayout.LayoutParams(0, wrap, 1f))
        parentsRow.addView(TextView(activity).apply {
            text = "×"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8); marginEnd = dp(8) })
        parentsRow.addView(parentChip(pair.femaleReptileId, femaleDisplayName(pair)), LinearLayout.LayoutParams(0, wrap, 1f))
        content.addView(parentsRow)

        val matingCard = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor(R.color.surface_card))
        }
        val matingColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        matingColumn.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "메이팅 날짜 · ${LocalDate.ofEpochDay(pair.matingDate).format(dateFormatter)}"
                textSize = 14f
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = formatDDay(pair.matingDate)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
            })
        })
        matingColumn.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "메이팅 날짜 수정"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(resColor(R.color.forest))
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.surface_card))
            setOnClickListener { view ->
                view.clickHaptic()
                editMatingDate(pair)
            }
        }, LinearLayout.LayoutParams(wrap, dp(36)).apply { topMargin = dp(10) })
        matingCard.addView(matingColumn, ViewGroup.LayoutParams(match, wrap))
        content.addView(matingCard, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })

        val pairClutches = clutchesFor(pair.id).sortedBy { it.clutchNumber }
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(4))
            addView(TextView(context).apply {
                text = "클러치 (${pairClutches.size}/$MAX_CLUTCHES)"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            if (pairClutches.size < MAX_CLUTCHES) {
                addView(MaterialButton(context).apply {
                    text = "클러치 추가"
                    textSize = 13f
                    setTextColor(resColor(R.color.forest))
                    backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(38)
                    minimumHeight = dp(38)
                    insetTop = 0
                    insetBottom = 0
                    setOnClickListener { view ->
                        view.clickHaptic()
                        showAddClutchDialog(pair, nextClutchNumber(pairClutches))
                    }
                })
            }
        })

        if (pairClutches.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "아직 등록된 클러치가 없습니다"
                textSize = 13f
                setTextColor(resColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            pairClutches.forEach { clutch ->
                content.addView(clutchCard(clutch), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
            }
        }

        content.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "브리딩 기록 삭제"
            setTextColor(resColor(R.color.danger))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(resColor(R.color.danger))
            strokeWidth = dp(1)
            cornerRadius = dp(12)
            setOnClickListener { view ->
                view.clickHaptic()
                confirmDeletePair(pair)
            }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(28) })

        return scrollView
    }

    private fun parentChip(reptileId: Long?, name: String): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(ImageView(activity).apply {
            background = activity.getDrawable(R.drawable.bg_profile_circle)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_lizard_placeholder)
            reptilePhotoUri(reptileId)?.let { uri ->
                Glide.with(activity)
                    .load(android.net.Uri.parse(uri))
                    .override(dp(44), dp(44))
                    .centerCrop()
                    .into(this)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(TextView(activity).apply {
            text = name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(96)
        }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(10) })
        return row
    }

    private fun clutchCard(clutch: Clutch): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = resColor(R.color.forest_light)
            setCardBackgroundColor(resColor(R.color.surface_card))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        column.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "${clutch.clutchNumber}차"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = formatDDay(clutch.layingDate)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
            })
        })
        column.addView(TextView(activity).apply {
            text = "산란일 · ${LocalDate.ofEpochDay(clutch.layingDate).format(dateFormatter)}"
            textSize = 13f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        column.addView(TextView(activity).apply {
            text = "인큐베이터 온도 · ${formatTemp(clutch.incubatorTemp)}"
            textSize = 13f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        })
        column.addView(TextView(activity).apply {
            text = "무정란 ${clutch.infertileEggCount} · 유정란 ${clutch.fertileEggCount} · 중간 탈락 ${clutch.lostEggCount}"
            textSize = 13f
            setTextColor(resColor(R.color.text_primary))
            setPadding(0, dp(6), 0, 0)
        })

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        actionsRow.addView(MaterialButton(activity).apply {
            text = "해츨링 추가"
            textSize = 12f
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { view ->
                view.clickHaptic()
                showAddHatchlingDialog(clutch)
            }
        })
        actionsRow.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "클러치 삭제"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(resColor(R.color.danger))
            setTextColor(resColor(R.color.danger))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.surface_card))
            setOnClickListener { view ->
                view.clickHaptic()
                confirmDeleteClutch(clutch)
            }
        }, LinearLayout.LayoutParams(wrap, dp(36)).apply { marginStart = dp(8) })
        column.addView(actionsRow)

        val clutchHatchlings = hatchlingsFor(clutch.id)
        if (clutchHatchlings.isNotEmpty()) {
            column.addView(View(activity).apply {
                setBackgroundColor(resColor(R.color.forest_light))
            }, LinearLayout.LayoutParams(match, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(8) })
            clutchHatchlings.forEach { hatchling ->
                column.addView(hatchlingRow(hatchling))
            }
        }

        card.addView(column, ViewGroup.LayoutParams(match, wrap))
        return card
    }

    private fun hatchlingRow(hatchling: Hatchling): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(activity).apply {
            text = buildString {
                append("정상 ${hatchling.normalCount} · 사망 ${hatchling.deathCount} · 장애 ${hatchling.disabledCount}")
                if (!hatchling.disabledReason.isNullOrBlank()) append("(${hatchling.disabledReason})")
                append(" · 중간 탈락 ${hatchling.midDropCount}")
            }
            textSize = 12f
            setTextColor(resColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(TextView(activity).apply {
            text = "삭제"
            textSize = 12f
            setTextColor(resColor(R.color.danger))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { view ->
                view.clickHaptic()
                confirmDeleteHatchling(hatchling)
            }
        })
        return row
    }

    // ---- 추가/수정 다이얼로그 ----

    /** 팝업(다이얼로그) 창이 액티비티와 다른 상태바/내비게이션 바 색과 아이콘 대비를 쓰지 않도록,
     *  현재 액티비티 창의 설정을 그대로 맞춰준다. 다이얼로그는 별도의 Window라 기본값(테마의 정적 색상)으로
     *  뜨는데, 액티비티는 런타임에 배경색을 덮어써두기 때문에 그대로 두면 팝업이 뜰 때 상태바 색이 어긋난다. */
    private fun matchSystemBarsToActivity(window: android.view.Window?) {
        window ?: return
        window.statusBarColor = activity.window.statusBarColor
        window.navigationBarColor = activity.window.navigationBarColor
        val activityController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val dialogController = WindowCompat.getInsetsController(window, window.decorView)
        dialogController.isAppearanceLightStatusBars = activityController.isAppearanceLightStatusBars
        dialogController.isAppearanceLightNavigationBars = activityController.isAppearanceLightNavigationBars
    }

    private fun showAddPairDialog() {
        var selectedMale: Reptile? = null
        var selectedFemale: Reptile? = null
        var selectedMatingDate = LocalDate.now()

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }
        val maleField = pickerField("부(수컷) 선택")
        val femaleField = pickerField("모(암컷) 선택")
        val dateField = pickerField("메이팅 날짜: ${selectedMatingDate.format(dateFormatter)}")

        form.addView(maleField)
        form.addView(femaleField, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        form.addView(dateField, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })

        maleField.setOnClickListener { view ->
            view.clickHaptic()
            showReptilePicker("부(수컷) 선택", "수컷") { reptile ->
                selectedMale = reptile
                maleField.text = "부 · ${reptile.name}"
            }
        }
        femaleField.setOnClickListener { view ->
            view.clickHaptic()
            showReptilePicker("모(암컷) 선택", "암컷") { reptile ->
                selectedFemale = reptile
                femaleField.text = "모 · ${reptile.name}"
            }
        }
        dateField.setOnClickListener { view ->
            view.clickHaptic()
            val pickerDialog = DatePickerDialog(activity, { _, year, month, day ->
                selectedMatingDate = LocalDate.of(year, month + 1, day)
                dateField.text = "메이팅 날짜: ${selectedMatingDate.format(dateFormatter)}"
            }, selectedMatingDate.year, selectedMatingDate.monthValue - 1, selectedMatingDate.dayOfMonth)
            pickerDialog.datePicker.maxDate = System.currentTimeMillis()
            matchSystemBarsToActivity(pickerDialog.window)
            pickerDialog.show()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("브리딩 기록 추가")
            .setView(form)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가", null)
            .create()
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            positiveButton.setOnClickListener { button ->
                val male = selectedMale
                val female = selectedFemale
                if (male == null || female == null) {
                    activity.showBriefToast("부, 모 개체를 모두 선택해 주세요")
                    return@setOnClickListener
                }
                val pair = BreedingPair().apply {
                    maleReptileId = male.id
                    maleName = male.name
                    femaleReptileId = female.id
                    femaleName = female.name
                    this.matingDate = selectedMatingDate.toEpochDay()
                    sortOrder = (pairs.maxOfOrNull { it.sortOrder } ?: 0) + 1
                    createdAt = System.currentTimeMillis()
                }
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.breedingPairDao().insert(pair) }
                    }.onSuccess {
                        activity.showBriefToast("브리딩 기록을 추가했습니다")
                        dialog.dismiss()
                    }.onFailure {
                        button.isEnabled = true
                        activity.showBriefToast("추가하지 못했습니다")
                    }
                }
            }
        }
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.show()
    }

    private fun showReptilePicker(title: String, gender: String, onPicked: (Reptile) -> Unit) {
        val candidates = activity.reptiles.filter { it.gender == gender }
        if (candidates.isEmpty()) {
            activity.showBriefToast("등록된 $gender 개체가 없습니다. 먼저 프로필에서 성별을 ${gender}으로 설정해 주세요")
            return
        }
        val names = candidates.map { "${it.name} · $gender" }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(names) { _, index -> onPicked(candidates[index]) }
            .show()
        matchSystemBarsToActivity(dialog.window)
    }

    private fun showAddClutchDialog(pair: BreedingPair, targetClutchNumber: Int) {
        var selectedLayingDate = LocalDate.now()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }
        form.addView(TextView(activity).apply {
            text = "${targetClutchNumber}차 산란"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
        })
        val dateField = pickerField("산란일: ${selectedLayingDate.format(dateFormatter)}")
        val infertileInput = numberEditText("무정란 수")
        val fertileInput = numberEditText("유정란 수")
        val lostInput = numberEditText("중간 탈락 수")
        val tempInput = decimalEditText("인큐베이터 온도 (°C)")

        form.addView(dateField, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        form.addView(infertileInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        form.addView(fertileInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        form.addView(lostInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        form.addView(tempInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        form.dismissKeyboardOnOutsideTouch(infertileInput, fertileInput, lostInput, tempInput)

        dateField.setOnClickListener { view ->
            view.clickHaptic()
            val pickerDialog = DatePickerDialog(activity, { _, year, month, day ->
                selectedLayingDate = LocalDate.of(year, month + 1, day)
                dateField.text = "산란일: ${selectedLayingDate.format(dateFormatter)}"
            }, selectedLayingDate.year, selectedLayingDate.monthValue - 1, selectedLayingDate.dayOfMonth)
            pickerDialog.datePicker.maxDate = System.currentTimeMillis()
            matchSystemBarsToActivity(pickerDialog.window)
            pickerDialog.show()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("클러치 추가")
            .setView(form)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { button ->
                val clutch = Clutch().apply {
                    pairId = pair.id
                    this.clutchNumber = targetClutchNumber
                    this.layingDate = selectedLayingDate.toEpochDay()
                    infertileEggCount = infertileInput.intValueOrZero()
                    fertileEggCount = fertileInput.intValueOrZero()
                    lostEggCount = lostInput.intValueOrZero()
                    incubatorTemp = tempInput.doubleValueOrNull()
                    createdAt = System.currentTimeMillis()
                }
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.clutchDao().insert(clutch) }
                    }.onSuccess {
                        activity.showBriefToast("클러치를 추가했습니다")
                        dialog.dismiss()
                    }.onFailure {
                        button.isEnabled = true
                        activity.showBriefToast("추가하지 못했습니다")
                    }
                }
            }
        }
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.show()
    }

    private fun showAddHatchlingDialog(clutch: Clutch) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }
        form.addView(TextView(activity).apply {
            text = "해츨링 관리 · ${clutch.clutchNumber}차"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
        })
        val normalInput = numberEditText("정상 개체 수")
        val deathInput = numberEditText("사망 수")
        val disabledInput = numberEditText("장애 개체 수")
        val reasonInput = textEditText("장애 사유 (선택)")
        val midDropInput = numberEditText("중간 탈락 수")
        listOf(normalInput, deathInput, disabledInput, reasonInput, midDropInput).forEach { field ->
            form.addView(field, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        }
        form.dismissKeyboardOnOutsideTouch(normalInput, deathInput, disabledInput, reasonInput, midDropInput)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("해츨링 추가")
            .setView(form)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { button ->
                val hatchling = Hatchling().apply {
                    clutchId = clutch.id
                    normalCount = normalInput.intValueOrZero()
                    deathCount = deathInput.intValueOrZero()
                    disabledCount = disabledInput.intValueOrZero()
                    disabledReason = reasonInput.text?.toString()?.trim().let { if (it.isNullOrBlank()) null else it }
                    midDropCount = midDropInput.intValueOrZero()
                    createdAt = System.currentTimeMillis()
                }
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.hatchlingDao().insert(hatchling) }
                    }.onSuccess {
                        activity.showBriefToast("해츨링 기록을 추가했습니다")
                        dialog.dismiss()
                    }.onFailure {
                        button.isEnabled = true
                        activity.showBriefToast("추가하지 못했습니다")
                    }
                }
            }
        }
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.show()
    }

    private fun editMatingDate(pair: BreedingPair) {
        val current = LocalDate.ofEpochDay(pair.matingDate)
        val pickerDialog = DatePickerDialog(activity, { _, year, month, day ->
            val newDate = LocalDate.of(year, month + 1, day)
            activity.lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        activity.database.breedingPairDao().update(pair.also { it.matingDate = newDate.toEpochDay() })
                    }
                }.onFailure {
                    activity.showBriefToast("수정하지 못했습니다")
                }
            }
        }, current.year, current.monthValue - 1, current.dayOfMonth)
        pickerDialog.datePicker.maxDate = System.currentTimeMillis()
        matchSystemBarsToActivity(pickerDialog.window)
        pickerDialog.show()
    }

    private fun confirmDeletePair(pair: BreedingPair) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("브리딩 기록 삭제")
            .setMessage("이 브리딩 기록과 등록된 클러치·해츨링 기록이 모두 삭제됩니다. 되돌릴 수 없어요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.breedingPairDao().delete(pair) }
                    }.onSuccess {
                        detailPairId = null
                        activity.replaceTopContent(createContentView())
                        activity.showBriefToast("삭제했습니다")
                    }.onFailure {
                        activity.showBriefToast("삭제하지 못했습니다")
                    }
                }
            }
            .show()
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(resColor(R.color.danger))
    }

    private fun confirmDeleteClutch(clutch: Clutch) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("클러치 삭제")
            .setMessage("${clutch.clutchNumber}차 클러치와 관련된 해츨링 기록이 함께 삭제됩니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.clutchDao().delete(clutch) }
                    }.onSuccess {
                        activity.showBriefToast("삭제했습니다")
                    }.onFailure {
                        activity.showBriefToast("삭제하지 못했습니다")
                    }
                }
            }
            .show()
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(resColor(R.color.danger))
    }

    private fun confirmDeleteHatchling(hatchling: Hatchling) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("해츨링 기록 삭제")
            .setMessage("이 해츨링 기록을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { activity.database.hatchlingDao().delete(hatchling) }
                    }.onSuccess {
                        activity.showBriefToast("삭제했습니다")
                    }.onFailure {
                        activity.showBriefToast("삭제하지 못했습니다")
                    }
                }
            }
            .show()
        matchSystemBarsToActivity(dialog.window)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(resColor(R.color.danger))
    }

    // ---- 입력 필드 헬퍼 ----

    private fun pickerField(initialText: String): TextView = TextView(activity).apply {
        text = initialText
        textSize = 14f
        setTextColor(resColor(R.color.text_primary))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = activity.getDrawable(R.drawable.bg_photo_placeholder)
        isClickable = true
        isFocusable = true
    }

    private fun numberEditText(hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        textSize = 14f
        inputType = InputType.TYPE_CLASS_NUMBER
        // Int 범위를 넘는 값은 toIntOrNull이 null이 되어 조용히 0으로 저장된다. 아예 못 넣게 막는다.
        filters = arrayOf(InputFilter.LengthFilter(MAX_COUNT_INPUT_LENGTH))
        setTextColor(resColor(R.color.text_primary))
        setHintTextColor(resColor(R.color.text_secondary))
        backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
    }

    private fun decimalEditText(hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        textSize = 14f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        filters = arrayOf(InputFilter.LengthFilter(MAX_DECIMAL_INPUT_LENGTH))
        setTextColor(resColor(R.color.text_primary))
        setHintTextColor(resColor(R.color.text_secondary))
        backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
    }

    private fun textEditText(hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(resColor(R.color.text_primary))
        setHintTextColor(resColor(R.color.text_secondary))
        backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
    }

    private fun EditText.intValueOrZero(): Int = text?.toString()?.trim()?.toIntOrNull() ?: 0
    private fun EditText.doubleValueOrNull(): Double? = text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()

    // ---- 데이터 조회/표시 헬퍼 ----

    /** 회차 번호는 가장 큰 번호 다음이다. 개수를 쓰면 2차를 지운 뒤 추가할 때
     *  이미 있는 3차와 번호가 겹쳐 두 기록을 구분할 수 없게 된다.
     *  '동시에 몇 개까지'(MAX_CLUTCHES)는 이것과 별개의 축이라 개수로 따로 센다 —
     *  그래서 지웠다 다시 넣으면 번호는 6차, 7차로 계속 올라간다(실제 몇 번째 산란인지 그대로). */
    private fun nextClutchNumber(pairClutches: List<Clutch>): Int =
        (pairClutches.maxOfOrNull { it.clutchNumber } ?: 0) + 1

    private fun clutchesFor(pairId: Long): List<Clutch> = clutches.filter { it.pairId == pairId }
    private fun hatchlingsFor(clutchId: Long): List<Hatchling> = hatchlings.filter { it.clutchId == clutchId }

    private fun maleDisplayName(pair: BreedingPair): String =
        pair.maleReptileId?.let { id -> activity.allReptiles.firstOrNull { it.id == id }?.name }
            ?: pair.maleName.ifBlank { "미상" }

    private fun femaleDisplayName(pair: BreedingPair): String =
        pair.femaleReptileId?.let { id -> activity.allReptiles.firstOrNull { it.id == id }?.name }
            ?: pair.femaleName.ifBlank { "미상" }

    private fun reptilePhotoUri(reptileId: Long?): String? =
        reptileId?.let { id -> activity.allReptiles.firstOrNull { it.id == id }?.photoUri }

    private fun formatDDay(epochDay: Long): String {
        val days = LocalDate.now().toEpochDay() - epochDay
        return if (days >= 0) "D+$days" else "D-${-days}"
    }

    private fun formatTemp(temp: Double?): String {
        if (temp == null) return "미입력"
        return if (temp % 1.0 == 0.0) "${temp.toInt()}°C" else "${temp}°C"
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

    companion object {
        // Int.MAX_VALUE가 10자리라 9자리까지만 받으면 오버플로가 원천적으로 안 생긴다.
        private const val MAX_COUNT_INPUT_LENGTH = 9
        private const val MAX_DECIMAL_INPUT_LENGTH = 6

        private const val MAX_CLUTCHES = 5
    }
}
