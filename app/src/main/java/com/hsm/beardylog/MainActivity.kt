package com.hsm.beardylog

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.CalendarEntryStore
import com.hsm.beardylog.data.CareSchedule
import com.hsm.beardylog.data.CareLog
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.data.WeightRecord
import com.hsm.beardylog.databinding.ActivityMainBinding
import com.hsm.beardylog.ui.WeightChartView
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.text.Collator
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import com.google.firebase.appdistribution.AppDistributionRelease
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.FirebaseAppDistributionException
import com.google.firebase.appdistribution.UpdateStatus
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class MainActivity : AppBaseActivity() {
    override val useEdgeToEdge: Boolean = false
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ReptileViewModel
    private var reptiles: List<Reptile> = emptyList()
    private var schedules: List<CareSchedule> = emptyList()
    private var careLogs: List<CareLog> = emptyList()
    private var selectedReptileId: Long? = null
    private var profileSortMode = 0
    private var initialProfileSelectionResolved = false
    private lateinit var database: AppDatabase
    private lateinit var calendarEntryStore: CalendarEntryStore
    private lateinit var mainColumn: LinearLayout
    private lateinit var homeContent: View
    private var currentTopContent: View? = null
    private var currentSection = MainSection.HOME
    private var currentCalendarMonth: YearMonth = YearMonth.now()
    private var calendarSelectedDate: LocalDate? = LocalDate.now()
    private var calendarLastSelectedDate: LocalDate? = LocalDate.now()
    private var calendarDetailDate: LocalDate? = null
    private var calendarScrollY = 0
    private var koreanHolidays: Map<LocalDate, String> = emptyMap()
    private var holidayFetchMonth: YearMonth? = null
    private val calendarDateCells = mutableMapOf<LocalDate, LinearLayout>()
    private var calendarSummaryDateText: TextView? = null
    private var calendarSummaryBodyContainer: LinearLayout? = null
    private var calendarMemoDateText: TextView? = null
    private var calendarMemoBodyText: TextView? = null
    private var calendarTodayButton: TextView? = null
    private var calendarRecordButton: MaterialButton? = null
    private var calendarSavedFeedbackView: TextView? = null
    private var calendarMonthGridView: View? = null
    private var calendarDetailEditor: CalendarDetailEditor? = null
    private var pendingCalendarMonthAnimationDirection = 0
    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    private val calendarDayLabels = listOf("일", "월", "화", "수", "목", "금", "토")
    private val calendarTaskLabels = listOf("수분 급여", "배변", "온욕", "구충제", "사육장 청소", "UVB 교체")
    private data class CalendarSummaryItem(val icon: Int?, val text: String)
    private data class CalendarDetailEditor(
        val date: LocalDate,
        val taskChecks: Map<String, CheckBox>,
        val hospitalInput: EditText,
        val medicineInput: EditText,
        val memoInput: EditText
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyHomeStatusBarInset(binding.root)
        mainColumn = binding.root.getChildAt(0) as LinearLayout
        homeContent = mainColumn.getChildAt(0)
        currentTopContent = homeContent
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleMainBackPressed()
            }
        })
        if (isUpdateCheckEnabled()) checkAppDistributionUpdate()
        currentCalendarMonth = savedInstanceState?.getString(KEY_CURRENT_CALENDAR_MONTH)?.let { value ->
            runCatching { YearMonth.parse(value) }.getOrNull()
        } ?: YearMonth.now()
        calendarSelectedDate = if (savedInstanceState == null) {
            LocalDate.now()
        } else {
            savedInstanceState.getLong(KEY_CALENDAR_SELECTED_DATE, Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
                ?.let(LocalDate::ofEpochDay)
        }
        calendarLastSelectedDate = savedInstanceState
            ?.getLong(KEY_CALENDAR_LAST_SELECTED_DATE, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE }
            ?.let(LocalDate::ofEpochDay)
            ?: calendarSelectedDate
            ?: LocalDate.now()
        calendarDetailDate = savedInstanceState?.getLong(KEY_CALENDAR_DETAIL_DATE, Long.MIN_VALUE)?.takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)

        database = AppDatabase.getInstance(applicationContext)
        calendarEntryStore = CalendarEntryStore(this)
        profileSortMode = getSharedPreferences("app_settings", MODE_PRIVATE).getInt("profile_sort_mode", 0)
        binding.profileSortSpinner.setSelection(profileSortMode)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(database.reptileDao())))[ReptileViewModel::class.java]
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay()
        database.careScheduleDao().observeBetween(weekStart, weekStart + 6).observe(this) {
            schedules = it
            renderDashboard()
        }
        database.careLogDao().observeAll().observe(this) {
            careLogs = it
            renderDashboard()
        }
        seedDemoWeekIfNeeded(database, weekStart)

        binding.currentDate.text = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
        binding.selectedProfileCard.setOnClickListener {
            selectedReptileId?.let(::openDetail)
        }
        binding.profileSortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (profileSortMode != position) {
                    profileSortMode = position
                    getSharedPreferences("app_settings", MODE_PRIVATE).edit().putInt("profile_sort_mode", position).apply()
                    renderProfiles()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.quickDietButton.setOnClickListener {
            selectedReptileId?.let { startActivity(Intent(this, CareScheduleActivity::class.java).putExtra(CareScheduleActivity.EXTRA_REPTILE_ID, it)) }
                ?: showBriefToast("먼저 개체를 등록해 주세요")
        }
        binding.quickWeightButton.setOnClickListener { showBriefToast("준비중인 기능입니다") }
        binding.quickMemoButton.setOnClickListener { showBriefToast("준비중인 기능입니다") }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showMainSection(MainSection.HOME)
                    true
                }
                R.id.nav_reptiles -> {
                    showMainSection(MainSection.REPTILES)
                    true
                }
                R.id.nav_calendar -> {
                    showMainSection(MainSection.CALENDAR)
                    true
                }
                R.id.nav_breeding -> {
                    showMainSection(MainSection.BREEDING)
                    true
                }
                R.id.nav_settings -> {
                    showMainSection(MainSection.SETTINGS)
                    true
                }
                else -> false
            }
        }
        val restoredSection = savedInstanceState?.getString(KEY_CURRENT_SECTION)?.let { value ->
            runCatching { MainSection.valueOf(value) }.getOrNull()
        } ?: MainSection.HOME
        binding.bottomNavigation.selectedItemId = menuIdForSection(restoredSection)
        showMainSection(restoredSection)
        viewModel.reptiles.observe(this) {
            reptiles = it
            resolveInitialProfileSelection()
            if (selectedReptileId != null && reptiles.none { reptile -> reptile.id == selectedReptileId }) selectedReptileId = null
            renderProfiles()
            renderDashboard()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_SECTION, currentSection.name)
        outState.putString(KEY_CURRENT_CALENDAR_MONTH, currentCalendarMonth.toString())
        calendarSelectedDate?.let { outState.putLong(KEY_CALENDAR_SELECTED_DATE, it.toEpochDay()) }
        calendarLastSelectedDate?.let { outState.putLong(KEY_CALENDAR_LAST_SELECTED_DATE, it.toEpochDay()) }
        calendarDetailDate?.let { outState.putLong(KEY_CALENDAR_DETAIL_DATE, it.toEpochDay()) }
        super.onSaveInstanceState(outState)
    }

    private fun isUpdateCheckEnabled(): Boolean =
        getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean(KEY_CHECK_UPDATES_ON_START, true)

    private fun isAutoSelectLastProfileEnabled(): Boolean =
        getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean(KEY_AUTO_SELECT_LAST_PROFILE, false)

    private fun resolveInitialProfileSelection() {
        if (initialProfileSelectionResolved) return
        initialProfileSelectionResolved = true
        if (!isAutoSelectLastProfileEnabled()) {
            selectedReptileId = null
            return
        }
        val lastSelectedId = getSharedPreferences("app_settings", MODE_PRIVATE).getLong(KEY_LAST_SELECTED_PROFILE_ID, -1L)
        selectedReptileId = lastSelectedId.takeIf { id -> id > 0L && reptiles.any { it.id == id } }
    }

    private fun showMainSection(section: MainSection) {
        if (currentSection == section) return
        currentSection = section
        if (section != MainSection.CALENDAR) {
            calendarDetailDate = null
            calendarDetailEditor = null
        }
        replaceTopContent(when (section) {
            MainSection.HOME -> homeContent
            MainSection.REPTILES -> createReptilesContentView()
            MainSection.CALENDAR -> createCalendarContentView()
            MainSection.BREEDING -> createBreedingContentView()
            MainSection.SETTINGS -> createSettingsContentView()
        })
    }

    private fun handleMainBackPressed() {
        calendarDetailEditor?.let { editor ->
            handleCalendarDetailBack(
                editor.date,
                editor.taskChecks,
                editor.hospitalInput,
                editor.medicineInput,
                editor.memoInput
            )
            return
        }
        calendarDetailDate?.let { date ->
            returnToCalendarFromDetail(date, resetScroll = true)
            return
        }
        if (currentSection != MainSection.HOME) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            return
        }
        finish()
    }

    private fun menuIdForSection(section: MainSection): Int = when (section) {
        MainSection.HOME -> R.id.nav_home
        MainSection.REPTILES -> R.id.nav_reptiles
        MainSection.CALENDAR -> R.id.nav_calendar
        MainSection.BREEDING -> R.id.nav_breeding
        MainSection.SETTINGS -> R.id.nav_settings
    }

    private fun replaceTopContent(content: View) {
        if (currentTopContent === content) return
        mainColumn.removeViewAt(0)
        mainColumn.addView(content, 0, LinearLayout.LayoutParams(match, 0, 1f))
        currentTopContent = content
    }

    private fun createReptilesContentView(): View {
        return createComingSoonContentView("개체", "개체 관리 화면은 준비중입니다")
    }

    private fun createCalendarContentView(): View {
        calendarDetailDate?.let { return createCalendarDayContentView(it) }
        val calendarSidePaddingDp = when {
            resources.configuration.screenWidthDp <= 360 -> 8
            resources.configuration.screenWidthDp <= 420 -> 12
            else -> 20
        }
        val alignedContentMargin = dp(20 - calendarSidePaddingDp)
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
            post { scrollTo(0, calendarScrollY) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(calendarSidePaddingDp), dp(20), dp(calendarSidePaddingDp), dp(24))
        }
        loadKoreanHolidays(currentCalendarMonth)
        fetchKoreanHolidaysIfNeeded(currentCalendarMonth)
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(calendarMonthHeader(), LinearLayout.LayoutParams(match, wrap).apply {
            leftMargin = alignedContentMargin
            rightMargin = alignedContentMargin
        })
        val monthGrid = calendarMonthGrid()
        content.addView(monthGrid)
        animateCalendarMonthGridIfNeeded(monthGrid)
        content.addView(calendarSummaryPreview(), LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(28)
            leftMargin = alignedContentMargin
            rightMargin = alignedContentMargin
        })
        content.addView(calendarMemoPreview(), LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(12)
            leftMargin = alignedContentMargin
            rightMargin = alignedContentMargin
        })
        return scrollView
    }

    private fun calendarMonthHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "달력"
                textSize = 28f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            val showTodayButton = shouldShowTodayButton()
            addView(TextView(context).apply {
                text = LocalDate.now().dayOfMonth.toString()
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.button_on_primary))
                setBackgroundResource(R.drawable.bg_today_button)
                visibility = if (showTodayButton) View.VISIBLE else View.INVISIBLE
                isClickable = showTodayButton
                isFocusable = showTodayButton
                calendarTodayButton = this
                setOnClickListener {
                    val today = LocalDate.now()
                    currentCalendarMonth = YearMonth.from(today)
                    calendarSelectedDate = today
                    calendarLastSelectedDate = today
                    calendarScrollY = 0
                    replaceTopContent(createCalendarContentView())
                }
            }, LinearLayout.LayoutParams(dp(58), dp(38)))
            post { updateCalendarTodayButton() }
        })
        addView(TextView(context).apply {
            text = "날짜를 선택해 확인하고, 길게 눌러 기록하세요"
            textSize = 12f
            setTextColor(resColor(R.color.text_secondary))
            alpha = 0.72f
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(context).apply {
                text = "‹"
                textSize = 34f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
                setBackgroundResource(R.drawable.bg_week_day)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    changeCalendarMonth(-1)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(TextView(context).apply {
                text = currentCalendarMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월"))
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 34f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
                setBackgroundResource(R.drawable.bg_week_day)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    changeCalendarMonth(1)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
        })
    }

    private fun changeCalendarMonth(offset: Long) {
        calendarMonthGridView?.animate()?.cancel()
        pendingCalendarMonthAnimationDirection = if (offset > 0) 1 else -1
        calendarSelectedDate?.let { calendarLastSelectedDate = it }
        currentCalendarMonth = currentCalendarMonth.plusMonths(offset)
        calendarScrollY = 0
        calendarSelectedDate = calendarLastSelectedDate?.takeIf { YearMonth.from(it) == currentCalendarMonth }
        replaceTopContent(createCalendarContentView())
    }

    private fun animateCalendarMonthGridIfNeeded(monthGrid: View) {
        calendarMonthGridView = monthGrid
        val direction = pendingCalendarMonthAnimationDirection
        pendingCalendarMonthAnimationDirection = 0
        if (direction == 0) return

        monthGrid.translationX = dp(24).toFloat() * direction
        monthGrid.post {
            if (!monthGrid.isAttachedToWindow) return@post
            monthGrid.animate()
                .translationX(0f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun calendarMonthGrid(): View = MaterialCardView(this).apply {
        calendarDateCells.clear()
        radius = dp(14).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(resColor(R.color.surface_card))
        strokeColor = resColor(R.color.forest_light)
        strokeWidth = dp(1)
        layoutParams = LinearLayout.LayoutParams(match, wrap)
        addView(android.widget.GridLayout(context).apply {
            columnCount = 7
            setPadding(dp(8), dp(10), dp(8), dp(10))
            calendarDayLabels.forEachIndexed { index, label ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(resColor(if (index == 0) R.color.danger else R.color.text_secondary))
                }, calendarCell(0, index, dp(34)))
            }
            val firstDay = currentCalendarMonth.atDay(1)
            val startOffset = firstDay.dayOfWeek.value % 7
            val daysInMonth = currentCalendarMonth.lengthOfMonth()
            val visibleWeeks = ((startOffset + daysInMonth + 6) / 7).coerceAtLeast(1)
            rowCount = visibleWeeks + 1
            repeat(visibleWeeks * 7) { index ->
                val dayNumber = index - startOffset + 1
                val row = index / 7 + 1
                val column = index % 7
                if (dayNumber in 1..daysInMonth) {
                    val date = currentCalendarMonth.atDay(dayNumber)
                    addView(calendarDateCell(date), calendarCell(row, column, dp(92)))
                } else {
                    addView(View(context).apply {
                        setBackgroundResource(R.drawable.bg_calendar_day_cell)
                        alpha = 0.45f
                    }, calendarCell(row, column, dp(92)))
                }
            }
        }, android.widget.FrameLayout.LayoutParams(match, wrap))
    }

    private fun calendarDateCell(date: LocalDate): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        setPadding(dp(2), dp(5), dp(2), dp(4))
        updateCalendarDateCellStyle(this, date)
        setOnClickListener {
            val previousDate = calendarSelectedDate
            calendarSelectedDate = date
            calendarLastSelectedDate = date
            previousDate?.let(::refreshCalendarDateCellStyle)
            refreshCalendarDateCellStyle(date)
            updateCalendarPreviewTexts()
        }
        setOnLongClickListener {
            openCalendarDetail(date)
            true
        }
        addView(TextView(context).apply {
            text = date.dayOfMonth.toString()
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        updateCalendarDateCellStyle(this, date)
        val cellIcons = calendarCellIcons(date)
        if (cellIcons.isNotEmpty()) {
            val iconSize = when {
                cellIcons.size <= 2 -> dp(17)
                cellIcons.size <= 4 -> dp(14)
                else -> dp(12)
            }
            addView(android.widget.GridLayout(context).apply {
                columnCount = cellIcons.size.coerceAtMost(3)
                rowCount = ((cellIcons.size + columnCount - 1) / columnCount).coerceAtLeast(1)
                cellIcons.forEach { iconRes ->
                    addView(ImageView(context).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(if (date == LocalDate.now()) Color.WHITE else resColor(R.color.forest))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = null
                    }, android.widget.GridLayout.LayoutParams().apply {
                        width = iconSize
                        height = iconSize
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        } else if (calendarHasRecordMarker(date)) {
            addView(View(context).apply {
                tag = CALENDAR_RECORD_DOT_TAG
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(6) })
            updateCalendarDateCellStyle(this, date)
        }
        calendarDateCells[date] = this
    }

    private fun refreshCalendarDateCellStyle(date: LocalDate) {
        calendarDateCells[date]?.let { updateCalendarDateCellStyle(it, date) }
    }

    private fun updateCalendarDateCellStyle(cell: LinearLayout, date: LocalDate) {
        val isToday = date == LocalDate.now()
        val isSelected = date == calendarSelectedDate
        cell.setBackgroundResource(when {
            isSelected -> R.drawable.bg_week_day_selected
            isToday -> R.drawable.bg_calendar_today_cell
            else -> R.drawable.bg_calendar_day_cell
        })
        (cell.getChildAt(0) as? TextView)?.setTextColor(when {
            isSelected -> Color.WHITE
            isHolidayDate(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> resColor(R.color.danger)
            date.dayOfWeek == DayOfWeek.SATURDAY || isToday -> resColor(R.color.forest)
            else -> resColor(R.color.text_primary)
        })
        updateCalendarCellIndicators(cell, if (isSelected) Color.WHITE else resColor(R.color.forest))
    }

    private fun updateCalendarCellIndicators(view: View, color: Int) {
        when (view) {
            is ImageView -> view.imageTintList = ColorStateList.valueOf(color)
            is ViewGroup -> {
                repeat(view.childCount) { index ->
                    updateCalendarCellIndicators(view.getChildAt(index), color)
                }
            }
            else -> if (view.tag == CALENDAR_RECORD_DOT_TAG) {
                view.setBackgroundResource(R.drawable.bg_record_dot)
                view.backgroundTintList = ColorStateList.valueOf(color)
            }
        }
    }

    private fun calendarCell(row: Int, column: Int, height: Int): android.widget.GridLayout.LayoutParams =
        android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(row), android.widget.GridLayout.spec(column, 1f)).apply {
            width = 0
            this.height = height
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun calendarSummaryPreview(): View = settingsCard {
        val selectedDate = calendarSelectedDate?.takeIf { YearMonth.from(it) == currentCalendarMonth }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "일정 요약"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = selectedDate?.format(DateTimeFormatter.ofPattern("M월 d일")) ?: "날짜 선택"
                textSize = 14f
                gravity = Gravity.END
                setTextColor(resColor(R.color.forest))
                calendarSummaryDateText = this
            })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "기록"
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(34)
                minimumHeight = dp(34)
                insetTop = 0
                insetBottom = 0
                isEnabled = selectedDate != null
                setTextColor(resColor(R.color.forest))
                strokeColor = ColorStateList.valueOf(resColor(R.color.forest_light))
                backgroundTintList = ColorStateList.valueOf(resColor(R.color.surface_card))
                setPadding(dp(12), 0, dp(12), 0)
                calendarRecordButton = this
                setOnClickListener { selectedDate?.let(::openCalendarDetail) }
            }, LinearLayout.LayoutParams(wrap, dp(34)).apply { leftMargin = dp(10) })
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            calendarSummaryBodyContainer = this
            updateCalendarSummaryBody(this, selectedDate)
        })
    }

    private fun calendarMemoPreview(): View = settingsCard {
        val selectedDate = calendarSelectedDate?.takeIf { YearMonth.from(it) == currentCalendarMonth }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "메모"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = selectedDate?.format(DateTimeFormatter.ofPattern("M월 d일")) ?: "날짜 선택"
                textSize = 14f
                gravity = Gravity.END
                setTextColor(resColor(R.color.forest))
                calendarMemoDateText = this
            })
        })
        addView(TextView(context).apply {
            text = selectedDate
                ?.let { calendarTextValue(it, "memo").trim().takeIf(String::isNotBlank) }
                ?: "메모가 없습니다"
            textSize = 14f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(10), 0, 0)
            calendarMemoBodyText = this
        })
    }

    private fun updateCalendarPreviewTexts() {
        val selectedDate = calendarSelectedDate?.takeIf { YearMonth.from(it) == currentCalendarMonth }
        val dateText = selectedDate?.format(DateTimeFormatter.ofPattern("M월 d일")) ?: "날짜 선택"
        calendarSummaryDateText?.text = dateText
        calendarSummaryBodyContainer?.let { updateCalendarSummaryBody(it, selectedDate) }
        calendarMemoDateText?.text = dateText
        calendarMemoBodyText?.text = selectedDate
            ?.let { calendarTextValue(it, "memo").trim().takeIf(String::isNotBlank) }
            ?: "메모가 없습니다"
        calendarRecordButton?.isEnabled = selectedDate != null
        updateCalendarTodayButton()
    }

    private fun updateCalendarSummaryBody(container: LinearLayout, selectedDate: LocalDate?) {
        container.removeAllViews()
        if (selectedDate == null) {
            container.addView(calendarSummaryMessage("날짜를 누르면 이곳에 일정 요약이 표시됩니다"))
            return
        }
        val items = calendarSummaryItems(selectedDate)
        if (items.isEmpty()) {
            container.addView(calendarSummaryMessage("기록이 없습니다. 기록 버튼으로 추가해 주세요."))
            return
        }
        items.forEachIndexed { index, item ->
            container.addView(calendarSummaryRow(item), LinearLayout.LayoutParams(match, wrap).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
    }

    private fun calendarSummaryMessage(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(resColor(R.color.text_secondary))
    }

    private fun calendarSummaryRow(item: CalendarSummaryItem): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        item.icon?.let { icon ->
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(resColor(R.color.forest))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        } ?: addView(TextView(context).apply {
            text = "•"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(resColor(R.color.forest))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        addView(TextView(context).apply {
            text = item.text
            textSize = 14f
            setTextColor(resColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
    }

    private fun updateCalendarTodayButton() {
        val showTodayButton = shouldShowTodayButton()
        calendarTodayButton?.apply {
            text = LocalDate.now().dayOfMonth.toString()
            visibility = if (showTodayButton) View.VISIBLE else View.INVISIBLE
            isClickable = showTodayButton
            isFocusable = showTodayButton
        }
    }

    private fun shouldShowTodayButton(): Boolean =
        currentCalendarMonth != YearMonth.now() || calendarSelectedDate != LocalDate.now()

    private fun createCalendarDayContentView(date: LocalDate): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        val taskChecks = mutableMapOf<String, CheckBox>()
        val hospitalInput = calendarEditText(calendarTextValue(date, "hospital"), "병원 기록")
        val medicineInput = calendarEditText(calendarTextValue(date, "medicine"), "약 기록")
        val memoInput = calendarEditText(calendarTextValue(date, "memo"), "메모").apply {
            minLines = 4
            gravity = Gravity.TOP
        }
        calendarDetailEditor = CalendarDetailEditor(date, taskChecks, hospitalInput, medicineInput, memoInput)
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(resColor(R.color.text_primary))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "뒤로가기"
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    handleCalendarDetailBack(date, taskChecks, hospitalInput, medicineInput, memoInput)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(TextView(context).apply {
                text = "달력"
                textSize = 24f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(View(context), LinearLayout.LayoutParams(dp(48), dp(44)))
        })
        content.addView(TextView(this).apply {
            text = calendarDetailTitle(date)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(18))
        })

        content.addView(settingsCard {
            calendarTaskLabels.forEach { label ->
                val checkBox = CheckBox(context).apply {
                    text = label
                    textSize = 16f
                    isChecked = calendarCheckedValue(date, label)
                    setTextColor(resColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(match, dp(44))
                }
                taskChecks[label] = checkBox
                addView(checkBox)
            }
            addSettingsDivider()
            addView(calendarInputRow("병원", hospitalInput, "병원 기록 지우기"))
            addView(calendarInputRow("약", medicineInput, "약 기록 지우기"))
        })
        content.addView(settingsCard {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "메모"
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(resColor(R.color.text_primary))
                }, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(
                    calendarClearButton(memoInput, "메모 지우기"),
                    LinearLayout.LayoutParams(dp(40), dp(40))
                )
            })
            addView(memoInput, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) }
        })
        content.addView(MaterialButton(this).apply {
            text = "저장"
            setTextColor(resColor(R.color.button_on_primary))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.button_primary))
            setOnClickListener {
                saveCalendarDetail(date, taskChecks, hospitalInput, medicineInput, memoInput)
                returnToCalendarFromDetail(date, resetScroll = false)
                showCalendarSavedSnackbar()
            }
            layoutParams = LinearLayout.LayoutParams(match, dp(52)).apply { topMargin = dp(18) }
        })
        val deleteButton = MaterialButton(this).apply {
            text = "이 날짜 기록 전체 삭제"
            setTextColor(resColor(R.color.danger))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(resColor(R.color.danger))
            strokeWidth = dp(1)
            cornerRadius = dp(12)
            setIconResource(R.drawable.ic_delete)
            iconTint = ColorStateList.valueOf(resColor(R.color.danger))
            setOnClickListener {
                showDeleteCalendarDateDialog(date)
            }
        }
        content.addView(deleteButton, LinearLayout.LayoutParams(match, dp(50)).apply { topMargin = dp(10) })
        val updateDeleteButtonState = {
            val hasContent = taskChecks.values.any(CheckBox::isChecked) ||
                hospitalInput.text.isNotBlank() ||
                medicineInput.text.isNotBlank() ||
                memoInput.text.isNotBlank()
            deleteButton.isEnabled = hasContent
            deleteButton.alpha = if (hasContent) 1f else 0.45f
        }
        taskChecks.values.forEach { checkBox ->
            checkBox.setOnCheckedChangeListener { _, _ -> updateDeleteButtonState() }
        }
        hospitalInput.doAfterTextChanged { updateDeleteButtonState() }
        medicineInput.doAfterTextChanged { updateDeleteButtonState() }
        memoInput.doAfterTextChanged { updateDeleteButtonState() }
        updateDeleteButtonState()
        return scrollView
    }

    private fun openCalendarDetail(date: LocalDate) {
        calendarScrollY = (currentTopContent as? ScrollView)?.scrollY ?: calendarScrollY
        calendarSelectedDate = date
        calendarLastSelectedDate = date
        calendarDetailDate = date
        replaceTopContent(createCalendarDayContentView(date))
    }

    private fun handleCalendarDetailBack(
        date: LocalDate,
        taskChecks: Map<String, CheckBox>,
        hospitalInput: EditText,
        medicineInput: EditText,
        memoInput: EditText
    ) {
        if (!hasUnsavedCalendarDetailChanges(date, taskChecks, hospitalInput, medicineInput, memoInput)) {
            showCalendarDetailCloseDialog(date)
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("변경 사항 저장")
            .setMessage("저장하지 않은 변경 사항이 있습니다. 저장할까요?")
            .setPositiveButton("저장") { _, _ ->
                saveCalendarDetail(date, taskChecks, hospitalInput, medicineInput, memoInput)
                returnToCalendarFromDetail(date, resetScroll = false)
                showCalendarSavedSnackbar()
            }
            .setNegativeButton("저장 안 함") { _, _ ->
                returnToCalendarFromDetail(date, resetScroll = true)
            }
            .setNeutralButton("취소", null)
            .show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
    }

    private fun showCalendarDetailCloseDialog(date: LocalDate) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("상세 일정 닫기")
            .setMessage("캘린더 화면으로 돌아가시겠습니까?")
            .setPositiveButton("돌아가기") { _, _ ->
                returnToCalendarFromDetail(date, resetScroll = true)
            }
            .setNegativeButton("취소", null)
            .show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
    }

    private fun hasUnsavedCalendarDetailChanges(
        date: LocalDate,
        taskChecks: Map<String, CheckBox>,
        hospitalInput: EditText,
        medicineInput: EditText,
        memoInput: EditText
    ): Boolean =
        taskChecks.any { (label, checkBox) -> calendarCheckedValue(date, label) != checkBox.isChecked } ||
            calendarTextValue(date, "hospital") != hospitalInput.text.toString().trim() ||
            calendarTextValue(date, "medicine") != medicineInput.text.toString().trim() ||
            calendarTextValue(date, "memo") != memoInput.text.toString().trim()

    private fun saveCalendarDetail(
        date: LocalDate,
        taskChecks: Map<String, CheckBox>,
        hospitalInput: EditText,
        medicineInput: EditText,
        memoInput: EditText
    ) {
        taskChecks.forEach { (label, checkBox) -> saveCalendarCheckedValue(date, label, checkBox.isChecked) }
        saveCalendarTextValue(date, "hospital", hospitalInput.text.toString())
        saveCalendarTextValue(date, "medicine", medicineInput.text.toString())
        saveCalendarTextValue(date, "memo", memoInput.text.toString())
    }

    private fun showDeleteCalendarDateDialog(date: LocalDate) {
        val dateText = date.format(DateTimeFormatter.ofPattern("M월 d일"))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("이 날짜 기록 전체 삭제")
            .setMessage("${dateText}의 체크 항목과 병원·약·메모 기록을 모두 삭제할까요? 현재 편집 중인 내용도 사라집니다.")
            .setPositiveButton("전체 삭제") { _, _ ->
                calendarEntryStore.deleteDate(date, calendarTaskLabels)
                returnToCalendarFromDetail(date, resetScroll = false)
                showCalendarFeedback("기록을 삭제했습니다")
            }
            .setNegativeButton("취소", null)
            .show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setTextColor(resColor(R.color.danger))
    }

    private fun returnToCalendarFromDetail(date: LocalDate, resetScroll: Boolean) {
        calendarSelectedDate = date
        calendarLastSelectedDate = date
        calendarDetailDate = null
        calendarDetailEditor = null
        if (resetScroll) calendarScrollY = 0
        replaceTopContent(createCalendarContentView())
    }

    private fun showCalendarSavedSnackbar() = showCalendarFeedback("저장되었습니다")

    private fun showCalendarFeedback(message: String) {
        binding.root.post {
            calendarSavedFeedbackView?.let(binding.root::removeView)
            val feedback = TextView(this).apply {
                text = message
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.surface_card))
                setBackgroundResource(R.drawable.bg_calendar_feedback)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                alpha = 0f
            }
            val bottomNavHeight = binding.bottomNavigation.height.takeIf { it > 0 } ?: dp(86)
            binding.root.addView(
                feedback,
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(match, wrap).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = dp(20)
                    rightMargin = dp(20)
                    bottomMargin = bottomNavHeight + dp(12)
                }
            )
            calendarSavedFeedbackView = feedback
            feedback.bringToFront()
            feedback.animate().alpha(1f).setDuration(120L).start()
            feedback.postDelayed({
                if (calendarSavedFeedbackView === feedback) {
                    feedback.animate().alpha(0f).setDuration(160L).withEndAction {
                        if (calendarSavedFeedbackView === feedback) {
                            binding.root.removeView(feedback)
                            calendarSavedFeedbackView = null
                        }
                    }.start()
                }
            }, 1700L)
        }
    }

    private fun calendarInputRow(label: String, input: EditText, clearDescription: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(resColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(dp(58), wrap))
        addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(calendarClearButton(input, clearDescription), LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun calendarClearButton(input: EditText, description: String): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_clear)
        imageTintList = ColorStateList.valueOf(resColor(R.color.text_secondary))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        visibility = if (input.text.isNullOrBlank()) View.GONE else View.VISIBLE
        input.doAfterTextChanged { text ->
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        setOnClickListener { input.text?.clear() }
    }

    private fun calendarEditText(value: String, hint: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        textSize = 14f
        setSingleLine(false)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(resColor(R.color.text_primary))
        setHintTextColor(resColor(R.color.text_secondary))
        backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
    }

    private fun calendarTaskIcon(label: String): Int? = when (label) {
        "수분 급여" -> R.drawable.ic_calendar_water
        "배변" -> R.drawable.ic_calendar_poop
        "온욕" -> R.drawable.ic_calendar_bath
        "구충제" -> R.drawable.ic_calendar_deworm
        "사육장 청소" -> R.drawable.ic_calendar_clean
        "UVB 교체" -> R.drawable.ic_calendar_lamp
        else -> null
    }

    private fun calendarCellIcons(date: LocalDate): List<Int> = buildList {
        addAll(calendarTaskLabels.filter { calendarCheckedValue(date, it) }.mapNotNull(::calendarTaskIcon))
        if (calendarTextValue(date, "hospital").isNotBlank()) add(R.drawable.ic_calendar_hospital)
        if (calendarTextValue(date, "medicine").isNotBlank()) add(R.drawable.ic_calendar_medicine_bottle)
    }

    private fun calendarHasRecordMarker(date: LocalDate): Boolean {
        val hasMemo = calendarTextValue(date, "memo").isNotBlank()
        val hasPlannedDiet = selectedReptileId?.let { reptileId ->
            schedules.any { it.reptileId == reptileId && isDietSchedule(it) && isScheduledOn(it, date) }
        } ?: false
        return hasMemo || hasPlannedDiet
    }

    private fun calendarDetailTitle(date: LocalDate): SpannableString {
        val dayOfWeek = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }
        val weekdayText = "${dayOfWeek}요일"
        val title = "${date.format(DateTimeFormatter.ofPattern("M월 d일"))} $weekdayText"
        return SpannableString(title).apply {
            val start = title.lastIndexOf(weekdayText)
            val color = when {
                isHolidayDate(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> resColor(R.color.danger)
                date.dayOfWeek == DayOfWeek.SATURDAY -> resColor(R.color.forest)
                else -> resColor(R.color.text_primary)
            }
            setSpan(ForegroundColorSpan(color), start, start + weekdayText.length, 0)
        }
    }

    private fun isHolidayDate(date: LocalDate): Boolean = koreanHolidays.containsKey(date)

    private fun calendarSummaryItems(date: LocalDate): List<CalendarSummaryItem> {
        val checked = calendarTaskLabels.filter { calendarCheckedValue(date, it) }
        val hospital = calendarTextValue(date, "hospital").trim()
        val medicine = calendarTextValue(date, "medicine").trim()
        val planned = selectedReptileId?.let { reptileId ->
            schedules.filter { it.reptileId == reptileId && isDietSchedule(it) && isScheduledOn(it, date) }
        }.orEmpty()
        return buildList {
            koreanHolidays[date]?.let { add(CalendarSummaryItem(null, "공휴일: $it")) }
            if (planned.isNotEmpty()) add(CalendarSummaryItem(null, "예정 식단: ${planned.joinToString(", ") { it.careType }}"))
            checked.forEach { label ->
                add(CalendarSummaryItem(calendarTaskIcon(label), label))
            }
            if (hospital.isNotBlank()) add(CalendarSummaryItem(R.drawable.ic_calendar_hospital, "병원: $hospital"))
            if (medicine.isNotBlank()) add(CalendarSummaryItem(R.drawable.ic_calendar_medicine_bottle, "약: $medicine"))
        }
    }

    private fun loadKoreanHolidays(month: YearMonth) {
        koreanHolidays = getSharedPreferences("korea_holidays", MODE_PRIVATE)
            .getString(holidayCacheKey(month), null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                runCatching { LocalDate.parse(parts[0]) to parts[1] }.getOrNull()
            }
            ?.toMap()
            .orEmpty()
    }

    private fun fetchKoreanHolidaysIfNeeded(month: YearMonth) {
        if (holidayFetchMonth == month || BuildConfig.KOREA_HOLIDAY_API_KEY.isBlank()) return
        holidayFetchMonth = month
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { fetchKoreanHolidays(month) }
            if (fetched.isNotEmpty()) {
                koreanHolidays = fetched
                getSharedPreferences("korea_holidays", MODE_PRIVATE)
                    .edit()
                    .putString(holidayCacheKey(month), fetched.entries.joinToString("\n") { "${it.key}|${it.value}" })
                    .apply()
                if (currentSection == MainSection.CALENDAR && calendarDetailDate == null && currentCalendarMonth == month) {
                    replaceTopContent(createCalendarContentView())
                }
            }
        }
    }

    private fun fetchKoreanHolidays(month: YearMonth): Map<LocalDate, String> = runCatching {
        val serviceKey = URLEncoder.encode(BuildConfig.KOREA_HOLIDAY_API_KEY, "UTF-8")
        val url = URL(
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getHoliDeInfo" +
                "?serviceKey=$serviceKey&solYear=${month.year}&solMonth=${"%02d".format(month.monthValue)}&numOfRows=100&pageNo=1"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        connection.inputStream.use { stream ->
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(stream, "UTF-8")
            }
            val holidays = linkedMapOf<LocalDate, String>()
            var event = parser.eventType
            var currentTag = ""
            var dateName = ""
            var locdate = ""
            var isHoliday = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "item") {
                            dateName = ""
                            locdate = ""
                            isHoliday = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        when (currentTag) {
                            "dateName" -> dateName = parser.text.orEmpty()
                            "locdate" -> locdate = parser.text.orEmpty()
                            "isHoliday" -> isHoliday = parser.text.orEmpty()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item" && isHoliday == "Y" && locdate.length == 8) {
                            val date = LocalDate.of(locdate.substring(0, 4).toInt(), locdate.substring(4, 6).toInt(), locdate.substring(6, 8).toInt())
                            holidays[date] = dateName
                        }
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
            holidays
        }
    }.getOrDefault(emptyMap())

    private fun holidayCacheKey(month: YearMonth): String = "${month.year}_${"%02d".format(month.monthValue)}"

    private fun calendarCheckedValue(date: LocalDate, label: String): Boolean =
        calendarEntryStore.checkedValue(date, label)
    private fun saveCalendarCheckedValue(date: LocalDate, label: String, checked: Boolean) {
        calendarEntryStore.saveCheckedValue(date, label, checked)
    }
    private fun calendarTextValue(date: LocalDate, field: String): String =
        calendarEntryStore.textValue(date, field)
    private fun saveCalendarTextValue(date: LocalDate, field: String, value: String) {
        calendarEntryStore.saveTextValue(date, field, value)
    }

    private fun createBreedingContentView(): View =
        createComingSoonContentView("브리딩", "브리딩 관리 화면은 준비중입니다")

    private fun createComingSoonContentView(title: String, message: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(resColor(R.color.surface_alt))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        container.addView(TextView(this).apply {
            text = title
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(resColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        return container
    }

    private fun createMainSectionContent(
        title: String,
        subtitle: String,
        contentBuilder: LinearLayout.() -> Unit
    ): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(sectionHeader(title, subtitle))
        content.contentBuilder()
        return scrollView
    }

    private fun sectionHeader(title: String, subtitle: String): View = LinearLayout(this).apply {
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

    private fun sectionSummaryCard(content: LinearLayout.() -> Unit): View = settingsCard(content)

    private fun sectionInfoRow(label: String, value: String): View = infoRow(label, value)

    private fun sectionTitleText(title: String): View = settingsSectionTitle(title)

    private fun emptySectionText(message: String): View = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(resColor(R.color.text_secondary))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun sectionListRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit
    ): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(resColor(R.color.surface_card))
        strokeColor = resColor(if (selected) R.color.forest else R.color.forest_light)
        strokeWidth = dp(1)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(8) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(resColor(R.color.text_secondary))
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    private fun LinearLayout.addSectionDivider() = addSettingsDivider()

    private fun createSettingsContentView(): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(settingsHeader())
        content.addView(settingsSectionTitle("앱 동작"))
        content.addView(profileSettingsCard())
        content.addView(settingsSectionTitle("업데이트"))
        content.addView(updateSettingsCard())
        content.addView(settingsSectionTitle("앱 정보"))
        content.addView(appInfoCard())
        return scrollView
    }

    private fun settingsHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(TextView(context).apply {
            text = "설정"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
        })
        addView(TextView(context).apply {
            text = "테스트 배포와 앱 동작을 관리합니다"
            textSize = 14f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun settingsSectionTitle(title: String): View = TextView(this).apply {
        text = title
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(resColor(R.color.text_primary))
        setPadding(0, dp(18), 0, dp(10))
    }

    private fun profileSettingsCard(): View = settingsCard {
        addView(settingRow(
            title = "앱 시작 시 마지막 프로필 표시",
            subtitle = "켜면 마지막으로 선택했던 개체를 메인 요약에 자동으로 보여줍니다",
            trailing = SwitchMaterial(context).apply {
                isChecked = isAutoSelectLastProfileEnabled()
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences("app_settings", MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_AUTO_SELECT_LAST_PROFILE, checked)
                        .apply()
                    if (checked) {
                        restoreLastSelectedProfile()
                    } else {
                        selectedReptileId = null
                        renderProfiles()
                        renderDashboard()
                    }
                    showBriefToast(if (checked) "마지막 프로필 자동 표시를 켰습니다" else "마지막 프로필 자동 표시를 껐습니다")
                }
            }
        ))
    }

    private fun updateSettingsCard(): View = settingsCard {
        addView(settingRow(
            title = "앱 시작 시 업데이트 확인",
            subtitle = "Firebase App Distribution에 새 테스트 버전이 있으면 안내합니다",
            trailing = SwitchMaterial(context).apply {
                isChecked = isUpdateCheckEnabled()
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences("app_settings", MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_CHECK_UPDATES_ON_START, checked)
                        .apply()
                    showBriefToast(if (checked) "자동 업데이트 확인을 켰습니다" else "자동 업데이트 확인을 껐습니다")
                }
            }
        ))
        addSettingsDivider()
        addView(MaterialButton(context).apply {
            text = "지금 업데이트 확인"
            icon = ContextCompat.getDrawable(context, android.R.drawable.stat_sys_download_done)
            iconTint = ColorStateList.valueOf(resColor(R.color.button_on_primary))
            setTextColor(resColor(R.color.button_on_primary))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.button_primary))
            setOnClickListener { checkAppDistributionUpdate(showNoUpdateToast = true) }
            layoutParams = LinearLayout.LayoutParams(match, dp(48)).apply { topMargin = dp(14) }
        })
    }

    private fun appInfoCard(): View = settingsCard {
        val info = packageInfo()
        addView(infoRow("앱 이름", getString(R.string.app_name)))
        addSettingsDivider()
        addView(infoRow("버전", "${info.versionName ?: "-"} (${versionCode(info)})"))
        addSettingsDivider()
        addView(infoRow("패키지", packageName))
    }

    private fun settingRow(title: String, subtitle: String, trailing: View): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(resColor(R.color.text_primary))
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(resColor(R.color.text_secondary))
                    setPadding(0, dp(4), dp(12), 0)
                })
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(trailing)
        }

    private fun infoRow(label: String, value: String): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(44)
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(resColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                gravity = Gravity.END
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1.5f))
        }

    private fun settingsCard(content: LinearLayout.() -> Unit): View =
        MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor(R.color.surface_card))
            strokeColor = resColor(R.color.forest_light)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                content()
            })
        }

    private fun LinearLayout.addSettingsDivider() {
        addView(View(context).apply {
            setBackgroundColor(resColor(R.color.forest_light))
            layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        })
    }

    private fun packageInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun checkAppDistributionUpdate(showNoUpdateToast: Boolean = false) {
        val appDistribution = FirebaseAppDistribution.getInstance()
        val checkTask = if (appDistribution.isTesterSignedIn) {
            appDistribution.checkForNewRelease()
        } else {
            appDistribution.signInTester().continueWithTask {
                appDistribution.checkForNewRelease()
            }
        }

        checkTask
            .addOnSuccessListener { release ->
                if (release == null && showNoUpdateToast) showBriefToast("사용 가능한 새 버전이 없습니다")
                release?.let { showUpdateDialog(appDistribution, it) }
            }
            .addOnFailureListener { exception ->
                handleAppDistributionFailure(exception, "업데이트 확인에 실패했습니다")
            }
    }

    private fun showUpdateDialog(
        appDistribution: FirebaseAppDistribution,
        release: AppDistributionRelease
    ) {
        val releaseNotes = release.releaseNotes.orEmpty().trim()
        val message = buildString {
            append("새 테스트 버전 ${release.displayVersion} (${release.versionCode})을 사용할 수 있습니다.")
            if (releaseNotes.isNotBlank()) {
                append("\n\n")
                append(releaseNotes)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("업데이트 가능")
            .setMessage(message)
            .setPositiveButton("다운로드") { _, _ -> startAppDistributionUpdate(appDistribution) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun startAppDistributionUpdate(appDistribution: FirebaseAppDistribution) {
        showBriefToast("업데이트 다운로드를 시작합니다")
        appDistribution.updateApp()
            .addOnProgressListener { updateState ->
                when (updateState.updateStatus) {
                    UpdateStatus.DOWNLOADING -> showBriefToast(
                        "업데이트 다운로드 ${formatDownloadPercent(updateState.apkBytesDownloaded, updateState.apkFileTotalBytes)}"
                    )
                    UpdateStatus.DOWNLOADED -> showBriefToast("다운로드가 완료되었습니다")
                    UpdateStatus.REDIRECTED_TO_PLAY -> showBriefToast("Play 앱에서 업데이트를 계속합니다")
                    UpdateStatus.DOWNLOAD_FAILED,
                    UpdateStatus.INSTALL_FAILED,
                    UpdateStatus.NEW_RELEASE_CHECK_FAILED -> showBriefToast("업데이트를 진행하지 못했습니다")
                    UpdateStatus.UPDATE_CANCELED,
                    UpdateStatus.INSTALL_CANCELED -> showBriefToast("업데이트가 취소되었습니다")
                    UpdateStatus.NEW_RELEASE_NOT_AVAILABLE -> showBriefToast("사용 가능한 새 버전이 없습니다")
                    UpdateStatus.PENDING -> Unit
                }
            }
            .addOnFailureListener { exception ->
                handleAppDistributionFailure(exception, "업데이트 다운로드에 실패했습니다")
            }
    }

    private fun formatDownloadPercent(downloadedBytes: Long, totalBytes: Long): String {
        if (totalBytes <= 0L) return "진행 중"
        val percent = ((downloadedBytes * 100) / totalBytes).coerceIn(0L, 100L)
        return "$percent%"
    }

    private fun handleAppDistributionFailure(exception: Exception, fallbackMessage: String) {
        val message = when ((exception as? FirebaseAppDistributionException)?.errorCode) {
            FirebaseAppDistributionException.Status.NOT_IMPLEMENTED -> "App Distribution SDK가 포함된 빌드에서만 업데이트를 확인할 수 있습니다"
            FirebaseAppDistributionException.Status.AUTHENTICATION_CANCELED -> "테스터 로그인이 취소되었습니다"
            FirebaseAppDistributionException.Status.UPDATE_NOT_AVAILABLE -> "사용 가능한 새 버전이 없습니다"
            else -> exception.localizedMessage?.takeIf { it.isNotBlank() } ?: fallbackMessage
        }
        showBriefToast(message)
    }

    private fun renderDashboard() {
        val selected = reptiles.firstOrNull { it.id == selectedReptileId }
        if (selected == null) {
            binding.selectedName.text = if (reptiles.isEmpty()) {
                "아래 프로필에서 개체를 추가해 주세요"
            } else {
                "아래 프로필에서 개체를 선택해 주세요"
            }
            binding.selectedPhoto.setImageResource(R.drawable.ic_lizard_placeholder)
            binding.selectedSpecies.text = "선택한 개체의 성별, 식단, 무게 정보가 표시됩니다"
            binding.plannedCareList.removeAllViews()
            binding.plannedCareList.addView(TextView(this).apply {
                text = "선택된 개체가 없습니다"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            binding.weekRange.text = "이번 주"
            binding.weekDays.removeAllViews()
            repeat(7) { index ->
                binding.weekDays.addView(TextView(this).apply {
                    gravity = Gravity.CENTER
                    text = "${dayLabels[index]}\n-"
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                    setBackgroundResource(R.drawable.bg_week_day)
                }, android.widget.LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            binding.weightChart.setValues(emptyList())
            binding.weightAxisLabels.setValues(emptyList())
            binding.currentWeight.text = "기록 없음"
            return
        }
        binding.selectedName.text = selected.name
        binding.selectedPhoto.setImageResource(R.drawable.ic_lizard_placeholder)
        selected.photoUri?.let { binding.selectedPhoto.setImageURI(android.net.Uri.parse(it)) }
        binding.selectedSpecies.text = listOf(selected.species, selected.morph, selected.gender?.ifBlank { "미구분" } ?: "미구분").filter { it.isNotBlank() }.joinToString(" · ")
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
        binding.weekDays.removeAllViews()
        repeat(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            val daySchedules = schedules.filter { schedule -> schedule.reptileId == selected.id && isDietSchedule(schedule) && isScheduledOn(schedule, date) }
            binding.weekDays.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = "${dayLabels[index]}\n${if (daySchedules.isEmpty()) "-" else daySchedules.joinToString("\n") { it.careType }}"
                textSize = 11f
                setTextColor(if (date == LocalDate.now()) Color.WHITE else getColor(R.color.text_secondary))
                setBackgroundResource(if (date == LocalDate.now()) R.drawable.bg_week_day_selected else R.drawable.bg_week_day)
            }, android.widget.LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        renderPlannedCare(selected.id)
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
                setTextColor(getColor(R.color.text_secondary))
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
                setTextColor(if (status == "미실시") getColor(R.color.danger) else getColor(R.color.text_primary))
                setOnClickListener { setCareStatus(schedule, date.toEpochDay(), if (isChecked) "완료" else "미실시") }
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

    private fun loadWeightHistory(reptileId: Long) {
        lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) { database.weightRecordDao().allForReptile(reptileId) }
            val values = records.map { it.grams }
            val labels = records.map { record ->
                LocalDate.ofEpochDay(record.recordedAt).format(DateTimeFormatter.ofPattern("M/d"))
            }
            binding.weightChart.setValues(values, labels)
            binding.weightAxisLabels.setValues(values)
            binding.currentWeight.text = values.lastOrNull()?.let { "최근 ${formatGrams(it)}g" } ?: "기록 없음"
        }
    }

    private fun formatGrams(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)

    private fun renderProfiles() {
        binding.profileGrid.removeAllViews()
        val sortedReptiles = sortedProfiles()
        val totalItems = sortedReptiles.size + 1
        val columns = ((totalItems + 1) / 2).coerceAtLeast(1)
        binding.profileGrid.columnCount = columns

        val addItem = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openEditor(null) }
        }
        addItem.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = getDrawable(R.drawable.bg_profile_add_circle)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, 0, 0, 0)
            setImageResource(R.drawable.ic_input_add)
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.forest))
        })
        addItem.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            text = "프로필 추가"
            textSize = 12f
            maxLines = 1
            includeFontPadding = false
            setTextColor(getColor(R.color.forest))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        binding.profileGrid.addView(addItem, gridCell(0, 0))

        sortedReptiles.forEachIndexed { index, reptile ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(4))
                if (reptile.id == selectedReptileId) setBackgroundResource(R.drawable.bg_profile_selected)
                setOnClickListener {
                    selectedReptileId = reptile.id
                    getSharedPreferences("app_settings", MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_LAST_SELECTED_PROFILE_ID, reptile.id)
                        .apply()
                    renderProfiles()
                    renderDashboard()
                }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                background = getDrawable(R.drawable.bg_profile_circle)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
                setImageResource(R.drawable.ic_lizard_placeholder)
                reptile.photoUri?.let { setImageURI(android.net.Uri.parse(it)) }
            }
            item.addView(image)
            item.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
                text = reptile.name
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextColor(if (reptile.id == selectedReptileId) getColor(R.color.forest) else getColor(R.color.text_secondary))
                setTypeface(typeface, if (reptile.id == selectedReptileId) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
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
        width = dp(82)
        height = dp(96)
    }

    private fun sortedProfiles(): List<Reptile> {
        val koreanCollator = Collator.getInstance(Locale.KOREAN).apply { strength = Collator.PRIMARY }
        return when (profileSortMode) {
            1 -> reptiles.sortedWith(compareBy<Reptile> { it.hatchingDate ?: if (it.referenceDateType == "해칭일") it.referenceDate else Long.MAX_VALUE })
            2 -> reptiles.sortedWith(compareBy<Reptile> { it.adoptionDate ?: if (it.referenceDateType == "입양일") it.referenceDate else Long.MAX_VALUE })
            else -> reptiles.sortedWith { first, second ->
                naturalNameCompare(first.name, second.name, koreanCollator)
            }
        }
    }

    private fun naturalNameCompare(first: String, second: String, collator: Collator): Int {
        val firstName = first.trim().lowercase(Locale.KOREAN)
        val secondName = second.trim().lowercase(Locale.KOREAN)
        val numberPattern = Regex("^(.*?)(\\d+)$")
        val firstMatch = numberPattern.find(firstName)
        val secondMatch = numberPattern.find(secondName)
        if (firstMatch != null && secondMatch != null) {
            val prefixCompare = collator.compare(firstMatch.groupValues[1], secondMatch.groupValues[1])
            if (prefixCompare != 0) return prefixCompare
            val numberCompare = firstMatch.groupValues[2].toInt().compareTo(secondMatch.groupValues[2].toInt())
            if (numberCompare != 0) return numberCompare
        }
        return collator.compare(firstName, secondName)
    }

    private fun openEditor(id: Long?) = startActivity(Intent(this, ReptileEditActivity::class.java).apply { id?.let { putExtra(ReptileEditActivity.EXTRA_ID, it) } })
    private fun openDetail(id: Long) = startActivity(Intent(this, ReptileDetailActivity::class.java).putExtra(ReptileDetailActivity.EXTRA_ID, id))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun resColor(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun restoreLastSelectedProfile() {
        val lastSelectedId = getSharedPreferences("app_settings", MODE_PRIVATE).getLong(KEY_LAST_SELECTED_PROFILE_ID, -1L)
        selectedReptileId = lastSelectedId.takeIf { id -> id > 0L && reptiles.any { it.id == id } }
        renderProfiles()
        renderDashboard()
    }

    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun applyHomeStatusBarInset(root: View) {
        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, initialTop + top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun seedDemoWeekIfNeeded(database: AppDatabase, weekStart: Long) {
        val preferences = getSharedPreferences("demo_data", MODE_PRIVATE)
        val profilesSeeded = preferences.getBoolean("profiles_seeded_v2", false)
        val demoNamesMigrated = preferences.getBoolean("demo_names_migrated_v1", false)
        val weightsSeeded = preferences.getBoolean("weights_seeded_v1", false)
        val variedWeightsSeeded = preferences.getBoolean("weights_seeded_v2", false)
        lifecycleScope.launch(Dispatchers.IO) {
            val existing = database.reptileDao().all().toMutableList()
            val names = (1..10).map { "테스트$it" }
            val oldNames = listOf("레오", "테스트 개체 02", "테스트 개체 03", "테스트 개체 04", "테스트 개체 05", "테스트 개체 06", "테스트 개체 07", "테스트 개체 08", "테스트 개체 09", "테스트 개체 10")
            if (!profilesSeeded) {
                names.drop(existing.size).forEachIndexed { index, name ->
                    val sample = Reptile(0L, name, if (index % 2 == 0) "레오파드 게코" else "비어디 드래곤", if (index % 3 == 0) "하이 옐로우" else "노멀", "미구분", LocalDate.now().minusMonths((index + 3).toLong()).toEpochDay(), "입양일", null, System.currentTimeMillis())
                    sample.id = database.reptileDao().insert(sample)
                    existing.add(sample)
                }
                val existingSchedules = database.careScheduleDao().findBetween(weekStart, weekStart + 6).map { it.reptileId }.toSet()
                existing.take(10).filter { it.id !in existingSchedules }.forEachIndexed { index, reptile ->
                    val type = if (index % 2 == 0) "충식" else "채식"
                    CareSchedule().apply { reptileId = reptile.id; scheduledDate = weekStart + (index % 7); careType = type; memo = if (type == "충식") "귀뚜라미 급여" else "채소 급여" }.also { database.careScheduleDao().insert(it) }
                }
                preferences.edit().putBoolean("profiles_seeded_v2", true).apply()
            }
            if (profilesSeeded && !demoNamesMigrated) {
                existing.forEach { reptile ->
                    oldNames.indexOf(reptile.name).takeIf { it >= 0 }?.let { index ->
                        reptile.name = names[index]
                        database.reptileDao().update(reptile)
                    }
                }
                preferences.edit().putBoolean("demo_names_migrated_v1", true).apply()
            }
            if (!weightsSeeded) {
                database.reptileDao().all().take(10).forEachIndexed { index, reptile ->
                    if (database.weightRecordDao().allForReptile(reptile.id).isEmpty()) {
                        val base = 45f + index * 2.5f
                        listOf(0f, 1f, 0.5f, 2f, 1.5f, 3f, 2.5f).forEachIndexed { day, change ->
                            database.weightRecordDao().insert(WeightRecord(0L, reptile.id, weekStart + day, base + change))
                        }
                    }
                }
                preferences.edit().putBoolean("weights_seeded_v1", true).apply()
            }
            if (!variedWeightsSeeded) {
                val patterns = listOf(
                    listOf(0f, 1f, 2f, 3f, 4f, 5f, 6f),
                    listOf(0f, -1f, -2f, -3f, -4f, -5f, -6f),
                    listOf(0f, 3f, 1f, 4f, 2f, 5f, 3f),
                    listOf(0f, -3f, -1f, -4f, -2f, -5f, -3f),
                    listOf(0f, 1f, 0f, 1f, 0f, 1f, 0f),
                    listOf(0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f),
                    listOf(0f, -0.5f, -1f, -1.5f, -2f, -2.5f, -3f),
                    listOf(0f, 2f, 4f, 2f, 4f, 2f, 4f),
                    listOf(0f, -2f, -4f, -2f, -4f, -2f, -4f),
                    listOf(0f, 1f, 3f, 2f, 4f, 3f, 5f)
                )
                database.reptileDao().all().take(10).forEachIndexed { index, reptile ->
                    val records = database.weightRecordDao().allForReptile(reptile.id)
                    val lastRecord = records.lastOrNull()
                    val startDate = (lastRecord?.recordedAt ?: weekStart + 6) + 1
                    val startWeight = lastRecord?.grams ?: (45f + index * 2.5f)
                    patterns[index].forEachIndexed { day, change ->
                        database.weightRecordDao().insert(WeightRecord(0L, reptile.id, startDate + day, (startWeight + change).coerceAtLeast(1f)))
                    }
                }
                preferences.edit().putBoolean("weights_seeded_v2", true).apply()
            }
        }
    }

    private enum class MainSection {
        HOME,
        REPTILES,
        CALENDAR,
        BREEDING,
        SETTINGS
    }

    companion object {
        private const val KEY_CHECK_UPDATES_ON_START = "check_updates_on_start"
        private const val KEY_AUTO_SELECT_LAST_PROFILE = "auto_select_last_profile"
        private const val KEY_LAST_SELECTED_PROFILE_ID = "last_selected_profile_id"
        private const val KEY_CURRENT_SECTION = "current_section"
        private const val KEY_CURRENT_CALENDAR_MONTH = "current_calendar_month"
        private const val KEY_CALENDAR_SELECTED_DATE = "calendar_selected_date"
        private const val KEY_CALENDAR_LAST_SELECTED_DATE = "calendar_last_selected_date"
        private const val KEY_CALENDAR_DETAIL_DATE = "calendar_detail_date"
        private const val CALENDAR_RECORD_DOT_TAG = "calendar_record_dot"
    }
}
