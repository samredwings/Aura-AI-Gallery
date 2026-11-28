package ai.sakana.aura.gallery.diagnostics

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import timber.log.Timber

/** Local-only measurements for the M1 performance targets. No performance pass is assumed. */
internal object PerformanceMetrics {

    @Volatile private var processStartedAtMs = 0L
    @Volatile private var firstContentLogged = false
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null
    private var frameCount = 0
    private var within16MsCount = 0
    private var deadlineFrameCount = 0
    private var withinDeadlineCount = 0
    private var droppedFrameReports = 0
    private var peakScrollHeapBytes = 0L

    @Synchronized
    fun markProcessStart() {
        processStartedAtMs = SystemClock.elapsedRealtime()
        firstContentLogged = false
    }

    @Synchronized
    fun recordFirstGalleryContent(itemCount: Int) {
        if (itemCount <= 0 || firstContentLogged) return
        firstContentLogged = true
        val started = processStartedAtMs
        if (started <= 0L) return
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        Timber.i("PERF metric=cold_start_to_first_gallery_content duration_ms=%d item_count=%d", elapsed, itemCount)
        logHeapUsage("after_first_gallery_content")
    }

    fun recordMediaStoreScan(metric: String, durationMs: Long, itemCountBefore: Int, itemCountAfter: Int) {
        Timber.i(
            "PERF metric=%s duration_ms=%d item_count_before=%d item_count_after=%d item_count_delta=%d",
            metric, durationMs, itemCountBefore, itemCountAfter, itemCountAfter - itemCountBefore,
        )
        logHeapUsage("after_$metric")
    }

    fun logHeapUsage(label: String) {
        Timber.i("PERF metric=heap_usage label=%s used_bytes=%d", label, usedHeapBytes())
    }

    @Synchronized
    fun startScrollFrameMonitor(window: Window) {
        if (frameMetricsListener != null) return
        frameCount = 0
        within16MsCount = 0
        deadlineFrameCount = 0
        withinDeadlineCount = 0
        droppedFrameReports = 0
        peakScrollHeapBytes = usedHeapBytes()

        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, droppedReports ->
            droppedFrameReports += droppedReports
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener

            val totalDurationNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (totalDurationNanos < 0L) return@OnFrameMetricsAvailableListener

            frameCount += 1
            if (isFrameWithin16Ms(totalDurationNanos)) within16MsCount += 1

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val deadlineNanos = metrics.getMetric(FrameMetrics.DEADLINE)
                if (deadlineNanos > 0L) {
                    deadlineFrameCount += 1
                    if (isFrameWithinDeadline(totalDurationNanos, deadlineNanos)) withinDeadlineCount += 1
                }
            }

            if (frameCount % 30 == 0) {
                peakScrollHeapBytes = maxOf(peakScrollHeapBytes, usedHeapBytes())
            }
        }

        frameMetricsListener = listener
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
    }

    @Synchronized
    fun stopScrollFrameMonitor(window: Window) {
        val listener = frameMetricsListener ?: return
        window.removeOnFrameMetricsAvailableListener(listener)
        frameMetricsListener = null

        val within16MsPercent = percentage(within16MsCount, frameCount)
        val withinDeadlinePercent = percentage(withinDeadlineCount, deadlineFrameCount)
        Timber.i(
            "PERF metric=scroll_jank frame_count=%d within_16ms=%d within_16ms_percent=%.1f deadline_frame_count=%d within_deadline=%d within_deadline_percent=%.1f dropped_reports=%d",
            frameCount,
            within16MsCount,
            within16MsPercent,
            deadlineFrameCount,
            withinDeadlineCount,
            withinDeadlinePercent,
            droppedFrameReports,
        )
        Timber.i("PERF metric=scroll_peak_heap peak_bytes=%d", maxOf(peakScrollHeapBytes, usedHeapBytes()))
    }

    private fun percentage(part: Int, total: Int): Double =
        if (total <= 0) 0.0 else part * 100.0 / total

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
    }
}

internal fun isFrameWithin16Ms(totalDurationNanos: Long): Boolean =
    totalDurationNanos in 0..16_000_000L

internal fun isFrameWithinDeadline(totalDurationNanos: Long, deadlineNanos: Long): Boolean =
    totalDurationNanos >= 0L && deadlineNanos > 0L && totalDurationNanos < deadlineNanos
