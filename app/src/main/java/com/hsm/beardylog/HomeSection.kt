package com.hsm.beardylog

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hsm.beardylog.data.CareLog
import com.hsm.beardylog.data.CareSchedule
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.WeightChartPreferences
import com.hsm.beardylog.ui.setWeightNumberText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * MainActivity의 "홈" 대시보드(프로필 그리드, 이번 주 식단, 무게 추이) 전용 뷰 빌드와 상태를 담당한다.
 * 다른 Section과 동일한 패턴: MainActivity가 소유하며, SettingsSection 등 다른 곳에서 필요한 진입점은
 * MainActivity의 얇은 위임 함수(renderDashboard/renderProfiles/restoreLastSelectedProfile/loadWeightHistory)를 통해 호출된다.
 */
internal class HomeSection(private val activity: MainActivity) {

    private var schedules: List<CareSchedule> = emptyList()
    private var careLogs: List<CareLog> = emptyList()
    private var profileSortMode = 0
    private var initialProfileSelectionResolved = false
    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")

    // ---- MainActivity가 호출하는 진입점 ----

    internal fun setup() {
        profileSortMode = activity.appSettings.profileSortMode
        activity.binding.profileSortSpinner.setSelection(profileSortMode)
        observeCareData()
        setupActions()
    }

    internal fun resolveInitialProfileSelection() {
        if (initialProfileSelectionResolved) return
        initialProfileSelectionResolved = true
        if (!activity.isAutoSelectLastProfileEnabled()) {
            activity.selectedReptileId = null
            return
        }
        activity.selectedReptileId = activity.appSettings.lastSelectedProfileId?.takeIf { id -> activity.reptiles.any { it.id == id } }
    }

    internal fun restoreLastSelectedProfile() {
        activity.selectedReptileId = activity.appSettings.lastSelectedProfileId?.takeIf { id -> activity.reptiles.any { it.id == id } }
        renderProfiles()
        renderDashboard()
    }

