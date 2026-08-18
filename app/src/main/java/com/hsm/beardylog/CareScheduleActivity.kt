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
import androidx.core.content.ContextCompat
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
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { DrawableCompat.setTint(it, ContextCompat.getColor(this, R.color.text_primary)) }
        binding.toolbar.setNavigationOnClickListener { requestClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestClose()
            }
        })
        updateDateButton()
        initialFormState = currentFormState()
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId -> updateMode(checkedId == R.id.repeatRadio) }
        binding.dateButton.setOnClickListener { pickDate() }
        binding.saveButton.setOnClickListener { saveSchedule(reptileId) }
        database.careScheduleDao().observeForReptile(reptileId).observe(this) {
            schedules = it
            renderSchedules()
        }
        database.careLogDao().observeForReptile(reptileId).observe(this) {
            logs = it
            renderSchedules()
        }
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
            careType = binding.typeSpinner.selectedItem.toString()
            memo = binding.memoInput.text?.toString()?.trim().orEmpty()
            repeatDayOfWeek = if (repeating) binding.daySpinner.selectedItemPosition + 1 else null
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
                showBriefToast("저장했습니다")
            }.onFailure {
                binding.saveButton.isEnabled = true
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
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
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
                    "매주 ${dayLabels[entries.first().first.repeatDayOfWeek!! - 1]}요일"
                }
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
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
                    setTextColor(ContextCompat.getColor(context, if (status == "미실시") R.color.danger else R.color.text_secondary))
                    setOnClickListener { effectiveDate?.let { setStatus(schedule, it, if (isChecked) "완료" else "미실시") } }
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                val actions = LinearLayout(this).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                actions.addView(check)
                actions.addView(MaterialButton(this@CareScheduleActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "수정"
                    applyScheduleActionButtonStyle(R.color.forest)
                    setOnClickListener { beginEdit(schedule) }
                })
                actions.addView(MaterialButton(this@CareScheduleActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "삭제"
                    applyScheduleActionButtonStyle(R.color.danger)
                    setOnClickListener { confirmDelete(schedule) }
                })
                row.addView(actions)
            }
            binding.scheduleList.addView(row)
        }
    }

    private fun MaterialButton.applyScheduleActionButtonStyle(colorRes: Int) {
        val actionColor = ColorStateList.valueOf(ContextCompat.getColor(this@CareScheduleActivity, colorRes))
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(36)
        minimumHeight = dp(36)
        insetTop = 0
        insetBottom = 0
        strokeWidth = dp(1)
        strokeColor = actionColor
        setTextColor(actionColor)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@CareScheduleActivity, R.color.surface_card))
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

    companion object { const val EXTRA_REPTILE_ID = "reptile_id" }
}
