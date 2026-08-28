package com.hsm.beardylog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.CalendarEntryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * MainActivity의 "달력" 섹션(월 달력 + 날짜 상세 편집) 전용 뷰 빌드와 상태를 담당한다.
 * MainActivity가 소유하며, 화면 전환/뒤로가기 같은 액티비티 공통 동작은 [activity]에 위임한다.
 */
internal class CalendarSection(private val activity: MainActivity) {

    private lateinit var calendarEntryStore: CalendarEntryStore
    private lateinit var holidayRepository: KoreanHolidayRepository

    private val calendarState = CalendarSectionState()
    private var currentCalendarMonth by calendarState::currentMonth
    private var calendarSelectedDate by calendarState::selectedDate
    private var calendarLastSelectedDate by calendarState::lastSelectedDate
    private var calendarDetailDate by calendarState::detailDate
    private var calendarScrollY by calendarState::scrollY

    private var koreanHolidays: Map<LocalDate, String> = emptyMap()
    private val holidayFetchMonths = mutableSetOf<YearMonth>()
    private val calendarDateCells = mutableMapOf<LocalDate, LinearLayout>()
    private var calendarMonthEntries: Map<LocalDate, CalendarEntryStore.MonthEntry> = emptyMap()
    private var calendarSummaryDateText: TextView? = null
    private var calendarSummaryBodyContainer: LinearLayout? = null
    private var calendarMemoDateText: TextView? = null
    private var calendarMemoHolidayText: TextView? = null
    private var calendarMemoBodyText: TextView? = null
    private var calendarTodayButton: TextView? = null
    private var calendarSavedFeedbackView: TextView? = null
    private var calendarMonthGridView: View? = null
    private var calendarMonthGridHost: FrameLayout? = null
    private var calendarMonthTitleText: TextView? = null
    private var calendarDetailDateTitleText: TextView? = null
    private var calendarDetailEditor: CalendarDetailEditor? = null
    private var calendarMonthRenderGeneration = 0
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

    // ---- MainActivity가 호출하는 진입점 ----

    fun setup() {
        calendarEntryStore = CalendarEntryStore(activity)
        holidayRepository = KoreanHolidayRepository(activity)
    }

    fun restore(savedInstanceState: Bundle?) {
        calendarState.restore(savedInstanceState)
    }

    fun save(outState: Bundle) {
        calendarState.save(outState)
    }

    fun leave() {
        calendarState.leave()
        calendarDetailEditor = null
    }

    /** 앱을 켜 둔 채 자정을 넘기면 '오늘' 강조와 오늘 버튼이 어제에 머문다. 날짜가 바뀐 뒤 돌아왔을 때만 다시 그린다.
     *  상세 화면이 열려 있으면 입력 중인 내용이 날아가므로 건드리지 않는다 — 목록으로 돌아갈 때 어차피 새로 그려진다. */
    fun refreshForNewDay() {
        if (calendarDetailDate != null) return
        activity.replaceTopContent(createCalendarContentView())
    }

    /** true를 반환하면 뒤로가기를 이 섹션이 처리했다는 뜻이다. */
    fun handleBackPressed(): Boolean {
        calendarDetailEditor?.let { editor ->
            handleCalendarDetailBack(editor.date, editor.taskChecks, editor.hospitalInput, editor.medicineInput, editor.memoInput)
            return true
        }
        calendarDetailDate?.let { date ->
            returnToCalendarFromDetail(date, resetScroll = true)
            return true
        }
        return false
    }

    fun createCalendarContentView(): View {
        calendarDetailDate?.let { date ->
            val detailMonth = YearMonth.from(date)
            loadKoreanHolidays(detailMonth)
            fetchKoreanHolidaysIfNeeded(detailMonth)
            return createCalendarDayContentView(date)
        }
        val calendarSidePaddingDp = when {
            activity.resources.configuration.screenWidthDp <= 360 -> 8
            activity.resources.configuration.screenWidthDp <= 420 -> 12
            else -> 20
        }
        val alignedContentMargin = dp(20 - calendarSidePaddingDp)
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
            post { scrollTo(0, calendarScrollY) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(calendarSidePaddingDp), dp(20), dp(calendarSidePaddingDp), dp(24))
        }
        loadKoreanHolidays(currentCalendarMonth)
        fetchKoreanHolidaysIfNeeded(currentCalendarMonth)
        calendarMonthEntries = calendarEntryStore.monthEntries(currentCalendarMonth, calendarTaskLabels)
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        content.addView(calendarMonthHeader(), LinearLayout.LayoutParams(match, wrap).apply {
            leftMargin = alignedContentMargin
            rightMargin = alignedContentMargin
        })
        val monthGrid = calendarMonthGrid()
        content.addView(FrameLayout(activity).apply {
            calendarMonthGridHost = this
            addView(monthGrid, FrameLayout.LayoutParams(match, wrap))
        }, LinearLayout.LayoutParams(match, wrap))
        calendarMonthGridView = monthGrid
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

