package ai.sakana.aura.gallery.data.repository

import ai.sakana.aura.gallery.data.deduplication.HashStore
import ai.sakana.aura.gallery.data.local.MediaItemDao
import ai.sakana.aura.gallery.data.local.MediaItemEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHashStore @Inject constructor(
    private val dao: MediaItemDao,
) : HashStore {
    override suspend fun availableItems(): List<MediaItemEntity> = dao.getAvailableNotTrashed()

    override suspend fun knownSha256(): Map<String, String> =
        dao.getKnownSha256Hashes().mapNotNull { row -> row.sha256Hash?.let { row.contentUri to it } }.toMap()

    override suspend fun knownPerceptualHashes(): Map<String, Long> =
        dao.getKnownPerceptualHashes().mapNotNull { row -> row.perceptualHash?.let { row.contentUri to it } }.toMap()

    override suspend fun saveSha256(contentUri: String, sha256: String) {
        dao.updateSha256Hash(contentUri, sha256)
    }

    override suspend fun savePerceptualHash(contentUri: String, hash: Long) {
        dao.updatePerceptualHash(contentUri, hash)
    }
}
