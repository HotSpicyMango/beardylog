package com.hsm.beardylog.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository

class ReptileViewModel(private val repository: ReptileRepository) : ViewModel() {
    val reptiles = repository.reptiles
    fun observeById(id: Long) = repository.observeById(id)
    suspend fun insertAndWait(reptile: Reptile) = repository.insert(reptile)
    suspend fun updateAndWait(reptile: Reptile) = repository.update(reptile)
}

class ReptileViewModelFactory(private val repository: ReptileRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReptileViewModel(repository) as T
}
