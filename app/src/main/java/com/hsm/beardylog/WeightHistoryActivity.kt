package com.hsm.beardylog

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.WeightChartPeriod
import com.hsm.beardylog.data.WeightRecord
import com.hsm.beardylog.databinding.ActivityWeightHistoryBinding
import com.hsm.beardylog.databinding.DialogWeightRecordBinding
import com.hsm.beardylog.ui.setWeightNumberText
import com.hsm.beardylog.ui.withWeightNumberTypeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class WeightHistoryActivity : AppBaseActivity() {
    private lateinit var binding: ActivityWeightHistoryBinding
    private lateinit var database: AppDatabase
    private var reptileId = -1L
    private var records: List<WeightRecord> = emptyList()
    private var selectedPeriod = WeightChartPeriod.THREE_MONTHS
    /** 이 개체가 존재하기 시작한 날. 그 전 무게는 있을 수 없으므로 날짜 선택의 하한이 된다. */
    private var earliestAllowedDate: LocalDate? = null
    private val historyAdapter by lazy {
        WeightRecordAdapter(
            dateFormatter = rowDateFormatter,
            formatGrams = ::formatGrams,
            onRowClick = { record -> showRecordSheet(record) },
            onDeleteClick = { record -> confirmDelete(record) },
        )
    }

    private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    private val rowDateFormatter = DateTimeFormatter.ofPattern("yyyy.M.d")
    private val chartDateFormatter = DateTimeFormatter.ofPattern("M/d")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWeightHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getInstance(applicationContext)
        reptileId = intent.getLongExtra(EXTRA_REPTILE_ID, -1L)
        if (reptileId <= 0L) {
            finish()
            return
        }

        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = historyAdapter
        // 바깥 NestedScrollView가 이미 스크롤을 담당하니, 리스트 자체는 중첩 스크롤을 하지 않는다.
        binding.historyList.isNestedScrollingEnabled = false

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { icon ->
            DrawableCompat.setTint(icon, appColor(R.color.text_primary))
        }
        binding.toolbar.setNavigationOnClickListener { view ->
            view.clickHaptic()
            finish()
        }
        binding.addWeightButton.setOnClickListener { view ->
            view.clickHaptic()
            showRecordSheet()
        }
        binding.periodGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedPeriod = when (checkedId) {
                R.id.periodOneWeek -> WeightChartPeriod.ONE_WEEK
                R.id.periodOneMonth -> WeightChartPeriod.ONE_MONTH
                R.id.periodAll -> WeightChartPeriod.ALL
                else -> WeightChartPeriod.THREE_MONTHS
            }
            renderChart()
        }
        listOf(binding.periodOneWeek, binding.periodOneMonth, binding.periodThreeMonths, binding.periodAll).forEach { chip ->
            chip.setOnClickListener { it.selectionHaptic() }
        }

        database.reptileDao().observeById(reptileId).observe(this) { reptile ->
            if (reptile == null) {
                finish()
                return@observe
            }
            // 해칭일과 입양일이 둘 다 있으면 이른 쪽이 기준이다. 둘 다 없는 예전 데이터는 referenceDate로.
            earliestAllowedDate = listOfNotNull(reptile.hatchingDate, reptile.adoptionDate).minOrNull()
                ?.let(LocalDate::ofEpochDay)
                ?: LocalDate.ofEpochDay(reptile.referenceDate)
            binding.profileName.text = reptile.name
            binding.profileSpecies.text = listOf(reptile.species, reptile.morph)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "종 정보 없음" }
            binding.profilePhoto.setImageResource(R.drawable.ic_lizard_placeholder)
            reptile.photoUri?.let {
                com.bumptech.glide.Glide.with(this)
                    .load(android.net.Uri.parse(it))
                    .override(dp(52), dp(52))
                    .centerCrop()
                    .into(binding.profilePhoto)
            }
        }
        database.weightRecordDao().observeForReptile(reptileId).observe(this) { next ->
            records = next.sortedWith(compareBy<WeightRecord> { it.recordedAt }.thenBy { it.id })
            renderSummary()
            renderChart()
            renderHistory()
        }
    }

    private fun renderSummary() {
        val latest = records.lastOrNull()
        if (latest == null) {
            binding.latestWeight.text = "-- g"
            binding.latestDate.text = "아직 기록이 없습니다"
            binding.weightChange.text = "무게를 기록하면 변화를 확인할 수 있어요"
            binding.weightChange.setTextColor(appColor(R.color.text_secondary))
            return
        }

        binding.latestWeight.setWeightNumberText("${formatGrams(latest.grams)}g")
        binding.latestDate.text = "최근 기록 · ${LocalDate.ofEpochDay(latest.recordedAt).format(fullDateFormatter)}"
        val previous = records.getOrNull(records.lastIndex - 1)
        binding.weightChange.setWeightNumberText(previous?.let {
            val difference = latest.grams - it.grams
            when {
                difference > 0f -> "이전 기록보다 +${formatGrams(difference)}g"
                difference < 0f -> "이전 기록보다 -${formatGrams(abs(difference))}g"
                else -> "이전 기록과 변화 없음"
            }
        } ?: "첫 번째 무게 기록")
        binding.weightChange.setTextColor(appColor(R.color.forest))
    }

    private fun renderChart() {
        if (!::binding.isInitialized) return
        val cutoff = selectedPeriod.cutoffEpochDay()
        val visibleRecords = records.filter { it.recordedAt >= cutoff }
        val hasRecords = visibleRecords.isNotEmpty()
        binding.chartContent.visibility = if (hasRecords) View.VISIBLE else View.INVISIBLE
        binding.chartEmpty.visibility = if (hasRecords) View.GONE else View.VISIBLE
        val values = visibleRecords.map { it.grams }
        val labels = visibleRecords.map { LocalDate.ofEpochDay(it.recordedAt).format(chartDateFormatter) }
        binding.weightChart.setValues(values, labels)
        binding.weightAxisLabels.setValues(values)
        if (hasRecords) binding.chartScroll.post { binding.chartScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun renderHistory() {
        binding.historyEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        // 최신순으로 보여주므로 오름차순 기록을 뒤집어서 넘긴다. 어댑터는 DiffUtil로 실제 바뀐 카드만 갱신한다.
        historyAdapter.submitList(records.asReversed())
    }

    private fun showRecordSheet(editing: WeightRecord? = null) {
        val sheetBinding = DialogWeightRecordBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        var selectedDate = editing?.let { LocalDate.ofEpochDay(it.recordedAt) } ?: LocalDate.now()
        sheetBinding.title.text = if (editing == null) "무게 기록 추가" else "무게 기록 수정"
        sheetBinding.dateInput.setText(selectedDate.format(fullDateFormatter))
        val initialWeight = editing?.grams ?: records.lastOrNull()?.grams
        initialWeight?.let { sheetBinding.weightInput.setText(formatGrams(it)) }
        sheetBinding.weightInput.setSelection(0, sheetBinding.weightInput.text?.length ?: 0)

        sheetBinding.dateInput.setOnClickListener { view ->
            view.clickHaptic()
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                sheetBinding.dateInput.setText(selectedDate.format(fullDateFormatter))
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
                earliestAllowedDate?.let { earliest ->
                    // 이미 저장된 기록이 기준일보다 이르거나(제한 도입 전 데이터) 기준일이 미래로 잘못
                    // 입력돼 있으면, 하한이 초기값이나 maxDate를 넘어서 DatePicker가 깨진다. 그래서 함께 낮춘다.
                    val floor = minOf(earliest, selectedDate, LocalDate.now())
                    datePicker.minDate = floor.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }.show()
        }
        sheetBinding.cancelButton.setOnClickListener { view ->
            view.clickHaptic()
            dialog.dismiss()
        }
        sheetBinding.saveButton.setOnClickListener { view ->
            view.clickHaptic()
            val grams = sheetBinding.weightInput.text?.toString()?.trim()?.replace(',', '.')?.toFloatOrNull()
            when {
                grams == null || !grams.isFinite() || grams <= 0f -> {
                    sheetBinding.weightInputLayout.error = "0보다 큰 무게를 입력해 주세요"
                    sheetBinding.weightInput.requestFocus()
                    view.rejectHaptic()
                }
                selectedDate.isAfter(LocalDate.now()) -> showBriefToast("미래 날짜에는 기록할 수 없습니다")
                else -> {
                    sheetBinding.weightInputLayout.error = null
                    saveRecord(dialog, sheetBinding, editing, selectedDate, grams)
                }
            }
        }
        sheetBinding.weightInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                sheetBinding.saveButton.performClick()
                true
            } else {
                false
            }
        }
        sheetBinding.root.dismissKeyboardOnOutsideTouch(sheetBinding.weightInput)
        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            sheetBinding.weightInput.requestFocus()
        }
        dialog.show()
    }

    private fun saveRecord(
        dialog: BottomSheetDialog,
        sheetBinding: DialogWeightRecordBinding,
        editing: WeightRecord?,
        selectedDate: LocalDate,
        grams: Float
    ) {
        sheetBinding.saveButton.isEnabled = false
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                database.weightRecordDao().findForDate(reptileId, selectedDate.toEpochDay())
            }
            if (existing != null && existing.id != editing?.id) {
                sheetBinding.saveButton.isEnabled = true
                if (editing != null) {
                    sheetBinding.weightInputLayout.error = "선택한 날짜에 이미 기록이 있습니다"
                    return@launch
                }
                confirmOverwrite(dialog, sheetBinding, existing, grams)
                return@launch
            }
            persistRecord(dialog, sheetBinding, editing, selectedDate, grams)
        }
    }

    private fun confirmOverwrite(
        dialog: BottomSheetDialog,
        sheetBinding: DialogWeightRecordBinding,
        existing: WeightRecord,
        grams: Float
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("기존 기록 덮어쓰기")
            .setMessage("이 날짜에 ${formatGrams(existing.grams)}g 기록이 있습니다. 새 무게로 바꿀까요?".withWeightNumberTypeface(this))
            .setNegativeButton("취소", null)
            .setPositiveButton("덮어쓰기") { _, _ ->
                persistRecord(
                    dialog,
                    sheetBinding,
                    WeightRecord(existing.id, existing.reptileId, existing.recordedAt, grams),
                    LocalDate.ofEpochDay(existing.recordedAt),
                    grams
                )
            }
            .show()
    }

    private fun persistRecord(
        dialog: BottomSheetDialog,
        sheetBinding: DialogWeightRecordBinding,
        editing: WeightRecord?,
        selectedDate: LocalDate,
        grams: Float
    ) {
        sheetBinding.saveButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val record = WeightRecord(editing?.id ?: 0L, reptileId, selectedDate.toEpochDay(), grams)
                    if (editing == null) database.weightRecordDao().insert(record)
                    else database.weightRecordDao().update(record)
                }
            }.onSuccess {
                binding.root.confirmHaptic()
                showBriefToast(if (editing == null) "무게를 기록했습니다" else "무게 기록을 수정했습니다")
                dialog.dismiss()
            }.onFailure {
                binding.root.rejectHaptic()
                sheetBinding.saveButton.isEnabled = true
                showBriefToast("무게 기록을 저장하지 못했습니다")
            }
        }
    }

    private fun confirmDelete(record: WeightRecord) {
        val dateText = LocalDate.ofEpochDay(record.recordedAt).format(fullDateFormatter)
        MaterialAlertDialogBuilder(this)
            .setTitle("무게 기록 삭제")
            .setMessage("$dateText · ${formatGrams(record.grams)}g 기록을 삭제할까요?".withWeightNumberTypeface(this))
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { database.weightRecordDao().delete(record) }
                    }.onSuccess {
                        binding.root.confirmHaptic()
                        showBriefToast("무게 기록을 삭제했습니다")
                    }.onFailure {
                        binding.root.rejectHaptic()
                        showBriefToast("무게 기록을 삭제하지 못했습니다")
                    }
                }
            }
            .show()
    }

    private fun formatGrams(value: Float): String = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REPTILE_ID = "reptile_id"
    }
}
