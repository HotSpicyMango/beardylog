package com.hsm.beardylog

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.CareSchedule
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
import java.time.format.DateTimeFormatter
import java.text.Collator
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ReptileViewModel
    private var reptiles: List<Reptile> = emptyList()
    private var schedules: List<CareSchedule> = emptyList()
    private var selectedReptileId: Long? = null
    private var profileSortMode = 0
    private lateinit var database: AppDatabase
    private val dayLabels = listOf("월", "화", "수", "목", "금", "토", "일")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        database = AppDatabase.getInstance(applicationContext)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(database.reptileDao())))[ReptileViewModel::class.java]
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay()
        database.careScheduleDao().observeBetween(weekStart, weekStart + 6).observe(this) {
            schedules = it
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
                    renderProfiles()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.quickDietButton.setOnClickListener { Toast.makeText(this, "식단 기록은 다음 단계에서 연결됩니다", Toast.LENGTH_SHORT).show() }
        binding.quickWeightButton.setOnClickListener { Toast.makeText(this, "무게 기록은 다음 단계에서 연결됩니다", Toast.LENGTH_SHORT).show() }
        binding.quickMemoButton.setOnClickListener { Toast.makeText(this, "메모 기록은 다음 단계에서 연결됩니다", Toast.LENGTH_SHORT).show() }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                else -> {
                    Toast.makeText(this, "다음 단계에서 제공됩니다", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
        viewModel.reptiles.observe(this) {
            reptiles = it
            if (selectedReptileId == null || reptiles.none { reptile -> reptile.id == selectedReptileId }) selectedReptileId = reptiles.firstOrNull()?.id
            renderProfiles()
            renderDashboard()
        }
    }

    private fun renderDashboard() {
        val selected = reptiles.firstOrNull { it.id == selectedReptileId }
        if (selected == null) {
            binding.selectedSpecies.text = "개체를 등록하면 주간 관리가 시작됩니다"
            binding.todayCareText.text = "등록된 개체가 없습니다"
            binding.weekRange.text = "이번 주"
            binding.weightChart.setValues(emptyList())
            binding.weightAxisLabels.setValues(emptyList())
            binding.currentWeight.text = "기록 없음"
            return
        }
        binding.selectedName.text = selected.name
        selected.photoUri?.let { binding.selectedPhoto.setImageURI(android.net.Uri.parse(it)) }
        binding.selectedSpecies.text = listOf(selected.species, selected.morph).filter { it.isNotBlank() }.joinToString(" · ")
        val weekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        binding.weekRange.text = "${weekStart.format(DateTimeFormatter.ofPattern("M월 d일"))} - ${weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("M월 d일"))}"
        binding.weekDays.removeAllViews()
        repeat(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            val daySchedules = schedules.filter { it.reptileId == selected.id && it.scheduledDate == date.toEpochDay() && (it.careType == "충식" || it.careType == "채식") }
            binding.weekDays.addView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = "${dayLabels[index]}\n${if (daySchedules.isEmpty()) "-" else daySchedules.joinToString("\n") { it.careType }}"
                textSize = 11f
                setTextColor(if (date == LocalDate.now()) Color.WHITE else getColor(R.color.text_secondary))
                setBackgroundResource(if (date == LocalDate.now()) R.drawable.bg_week_day_selected else R.drawable.bg_week_day)
            }, android.widget.LinearLayout.LayoutParams(0, dp(64), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        val todaySchedules = schedules.filter { it.reptileId == selected.id && it.scheduledDate == LocalDate.now().toEpochDay() && (it.careType == "충식" || it.careType == "채식") }
        binding.todayCareText.text = if (todaySchedules.isEmpty()) "오늘 예정된 식단이 없습니다" else todaySchedules.joinToString("\n") { schedule -> if (schedule.memo.isNullOrBlank()) schedule.careType else "${schedule.careType} · ${schedule.memo}" }
        loadWeightHistory(selected.id)
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
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
                setOnClickListener { selectedReptileId = reptile.id; renderProfiles(); renderDashboard() }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                background = getDrawable(R.drawable.bg_profile_circle)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
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
            1 -> reptiles.sortedWith(compareBy<Reptile> { if (it.referenceDateType == "해칭일") 0 else 1 }.thenBy { it.referenceDate })
            2 -> reptiles.sortedWith(compareBy<Reptile> { if (it.referenceDateType == "입양일") 0 else 1 }.thenBy { it.referenceDate })
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

    private fun applySystemBarInsets(root: View) {
        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, initialTop + statusBar.top, view.paddingRight, view.paddingBottom)
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
                    val sample = Reptile(0L, name, if (index % 2 == 0) "레오파드 게코" else "비어디 드래곤", if (index % 3 == 0) "하이 옐로우" else "노멀", LocalDate.now().minusMonths((index + 3).toLong()).toEpochDay(), "입양일", null, System.currentTimeMillis())
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
}
