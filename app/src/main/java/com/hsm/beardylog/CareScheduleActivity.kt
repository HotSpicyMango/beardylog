package com.hsm.beardylog

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.CareLog
import com.hsm.beardylog.data.CareSchedule
import com.hsm.beardylog.databinding.ActivityCareScheduleBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CareScheduleActivity : AppBaseActivity() {
    private lateinit var binding: ActivityCareScheduleBinding
    private lateinit var database: AppDatabase
    private var selectedDate = LocalDate.now()
    private var schedules: List<CareSchedule> = emptyList()
    private var logs: List<CareLog> = emptyList()
    private var editingScheduleId: Long? = null
    private var initialFormState: FormState? = null
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")
    private data class FormState(
        val editingScheduleId: Long?,
        val selectedDate: LocalDate,
        val repeating: Boolean,
        val repeatDayPosition: Int,
        val careTypePosition: Int,
        val memo: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCareScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getInstance(applicationContext)
        val reptileId = intent.getLongExtra(EXTRA_REPTILE_ID, -1L)
        // 다른 화면들과 동일하게, 개체를 특정하지 못하면 들어오지 않는다. 그냥 두면 reptileId=-1로
        // 저장이 시도되고 FK 위반으로 "저장하지 못했습니다"만 반복돼 원인을 알 수 없다.
        if (reptileId <= 0L) {
            finish()
            return
        }
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
        savedInstanceState?.let { saved ->
            selectedDate = LocalDate.ofEpochDay(saved.getLong(KEY_SELECTED_DATE, selectedDate.toEpochDay()))
            editingScheduleId = saved.getLong(KEY_EDITING_ID, -1L).takeIf { it > 0L }
            if (editingScheduleId != null) binding.saveButton.text = "일정 수정"
        }
        updateDateButton()
        initialFormState = currentFormState()
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (binding.oneTimeRadio.isPressed || binding.repeatRadio.isPressed) {
                binding.modeGroup.selectionHaptic()
            }
            updateMode(checkedId == R.id.repeatRadio)
        }
        binding.dateButton.setOnClickListener { view ->
            view.clickHaptic()
            pickDate()
        }
        binding.saveButton.setOnClickListener { view ->
            view.clickHaptic()
            saveSchedule(reptileId)
        }
        database.careScheduleDao().observeForReptile(reptileId).observe(this) {
            schedules = it
            renderSchedules()
        }
        database.careLogDao().observeForReptile(reptileId).observe(this) {
            logs = it
            renderSchedules()
        }
    }

    /** 회전이나 프로세스 종료로 화면이 다시 만들어져도 고른 날짜와 '수정 중'인 일정은 살아 있어야 한다.
     *  이게 없으면 수정하던 일정이 새 일정으로 저장돼 중복이 생긴다. 입력 필드 자체는 뷰가 알아서 복원한다.
     *  ponytail: 미저장 경고의 기준점(initialFormState)은 복원 후 현재 상태로 다시 잡는다 —
     *  회전 직전까지 친 내용은 경고 대상에서 빠지지만, 그 이후 수정은 정상적으로 잡힌다. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_SELECTED_DATE, selectedDate.toEpochDay())
        outState.putLong(KEY_EDITING_ID, editingScheduleId ?: -1L)
        super.onSaveInstanceState(outState)
    }

    private fun pickDate() {
        DatePickerDialog(this, { _, year, month, day ->
            selectedDate = LocalDate.of(year, month + 1, day)
            updateDateButton()
        }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
    }

    private fun updateDateButton() {
        binding.dateButton.text = "날짜: ${selectedDate.format(dateFormatter)}"
    }

    private fun saveSchedule(reptileId: Long) {
        val repeating = binding.repeatRadio.isChecked
        val schedule = CareSchedule().apply {
            this.reptileId = reptileId
            scheduledDate = selectedDate.toEpochDay()
            careType = binding.typeSpinner.selectedItem?.toString().orEmpty()
            memo = binding.memoInput.text?.toString()?.trim().orEmpty()
            // selectedItemPosition은 선택이 없으면 INVALID_POSITION(-1)이라 그대로 쓰면 0요일이 된다.
            repeatDayOfWeek = if (repeating) binding.daySpinner.selectedItemPosition.coerceAtLeast(0) + 1 else null
        }
        val updateId = editingScheduleId
        binding.saveButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (updateId == null) database.careScheduleDao().insert(schedule)
                    else {
                        schedule.id = updateId
                        database.careScheduleDao().update(schedule)
                    }
                }
            }.onSuccess {
                editingScheduleId = null
                binding.memoInput.text?.clear()
                binding.saveButton.text = "일정 저장"
                binding.saveButton.isEnabled = true
                initialFormState = currentFormState()
                binding.saveButton.confirmHaptic()
                showBriefToast("저장했습니다")
            }.onFailure {
                binding.saveButton.isEnabled = true
                binding.saveButton.rejectHaptic()
                showBriefToast("저장하지 못했습니다")
            }
        }
    }

    private fun renderSchedules() {
        if (!::binding.isInitialized) return
        binding.scheduleList.removeAllViews()
        if (schedules.isEmpty()) {
            binding.scheduleList.addView(TextView(this).apply {
                text = "등록된 일정이 없습니다"
                setTextColor(context.appColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val today = LocalDate.now()
        val grouped = linkedMapOf<String, MutableList<Pair<CareSchedule, Long?>>>()
        schedules.sortedWith(compareBy<CareSchedule> { it.scheduledDate }.thenBy { it.id }).forEach { schedule ->
            val effectiveDate = when {
                schedule.repeatDayOfWeek == null -> schedule.scheduledDate
                schedule.repeatDayOfWeek == today.dayOfWeek.value -> today.toEpochDay()
                else -> null
            }
            val key = effectiveDate?.let { "date:$it" } ?: "repeat:${schedule.repeatDayOfWeek}"
            grouped.getOrPut(key) { mutableListOf() }.add(schedule to effectiveDate)
        }
        grouped.forEach { (key, entries) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.bg_photo_placeholder)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = if (key.startsWith("date:")) {
                    LocalDate.ofEpochDay(key.removePrefix("date:").toLong()).format(dateFormatter)
                } else {
                    "매주 ${dayLabels.getOrNull((entries.first().first.repeatDayOfWeek ?: 0) - 1) ?: "?"}요일"
                }
                setTextColor(context.appColor(R.color.text_primary))
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            entries.forEach { (schedule, effectiveDate) ->
                val currentLog = effectiveDate?.let { date -> logs.firstOrNull { it.scheduleId == schedule.id && it.completedDate == date } }
                val status = currentLog?.status ?: if (effectiveDate == null) "다음 반복일" else "미실시"
                val check = CheckBox(this).apply {
                    text = buildString {
                        append(schedule.careType)
                        if (!schedule.memo.isNullOrBlank()) append(" · ${schedule.memo}")
                        append("  [$status]")
                    }
                    isChecked = status == "완료"
                    isEnabled = effectiveDate != null
                    setTextColor(context.appColor(if (status == "미실시") R.color.danger else R.color.text_secondary))
                    setOnClickListener { view ->
                        view.selectionHaptic()
                        effectiveDate?.let { setStatus(schedule, it, if (isChecked) "완료" else "미실시") }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val actions = LinearLayout(this).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                actions.addView(check)
                actions.addView(MaterialButton(this@CareScheduleActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "수정"
                    applyScheduleActionButtonStyle(R.color.forest)
                    setOnClickListener { view ->
                        view.clickHaptic()
                        beginEdit(schedule)
                    }
                })
                actions.addView(MaterialButton(this@CareScheduleActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "삭제"
                    applyScheduleActionButtonStyle(R.color.danger)
                    setOnClickListener { view ->
                        view.clickHaptic()
                        confirmDelete(schedule)
                    }
                })
                row.addView(actions)
            }
            binding.scheduleList.addView(row)
        }
    }

    private fun MaterialButton.applyScheduleActionButtonStyle(colorRes: Int) {
        val actionColor = ColorStateList.valueOf(this@CareScheduleActivity.appColor(colorRes))
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(36)
        minimumHeight = dp(36)
        insetTop = 0
        insetBottom = 0
        strokeWidth = dp(1)
        strokeColor = actionColor
        setTextColor(actionColor)
        backgroundTintList = ColorStateList.valueOf(this@CareScheduleActivity.appColor(R.color.surface_card))
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply {
            marginStart = dp(6)
        }
    }

    private fun setStatus(schedule: CareSchedule, date: Long, status: String) {
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

    private fun updateMode(repeating: Boolean) {
        binding.daySpinner.visibility = if (repeating) View.VISIBLE else View.GONE
        binding.dateButton.visibility = if (repeating) View.GONE else View.VISIBLE
    }

    private fun beginEdit(schedule: CareSchedule) {
        editingScheduleId = schedule.id
        binding.saveButton.text = "일정 수정"
        binding.memoInput.setText(schedule.memo.orEmpty())
        binding.typeSpinner.setSelection(resources.getStringArray(R.array.care_types).indexOf(schedule.careType).coerceAtLeast(0))
        selectedDate = LocalDate.ofEpochDay(schedule.scheduledDate)
        val repeatDay = schedule.repeatDayOfWeek
        if (repeatDay != null) {
            binding.modeGroup.check(R.id.repeatRadio)
            binding.daySpinner.setSelection(repeatDay - 1)
        } else {
            binding.modeGroup.check(R.id.oneTimeRadio)
            updateDateButton()
        }
        binding.memoInput.requestFocus()
        initialFormState = currentFormState()
    }

    private fun confirmDelete(schedule: CareSchedule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("일정 삭제")
            .setMessage("이 일정과 실행 기록을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { database.careScheduleDao().delete(schedule) }
                    }.onSuccess {
                        showBriefToast("삭제했습니다")
                    }.onFailure {
                        showBriefToast("삭제하지 못했습니다")
                    }
                }
            }
            .show()
    }

    private fun requestClose() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("변경 사항 취소")
            .setMessage("저장하지 않은 변경 사항이 있습니다. 나가시겠습니까?")
            .setNegativeButton("계속 작성", null)
            .setPositiveButton("나가기") { _, _ -> finish() }
            .show()
    }

    private fun hasUnsavedChanges(): Boolean = currentFormState() != initialFormState

    private fun currentFormState(): FormState = FormState(
        editingScheduleId = editingScheduleId,
        selectedDate = selectedDate,
        repeating = binding.repeatRadio.isChecked,
        repeatDayPosition = binding.daySpinner.selectedItemPosition,
        careTypePosition = binding.typeSpinner.selectedItemPosition,
        memo = binding.memoInput.text?.toString()?.trim().orEmpty()
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REPTILE_ID = "reptile_id"
        private const val KEY_SELECTED_DATE = "selected_date"
        private const val KEY_EDITING_ID = "editing_schedule_id"
    }
}
