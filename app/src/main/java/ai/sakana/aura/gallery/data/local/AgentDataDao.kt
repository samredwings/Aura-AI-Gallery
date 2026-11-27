package ai.sakana.aura.gallery.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaVersionDao {
    @Query("SELECT * FROM media_versions WHERE sourceContentUri = :sourceUri ORDER BY createdAtEpochMillis DESC")
    fun observeVersions(sourceUri: String): Flow<List<MediaVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: MediaVersionEntity): Long

    @Query("SELECT * FROM media_versions WHERE versionContentUri = :versionUri LIMIT 1")
    suspend fun findVersion(versionUri: String): MediaVersionEntity?
}

@Dao
interface AgentSkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY name COLLATE NOCASE ASC")
    fun observeSkills(): Flow<List<AgentSkillEntity>>

    @Query("SELECT * FROM agent_skills WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getEnabledSkills(): List<AgentSkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSkill(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET enabled = :enabled WHERE id = :id")
    suspend fun setSkillEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_skills WHERE id = :id")
    suspend fun deleteSkill(id: String)
}
