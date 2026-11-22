package ai.sakana.aura.gallery.data.deduplication

import ai.sakana.aura.gallery.data.local.MediaItemEntity

/** Persistence boundary for exact and perceptual hashes used by background deduplication. */
interface HashStore {
    suspend fun availableItems(): List<MediaItemEntity>
    suspend fun knownSha256(): Map<String, String>
    suspend fun knownPerceptualHashes(): Map<String, Long>
    suspend fun saveSha256(contentUri: String, sha256: String)
    suspend fun savePerceptualHash(contentUri: String, hash: Long)
}
