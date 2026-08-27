package com.hsm.beardylog.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import kotlinx.coroutines.launch

class ReptileViewModel(private val repository: ReptileRepository) : ViewModel() {
    val reptiles = repository.reptiles
    fun observeById(id: Long) = repository.observeById(id)
    fun insert(reptile: Reptile) = viewModelScope.launch { repository.insert(reptile) }
    fun update(reptile: Reptile) = viewModelScope.launch { repository.update(reptile) }
    fun delete(reptile: Reptile) = viewModelScope.launch { repository.delete(reptile) }
    fun deleteById(id: Long) = viewModelScope.launch { repository.deleteById(id) }
    suspend fun insertAndWait(reptile: Reptile) = repository.insert(reptile)
    suspend fun updateAndWait(reptile: Reptile) = repository.update(reptile)
    suspend fun deleteByIdAndWait(id: Long) = repository.deleteById(id)
}

class ReptileViewModelFactory(private val repository: ReptileRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ReptileViewModel(repository) as T
}
