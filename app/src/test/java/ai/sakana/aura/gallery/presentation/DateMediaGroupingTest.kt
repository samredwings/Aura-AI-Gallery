package ai.sakana.aura.gallery.presentation

import ai.sakana.aura.gallery.domain.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DateMediaGroupingTest {
    @Test
    fun `grouping preserves descending date order and media order without re-sorting`() {
        val newest = mediaItem(1L, "newest", "2026-11-28T12:00:00Z")
        val sameDayEarlier = mediaItem(2L, "same-day-earlier", "2026-11-28T08:00:00Z")
        val previousDay = mediaItem(3L, "previous-day", "2026-11-27T23:00:00Z")

        val groups = groupMediaByDate(
            items = listOf(newest, sameDayEarlier, previousDay),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals(
            listOf(LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-27")),
            groups.map { it.date },
        )
        assertEquals(
            listOf("newest", "same-day-earlier"),
            groups.first().items.map { it.displayName },
        )
    }

    @Test
    fun `grouping handles empty media list`() {
        assertEquals(emptyList<DateMediaGroup>(), groupMediaByDate(emptyList(), ZoneId.of("UTC")))
    }

    @Test
    fun `video thumbnail cache key changes when media content changes`() {
        val original = mediaItem(1L, "clip", "2026-11-28T12:00:00Z").copy(
            mimeType = "video/mp4",
            mediaType = "VIDEO",
            sizeBytes = 100L,
            dateModifiedEpochMillis = 1_000L,
        )

        assertEquals(
            false,
            videoThumbnailCacheKey(original) == videoThumbnailCacheKey(original.copy(dateModifiedEpochMillis = 2_000L)),
        )
        assertEquals(
            false,
            videoThumbnailCacheKey(original) == videoThumbnailCacheKey(original.copy(sizeBytes = 200L)),
        )
    }

    private fun mediaItem(id: Long, name: String, takenAt: String) = MediaItem(
        id = id,
        contentUri = "content://media/$id",
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 1L,
        dateTakenEpochMillis = Instant.parse(takenAt).toEpochMilli(),
        width = 100,
        height = 100,
        isFavorite = false,
    )
}
