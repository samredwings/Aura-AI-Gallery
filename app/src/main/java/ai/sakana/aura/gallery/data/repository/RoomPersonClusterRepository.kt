package ai.sakana.aura.gallery.data.repository

import androidx.room.withTransaction
import ai.sakana.aura.gallery.data.local.AuraGalleryDatabase
import ai.sakana.aura.gallery.data.local.DetectedFaceEntity
import ai.sakana.aura.gallery.data.local.MediaRecognitionDao
import ai.sakana.aura.gallery.data.local.PersonClusterEntity
import ai.sakana.aura.gallery.data.local.RecognitionProgressEntity
import ai.sakana.aura.gallery.data.recognition.SceneVectorCodec
import ai.sakana.aura.gallery.data.recognition.SFaceFaceEmbedder
import ai.sakana.aura.gallery.domain.recognition.DetectedFace
import ai.sakana.aura.gallery.domain.recognition.PersonClusterCandidate
import ai.sakana.aura.gallery.domain.repository.PersonClusterRepository
import ai.sakana.aura.gallery.domain.repository.RecognitionProgressState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt

@Singleton
class RoomPersonClusterRepository @Inject constructor(
    private val database: AuraGalleryDatabase,
    private val dao: MediaRecognitionDao,
) : PersonClusterRepository {

    override fun observePersonClusters(): Flow<List<PersonClusterCandidate>> =
        dao.observeSavedPersonClusters().map { rows ->
            rows.groupBy { it.personId }.values.map { group ->
                val first = group.first()
                val faceIds = group.mapNotNull { it.faceId }.distinct().sorted()
                val mediaUris = group.mapNotNull { it.contentUri }.distinct().sorted()
                val vectors = group.mapNotNull { row ->
                    if (row.modelVersion != SFaceFaceEmbedder.MODEL_VERSION) return@mapNotNull null
                    val bytes = row.vectorBytes ?: return@mapNotNull null
                    val dimensions = row.dimensions ?: return@mapNotNull null
                    runCatching { SceneVectorCodec.decode(bytes, dimensions) }.getOrNull()
                }
                val centroid = buildCentroid(vectors)
                PersonClusterCandidate(
                    clusterId = first.personId,
                    name = first.name,
                    representativeFaceId = first.representativeFaceId,
                    faceIds = faceIds,
                    mediaUris = mediaUris,
                    averageSimilarity = first.confidence,
                    confidence = first.confidence.coerceIn(0f, 1f),
                    centroid = centroid,
                )
            }
        }

    override suspend fun savePerson(candidate: PersonClusterCandidate, name: String): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Person name cannot be blank." }
        val faceIds = candidate.faceIds.distinct()
        require(faceIds.isNotEmpty()) { "A person needs at least one detected face." }

        return database.withTransaction {
            val representative = candidate.representativeFaceId.takeIf { it in faceIds } ?: faceIds.first()
            val id = dao.insertPersonCluster(
                PersonClusterEntity(
                    name = normalizedName,
                    representativeFaceId = representative,
                    confidence = candidate.confidence.coerceIn(0f, 1f),
                    memberCount = 1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            val assigned = dao.assignUnassignedFaces(id, faceIds)
            if (assigned == 0) {
                dao.deletePersonCluster(id)
                throw IllegalStateException("These face samples are already assigned to saved people.")
            }
            dao.refreshPersonClusterStats(id)
            id
        }
    }

    override suspend fun getPersonFaces(personId: Long): List<DetectedFace> =
        dao.getFacesForPerson(personId).map(DetectedFaceEntity::toDomain)

    override suspend fun renamePerson(personId: Long, newName: String) {
        val name = newName.trim()
        require(name.isNotEmpty()) { "Person name cannot be blank." }
        check(dao.renamePersonCluster(personId, name) > 0) { "Saved person was not found." }
    }

    override suspend fun mergePersons(sourcePersonId: Long, targetPersonId: Long) {
        require(sourcePersonId != targetPersonId) { "Choose two different people to merge." }
        database.withTransaction {
            check(dao.getPersonCluster(sourcePersonId) != null) { "Source person was not found." }
            check(dao.getPersonCluster(targetPersonId) != null) { "Target person was not found." }
            dao.movePersonFaces(sourcePersonId, targetPersonId)
            dao.refreshPersonClusterStats(targetPersonId)
            dao.deletePersonCluster(sourcePersonId)
        }
    }

    override suspend fun splitPerson(personId: Long, faceIdToDetach: Long) {
        database.withTransaction {
            val source = dao.getPersonCluster(personId)
                ?: throw IllegalArgumentException("Saved person was not found.")
            val newPersonId = dao.insertPersonCluster(
                PersonClusterEntity(
                    name = "Person",
                    representativeFaceId = faceIdToDetach,
                    confidence = source.confidence,
                    memberCount = 1,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            val moved = dao.moveFaceToPerson(personId, newPersonId, faceIdToDetach)
            if (moved == 0) {
                dao.deletePersonCluster(newPersonId)
                throw IllegalArgumentException("That face is not a member of this person.")
            }
            dao.refreshPersonClusterStats(personId)
            dao.refreshPersonClusterStats(newPersonId)
            if (dao.getFacesForPerson(personId).isEmpty()) dao.deletePersonCluster(personId)
        }
    }

    override suspend fun deletePerson(personId: Long) {
        database.withTransaction {
            check(dao.getPersonCluster(personId) != null) { "Saved person was not found." }
            dao.clearPersonFaces(personId)
            dao.deletePersonCluster(personId)
        }
    }

    override suspend fun hasFaceEmbedding(detectedFaceId: Long, modelVersion: String): Boolean =
        dao.hasFaceEmbedding(detectedFaceId, modelVersion)

    override suspend fun saveRecognitionProgress(state: RecognitionProgressState) {
        dao.upsertRecognitionProgress(
            RecognitionProgressEntity(
                id = 1,
                currentItemIndex = state.currentItemIndex.coerceAtLeast(0),
                totalItems = state.totalItems.coerceAtLeast(0),
                processedCount = state.processedCount.coerceAtLeast(0),
                lastRunEpochMillis = state.lastRunEpochMillis,
                isPaused = state.isPaused,
            ),
        )
    }

    override suspend fun getRecognitionProgress(): RecognitionProgressState? =
        dao.getRecognitionProgress()?.let { row ->
            RecognitionProgressState(
                currentItemIndex = row.currentItemIndex,
                totalItems = row.totalItems,
                processedCount = row.processedCount,
                lastRunEpochMillis = row.lastRunEpochMillis,
                isPaused = row.isPaused,
            )
        }

    private fun buildCentroid(vectors: List<FloatArray>): FloatArray {
        val dimensions = vectors.firstOrNull()?.size ?: return FloatArray(0)
        val compatible = vectors.filter { it.size == dimensions }
        if (compatible.isEmpty()) return FloatArray(0)
        val centroid = FloatArray(dimensions)
        compatible.forEach { vector ->
            vector.forEachIndexed { index, value -> centroid[index] += value }
        }
        val norm = sqrt(centroid.sumOf { it.toDouble() * it.toDouble() })
        if (norm <= 1e-8) return FloatArray(0)
        centroid.indices.forEach { centroid[it] = (centroid[it] / norm).toFloat() }
        return centroid
    }
}

private fun DetectedFaceEntity.toDomain() = DetectedFace(
    contentUri = contentUri,
    frameTimeMillis = frameTimeMillis,
    faceIndex = faceIndex,
    leftNormalized = leftNormalized,
    topNormalized = topNormalized,
    rightNormalized = rightNormalized,
    bottomNormalized = bottomNormalized,
    yawDegrees = yawDegrees,
    rollDegrees = rollDegrees,
)
