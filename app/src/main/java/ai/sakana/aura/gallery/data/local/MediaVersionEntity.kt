package ai.sakana.aura.gallery.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Metadata for a non-destructive derivative. The original media row is never changed. */
@Entity(
    tableName = "media_versions",
    indices = [
        Index(value = ["sourceContentUri", "createdAtEpochMillis"]),
        Index(value = ["versionContentUri"], unique = true),
    ],
)
data class MediaVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceContentUri: String,
    val versionContentUri: String,
    val operation: String,
    val prompt: String,
    val modelId: String,
    val createdAtEpochMillis: Long,
)
