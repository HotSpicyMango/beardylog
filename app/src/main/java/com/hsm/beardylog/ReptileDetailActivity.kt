package com.hsm.beardylog

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.databinding.ActivityReptileDetailBinding
import com.hsm.beardylog.databinding.DialogMemorialBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

class ReptileDetailActivity : AppBaseActivity() {
    private lateinit var binding: ActivityReptileDetailBinding
    private lateinit var viewModel: ReptileViewModel
    private var currentId = -1L
    private var currentReptile: Reptile? = null
    private val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReptileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        currentId = intent.getLongExtra(EXTRA_ID, -1L)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(AppDatabase.getInstance(applicationContext).reptileDao())))[ReptileViewModel::class.java]
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { icon ->
            DrawableCompat.setTint(icon, appColor(R.color.text_primary))
        }
        binding.toolbar.setNavigationOnClickListener { view ->
            view.clickHaptic()
            finish()
        }
        binding.editButton.setOnClickListener { view ->
            view.clickHaptic()
            startActivity(Intent(this, ReptileEditActivity::class.java).putExtra(ReptileEditActivity.EXTRA_ID, currentId))
        }
        binding.careScheduleButton.setOnClickListener { view ->
            view.clickHaptic()
            startActivity(Intent(this, CareScheduleActivity::class.java).putExtra(CareScheduleActivity.EXTRA_REPTILE_ID, currentId))
        }
        binding.deleteButton.setOnClickListener { view ->
            view.clickHaptic()
            confirmDelete()
        }
        binding.memorialButton.setOnClickListener { view ->
            view.clickHaptic()
            showMemorialDialog()
        }
        viewModel.observeById(currentId).observe(this) { reptile ->
            if (reptile == null) { finish(); return@observe }
            currentReptile = reptile
            binding.name.text = reptile.name
            binding.species.text = reptile.species.ifBlank { "종 미입력" }
            binding.morph.text = reptile.morph.ifBlank { "모프 미입력" }
            binding.gender.text = "성별: ${reptile.gender?.ifBlank { "미구분" } ?: "미구분"}"
            val ageDate = reptile.hatchingDate ?: reptile.adoptionDate ?: reptile.referenceDate
            val date = LocalDate.ofEpochDay(ageDate)
            val age = Period.between(date, LocalDate.now())
            binding.age.text = "${age.years}년 ${age.months}개월"
            val hatchingText = reptile.hatchingDate?.let { "해칭일: ${LocalDate.ofEpochDay(it).format(formatter)}" }
            val adoptionText = reptile.adoptionDate?.let { "입양일: ${LocalDate.ofEpochDay(it).format(formatter)}" }
            binding.referenceDate.text = listOfNotNull(hatchingText, adoptionText).ifEmpty { listOf("${reptile.referenceDateType}: ${date.format(formatter)}") }.joinToString("\n")
            reptile.photoUri?.let {
                com.bumptech.glide.Glide.with(this)
                    .load(android.net.Uri.parse(it))
                    .override(dp(128), dp(128))
                    .centerCrop()
                    .into(binding.photo)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMemorialDialog() {
        val reptile = currentReptile ?: return
        val sheetBinding = DialogMemorialBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        var selectedDate = LocalDate.now()
        sheetBinding.dateInput.setText(selectedDate.format(formatter))
        sheetBinding.dateInput.setOnClickListener { view ->
            view.clickHaptic()
            DatePickerDialog(this, { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                sheetBinding.dateInput.setText(selectedDate.format(formatter))
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        sheetBinding.cancelButton.setOnClickListener { view ->
            view.clickHaptic()
            dialog.dismiss()
        }
        sheetBinding.saveButton.setOnClickListener { view ->
            view.clickHaptic()
            val note = sheetBinding.noteInput.text?.toString()?.trim().orEmpty()
            MaterialAlertDialogBuilder(this)
                .setTitle("정말 바꿀까요?")
                .setMessage("${reptile.name}을(를) 추억프로필로 바꾸면 식단·캘린더 기록이 삭제되고 되돌릴 수 없습니다. 계속할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("바꾸기") { _, _ ->
                    sheetBinding.saveButton.isEnabled = false
                    val updated = Reptile(
                        reptile.id, reptile.name, reptile.species, reptile.morph, reptile.gender,
                        reptile.referenceDate, reptile.referenceDateType, reptile.photoUri, reptile.createdAt
                    ).apply {
                        hatchingDate = reptile.hatchingDate
                        adoptionDate = reptile.adoptionDate
                        deathDate = selectedDate.toEpochDay()
                        memorialNote = note.ifBlank { null }
                    }
                    lifecycleScope.launch {
                        runCatching {
                            viewModel.updateAndWait(updated)
                            withContext(Dispatchers.IO) {
                                AppDatabase.getInstance(applicationContext).clearActivityRecordsForReptile(reptile.id)
                            }
                        }.onSuccess {
                            dialog.dismiss()
                            showBriefToast("추억프로필로 바꿨습니다.")
                            finish()
                        }.onFailure {
                            sheetBinding.saveButton.isEnabled = true
                            showBriefToast("처리하지 못했습니다")
                        }
                    }
                }
                .show()
        }
        sheetBinding.root.dismissKeyboardOnOutsideTouch(sheetBinding.noteInput)
        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun confirmDelete() = MaterialAlertDialogBuilder(this).setTitle("개체 삭제")
        .setMessage("이 개체와 연결된 기록을 삭제할까요?").setNegativeButton("취소", null)
        .setPositiveButton("삭제") { _, _ ->
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(applicationContext).deleteReptileFully(currentId)
                    }
                }.onSuccess {
                    showBriefToast("삭제했습니다")
                    finish()
                }.onFailure {
                    showBriefToast("삭제하지 못했습니다")
                }
            }
        }.show()

    companion object { const val EXTRA_ID = "reptile_id" }
}
