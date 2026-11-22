package ai.sakana.aura.gallery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaRecognitionDao {
    @Query("SELECT * FROM scene_embeddings ORDER BY contentUri")
    fun observeSceneEmbeddings(): Flow<List<SceneEmbeddingEntity>>

    @Query("SELECT * FROM scene_embeddings")
    suspend fun getSceneEmbeddings(): List<SceneEmbeddingEntity>

    @Query("SELECT * FROM scene_embeddings WHERE contentUri = :contentUri LIMIT 1")
    suspend fun getSceneEmbedding(contentUri: String): SceneEmbeddingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSceneEmbedding(embedding: SceneEmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSceneEmbeddings(embeddings: List<SceneEmbeddingEntity>)

    @Query("SELECT * FROM detected_faces ORDER BY contentUri, frameTimeMillis, faceIndex")
    fun observeDetectedFaces(): Flow<List<DetectedFaceEntity>>

    @Query("SELECT COUNT(*) FROM detected_faces")
    fun observeDetectedFaceCount(): Flow<Int>

    @Query("DELETE FROM detected_faces WHERE contentUri = :contentUri")
    suspend fun deleteFacesForMedia(contentUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<DetectedFaceEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaceEmbeddings(embeddings: List<FaceEmbeddingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPersonCluster(cluster: PersonClusterEntity): Long

    @Query("""
        SELECT person_clusters.id AS personId,
               person_clusters.name AS name,
               person_clusters.representativeFaceId AS representativeFaceId,
               person_clusters.confidence AS confidence,
               person_clusters.memberCount AS memberCount,
               detected_faces.id AS faceId,
               detected_faces.contentUri AS contentUri,
               face_embeddings.modelVersion AS modelVersion,
               face_embeddings.vectorBytes AS vectorBytes,
               face_embeddings.dimensions AS dimensions
        FROM person_clusters
        LEFT JOIN detected_faces ON detected_faces.personClusterId = person_clusters.id
        LEFT JOIN face_embeddings ON face_embeddings.detectedFaceId = detected_faces.id
        ORDER BY person_clusters.id, detected_faces.id
    """)
    fun observeSavedPersonClusters(): Flow<List<PersonClusterFaceRow>>

    @Query("SELECT * FROM detected_faces WHERE personClusterId = :personId ORDER BY contentUri, frameTimeMillis, faceIndex")
    suspend fun getFacesForPerson(personId: Long): List<DetectedFaceEntity>

    @Query("SELECT * FROM person_clusters WHERE id = :personId LIMIT 1")
    suspend fun getPersonCluster(personId: Long): PersonClusterEntity?

    @Query("UPDATE person_clusters SET name = :name WHERE id = :personId")
    suspend fun renamePersonCluster(personId: Long, name: String): Int

    @Query("UPDATE detected_faces SET personClusterId = :personId WHERE id IN (:faceIds) AND personClusterId IS NULL")
    suspend fun assignUnassignedFaces(personId: Long, faceIds: List<Long>): Int

    @Query("UPDATE detected_faces SET personClusterId = :targetPersonId WHERE id = :faceId AND personClusterId = :sourcePersonId")
    suspend fun moveFaceToPerson(sourcePersonId: Long, targetPersonId: Long, faceId: Long): Int

    @Query("UPDATE detected_faces SET personClusterId = :targetPersonId WHERE personClusterId = :sourcePersonId")
    suspend fun movePersonFaces(sourcePersonId: Long, targetPersonId: Long): Int

    @Query("UPDATE detected_faces SET personClusterId = NULL WHERE id = :faceId AND personClusterId = :personId")
    suspend fun detachFaceFromPerson(personId: Long, faceId: Long): Int

    @Query("UPDATE detected_faces SET personClusterId = NULL WHERE personClusterId = :personId")
    suspend fun clearPersonFaces(personId: Long): Int

    @Query("UPDATE person_clusters SET memberCount = (SELECT COUNT(*) FROM detected_faces WHERE personClusterId = :personId), representativeFaceId = COALESCE((SELECT MIN(id) FROM detected_faces WHERE personClusterId = :personId), representativeFaceId) WHERE id = :personId")
    suspend fun refreshPersonClusterStats(personId: Long): Int

    @Query("DELETE FROM person_clusters WHERE id = :personId")
    suspend fun deletePersonCluster(personId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM face_embeddings WHERE detectedFaceId = :faceId AND modelVersion = :modelVersion)")
    suspend fun hasFaceEmbedding(faceId: Long, modelVersion: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecognitionProgress(progress: RecognitionProgressEntity)

    @Query("SELECT * FROM recognition_progress WHERE id = 1 LIMIT 1")
    suspend fun getRecognitionProgress(): RecognitionProgressEntity?

    @Query("""
        SELECT detected_faces.id AS faceId,
               detected_faces.contentUri AS contentUri,
               detected_faces.frameTimeMillis AS frameTimeMillis,
               detected_faces.faceIndex AS faceIndex,
               detected_faces.yawDegrees AS yawDegrees,
               detected_faces.rollDegrees AS rollDegrees,
               face_embeddings.modelVersion AS modelVersion,
               face_embeddings.vectorBytes AS vectorBytes,
               face_embeddings.dimensions AS dimensions
        FROM face_embeddings
        INNER JOIN detected_faces ON detected_faces.id = face_embeddings.detectedFaceId
        ORDER BY detected_faces.contentUri, detected_faces.frameTimeMillis, detected_faces.faceIndex
    """)
    fun observeFaceEmbeddings(): Flow<List<FaceEmbeddingRow>>


    @Query("SELECT ai_sets.id AS setId, ai_sets.name AS name, ai_sets.createdAtEpochMillis AS createdAtEpochMillis, ai_sets.updatedAtEpochMillis AS updatedAtEpochMillis, ai_set_memberships.contentUri AS contentUri FROM ai_sets LEFT JOIN ai_set_memberships ON ai_sets.id = ai_set_memberships.setId ORDER BY ai_sets.updatedAtEpochMillis DESC, ai_sets.id, ai_set_memberships.contentUri")
    fun observeAiSetMembers(): Flow<List<AiSetMemberRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAiSet(set: AiSetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAiSetMembers(members: List<AiSetMembershipEntity>)

    @Query("UPDATE ai_sets SET name = :name, updatedAtEpochMillis = :updatedAt WHERE id = :setId")
    suspend fun renameAiSet(setId: String, name: String, updatedAt: Long): Int

    @Query("DELETE FROM ai_set_memberships WHERE setId = :setId AND contentUri = :contentUri")
    suspend fun removeAiSetMember(setId: String, contentUri: String): Int

    @Query("INSERT OR IGNORE INTO ai_set_memberships (setId, contentUri, addedAtEpochMillis) SELECT :targetSetId, contentUri, :addedAt FROM ai_set_memberships WHERE setId = :sourceSetId")
    suspend fun copyAiSetMembers(sourceSetId: String, targetSetId: String, addedAt: Long)

    @Query("DELETE FROM ai_sets WHERE id = :setId")
    suspend fun deleteAiSet(setId: String): Int

    @Query("SELECT COUNT(*) FROM ai_sets WHERE id = :setId")
    suspend fun countAiSet(setId: String): Int

    @Query("UPDATE ai_sets SET updatedAtEpochMillis = :updatedAt WHERE id = :setId")
    suspend fun touchAiSet(setId: String, updatedAt: Long): Int

}
