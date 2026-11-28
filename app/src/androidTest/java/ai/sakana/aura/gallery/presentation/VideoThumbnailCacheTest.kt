package ai.sakana.aura.gallery.presentation

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoThumbnailCacheTest {
    @Test
    fun cacheReturnsPreviouslyStoredThumbnail() {
        val cache = VideoThumbnailCache(maxSizeKb = 8)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        cache.put("content://media/1", bitmap)

        assertSame(bitmap, cache.get("content://media/1"))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedThumbnailAtByteLimit() {
        val cache = VideoThumbnailCache(maxSizeKb = 1)
        val first = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        cache.put("first", first)
        cache.put("second", second)

        assertNull(cache.get("first"))
        assertSame(second, cache.get("second"))
    }
}
