package com.hsm.beardylog

import android.app.DatePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.databinding.ActivityReptileEditBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ReptileEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReptileEditBinding
    private lateinit var viewModel: ReptileViewModel
    private var editing: Reptile? = null
    private var selectedDate = LocalDate.now()
    private var photoUri: Uri? = null
    private val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photoUri = it; binding.photoPreview.setImageURI(it); binding.photoPreview.alpha = 1f }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityReptileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(AppDatabase.getInstance(applicationContext).reptileDao())))[ReptileViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.photoPreview.setOnClickListener { pickPhoto.launch("image/*") }
        binding.dateButton.setOnClickListener { showDatePicker() }
        binding.dateType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateDateLabel()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.saveButton.setOnClickListener { save() }
        intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let { id ->
            viewModel.observeById(id).observe(this) { reptile -> reptile?.let(::populate) }
        } ?: updateDateLabel()
    }

    private fun populate(reptile: Reptile) {
        if (editing != null) return
        editing = reptile
        binding.toolbar.title = "개체 수정"
        binding.nameInput.setText(reptile.name)
        binding.speciesInput.setText(reptile.species)
        binding.morphInput.setText(reptile.morph)
        selectedDate = LocalDate.ofEpochDay(reptile.referenceDate)
        binding.dateType.setSelection(if (reptile.referenceDateType == "입양일") 1 else 0)
        photoUri = reptile.photoUri?.let(Uri::parse)
        photoUri?.let { binding.photoPreview.setImageURI(it); binding.photoPreview.alpha = 1f }
        updateDateLabel()
    }

    private fun showDatePicker() = DatePickerDialog(this, { _, year, month, day ->
        selectedDate = LocalDate.of(year, month + 1, day)
        updateDateLabel()
    }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()

    private fun updateDateLabel() { binding.dateButton.text = "${binding.dateType.selectedItem ?: "해칭일"}: ${selectedDate.format(formatter)}" }

    private fun applySystemBarInsets(root: View) {
        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, initialTop + statusBar.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun save() {
        val name = binding.nameInput.text.toString().trim()
        if (name.isBlank()) { binding.nameInput.error = "이름을 입력해 주세요"; return }
        val reptile = Reptile(editing?.id ?: 0, name, binding.speciesInput.text.toString().trim(), binding.morphInput.text.toString().trim(), selectedDate.toEpochDay(), binding.dateType.selectedItem.toString(), photoUri?.toString(), editing?.createdAt ?: System.currentTimeMillis())
        if (editing == null) viewModel.insert(reptile) else viewModel.update(reptile)
        Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object { const val EXTRA_ID = "reptile_id" }
}
