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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReptileEditActivity : AppBaseActivity() {
    private lateinit var binding: ActivityReptileEditBinding
    private lateinit var viewModel: ReptileViewModel
    private var editing: Reptile? = null
    private var hatchingDate: LocalDate? = null
    private var adoptionDate: LocalDate? = null
    private var photoUri: Uri? = null
    private var initialFormState: FormState? = null
    /** 화면이 다시 만들어진 경우, DB에서 다시 읽은 값으로 사용자가 고르던 날짜·사진을 덮어쓰면 안 된다. */
    private var restoredFromState = false
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
                showPhotoPreview()
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
        savedInstanceState?.let { saved ->
            restoredFromState = true
            hatchingDate = saved.getLong(KEY_HATCHING_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
            adoptionDate = saved.getLong(KEY_ADOPTION_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
            photoUri = saved.getString(KEY_PHOTO_URI)?.let(Uri::parse)
            updateDateLabels()
            showPhotoPreview()
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
        // 복원된 화면이면 폼에는 사용자가 작업하던 값이 이미 들어 있다. editing만 채우고 빠진다.
        if (restoredFromState) {
            initialFormState = currentFormState()
            return
        }
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
        showPhotoPreview()
        updateDateLabels()
        initialFormState = currentFormState()
    }

    private fun showPhotoPreview() {
        val uri = photoUri ?: return
        com.bumptech.glide.Glide.with(this)
            .load(uri)
            .override(dp(112), dp(112))
            .centerCrop()
            .into(binding.photoPreview)
        binding.photoPreview.alpha = 1f
    }

    /** 이름/종/모프 같은 입력 필드는 뷰가 알아서 복원하지만, 날짜와 사진은 이 화면이 들고 있어서 직접 저장한다. */
    override fun onSaveInstanceState(outState: Bundle) {
        hatchingDate?.let { outState.putLong(KEY_HATCHING_DATE, it.toEpochDay()) }
        adoptionDate?.let { outState.putLong(KEY_ADOPTION_DATE, it.toEpochDay()) }
        photoUri?.let { outState.putString(KEY_PHOTO_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun showDatePicker(isHatching: Boolean) {
        val current = if (isHatching) hatchingDate else adoptionDate
        val initial = current ?: LocalDate.now()
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (isHatching) hatchingDate = date else adoptionDate = date
            updateDateLabels()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            // 미래 해칭일·입양일은 나이를 음수로 만들고 무게 기록의 하한도 무력화한다.
            // 다만 제한 도입 전에 미래로 저장된 값을 수정하러 들어오면 초기값이 상한을 넘어
            // DatePicker가 깨지므로, 그때만 상한을 그 날짜까지 열어 둔다.
            datePicker.maxDate = maxOf(
                System.currentTimeMillis(),
                initial.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
        }.show()
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
            // @Update는 행 전체를 덮어쓴다. 여기서 안 옮기면 추모 프로필을 수정하는 순간
            // 추억공간에서 사라지고 남긴 글도 지워진다.
            this.deathDate = editing?.deathDate
            this.memorialNote = editing?.memorialNote
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

    companion object {
        const val EXTRA_ID = "reptile_id"
        private const val KEY_HATCHING_DATE = "hatching_date"
        private const val KEY_ADOPTION_DATE = "adoption_date"
        private const val KEY_PHOTO_URI = "photo_uri"
    }
}
