package ai.sakana.aura.gallery.domain.repository

import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.domain.editing.EditParams
import kotlinx.coroutines.flow.Flow

interface EditHistoryRepository {
    fun observeForUri(contentUri: String): Flow<List<EditHistoryEntity>>
    suspend fun getAllForUri(contentUri: String): List<EditHistoryEntity>
    suspend fun savePreset(contentUri: String, name: String, params: EditParams, now: Long): Long
    suspend fun applyPreset(presetId: Long, now: Long): Long
    suspend fun branchPreset(presetId: Long, newName: String, now: Long): Long
    suspend fun deletePreset(preset: EditHistoryEntity): Boolean
    suspend fun reset(contentUri: String, now: Long): Long
    suspend fun recordExportedEdit(contentUri: String, params: EditParams, now: Long): Long
}
