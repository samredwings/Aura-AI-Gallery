package ai.sakana.aura.gallery.data.repository

import ai.sakana.aura.gallery.data.local.EditHistoryDao
import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.domain.editing.EditHistoryRules
import ai.sakana.aura.gallery.domain.editing.EditParams
import ai.sakana.aura.gallery.domain.repository.EditHistoryRepository
import javax.inject.Inject

class RoomEditHistoryRepository @Inject constructor(
    private val dao: EditHistoryDao,
) : EditHistoryRepository {
    override fun observeForUri(contentUri: String) = dao.observeAllForUri(contentUri)

    override suspend fun getAllForUri(contentUri: String) = dao.getAllForUri(contentUri)

    override suspend fun savePreset(contentUri: String, name: String, params: EditParams, now: Long): Long =
        dao.insertActive(EditHistoryRules.newPreset(contentUri, name, params, now))

    override suspend fun applyPreset(presetId: Long, now: Long): Long {
        val source = requireNotNull(dao.getById(presetId)) { "The selected edit preset no longer exists." }
        return dao.insertActive(EditHistoryRules.reapply(source, now))
    }

    override suspend fun branchPreset(presetId: Long, newName: String, now: Long): Long {
        val source = requireNotNull(dao.getById(presetId)) { "The selected edit preset no longer exists." }
        return dao.insert(EditHistoryRules.branch(source, newName, now))
    }

    override suspend fun deletePreset(preset: EditHistoryEntity): Boolean {
        val current = dao.getById(preset.id) ?: return false
        if (EditHistoryRules.deleteBlockedReason(current) != null) return false
        return dao.delete(current) > 0
    }

    override suspend fun reset(contentUri: String, now: Long): Long =
        dao.insertActive(EditHistoryRules.newPreset(contentUri, "Original", EditParams(), now))

    override suspend fun recordExportedEdit(contentUri: String, params: EditParams, now: Long): Long {
        val name = EditHistoryRules.defaultName(dao.getAllForUri(contentUri).size)
        return savePreset(contentUri, name, params, now)
    }
}
