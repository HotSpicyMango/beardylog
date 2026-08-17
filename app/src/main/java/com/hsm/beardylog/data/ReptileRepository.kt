package com.hsm.beardylog.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReptileRepository(private val dao: ReptileDao) {
    val reptiles = dao.observeAll()
    fun observeById(id: Long) = dao.observeById(id)
    suspend fun insert(reptile: Reptile) = withContext(Dispatchers.IO) { dao.insert(reptile) }
    suspend fun update(reptile: Reptile) = withContext(Dispatchers.IO) { dao.update(reptile) }
    suspend fun delete(reptile: Reptile) = withContext(Dispatchers.IO) { dao.delete(reptile) }
}
