package com.hsm.beardylog.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.databinding.ItemReptileBinding
import java.time.LocalDate
import java.time.Period

class ReptileAdapter(private val onClick: (Reptile) -> Unit) : ListAdapter<Reptile, ReptileAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemReptileBinding.inflate(LayoutInflater.from(parent.context), parent, false), onClick
    )
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemReptileBinding, private val onClick: (Reptile) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Reptile) {
            binding.name.text = item.name
            binding.species.text = listOf(item.species, item.morph).filter { it.isNotBlank() }.joinToString(" · ")
            val ageDate = item.hatchingDate ?: item.adoptionDate ?: item.referenceDate
            val age = Period.between(LocalDate.ofEpochDay(ageDate), LocalDate.now())
            binding.age.text = "${age.years}년 ${age.months}개월"
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Reptile>() {
            override fun areItemsTheSame(old: Reptile, new: Reptile) = old.id == new.id
            override fun areContentsTheSame(old: Reptile, new: Reptile) = old == new
        }
    }
}
