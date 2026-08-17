package com.hsm.beardylog

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.databinding.ActivityReptileDetailBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter

class ReptileDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReptileDetailBinding
    private lateinit var viewModel: ReptileViewModel
    private var currentId = -1L
    private val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityReptileDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        currentId = intent.getLongExtra(EXTRA_ID, -1L)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(AppDatabase.getInstance(applicationContext).reptileDao())))[ReptileViewModel::class.java]
        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.navigationIcon?.let { icon ->
            DrawableCompat.setTint(icon, ContextCompat.getColor(this, R.color.text_primary))
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.editButton.setOnClickListener { startActivity(Intent(this, ReptileEditActivity::class.java).putExtra(ReptileEditActivity.EXTRA_ID, currentId)) }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        viewModel.observeById(currentId).observe(this) { reptile ->
            if (reptile == null) { finish(); return@observe }
            binding.name.text = reptile.name
            binding.species.text = reptile.species.ifBlank { "종 미입력" }
            binding.morph.text = reptile.morph.ifBlank { "모프 미입력" }
            val date = LocalDate.ofEpochDay(reptile.referenceDate)
            val age = Period.between(date, LocalDate.now())
            binding.age.text = "${age.years}년 ${age.months}개월"
            binding.referenceDate.text = "${reptile.referenceDateType}: ${date.format(formatter)}"
            reptile.photoUri?.let { binding.photo.setImageURI(android.net.Uri.parse(it)) }
        }
    }

    private fun applySystemBarInsets(root: View) {
        val initialTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, initialTop + statusBar.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun confirmDelete() = AlertDialog.Builder(this).setTitle("개체 삭제")
        .setMessage("이 개체와 연결된 기록을 삭제할까요?").setNegativeButton("취소", null)
        .setPositiveButton("삭제") { _, _ -> viewModel.observeById(currentId).value?.let(viewModel::delete); finish() }.show()

    companion object { const val EXTRA_ID = "reptile_id" }
}
