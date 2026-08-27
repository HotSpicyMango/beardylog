package com.hsm.beardylog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hsm.beardylog.data.WeightRecord
import com.hsm.beardylog.databinding.ItemWeightRecordBinding
import com.hsm.beardylog.ui.setWeightNumberText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** 무게 기록 목록. 예전에는 데이터가 바뀔 때마다 [WeightHistoryActivity]가 카드 뷰 전체를
 *  removeAllViews() 후 처음부터 다시 만들었는데, 기록이 몇 년치 쌓이면 기록 하나 추가/삭제할 때마다
 *  이미 화면에 있던 나머지 카드까지 전부 다시 만드는 셈이라 갈수록 느려진다.
 *  RecyclerView + DiffUtil로 바꿔서 실제로 바뀐 항목만 다시 그리도록 한다. */
class WeightRecordAdapter(
    private val dateFormatter: DateTimeFormatter,
    private val formatGrams: (Float) -> String,
    private val onRowClick: (WeightRecord) -> Unit,
    private val onDeleteClick: (WeightRecord) -> Unit,
) : ListAdapter<WeightRecord, WeightRecordAdapter.RowViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemWeightRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        // 목록은 최신순(내림차순)으로 표시되므로, 시간상 "이전 기록"은 한 칸 뒤(더 오래된 쪽)에 있다.
        val record = getItem(position)
        val previous = if (position + 1 < itemCount) getItem(position + 1) else null
        holder.bind(record, previous)
    }

    inner class RowViewHolder(private val binding: ItemWeightRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: WeightRecord, previous: WeightRecord?) {
            binding.root.contentDescription =
                "${LocalDate.ofEpochDay(record.recordedAt).format(dateFormatter)}, ${formatGrams(record.grams)}그램, 눌러서 수정"
            binding.root.setOnClickListener { view ->
                view.clickHaptic()
                onRowClick(record)
            }
            binding.rowDate.text = LocalDate.ofEpochDay(record.recordedAt).format(dateFormatter)
            binding.rowChange.setWeightNumberText(
                previous?.let {
                    val difference = record.grams - it.grams
                    when {
                        difference > 0f -> "이전보다 +${formatGrams(difference)}g"
                        difference < 0f -> "이전보다 -${formatGrams(abs(difference))}g"
                        else -> "변화 없음"
                    }
                } ?: "첫 기록"
            )
            binding.rowWeight.setWeightNumberText("${formatGrams(record.grams)}g")
            binding.rowDeleteButton.setOnClickListener { view ->
                view.clickHaptic()
                onDeleteClick(record)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WeightRecord>() {
            override fun areItemsTheSame(oldItem: WeightRecord, newItem: WeightRecord) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: WeightRecord, newItem: WeightRecord) =
                oldItem.recordedAt == newItem.recordedAt && oldItem.grams == newItem.grams
        }
    }
}
