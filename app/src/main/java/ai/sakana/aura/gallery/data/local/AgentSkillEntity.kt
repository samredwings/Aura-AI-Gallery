package ai.sakana.aura.gallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Imported Markdown is stored as untrusted guidance; it never becomes executable code. */
@Entity(tableName = "agent_skills", indices = [Index(value = ["enabled", "name"])])
data class AgentSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val sourceUri: String?,
    val enabled: Boolean = true,
    val createdAtEpochMillis: Long,
)
