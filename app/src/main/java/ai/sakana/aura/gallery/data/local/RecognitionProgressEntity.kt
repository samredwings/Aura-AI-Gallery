package ai.sakana.aura.gallery.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row durable checkpoint for the on-device recognition worker. */
@Entity(tableName = "recognition_progress")
data class RecognitionProgressEntity(
    @PrimaryKey val id: Int = 1,
    val currentItemIndex: Int,
    val totalItems: Int,
    val processedCount: Int,
    val lastRunEpochMillis: Long,
    val isPaused: Boolean = false,
)
