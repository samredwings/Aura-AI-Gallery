package ai.sakana.aura.gallery.data.repository

import ai.sakana.aura.gallery.data.local.EditHistoryDao
import ai.sakana.aura.gallery.data.local.EditHistoryEntity
import ai.sakana.aura.gallery.domain.editing.EditParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomEditHistoryRepositoryTest {
    private val dao = FakeEditHistoryDao()
    private val repository = RoomEditHistoryRepository(dao)

    @Test
    fun savingPresetMakesItTheOnlyActiveEntryForThatPhoto() = runBlocking {
        val firstId = repository.savePreset(PHOTO, "Warm", EditParams(warmth = 0.25f), now = 10L)
        val secondId = repository.savePreset(PHOTO, "Cool", EditParams(warmth = -0.2f), now = 20L)

        val history = repository.getAllForUri(PHOTO)
        assertEquals(2, history.size)
        assertFalse(history.single { it.id == firstId }.isActive)
        assertTrue(history.single { it.id == secondId }.isActive)
    }

    @Test
    fun reapplyingPresetAddsANewActiveHistoryEntryAndKeepsTheSource() = runBlocking {
        val sourceId = repository.savePreset(PHOTO, "Warm", EditParams(brightness = 0.3f), now = 10L)
        val appliedId = repository.applyPreset(sourceId, now = 20L)

        val history = repository.getAllForUri(PHOTO)
        assertEquals(2, history.size)
        assertNotEquals(sourceId, appliedId)
        assertFalse(history.single { it.id == sourceId }.isActive)
        assertTrue(history.single { it.id == appliedId }.isActive)
        assertEquals(0.3f, history.single { it.id == appliedId }.brightness, 0f)
    }

    @Test
    fun branchingPresetCreatesInactiveCopyWithoutChangingActiveEntry() = runBlocking {
        val activeId = repository.savePreset(PHOTO, "Warm", EditParams(warmth = 0.2f), now = 10L)
        val branchId = repository.branchPreset(activeId, "Cooler", now = 20L)

        val history = repository.getAllForUri(PHOTO)
        assertEquals("Cooler", history.single { it.id == branchId }.name)
        assertFalse(history.single { it.id == branchId }.isActive)
        assertTrue(history.single { it.id == activeId }.isActive)
    }

    @Test
    fun activePresetCannotBeDeletedButInactivePresetCan() = runBlocking {
        val firstId = repository.savePreset(PHOTO, "First", EditParams(), now = 10L)
        val secondId = repository.savePreset(PHOTO, "Second", EditParams(brightness = 0.1f), now = 20L)
        val first = repository.getAllForUri(PHOTO).single { it.id == firstId }
        val second = repository.getAllForUri(PHOTO).single { it.id == secondId }

        assertFalse(repository.deletePreset(second))
        assertTrue(repository.deletePreset(first))
        assertEquals(listOf(secondId), repository.getAllForUri(PHOTO).map { it.id })
    }

    @Test
    fun resetStoresIdentityAsTheNewActiveEntry() = runBlocking {
        repository.savePreset(PHOTO, "Warm", EditParams(warmth = 0.4f), now = 10L)
        val resetId = repository.reset(PHOTO, now = 20L)

        val active = repository.getAllForUri(PHOTO).single { it.id == resetId }
        assertEquals("Original", active.name)
        assertTrue(active.isActive)
        assertTrue(EditParams(active.brightness, active.contrast, active.saturation, active.warmth).isIdentity)
    }

    @Test
    fun successfulExportCanBeRecordedWithASequentialHistoryName() = runBlocking {
        repository.recordExportedEdit(PHOTO, EditParams(contrast = 0.15f), now = 10L)
        repository.recordExportedEdit(PHOTO, EditParams(contrast = 0.25f), now = 20L)

        val history = repository.getAllForUri(PHOTO).sortedBy { it.appliedAt }
        assertEquals(listOf("Edit 1", "Edit 2"), history.map { it.name })
        assertTrue(history.last().isActive)
        assertFalse(history.first().isActive)
    }

    private class FakeEditHistoryDao : EditHistoryDao() {
        private val rows = mutableListOf<EditHistoryEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: EditHistoryEntity): Long {
            val saved = entity.copy(id = nextId++)
            rows += saved
            return saved.id
        }

        override suspend fun update(entity: EditHistoryEntity): Int {
            val index = rows.indexOfFirst { it.id == entity.id }
            if (index < 0) return 0
            rows[index] = entity
            return 1
        }

        override suspend fun delete(entity: EditHistoryEntity): Int {
            val removed = rows.removeAll { it.id == entity.id }
            return if (removed) 1 else 0
        }

        override suspend fun getById(id: Long): EditHistoryEntity? = rows.firstOrNull { it.id == id }

        override suspend fun getAllForUri(contentUri: String): List<EditHistoryEntity> =
            rows.filter { it.contentUri == contentUri }.sortedWith(compareByDescending<EditHistoryEntity> { it.appliedAt }.thenByDescending { it.id })

        override fun observeAllForUri(contentUri: String): Flow<List<EditHistoryEntity>> = flow {
            emit(getAllForUri(contentUri))
        }

        override suspend fun getActiveForUri(contentUri: String): EditHistoryEntity? =
            rows.firstOrNull { it.contentUri == contentUri && it.isActive }

        override suspend fun clearActive(contentUri: String) {
            rows.indices.forEach { index ->
                if (rows[index].contentUri == contentUri) rows[index] = rows[index].copy(isActive = false)
            }
        }

        override suspend fun markActive(id: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(isActive = true)
        }
    }

    private companion object {
        const val PHOTO = "content://media/external/images/media/42"
    }
}
