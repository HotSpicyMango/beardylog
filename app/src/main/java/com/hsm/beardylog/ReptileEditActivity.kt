package com.hsm.beardylog

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.databinding.ActivityReptileEditBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ReptileEditActivity : AppBaseActivity() {
    private lateinit var binding: ActivityReptileEditBinding
    private lateinit var viewModel: ReptileViewModel
    private var editing: Reptile? = null
    private var hatchingDate: LocalDate? = null
    private var adoptionDate: LocalDate? = null
    private var photoUri: Uri? = null
    private var initialFormState: FormState? = null
    private val genderOptions: List<String>
        get() = resources.getStringArray(R.array.gender_types).toList()
    private val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    private data class FormState(
        val name: String,
        val species: String,
        val morph: String,
        val gender: String,
        val hatchingDate: LocalDate?,
        val adoptionDate: LocalDate?,
        val photoUri: String?
    )
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { launchCrop(it) }
    }
    private val cropPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(CropActivity.EXTRA_RESULT_URI)?.let { value ->
                photoUri = Uri.parse(value)
                com.bumptech.glide.Glide.with(this)
                    .load(photoUri)
                    .override(dp(112), dp(112))
                    .centerCrop()
                    .into(binding.photoPreview)
                binding.photoPreview.alpha = 1f
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReptileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(AppDatabase.getInstance(applicationContext).reptileDao())))[ReptileViewModel::class.java]
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
        binding.genderInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, genderOptions))
        binding.genderInput.setText("미구분", false)
        binding.photoPreview.setOnClickListener { view ->
            view.clickHaptic()
            pickPhoto.launch("image/*")
        }
        binding.hatchingDateButton.setOnClickListener { view ->
            view.clickHaptic()
            showDatePicker(true)
        }
        binding.adoptionDateButton.setOnClickListener { view ->
            view.clickHaptic()
            showDatePicker(false)
        }
        binding.saveButton.setOnClickListener { view ->
            view.clickHaptic()
            save()
        }
        intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { id ->
            viewModel.observeById(id).observe(this) { reptile -> reptile?.let(::populate) }
        } ?: run {
            updateDateLabels()
            initialFormState = currentFormState()
        }
    }

    private fun populate(reptile: Reptile) {
        if (editing != null) return
        editing = reptile
        binding.toolbar.title = "개체 수정"
        binding.nameInput.setText(reptile.name)
        binding.speciesInput.setText(reptile.species)
        binding.morphInput.setText(reptile.morph)
        binding.genderInput.setText(reptile.gender?.takeIf { it in genderOptions } ?: "미구분", false)
        if (reptile.hatchingDate != null || reptile.adoptionDate != null) {
            hatchingDate = reptile.hatchingDate?.let(LocalDate::ofEpochDay)
            adoptionDate = reptile.adoptionDate?.let(LocalDate::ofEpochDay)
        } else if (reptile.referenceDateType == "입양일") {
            adoptionDate = LocalDate.ofEpochDay(reptile.referenceDate)
        } else {
            hatchingDate = LocalDate.ofEpochDay(reptile.referenceDate)
        }
        photoUri = reptile.photoUri?.let(Uri::parse)
        photoUri?.let {
            com.bumptech.glide.Glide.with(this).load(it).override(dp(112), dp(112)).centerCrop().into(binding.photoPreview)
            binding.photoPreview.alpha = 1f
        }
        updateDateLabels()
        initialFormState = currentFormState()
    }

    private fun showDatePicker(isHatching: Boolean) {
        val current = if (isHatching) hatchingDate else adoptionDate
        val initial = current ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (isHatching) hatchingDate = date else adoptionDate = date
            updateDateLabels()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    private fun launchCrop(uri: Uri) {
        cropPhoto.launch(Intent(this, CropActivity::class.java).putExtra(CropActivity.EXTRA_URI, uri.toString()))
    }

    private fun updateDateLabels() {
        binding.hatchingDateButton.text = hatchingDate?.let { "해칭일: ${it.format(formatter)}" } ?: "해칭일 선택"
        binding.adoptionDateButton.text = adoptionDate?.let { "입양일: ${it.format(formatter)}" } ?: "입양일 선택"
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
        name = binding.nameInput.text?.toString()?.trim().orEmpty(),
        species = binding.speciesInput.text?.toString()?.trim().orEmpty(),
        morph = binding.morphInput.text?.toString()?.trim().orEmpty(),
        gender = binding.genderInput.text?.toString().takeIf { it in genderOptions } ?: "미구분",
        hatchingDate = hatchingDate,
        adoptionDate = adoptionDate,
        photoUri = photoUri?.toString()
    )

    private fun save() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) {
            binding.saveButton.rejectHaptic()
            binding.nameInput.error = "이름을 입력해 주세요"
            return
        }
        val primaryDate = hatchingDate ?: adoptionDate
        if (primaryDate == null) {
            binding.saveButton.rejectHaptic()
            showBriefToast("해칭일 또는 입양일을 하나 이상 입력해 주세요")
            return
        }
        val primaryType = if (hatchingDate != null) "해칭일" else "입양일"
        val gender = binding.genderInput.text.toString().takeIf { it in genderOptions } ?: "미구분"
        val reptile = Reptile(editing?.id ?: 0, name, binding.speciesInput.text.toString().trim(), binding.morphInput.text.toString().trim(), gender, primaryDate.toEpochDay(), primaryType, photoUri?.toString(), editing?.createdAt ?: System.currentTimeMillis()).apply {
            this.hatchingDate = this@ReptileEditActivity.hatchingDate?.toEpochDay()
            this.adoptionDate = this@ReptileEditActivity.adoptionDate?.toEpochDay()
        }
        binding.saveButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                if (editing == null) {
                    viewModel.insertAndWait(reptile)
                } else {
                    viewModel.updateAndWait(reptile)
                }
            }.onSuccess {
                initialFormState = currentFormState()
                binding.saveButton.confirmHaptic()
                showBriefToast("저장했습니다")
                finish()
            }.onFailure {
                binding.saveButton.isEnabled = true
                binding.saveButton.rejectHaptic()
                showBriefToast("저장하지 못했습니다")
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_ID = "reptile_id" }
}