    private fun calendarMonthHeader(): View = LinearLayout(activity).apply {
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
                setOnClickListener { view ->
                    view.selectionHaptic()
                    calendarMonthRenderGeneration += 1
                    calendarState.showToday()
                    activity.replaceTopContent(createCalendarContentView())
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
                id = R.id.calendar_previous_month_button
                text = "‹"
                contentDescription = "이전 달"
                textSize = 34f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
                setBackgroundResource(R.drawable.bg_week_day)
                isClickable = true
                isFocusable = true
                setOnClickListener { view ->
                    view.selectionHaptic()
                    changeCalendarMonth(-1)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(TextView(context).apply {
                id = R.id.calendar_month_title
                text = currentCalendarMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월"))
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
                calendarMonthTitleText = this
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                id = R.id.calendar_next_month_button
                text = "›"
                contentDescription = "다음 달"
                textSize = 34f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.forest))
                setBackgroundResource(R.drawable.bg_week_day)
                isClickable = true
                isFocusable = true
                setOnClickListener { view ->
                    view.selectionHaptic()
                    changeCalendarMonth(1)
                }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
        })
    }

    private fun changeCalendarMonth(offset: Long) {
        calendarMonthGridView?.animate()?.cancel()
        val direction = if (offset > 0) 1 else -1
        calendarState.moveMonth(offset)
        loadKoreanHolidays(currentCalendarMonth)
        fetchKoreanHolidaysIfNeeded(currentCalendarMonth)
        calendarMonthTitleText?.text = currentCalendarMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월"))
        updateCalendarPreviewTexts()
        (activity.currentTopContent as? ScrollView)?.scrollTo(0, 0)

        val renderGeneration = ++calendarMonthRenderGeneration
        val gridHost = calendarMonthGridHost ?: run {
            activity.replaceTopContent(createCalendarContentView())
            return
        }
        gridHost.postOnAnimation {
            if (renderGeneration != calendarMonthRenderGeneration || activity.currentSection != MainActivity.MainSection.CALENDAR) return@postOnAnimation
            calendarMonthEntries = calendarEntryStore.monthEntries(currentCalendarMonth, calendarTaskLabels)
            val monthGrid = calendarMonthGrid()
            gridHost.removeAllViews()
            gridHost.addView(monthGrid, FrameLayout.LayoutParams(match, wrap))
            animateCalendarMonthGrid(monthGrid, direction)
        }
    }

    private fun animateCalendarMonthGrid(monthGrid: View, direction: Int) {
        calendarMonthGridView = monthGrid
        monthGrid.translationX = dp(24).toFloat() * direction
        monthGrid.post {
            if (!monthGrid.isAttachedToWindow) return@post
            monthGrid.animate()
                .translationX(0f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .withLayer()
                .start()
        }
    }

    private fun calendarMonthGrid(): View = MaterialCardView(activity).apply {
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

    private fun calendarDateCell(date: LocalDate): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        setPadding(dp(2), dp(5), dp(2), dp(4))
            setOnClickListener { view ->
                view.selectionHaptic()
                val previousDate = calendarSelectedDate
                calendarState.select(date)
            previousDate?.let(::refreshCalendarDateCellStyle)
            refreshCalendarDateCellStyle(date)
            updateCalendarPreviewTexts()
        }
        setOnLongClickListener { view ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openCalendarDetail(date)
            true
        }
        addView(TextView(context).apply {
            text = date.dayOfMonth.toString()
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        val monthEntry = calendarMonthEntries[date]
        val cellIcons = calendarCellIcons(monthEntry)
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
        } else if (calendarHasRecordMarker(date, monthEntry)) {
            addView(View(context).apply {
                tag = CALENDAR_RECORD_DOT_TAG
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(6) })
        }
        updateCalendarDateCellStyle(this, date)
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
            else -> calendarDateTextColor(date)
        })
        updateCalendarCellIndicators(cell, if (isSelected) Color.WHITE else resColor(R.color.forest))
    }

