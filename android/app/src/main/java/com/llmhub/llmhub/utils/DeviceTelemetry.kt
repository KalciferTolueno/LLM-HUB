package com.llmhub.llmhub.utils

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * System load telemetry for the home monitor UI.
 * CPU: delta from [/proc/stat] (standard Linux jiffies), with load-average fallback.
 * RAM: fraction of total RAM not in [ActivityManager.MemoryInfo.availMem].
 * GPU: best-effort read from vendor sysfs when exposed (Qualcomm/Mali/etc.); many devices return null.
 */
object DeviceTelemetry {

    data class CpuTimes(val idle: Long, val total: Long)

    fun readCpuTimes(): CpuTimes? {
        return try {
            val line = File("/proc/stat").bufferedReader().use { reader ->
                reader.lineSequence().firstOrNull { it.startsWith("cpu ") }
            } ?: return null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) return null
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return null
            val idle = nums[3] + if (nums.size > 4) nums[4] else 0L
            val count = minOf(nums.size, 10)
            var total = 0L
            for (i in 0 until count) total += nums[i]
            if (total <= 0L) return null
            CpuTimes(idle, total)
        } catch (_: Exception) {
            null
        }
    }

    fun cpuUsagePercentBetween(prev: CpuTimes?, curr: CpuTimes?): Float? {
        if (prev == null || curr == null) return null
        val idleD = curr.idle - prev.idle
        val totalD = curr.total - prev.total
        if (totalD <= 0L) return null
        val usage = 100f * (totalD - idleD).toFloat() / totalD.toFloat()
        return usage.coerceIn(0f, 100f)
    }

    /** 1-minute load average from [/proc/loadavg] (Linux). */
    fun readLoadAvgOneMinute(): Float? {
        return try {
            val line = File("/proc/loadavg").bufferedReader().use { it.readLine() } ?: return null
            val first = line.trim().split(Regex("\\s+")).firstOrNull() ?: return null
            first.toFloatOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Rough CPU% from load average when [/proc/stat] delta is unavailable.
     */
    fun cpuEstimatePercentFromLoad(load1: Float, numCores: Int): Float {
        if (numCores <= 0 || load1 < 0f) return 0f
        val est = (load1 / numCores.toFloat()) * 100f
        return est.coerceIn(0f, 100f)
    }

    fun ramUsedPercent(context: Context): Float {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        if (total <= 0L) return 0f
        val used = (total - mi.availMem).coerceAtLeast(0L)
        return (100f * used.toFloat() / total.toFloat()).coerceIn(0f, 100f)
    }

    private val gpuBusyPaths = listOf(
        "/sys/class/kgsl/kgsl/poll/gpu_busy_percent",
        "/sys/kernel/gpu/gpu_busy_percent",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percent",
        "/sys/class/misc/mali0/device/gpu_busy_percent",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/mali/mali0/utilization",
        "/sys/kernel/debug/mali0/utilization",
        "/sys/devices/platform/fb000000.gpu/utilization",
        "/sys/devices/platform/gpu.0/devfreq/gpu.0/load"
    )

    fun tryReadGpuBusyPercent(): Float? {
        for (path in gpuBusyPaths) {
            try {
                val raw = File(path).bufferedReader().use { it.readLine() }?.trim() ?: continue
                val v = parseGpuMetric(raw) ?: continue
                return v.coerceIn(0f, 100f)
            } catch (_: Exception) {
                // SELinux or missing node
            }
        }
        return null
    }

    private fun parseGpuMetric(raw: String): Float? {
        raw.toFloatOrNull()?.let { return it }
        val noSuffix = raw.removeSuffix("%").trim().toFloatOrNull()
        if (noSuffix != null) return noSuffix
        val percentMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*%").find(raw)
        val fromPct = percentMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (fromPct != null) return fromPct
        val first = raw.split("@", "/", " ").firstOrNull()?.trim()?.toFloatOrNull()
        if (first != null && first in 0f..100f) return first
        return null
    }
}
