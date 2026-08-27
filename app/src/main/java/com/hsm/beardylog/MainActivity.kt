package com.hsm.beardylog

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.CareSchedule
import com.hsm.beardylog.data.CareLog
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.data.WeightChartPreferences
import com.hsm.beardylog.databinding.ActivityMainBinding
import com.hsm.beardylog.ui.setWeightNumberText
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppBaseActivity() {
    internal lateinit var binding: ActivityMainBinding
    override val bottomInsetTarget: View?
        get() = if (::binding.isInitialized) binding.bottomNavigation else null
    private lateinit var viewModel: ReptileViewModel
    internal var reptiles: List<Reptile> = emptyList()
    internal var allReptiles: List<Reptile> = emptyList()
    private val memorialSection = MemorialSection(this)
    private val breedingSection = BreedingSection(this)
    private val settingsSection = SettingsSection(this)
    private var schedules: List<CareSchedule> = emptyList()
    private var careLogs: List<CareLog> = emptyList()
    internal var selectedReptileId: Long? = null
    private var profileSortMode = 0
    private var initialProfileSelectionResolved = false
    internal lateinit var database: AppDatabase
    internal lateinit var appSettings: AppSettings
    private lateinit var mainColumn: LinearLayout
    private lateinit var homeContent: View
    internal var currentTopContent: View? = null
    internal var currentSection = MainSection.HOME
    private val calendarSection = CalendarSection(this)
    internal var settingsScrollY = 0
    private var isNavigationHapticsReady = false
    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    internal val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        settingsSection.handleDriveAuthorizationResult(result)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRootContent()
        registerBackHandler()
        restoreMainState(savedInstanceState)
        setupDataLayer()
        observeCareData()
        setupHomeActions()
        setupBottomNavigation(savedInstanceState)
        if (isUpdateCheckEnabled()) settingsSection.checkForUpdate()
        settingsSection.showPendingThemeTransition()
        observeProfiles()
    }

    private fun setupRootContent() {
        mainColumn = binding.root.getChildAt(0) as LinearLayout
        homeContent = mainColumn.getChildAt(0)
        currentTopContent = homeContent
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleMainBackPressed()
            }
        })
    }

    private fun restoreMainState(savedInstanceState: Bundle?) {
        calendarSection.restore(savedInstanceState)
        settingsScrollY = savedInstanceState?.getInt(KEY_SETTINGS_SCROLL_Y, 0) ?: 0
    }

    private fun setupDataLayer() {
        database = AppDatabase.getInstance(applicationContext)
        calendarSection.setup()
        breedingSection.setup()
        settingsSection.setup()
        appSettings = AppSettings(this)
        profileSortMode = appSettings.profileSortMode
        binding.profileSortSpinner.setSelection(profileSortMode)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(database.reptileDao())))[ReptileViewModel::class.java]
    }

    private fun observeCareData() {
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay()
        database.careScheduleDao().observeBetween(weekStart, weekStart + 6).observe(this) {
            schedules = it
            renderDashboard()
        }
        database.careLogDao().observeAll().observe(this) {
            careLogs = it
            renderDashboard()
        }
    }

    private fun setupHomeActions() {
        binding.currentDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
        binding.badge.setOnClickListener { view ->
            view.clickHaptic()
            showBriefToast("베타테스트에 참여해 주셔서 감사합니다.")
        }
        binding.selectedProfileDetailButton.setOnClickListener { view ->
            view.confirmHaptic()
            selectedReptileId?.let(::openDetail)
        }
        binding.profileSortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (profileSortMode != position) {
                    profileSortMode = position
                    appSettings.profileSortMode = position
                    renderProfiles()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.weightDetailButton.setOnClickListener { view ->
            view.clickHaptic()
            openWeightHistory()
        }
    }

    private fun setupBottomNavigation(savedInstanceState: Bundle?) {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val section = sectionForMenuItem(item.itemId)
            if (section == null) {
                false
            } else {
                if (isNavigationHapticsReady && currentSection != section) {
                    binding.bottomNavigation.selectionHaptic()
                }
                showMainSection(section)
                true
            }
        }
        val restoredSection = savedInstanceState?.getString(KEY_CURRENT_SECTION)?.let { value ->
            runCatching { MainSection.valueOf(value) }.getOrNull()
        } ?: MainSection.HOME
        binding.bottomNavigation.selectedItemId = menuIdForSection(restoredSection)
        showMainSection(restoredSection)
        isNavigationHapticsReady = true
    }

    private fun sectionForMenuItem(itemId: Int): MainSection? = when (itemId) {
        R.id.nav_home -> MainSection.HOME
        R.id.nav_breeding -> MainSection.BREEDING
        R.id.nav_calendar -> MainSection.CALENDAR
        R.id.nav_memorial -> MainSection.MEMORIAL
        R.id.nav_settings -> MainSection.SETTINGS
        else -> null
    }

    private fun observeProfiles() {
        viewModel.reptiles.observe(this) {
            allReptiles = it
            reptiles = it.filter { reptile -> reptile.deathDate == null }
            settingsSection.updateDriveActionAvailability()
            resolveInitialProfileSelection()
            if (selectedReptileId != null && reptiles.none { reptile -> reptile.id == selectedReptileId }) selectedReptileId = null
            renderProfiles()
            renderDashboard()
            if (currentSection == MainSection.MEMORIAL) memorialSection.refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_SECTION, currentSection.name)
        calendarSection.save(outState)
        outState.putInt(KEY_SETTINGS_SCROLL_Y, settingsScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(this).displayName
        selectedReptileId?.let(::loadWeightHistory)
        if (currentSection == MainSection.MEMORIAL && memorialSection.hasOpenDetail()) {
            replaceTopContent(memorialSection.createContentView())
        }
        if (currentSection == MainSection.BREEDING && breedingSection.hasOpenDetail()) {
            replaceTopContent(breedingSection.createContentView())
        }
    }

    internal fun isUpdateCheckEnabled(): Boolean =
        appSettings.checkUpdatesOnStart

    internal fun isAutoSelectLastProfileEnabled(): Boolean =
        appSettings.autoSelectLastProfile

    private fun resolveInitialProfileSelection() {
        if (initialProfileSelectionResolved) return
        initialProfileSelectionResolved = true
        if (!isAutoSelectLastProfileEnabled()) {
            selectedReptileId = null
            return
        }
        selectedReptileId = appSettings.lastSelectedProfileId?.takeIf { id -> reptiles.any { it.id == id } }
    }

    private fun showMainSection(section: MainSection) {
        if (currentSection == section) return
        currentSection = section
        if (section != MainSection.CALENDAR) {
            calendarSection.leave()
        }
        if (section != MainSection.MEMORIAL) {
            memorialSection.leave()
        }
        if (section != MainSection.BREEDING) {
            breedingSection.leave()
        }
        replaceTopContent(when (section) {
            MainSection.HOME -> homeContent
            MainSection.BREEDING -> breedingSection.createContentView()
            MainSection.CALENDAR -> calendarSection.createCalendarContentView()
            MainSection.MEMORIAL -> memorialSection.createContentView()
            MainSection.SETTINGS -> settingsSection.createContentView()
        })
    }

    private fun handleMainBackPressed() {
        if (currentSection == MainSection.MEMORIAL && memorialSection.closeDetail()) {
            replaceTopContent(memorialSection.createContentView())
            return
        }
        if (currentSection == MainSection.BREEDING && breedingSection.closeDetail()) {
            replaceTopContent(breedingSection.createContentView())
            return
        }
        if (calendarSection.handleBackPressed()) return
        if (currentSection != MainSection.HOME) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            return
        }
        finish()
    }

    private fun menuIdForSection(section: MainSection): Int = when (section) {
        MainSection.HOME -> R.id.nav_home
        MainSection.BREEDING -> R.id.nav_breeding
        MainSection.CALENDAR -> R.id.nav_calendar
        MainSection.MEMORIAL -> R.id.nav_memorial
        MainSection.SETTINGS -> R.id.nav_settings
    }

    internal fun replaceTopContent(content: View) {
        if (currentTopContent === content) return
        mainColumn.removeViewAt(0)
        mainColumn.addView(content, 0, LinearLayout.LayoutParams(match, 0, 1f))
        currentTopContent = content
    }

    internal fun renderDashboard() {
        val selected = reptiles.firstOrNull { it.id == selectedReptileId }
        if (selected == null) {
            binding.selectedProfileInfo.text = if (reptiles.isEmpty()) {
                "개체 프로필을 추가해 관리를 시작해 보세요"
            } else {
                "프로필을 선택하면 식단과 무게 정보를 확인할 수 있어요"
            }
            binding.selectedProfileDetailButton.visibility = View.GONE
            binding.plannedCareList.removeAllViews()
            binding.plannedCareList.addView(TextView(this).apply {
                text = "선택된 개체가 없습니다"
                setTextColor(appColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            val today = LocalDate.now()
            val weekStart = today.with(DayOfWeek.MONDAY)
            binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
            binding.weekDays.removeAllViews()
            val weekCellBottomSpace = dp(4)
            binding.weekDays.layoutParams = binding.weekDays.layoutParams.apply { height = dp(64) + weekCellBottomSpace }
            repeat(7) { index ->
                val date = weekStart.plusDays(index.toLong())
                binding.weekDays.addView(TextView(this).apply {
                    gravity = Gravity.CENTER
                    text = "${dayLabels[index]}\n-"
                    textSize = 11f
                    setPadding(0, 0, 0, dp(6))
                    setTextColor(if (date == today) Color.WHITE else appColor(R.color.text_secondary))
                    setBackgroundResource(if (date == today) R.drawable.bg_home_week_day_selected else R.drawable.bg_week_day)
                }, android.widget.LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    setMargins(dp(2), 0, dp(2), weekCellBottomSpace)
                })
            }
            binding.weightChart.setValues(emptyList())
            binding.weightAxisLabels.setValues(emptyList())
            binding.currentWeight.text = "기록 없음"
            binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(this).displayName
            binding.homeWeightChartContent.visibility = View.INVISIBLE
            binding.homeWeightChartEmpty.visibility = View.VISIBLE
            binding.homeWeightChartEmpty.text = if (reptiles.isEmpty()) "프로필을 추가해 주세요" else "프로필을 선택해 주세요"
            return
        }
        binding.selectedProfileInfo.text = buildList {
            add(selected.name)
            addAll(listOf(selected.species, selected.morph).filter { it.isNotBlank() })
            add(selected.gender?.ifBlank { "미구분" } ?: "미구분")
        }.joinToString(" · ")
        binding.selectedProfileDetailButton.visibility = View.VISIBLE
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
        binding.weekDays.removeAllViews()
        val weekScheduleEntries = (0..6).map { index ->
            val date = weekStart.plusDays(index.toLong())
            date to schedules.filter { schedule ->
                schedule.reptileId == selected.id && isDietSchedule(schedule) && isScheduledOn(schedule, date)
            }
        }
        val maxDietCount = weekScheduleEntries.maxOfOrNull { it.second.size }?.coerceAtLeast(1) ?: 1
        val weekCellHeight = dp(64 + (maxDietCount - 1) * 16)
        val weekCellBottomSpace = dp(4)
        binding.weekDays.layoutParams = binding.weekDays.layoutParams.apply { height = weekCellHeight + weekCellBottomSpace }
        weekScheduleEntries.forEachIndexed { index, (date, daySchedules) ->
            binding.weekDays.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = "${dayLabels[index]}\n${if (daySchedules.isEmpty()) "-" else daySchedules.joinToString("\n") { it.careType }}"
                textSize = 11f
                setPadding(0, 0, 0, dp(6))
                setTextColor(if (date == LocalDate.now()) Color.WHITE else appColor(R.color.text_secondary))
                setBackgroundResource(if (date == LocalDate.now()) R.drawable.bg_home_week_day_selected else R.drawable.bg_week_day)
            }, android.widget.LinearLayout.LayoutParams(0, weekCellHeight, 1f).apply {
                setMargins(dp(2), 0, dp(2), weekCellBottomSpace)
            })
        }
        renderPlannedCare(selected.id)
        binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(this).displayName
        loadWeightHistory(selected.id)
    }

    private fun isDietSchedule(schedule: CareSchedule): Boolean = schedule.careType in setOf("충식", "채식", "사료", "금식")

    private fun isScheduledOn(schedule: CareSchedule, date: LocalDate): Boolean =
        schedule.repeatDayOfWeek == date.dayOfWeek.value || (schedule.repeatDayOfWeek == null && schedule.scheduledDate == date.toEpochDay())

    private fun renderPlannedCare(reptileId: Long) {
        binding.plannedCareList.removeAllViews()
        val today = LocalDate.now()
        val occurrences = schedules
            .filter { it.reptileId == reptileId && isDietSchedule(it) && isScheduledOn(it, today) }
            .map { it to today }
        if (occurrences.isEmpty()) {
            binding.plannedCareList.addView(TextView(this).apply {
                text = "오늘 예정된 식단이 없습니다"
                setTextColor(appColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        occurrences.forEach { (schedule, date) ->
            val status = careLogs.firstOrNull { it.scheduleId == schedule.id && it.completedDate == date.toEpochDay() }?.status ?: "미실시"
            val check = android.widget.CheckBox(this).apply {
                text = buildString {
                    append("${date.format(DateTimeFormatter.ofPattern("M/d (E)"))}  ${schedule.careType}")
                    if (!schedule.memo.isNullOrBlank()) append(" · ${schedule.memo}")
                    append("  [$status]")
                }
                isChecked = status == "완료"
                setTextColor(if (status == "미실시") appColor(R.color.danger) else appColor(R.color.text_primary))
                setOnClickListener { view ->
                    view.selectionHaptic()
                    setCareStatus(schedule, date.toEpochDay(), if (isChecked) "완료" else "미실시")
                }
                layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            }
            binding.plannedCareList.addView(check)
        }
    }

    private fun setCareStatus(schedule: CareSchedule, date: Long, status: String) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    database.careLogDao().deleteForDate(schedule.id, date)
                    database.careLogDao().insert(CareLog().apply {
                        scheduleId = schedule.id
                        completedDate = date
                        this.status = status
                    })
                }
            }.onFailure {
                showBriefToast("상태를 저장하지 못했습니다")
            }
        }
    }

    internal fun loadWeightHistory(reptileId: Long) {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { database.weightRecordDao().allForReptile(reptileId) }
            if (selectedReptileId != reptileId) return@launch
            val period = WeightChartPreferences.homePeriod(this@MainActivity)
            val cutoff = period.cutoffEpochDay()
            val visibleRecords = records.filter { it.recordedAt >= cutoff }
            val values = visibleRecords.map { it.grams }
            val labels = visibleRecords.map { record ->
                LocalDate.ofEpochDay(record.recordedAt).format(DateTimeFormatter.ofPattern("M/d"))
            }
            val hasVisibleRecords = visibleRecords.isNotEmpty()
            binding.weightPeriodLabel.text = period.displayName
            binding.homeWeightChartContent.visibility = if (hasVisibleRecords) View.VISIBLE else View.INVISIBLE
            binding.homeWeightChartEmpty.visibility = if (hasVisibleRecords) View.GONE else View.VISIBLE
            binding.homeWeightChartEmpty.setWeightNumberText("${period.displayName} 기록 없음")
            binding.weightChart.setValues(values, labels)
            binding.weightAxisLabels.setValues(values)
            binding.currentWeight.setWeightNumberText(records.lastOrNull()?.grams?.let { "최근 ${formatGrams(it)}g" } ?: "기록 없음")
        }
    }

    private fun formatGrams(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

    internal fun renderProfiles() {
        binding.profileGrid.removeAllViews()
        val sortedReptiles = sortedProfiles()
        val totalItems = sortedReptiles.size + 1
        val columns = profileGridColumns(totalItems)
        binding.profileGrid.columnCount = columns

        val addItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { view ->
                view.clickHaptic()
                openEditor(null)
            }
        }
        addItem.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = getDrawable(R.drawable.bg_profile_add_circle)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(R.drawable.ic_input_add)
            imageTintList = android.content.res.ColorStateList.valueOf(appColor(R.color.forest))
        })
        addItem.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            text = "프로필 추가"
            textSize = 12f
            maxLines = 1
            includeFontPadding = false
            setTextColor(appColor(R.color.forest))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        binding.profileGrid.addView(addItem, gridCell(0, 0))

        sortedReptiles.forEachIndexed { index, reptile ->
            val isSelectedProfile = reptile.id == selectedReptileId
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                tag = reptile.id
                contentDescription = "${reptile.name}, ${if (isSelectedProfile) "선택됨, " else ""}눌러 선택, 길게 눌러 상세 보기"
                if (isSelectedProfile) setBackgroundResource(R.drawable.bg_profile_selected)
                setOnClickListener { view ->
                    view.selectionHaptic()
                    if (selectedReptileId == reptile.id) return@setOnClickListener
                    selectedReptileId = reptile.id
                    appSettings.lastSelectedProfileId = reptile.id
                    renderProfiles()
                    renderDashboard()
                }
                setOnLongClickListener { view ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    openDetail(reptile.id)
                    true
                }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                background = getDrawable(R.drawable.bg_profile_circle)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
                setImageResource(R.drawable.ic_lizard_placeholder)
                reptile.photoUri?.let { uri ->
                    Glide.with(this@MainActivity)
                        .load(android.net.Uri.parse(uri))
                        .override(dp(56), dp(56))
                        .centerCrop()
                        .placeholder(R.drawable.ic_lizard_placeholder)
                        .error(R.drawable.ic_lizard_placeholder)
                        .into(this)
                }
            }
            item.addView(image)
            item.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
                text = reptile.name
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(if (isSelectedProfile) appColor(R.color.forest) else appColor(R.color.text_secondary))
                setTypeface(typeface, if (isSelectedProfile) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setPadding(0, dp(3), 0, 0)
                gravity = Gravity.CENTER
            })
            val itemIndex = index + 1
            binding.profileGrid.addView(item, gridCell(itemIndex / columns, itemIndex % columns))
        }
    }

    private fun gridCell(row: Int, column: Int): android.widget.GridLayout.LayoutParams = android.widget.GridLayout.LayoutParams(
        android.widget.GridLayout.spec(row), android.widget.GridLayout.spec(column)
    ).apply {
        width = dp(PROFILE_GRID_CELL_WIDTH_DP)
        height = dp(96)
    }

    private fun profileGridColumns(totalItems: Int): Int {
        val balancedColumns = ((totalItems + PROFILE_GRID_ROWS - 1) / PROFILE_GRID_ROWS).coerceAtLeast(1)
        val scrollWidth = (binding.profileGrid.parent as? View)?.width?.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(PROFILE_GRID_FALLBACK_HORIZONTAL_PADDING_DP))
        val visibleColumns = (scrollWidth / dp(PROFILE_GRID_CELL_WIDTH_DP)).coerceAtLeast(1)
        return balancedColumns.coerceAtLeast(visibleColumns)
    }

    private fun sortedProfiles(): List<Reptile> {
        return ProfileSorter.sortedProfiles(reptiles, profileSortMode)
    }

    private fun openEditor(id: Long?) = startActivity(Intent(this, ReptileEditActivity::class.java).apply { id?.let { putExtra(ReptileEditActivity.EXTRA_ID, it) } })
    private fun openDetail(id: Long) = startActivity(Intent(this, ReptileDetailActivity::class.java).putExtra(ReptileDetailActivity.EXTRA_ID, id))
    private fun openWeightHistory() {
        val reptileId = selectedReptileId
        if (reptileId == null) {
            showBriefToast(if (reptiles.isEmpty()) "먼저 개체를 등록해 주세요" else "먼저 개체를 선택해 주세요")
            return
        }
        startActivity(Intent(this, WeightHistoryActivity::class.java).putExtra(WeightHistoryActivity.EXTRA_REPTILE_ID, reptileId))
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal fun restoreLastSelectedProfile() {
        selectedReptileId = appSettings.lastSelectedProfileId?.takeIf { id -> reptiles.any { it.id == id } }
        renderProfiles()
        renderDashboard()
    }

    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT

    internal enum class MainSection {
        HOME,
        BREEDING,
        CALENDAR,
        MEMORIAL,
        SETTINGS
    }

    companion object {
        private const val KEY_CURRENT_SECTION = "current_section"
        private const val KEY_SETTINGS_SCROLL_Y = "settings_scroll_y"
        private const val PROFILE_GRID_ROWS = 2
        private const val PROFILE_GRID_CELL_WIDTH_DP = 82
        private const val PROFILE_GRID_FALLBACK_HORIZONTAL_PADDING_DP = 40
    }
}