    private fun calendarDateTextColor(date: LocalDate): Int = when {
        isHolidayDate(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> resColor(R.color.danger)
        date.dayOfWeek == DayOfWeek.SATURDAY || date == LocalDate.now() -> resColor(R.color.forest)
        else -> resColor(R.color.text_primary)
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
                setTextColor(selectedDate?.let(::calendarDateTextColor) ?: resColor(R.color.forest))
                calendarSummaryDateText = this
            })
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
            gravity = Gravity.TOP
            addView(TextView(context).apply {
                text = "메모"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(wrap, wrap))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(TextView(context).apply {
                    text = selectedDate?.format(DateTimeFormatter.ofPattern("M월 d일")) ?: "날짜 선택"
                    textSize = 14f
                    gravity = Gravity.END
                    isSingleLine = true
                    setTextColor(selectedDate?.let(::calendarDateTextColor) ?: resColor(R.color.forest))
                    calendarMemoDateText = this
                }, LinearLayout.LayoutParams(match, wrap))
                addView(TextView(context).apply {
                    textSize = 12f
                    gravity = Gravity.END
                    maxLines = 2
                    setTextColor(resColor(R.color.danger))
                    calendarMemoHolidayText = this
                    updateCalendarMemoHolidayText(selectedDate)
                }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(12) })
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
        calendarSummaryDateText?.setTextColor(selectedDate?.let(::calendarDateTextColor) ?: resColor(R.color.forest))
        calendarSummaryBodyContainer?.let { updateCalendarSummaryBody(it, selectedDate) }
        calendarMemoDateText?.text = dateText
        calendarMemoDateText?.setTextColor(selectedDate?.let(::calendarDateTextColor) ?: resColor(R.color.forest))
        updateCalendarMemoHolidayText(selectedDate)
        calendarMemoBodyText?.text = selectedDate
            ?.let { calendarTextValue(it, "memo").trim().takeIf(String::isNotBlank) }
            ?: "메모가 없습니다"
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
            container.addView(calendarSummaryMessage("기록이 없습니다"))
            return
        }
        items.forEachIndexed { index, item ->
            container.addView(calendarSummaryRow(item), LinearLayout.LayoutParams(match, wrap).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
    }

    private fun calendarSummaryMessage(message: String): TextView = TextView(activity).apply {
        text = message
        textSize = 14f
        setTextColor(resColor(R.color.text_secondary))
    }

    private fun calendarSummaryRow(item: CalendarSummaryItem): View = LinearLayout(activity).apply {
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
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
        }
        val content = LinearLayout(activity).apply {
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
        content.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(resColor(R.color.text_primary))
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "뒤로가기"
                isClickable = true
                isFocusable = true
                setOnClickListener { view ->
                    view.clickHaptic()
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
        content.addView(TextView(activity).apply {
            text = calendarDetailTitle(date)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resColor(R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(18))
            calendarDetailDateTitleText = this
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
        content.addView(MaterialButton(activity).apply {
            text = "저장"
            setTextColor(resColor(R.color.button_on_primary))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.button_primary))
            setOnClickListener { view ->
                view.confirmHaptic()
                saveCalendarDetail(date, taskChecks, hospitalInput, medicineInput, memoInput)
                returnToCalendarFromDetail(date, resetScroll = false)
                showCalendarSavedSnackbar()
            }
            layoutParams = LinearLayout.LayoutParams(match, dp(52)).apply { topMargin = dp(18) }
        })
        val deleteButton = MaterialButton(activity).apply {
            text = "이 날짜 기록 전체 삭제"
            setTextColor(resColor(R.color.danger))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(resColor(R.color.danger))
            strokeWidth = dp(1)
            cornerRadius = dp(12)
            setIconResource(R.drawable.ic_delete)
            iconTint = ColorStateList.valueOf(resColor(R.color.danger))
            setOnClickListener { view ->
                view.clickHaptic()
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
            checkBox.setOnCheckedChangeListener { button, _ ->
                button.selectionHaptic()
                updateDeleteButtonState()
            }
        }
        hospitalInput.doAfterTextChanged { updateDeleteButtonState() }
        medicineInput.doAfterTextChanged { updateDeleteButtonState() }
        memoInput.doAfterTextChanged { updateDeleteButtonState() }
        updateDeleteButtonState()
        return scrollView
    }

    private fun openCalendarDetail(date: LocalDate) {
        calendarState.openDetail(date, (activity.currentTopContent as? ScrollView)?.scrollY ?: calendarScrollY)
        activity.replaceTopContent(createCalendarDayContentView(date))
    }

    private fun handleCalendarDetailBack(
        date: LocalDate,
        taskChecks: Map<String, CheckBox>,
        hospitalInput: EditText,
        medicineInput: EditText,
        memoInput: EditText
    ) {
        // CareScheduleActivity/ReptileEditActivity와 동일하게, 바꾼 게 없으면 묻지 않고 닫는다.
        if (!hasUnsavedCalendarDetailChanges(date, taskChecks, hospitalInput, medicineInput, memoInput)) {
            returnToCalendarFromDetail(date, resetScroll = true)
            return
        }
        val dialog = MaterialAlertDialogBuilder(activity)
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
        val dialog = MaterialAlertDialogBuilder(activity)
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
        calendarState.returnFromDetail(date, resetScroll)
        calendarDetailDateTitleText = null
        calendarDetailEditor = null
        activity.replaceTopContent(createCalendarContentView())
    }

    private fun showCalendarSavedSnackbar() = showCalendarFeedback("저장되었습니다")

    private fun showCalendarFeedback(message: String) {
        activity.binding.root.post {
            calendarSavedFeedbackView?.let(activity.binding.root::removeView)
            val feedback = TextView(activity).apply {
                text = message
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resColor(R.color.surface_card))
                setBackgroundResource(R.drawable.bg_calendar_feedback)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                alpha = 0f
            }
            val bottomNavHeight = activity.binding.bottomNavigation.height.takeIf { it > 0 } ?: dp(86)
            activity.binding.root.addView(
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
                            activity.binding.root.removeView(feedback)
                            calendarSavedFeedbackView = null
                        }
                    }.start()
                }
            }, 1700L)
        }
    }

    private fun calendarInputRow(label: String, input: EditText, clearDescription: String): View = LinearLayout(activity).apply {
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

    private fun calendarClearButton(input: EditText, description: String): ImageView = ImageView(activity).apply {
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
        setOnClickListener { view ->
            view.clickHaptic()
            input.text?.clear()
        }
    }

    private fun calendarEditText(value: String, hint: String): EditText = EditText(activity).apply {
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

    private fun calendarCellIcons(entry: CalendarEntryStore.MonthEntry?): List<Int> = buildList {
        addAll(entry?.checkedTasks.orEmpty().mapNotNull(::calendarTaskIcon))
        if (!entry?.hospital.isNullOrBlank()) add(R.drawable.ic_calendar_hospital)
        if (!entry?.medicine.isNullOrBlank()) add(R.drawable.ic_calendar_medicine_bottle)
    }

    private fun calendarHasRecordMarker(date: LocalDate, entry: CalendarEntryStore.MonthEntry?): Boolean {
        return !entry?.memo.isNullOrBlank()
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
        return buildList {
            checked.forEach { label ->
                add(CalendarSummaryItem(calendarTaskIcon(label), label))
            }
            if (hospital.isNotBlank()) add(CalendarSummaryItem(R.drawable.ic_calendar_hospital, "병원: $hospital"))
            if (medicine.isNotBlank()) add(CalendarSummaryItem(R.drawable.ic_calendar_medicine_bottle, "약: $medicine"))
        }
    }

    private fun loadKoreanHolidays(month: YearMonth) {
        koreanHolidays = holidayRepository.cached(month)
    }

    private fun updateCalendarMemoHolidayText(date: LocalDate?) {
        calendarMemoHolidayText?.apply {
            val holidayName = date?.let(koreanHolidays::get)
            text = holidayName.orEmpty()
            visibility = if (holidayName == null) View.GONE else View.VISIBLE
        }
    }

    private fun fetchKoreanHolidaysIfNeeded(month: YearMonth) {
        if (!holidayRepository.isConfigured || !holidayFetchMonths.add(month)) return
        activity.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { holidayRepository.fetch(month) }
            if (fetched.isEmpty()) {
                // fetch는 실패를 빈 결과로 삼킨다. 표시를 지워두지 않으면 지하철에서 한 번 실패한 달은
                // 앱을 껐다 켤 때까지 공휴일이 영영 안 나온다.
                holidayFetchMonths.remove(month)
            } else {
                if (activity.currentSection == MainActivity.MainSection.CALENDAR && calendarDetailDate == null && currentCalendarMonth == month) {
                    koreanHolidays = fetched
                    calendarDateCells.forEach { (date, cell) -> updateCalendarDateCellStyle(cell, date) }
                    updateCalendarPreviewTexts()
                }
                calendarDetailDate?.takeIf {
                    activity.currentSection == MainActivity.MainSection.CALENDAR && YearMonth.from(it) == month
                }?.let { detailDate ->
                    koreanHolidays = fetched
                    calendarDetailDateTitleText?.text = calendarDetailTitle(detailDate)
                }
            }
        }
    }

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

    // ---- 이 섹션 전용 소품 헬퍼 (MainActivity의 것과 동일한 구현을 그대로 둔다) ----

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun resColor(resId: Int): Int = activity.appColor(resId)
    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun settingsCard(verticalPaddingDp: Int = 16, content: LinearLayout.() -> Unit): View =
        MaterialCardView(activity).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resColor(R.color.surface_card))
            strokeColor = resColor(R.color.forest_light)
            strokeWidth = dp(1)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(verticalPaddingDp), dp(16), dp(verticalPaddingDp))
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

    private companion object {
        const val CALENDAR_RECORD_DOT_TAG = "calendar_record_dot"
    }
}