    internal fun renderDashboard() {
        val selected = activity.reptiles.firstOrNull { it.id == activity.selectedReptileId }
        if (selected == null) {
            activity.binding.selectedProfileInfo.text = if (activity.reptiles.isEmpty()) {
                "개체 프로필을 추가해 관리를 시작해 보세요"
            } else {
                "프로필을 선택하면 식단과 무게 정보를 확인할 수 있어요"
            }
            activity.binding.selectedProfileDetailButton.visibility = View.GONE
            activity.binding.plannedCareList.removeAllViews()
            activity.binding.plannedCareList.addView(TextView(activity).apply {
                text = "선택된 개체가 없습니다"
                setTextColor(activity.appColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            val today = LocalDate.now()
            val weekStart = today.with(DayOfWeek.MONDAY)
            activity.binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
            activity.binding.weekDays.removeAllViews()
            val weekCellBottomSpace = dp(4)
            activity.binding.weekDays.layoutParams = activity.binding.weekDays.layoutParams.apply { height = dp(64) + weekCellBottomSpace }
            repeat(7) { index ->
                val date = weekStart.plusDays(index.toLong())
                activity.binding.weekDays.addView(TextView(activity).apply {
                    gravity = Gravity.CENTER
                    text = "${dayLabels[index]}\n-"
                    textSize = 11f
                    setPadding(0, 0, 0, dp(6))
                    setTextColor(if (date == today) Color.WHITE else activity.appColor(R.color.text_secondary))
                    setBackgroundResource(if (date == today) R.drawable.bg_home_week_day_selected else R.drawable.bg_week_day)
                }, android.widget.LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    setMargins(dp(2), 0, dp(2), weekCellBottomSpace)
                })
            }
            activity.binding.weightChart.setValues(emptyList())
            activity.binding.weightAxisLabels.setValues(emptyList())
            activity.binding.currentWeight.text = "기록 없음"
            activity.binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(activity).displayName
            activity.binding.homeWeightChartContent.visibility = View.INVISIBLE
            activity.binding.homeWeightChartEmpty.visibility = View.VISIBLE
            activity.binding.homeWeightChartEmpty.text = if (activity.reptiles.isEmpty()) "프로필을 추가해 주세요" else "프로필을 선택해 주세요"
            return
        }
        activity.binding.selectedProfileInfo.text = buildList {
            add(selected.name)
            addAll(listOf(selected.species, selected.morph).filter { it.isNotBlank() })
            add(selected.gender?.ifBlank { "미구분" } ?: "미구분")
        }.joinToString(" · ")
        activity.binding.selectedProfileDetailButton.visibility = View.VISIBLE
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        activity.binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
        activity.binding.weekDays.removeAllViews()
        val weekScheduleEntries = (0..6).map { index ->
            val date = weekStart.plusDays(index.toLong())
            date to schedules.filter { schedule ->
                schedule.reptileId == selected.id && isDietSchedule(schedule) && isScheduledOn(schedule, date)
            }
        }
        val maxDietCount = weekScheduleEntries.maxOfOrNull { it.second.size }?.coerceAtLeast(1) ?: 1
        val weekCellHeight = dp(64 + (maxDietCount - 1) * 16)
        val weekCellBottomSpace = dp(4)
        activity.binding.weekDays.layoutParams = activity.binding.weekDays.layoutParams.apply { height = weekCellHeight + weekCellBottomSpace }
        weekScheduleEntries.forEachIndexed { index, (date, daySchedules) ->
            activity.binding.weekDays.addView(TextView(activity).apply {
                gravity = Gravity.CENTER
                text = "${dayLabels[index]}\n${if (daySchedules.isEmpty()) "-" else daySchedules.joinToString("\n") { it.careType }}"
                textSize = 11f
                setPadding(0, 0, 0, dp(6))
                setTextColor(if (date == LocalDate.now()) Color.WHITE else activity.appColor(R.color.text_secondary))
                setBackgroundResource(if (date == LocalDate.now()) R.drawable.bg_home_week_day_selected else R.drawable.bg_week_day)
            }, android.widget.LinearLayout.LayoutParams(0, weekCellHeight, 1f).apply {
                setMargins(dp(2), 0, dp(2), weekCellBottomSpace)
            })
        }
        renderPlannedCare(selected.id)
        activity.binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(activity).displayName
        loadWeightHistory(selected.id)
    }

    internal fun renderProfiles() {
        activity.binding.profileGrid.removeAllViews()
        val sortedReptiles = sortedProfiles()
        val totalItems = sortedReptiles.size + 1
        val columns = profileGridColumns(totalItems)
        activity.binding.profileGrid.columnCount = columns

        val addItem = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { view ->
                view.clickHaptic()
                openEditor(null)
            }
        }
        addItem.addView(ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = activity.getDrawable(R.drawable.bg_profile_add_circle)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(R.drawable.ic_input_add)
            imageTintList = android.content.res.ColorStateList.valueOf(activity.appColor(R.color.forest))
        })
        addItem.addView(TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            text = "프로필 추가"
            textSize = 12f
            maxLines = 1
            includeFontPadding = false
            setTextColor(activity.appColor(R.color.forest))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        activity.binding.profileGrid.addView(addItem, gridCell(0, 0))

        sortedReptiles.forEachIndexed { index, reptile ->
            val isSelectedProfile = reptile.id == activity.selectedReptileId
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                tag = reptile.id
                contentDescription = "${reptile.name}, ${if (isSelectedProfile) "선택됨, " else ""}눌러 선택, 길게 눌러 상세 보기"
                if (isSelectedProfile) setBackgroundResource(R.drawable.bg_profile_selected)
                setOnClickListener { view ->
                    view.selectionHaptic()
                    if (activity.selectedReptileId == reptile.id) return@setOnClickListener
                    activity.selectedReptileId = reptile.id
                    activity.appSettings.lastSelectedProfileId = reptile.id
                    renderProfiles()
                    renderDashboard()
                }
                setOnLongClickListener { view ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    openDetail(reptile.id)
                    true
                }
            }
            val image = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                background = activity.getDrawable(R.drawable.bg_profile_circle)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
                setImageResource(R.drawable.ic_lizard_placeholder)
                reptile.photoUri?.let { uri ->
                    Glide.with(activity)
                        .load(android.net.Uri.parse(uri))
                        .override(dp(56), dp(56))
                        .centerCrop()
                        .placeholder(R.drawable.ic_lizard_placeholder)
                        .error(R.drawable.ic_lizard_placeholder)
                        .into(this)
                }
            }
            item.addView(image)
            item.addView(TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
                text = reptile.name
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(if (isSelectedProfile) activity.appColor(R.color.forest) else activity.appColor(R.color.text_secondary))
                setTypeface(typeface, if (isSelectedProfile) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(0, dp(3), 0, 0)
                gravity = Gravity.CENTER
            })
            val itemIndex = index + 1
            activity.binding.profileGrid.addView(item, gridCell(itemIndex / columns, itemIndex % columns))
        }
    }

    internal fun loadWeightHistory(reptileId: Long) {
        activity.lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { activity.database.weightRecordDao().allForReptile(reptileId) }
            if (activity.selectedReptileId != reptileId) return@launch
            val period = WeightChartPreferences.homePeriod(activity)
            val cutoff = period.cutoffEpochDay()
            val visibleRecords = records.filter { it.recordedAt >= cutoff }
            val values = visibleRecords.map { it.grams }
            val labels = visibleRecords.map { record ->
                LocalDate.ofEpochDay(record.recordedAt).format(DateTimeFormatter.ofPattern("M/d"))
            }
            val hasVisibleRecords = visibleRecords.isNotEmpty()
            activity.binding.weightPeriodLabel.text = period.displayName
            activity.binding.homeWeightChartContent.visibility = if (hasVisibleRecords) View.VISIBLE else View.INVISIBLE
            activity.binding.homeWeightChartEmpty.visibility = if (hasVisibleRecords) View.GONE else View.VISIBLE
            activity.binding.homeWeightChartEmpty.setWeightNumberText("${period.displayName} 기록 없음")
            activity.binding.weightChart.setValues(values, labels)
            activity.binding.weightAxisLabels.setValues(values)
            activity.binding.currentWeight.setWeightNumberText(records.lastOrNull()?.grams?.let { "최근 ${formatGrams(it)}g" } ?: "기록 없음")
        }
    }

    // ---- 내부 구현 ----

    private fun observeCareData() {
        activity.database.careScheduleDao().observeAll().observe(activity) {
            schedules = it
            renderDashboard()
        }
        activity.database.careLogDao().observeAll().observe(activity) {
            careLogs = it
            renderDashboard()
        }
    }

    /** 앱이 백그라운드에서 며칠 살아 있어도 홈이 오늘 기준으로 다시 그려지도록, 돌아올 때마다 갱신한다. */
    internal fun onResume() {
        activity.binding.currentDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
        renderDashboard()
    }

    private fun setupActions() {
        activity.binding.currentDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
        activity.binding.badge.setOnClickListener { view ->
            view.clickHaptic()
            activity.showBriefToast("베타테스트에 참여해 주셔서 감사합니다.")
        }
        activity.binding.selectedProfileDetailButton.setOnClickListener { view ->
            view.confirmHaptic()
            activity.selectedReptileId?.let(::openDetail)
        }
        activity.binding.profileSortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (profileSortMode != position) {
                    profileSortMode = position
                    activity.appSettings.profileSortMode = position
                    renderProfiles()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        activity.binding.weightDetailButton.setOnClickListener { view ->
            view.clickHaptic()
            openWeightHistory()
        }
    }

    private fun isDietSchedule(schedule: CareSchedule): Boolean = schedule.careType in setOf("충식", "채식", "사료", "금식")

    private fun isScheduledOn(schedule: CareSchedule, date: LocalDate): Boolean =
        schedule.repeatDayOfWeek == date.dayOfWeek.value || (schedule.repeatDayOfWeek == null && schedule.scheduledDate == date.toEpochDay())

    private fun renderPlannedCare(reptileId: Long) {
        activity.binding.plannedCareList.removeAllViews()
        val today = LocalDate.now()
        val occurrences = schedules
            .filter { it.reptileId == reptileId && isDietSchedule(it) && isScheduledOn(it, today) }
            .map { it to today }
        if (occurrences.isEmpty()) {
            activity.binding.plannedCareList.addView(TextView(activity).apply {
                text = "오늘 예정된 식단이 없습니다"
                setTextColor(activity.appColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        occurrences.forEach { (schedule, date) ->
            val status = careLogs.firstOrNull { it.scheduleId == schedule.id && it.completedDate == date.toEpochDay() }?.status ?: "미실시"
            val check = android.widget.CheckBox(activity).apply {
                text = buildString {
                    append("${date.format(DateTimeFormatter.ofPattern("M/d (E)"))}  ${schedule.careType}")
                    if (!schedule.memo.isNullOrBlank()) append(" · ${schedule.memo}")
                    append("  [$status]")
                }
                isChecked = status == "완료"
                setTextColor(if (status == "미실시") activity.appColor(R.color.danger) else activity.appColor(R.color.text_primary))
                setOnClickListener { view ->
                    view.selectionHaptic()
                    setCareStatus(schedule, date.toEpochDay(), if (isChecked) "완료" else "미실시")
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            }
            activity.binding.plannedCareList.addView(check)
        }
    }

    private fun setCareStatus(schedule: CareSchedule, date: Long, status: String) {
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    activity.database.careLogDao().deleteForDate(schedule.id, date)
                    activity.database.careLogDao().insert(CareLog().apply {
                        scheduleId = schedule.id
                        completedDate = date
                        this.status = status
                    })
                }
            }.onFailure {
                activity.showBriefToast("상태를 저장하지 못했습니다")
            }
        }
    }

    private fun formatGrams(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

    private fun gridCell(row: Int, column: Int): android.widget.GridLayout.LayoutParams = android.widget.GridLayout.LayoutParams(
        android.widget.GridLayout.spec(row), android.widget.GridLayout.spec(column)
    ).apply {
        width = dp(PROFILE_GRID_CELL_WIDTH_DP)
        height = dp(96)
    }

    private fun profileGridColumns(totalItems: Int): Int {
        val balancedColumns = ((totalItems + PROFILE_GRID_ROWS - 1) / PROFILE_GRID_ROWS).coerceAtLeast(1)
        val scrollWidth = (activity.binding.profileGrid.parent as? View)?.width?.takeIf { it > 0 }
            ?: (activity.resources.displayMetrics.widthPixels - dp(PROFILE_GRID_FALLBACK_HORIZONTAL_PADDING_DP))
        val visibleColumns = (scrollWidth / dp(PROFILE_GRID_CELL_WIDTH_DP)).coerceAtLeast(1)
        return balancedColumns.coerceAtLeast(visibleColumns)
    }

    private fun sortedProfiles(): List<Reptile> {
        return ProfileSorter.sortedProfiles(activity.reptiles, profileSortMode)
    }

    private fun openEditor(id: Long?) = activity.startActivity(Intent(activity, ReptileEditActivity::class.java).apply { id?.let { putExtra(ReptileEditActivity.EXTRA_ID, it) } })
    private fun openDetail(id: Long) = activity.startActivity(Intent(activity, ReptileDetailActivity::class.java).putExtra(ReptileDetailActivity.EXTRA_ID, id))
    private fun openWeightHistory() {
        val reptileId = activity.selectedReptileId
        if (reptileId == null) {
            activity.showBriefToast(if (activity.reptiles.isEmpty()) "먼저 개체를 등록해 주세요" else "먼저 개체를 선택해 주세요")
            return
        }
        activity.startActivity(Intent(activity, WeightHistoryActivity::class.java).putExtra(WeightHistoryActivity.EXTRA_REPTILE_ID, reptileId))
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val PROFILE_GRID_ROWS = 2
        private const val PROFILE_GRID_CELL_WIDTH_DP = 82
        private const val PROFILE_GRID_FALLBACK_HORIZONTAL_PADDING_DP = 40
    }
}
