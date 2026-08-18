package com.hsm.beardylog

import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.databinding.ActivityReptileDetailBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

class ReptileDetailActivity : AppBaseActivity() {
    private lateinit var binding: ActivityReptileDetailBinding
    private lateinit var viewModel: ReptileViewModel
    private var currentId = -1L
    private val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReptileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        currentId = intent.getLongExtra(EXTRA_ID, -1L)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(AppDatabase.getInstance(applicationContext).reptileDao())))[ReptileViewModel::class.java]
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { icon ->
            DrawableCompat.setTint(icon, ContextCompat.getColor(this, R.color.text_primary))
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.editButton.setOnClickListener { startActivity(Intent(this, ReptileEditActivity::class.java).putExtra(ReptileEditActivity.EXTRA_ID, currentId)) }
        binding.careScheduleButton.setOnClickListener { startActivity(Intent(this, CareScheduleActivity::class.java).putExtra(CareScheduleActivity.EXTRA_REPTILE_ID, currentId)) }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewModel.observeById(currentId).observe(this) { reptile ->
            if (reptile == null) { finish(); return@observe }
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
            reptile.photoUri?.let { binding.photo.setImageURI(android.net.Uri.parse(it)) }
        }
    }

    private fun confirmDelete() = MaterialAlertDialogBuilder(this).setTitle("개체 삭제")
        .setMessage("이 개체와 연결된 기록을 삭제할까요?").setNegativeButton("취소", null)
        .setPositiveButton("삭제") { _, _ ->
            lifecycleScope.launch {
                runCatching {
                    viewModel.deleteByIdAndWait(currentId)
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
