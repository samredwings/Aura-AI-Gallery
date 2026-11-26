package ai.sakana.aura.gallery.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved, named set of tone parameters for a photo. The original file is never modified.
 * Persisted by AuraGalleryDatabase v8+ and scoped to the original media URI.
 */
@Entity(
    tableName = "edit_history",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["contentUri"],
            childColumns = ["contentUri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["contentUri"])],
)
data class EditHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentUri: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val brightness: Float = 0f,
    @ColumnInfo(defaultValue = "0") val contrast: Float = 0f,
    @ColumnInfo(defaultValue = "0") val saturation: Float = 0f,
    @ColumnInfo(defaultValue = "0") val warmth: Float = 0f,
    val appliedAt: Long,
    @ColumnInfo(defaultValue = "0") val isActive: Boolean = false,
)
