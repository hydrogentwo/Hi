package com.example.cluster.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

class DeviceTelemetryCollector(private val context: Context) {

    private var prevProcessCpuMs: Long = Process.getElapsedCpuTime()
    private var prevWallTimeMs: Long = SystemClock.elapsedRealtime()
    private var prevProcStatTotal: Long = -1L
    private var prevProcStatIdle: Long = -1L

    fun collect(
        nodeId: String,
        nodeName: String,
        role: NodeRole,
        serverPort: Int,
        activePods: Int,
        aiCoreStatus: AiCoreStatus = AiCoreStatus.UNKNOWN
    ): NodeTelemetry {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val (totalRamMb, availRamMb) = readMemoryMb()
        val (totalStorageGb, availStorageGb) = readStorageGb()
        val (batteryPct, isCharging) = readBattery()
        val (thermalLabel, isThrottled) = readThermal()
        val cpuLoad = readCpuLoadPercent(cores)
        val hostIp = resolveLanIpv4Address()

        return NodeTelemetry(
            nodeId = nodeId,
            nodeName = nodeName,
            role = role,
            hostIp = hostIp,
            port = serverPort,
            cpuCores = cores,
            cpuLoadPercent = cpuLoad,
            cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            totalRamMb = totalRamMb,
            availRamMb = availRamMb,
            totalStorageGb = totalStorageGb,
            availStorageGb = availStorageGb,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            thermalStatusLabel = thermalLabel,
            isThermalThrottled = isThrottled,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            activePods = activePods,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
            rttMs = 0L,
            lastHeartbeatMs = System.currentTimeMillis(),
            isLocal = true,
            aiCoreStatus = aiCoreStatus.name
        )
    }

    private fun readMemoryMb(): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(info)
            val totalMb = info.totalMem / (1024L * 1024L)
            val availMb = info.availMem / (1024L * 1024L)
            return totalMb to availMb
        }
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() / (1024L * 1024L)) to (rt.freeMemory() / (1024L * 1024L))
    }

    private fun readStorageGb(): Pair<Float, Float> {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.totalBytes.toDouble()
            val availBytes = stat.availableBytes.toDouble()
            val gb = 1024.0 * 1024.0 * 1024.0
            ((totalBytes / gb).toFloat()) to ((availBytes / gb).toFloat())
        } catch (_: Exception) {
            0f to 0f
        }
    }

    private fun readBattery(): Pair<Int, Boolean> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else 100
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct.coerceIn(0, 100) to charging
        } catch (_: Exception) {
            100 to true
        }
    }

    private fun readThermal(): Pair<String, Boolean> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal" to false
                PowerManager.THERMAL_STATUS_LIGHT -> "Warm" to false
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate" to false
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe" to true
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical" to true
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Emergency" to true
                else -> "Nominal" to false
            }
        }
        return "Nominal" to false
    }

    private fun readCpuLoadPercent(cores: Int): Int {
        // Attempt /proc/stat first (available on some kernels/emulators), then fall back to Process CPU + cpufreq
        try {
            val firstLine = File("/proc/stat").bufferedReader().use { it.readLine() }
            if (firstLine != null && firstLine.startsWith("cpu ")) {
                val parts = firstLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size >= 4) {
                    val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
                    val total = parts.sum()
                    if (prevProcStatTotal > 0L) {
                        val deltaTotal = (total - prevProcStatTotal).coerceAtLeast(1L)
                        val deltaIdle = (idle - prevProcStatIdle).coerceAtLeast(0L)
                        prevProcStatTotal = total
                        prevProcStatIdle = idle
                        return (((deltaTotal - deltaIdle) * 100.0) / deltaTotal).roundToInt().coerceIn(1, 100)
                    }
                    prevProcStatTotal = total
                    prevProcStatIdle = idle
                }
            }
        } catch (_: Exception) {
            // SELinux blocks /proc/stat on Android 8+ devices; use Process CPU + scaling freq fallback below
        }

        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val deltaWall = (nowWall - prevWallTimeMs).coerceAtLeast(100L)
        val deltaCpu = (nowCpu - prevProcessCpuMs).coerceAtLeast(0L)
        prevWallTimeMs = nowWall
        prevProcessCpuMs = nowCpu

        val appCpuPct = ((deltaCpu.toDouble() / (deltaWall * cores)) * 100.0).roundToInt()
        val freqPct = readCpuFreqUtilizationHint(cores)
        return maxOf(appCpuPct, freqPct).coerceIn(2, 100)
    }

    private fun readCpuFreqUtilizationHint(cores: Int): Int {
        return try {
            var sumRatio = 0.0
            var counted = 0
            for (i in 0 until cores.coerceAtMost(16)) {
                val curFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                val maxFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (curFile.canRead() && maxFile.canRead()) {
                    val cur = curFile.readText().trim().toDoubleOrNull() ?: continue
                    val max = maxFile.readText().trim().toDoubleOrNull() ?: continue
                    if (max > 0) {
                        sumRatio += (cur / max).coerceIn(0.0, 1.0)
                        counted++
                    }
                }
            }
            if (counted > 0) ((sumRatio / counted) * 35.0).roundToInt() else 6
        } catch (_: Exception) {
            6
        }
    }

    fun resolveLanIpv4Address(): String {
        // 1. ConnectivityManager LinkProperties (zero location permission required)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(network)
            val addr = linkProps?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (addr?.hostAddress != null) {
                return addr.hostAddress!!
            }
        } catch (_: Exception) {
        }

        // 2. NetworkInterface fallback (prioritize wlan / eth / ap / rndis interfaces)
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { iface ->
                    val n = iface.name.lowercase()
                    when {
                        n.startsWith("wlan") || n.startsWith("ap") -> 3
                        n.startsWith("eth") || n.startsWith("rndis") -> 2
                        else -> 1
                    }
                }
            for (iface in candidates) {
                val ipv4 = iface.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                if (ipv4?.hostAddress != null) return ipv4.hostAddress!!
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
