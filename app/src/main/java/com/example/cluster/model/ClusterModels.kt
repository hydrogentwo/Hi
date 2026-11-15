package com.example.cluster.model

import com.squareup.moshi.JsonClass

enum class NodeRole(val displayName: String, val kubeRoleLabel: String) {
    CONTROL_PLANE("Control Plane", "control-plane,worker"),
    WORKER("Worker Node", "worker")
}

enum class NodeConditionType(val label: String) {
    READY("Ready"),
    MEMORY_PRESSURE("MemoryPressure"),
    THERMAL_PRESSURE("ThermalPressure"),
    BATTERY_LOW("BatteryLow"),
    OFFLINE("NotReady")
}

@JsonClass(generateAdapter = true)
data class NodeTelemetry(
    val nodeId: String,
    val nodeName: String,
    val role: NodeRole,
    val hostIp: String,
    val port: Int,
    val cpuCores: Int,
    val cpuLoadPercent: Int,
    val cpuArch: String,
    val totalRamMb: Long,
    val availRamMb: Long,
    val totalStorageGb: Float,
    val availStorageGb: Float,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalStatusLabel: String,
    val isThermalThrottled: Boolean,
    val androidVersion: String,
    val deviceModel: String,
    val activePods: Int,
    val uptimeSeconds: Long,
    val rttMs: Long = 0L,
    val lastHeartbeatMs: Long = System.currentTimeMillis(),
    val isLocal: Boolean = false,
    val aiCoreStatus: String = AiCoreStatus.UNKNOWN.name
) {
    val usedRamMb: Long get() = (totalRamMb - availRamMb).coerceAtLeast(0L)
    val ramUsageFraction: Float
        get() = if (totalRamMb > 0L) (usedRamMb.toFloat() / totalRamMb.toFloat()).coerceIn(0f, 1f) else 0f

    val conditions: List<NodeConditionType>
        get() {
            val list = mutableListOf<NodeConditionType>()
            val memLow = totalRamMb > 0 && (availRamMb.toFloat() / totalRamMb) < 0.12f
            val battLow = !isCharging && batteryPercent in 1..14
            if (memLow) list.add(NodeConditionType.MEMORY_PRESSURE)
            if (isThermalThrottled) list.add(NodeConditionType.THERMAL_PRESSURE)
            if (battLow) list.add(NodeConditionType.BATTERY_LOW)
            if (list.isEmpty()) list.add(NodeConditionType.READY)
            return list
        }

    val isSchedulable: Boolean
        get() = !conditions.contains(NodeConditionType.OFFLINE)

    val endpoint: String get() = "$hostIp:$port"
}

data class NodeMetricSample(
    val cpuLoadPercent: Int,
    val ramUsagePercent: Int,
    val batteryPercent: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class AiCoreStatus(val label: String) {
    UNKNOWN("Checking"),
    AVAILABLE("Nano Ready"),
    DOWNLOADABLE("Downloadable"),
    DOWNLOADING("Downloading"),
    UNAVAILABLE("Unavailable");

    companion object {
        fun fromWire(value: String?): AiCoreStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

enum class NsdStatus {
    IDLE,
    DISCOVERING,
    FAILED
}

data class DiscoveredPeer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val nodeId: String = "",
    val nodeName: String = "",
    val role: NodeRole? = null,
    val cpuCores: Int = 0,
    val totalRamMb: Long = 0L,
    val deviceModel: String = "",
    val aiCoreStatus: AiCoreStatus = AiCoreStatus.UNKNOWN,
    val discoveredAtMs: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = nodeName.ifBlank { serviceName }
}

enum class WorkloadType(
    val id: String,
    val title: String,
    val kubeImage: String,
    val subtitle: String,
    val defaultIterations: Long,
    val unitLabel: String
) {
    PRIME_SIEVE(
        id = "prime-sieve",
        title = "Prime Sieve",
        kubeImage = "kubemesh/prime-sieve:v1.4",
        subtitle = "Parallel range primality & largest-prime discovery",
        defaultIterations = 1_600_000L,
        unitLabel = "integers"
    ),
    MONTE_CARLO_PI(
        id = "montecarlo-pi",
        title = "Monte Carlo π",
        kubeImage = "kubemesh/montecarlo-pi:v2.1",
        subtitle = "Distributed stochastic sampling across cluster cores",
        defaultIterations = 24_000_000L,
        unitLabel = "samples"
    ),
    SHA256_BENCHMARK(
        id = "sha256-mesh",
        title = "SHA-256 Mesh",
        kubeImage = "kubemesh/sha256-worker:v1.0",
        subtitle = "Multi-node cryptographic digest throughput kernel",
        defaultIterations = 900_000L,
        unitLabel = "hashes"
    ),
    MATRIX_MATMUL(
        id = "tensor-matmul",
        title = "Matrix Kernel",
        kubeImage = "kubemesh/tensor-matmul:v1.2",
        subtitle = "Row-sharded dense 320×320 float matrix multiplication",
        defaultIterations = 320L,
        unitLabel = "rows"
    )
}

enum class SchedulingPolicy(val label: String, val description: String) {
    PROPORTIONAL_SPLIT(
        "Proportional CPU Pool",
        "Shards workload across Control Plane & Worker nodes weighted by CPU cores"
    ),
    WORKER_PREFERRED(
        "Offload to Worker",
        "Schedules compute shards onto connected Worker device first"
    ),
    CONTROL_PLANE_ONLY(
        "Local Node Only",
        "Pins execution strictly to this device's CPU cores"
    )
}

enum class PodPhase(val label: String) {
    PENDING("Pending"),
    RUNNING("Running"),
    SUCCEEDED("Succeeded"),
    FAILED("Failed")
}

enum class TaskQueueItemStatus(val label: String) {
    PENDING("Pending"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    FAILED("Failed")
}

data class QueuedChunkState(
    val podId: String,
    val podName: String,
    val workloadType: WorkloadType,
    val chunkIndex: Int,
    val totalChunks: Int,
    val rangeStart: Long,
    val rangeEnd: Long,
    val iterations: Long,
    val status: TaskQueueItemStatus,
    val attempt: Int = 1,
    val assignedNodeId: String? = null,
    val assignedNodeName: String? = null,
    val isRemoteNode: Boolean = false,
    val elapsedMs: Long = 0L,
    val itemsProcessed: Long = 0L,
    val errorMessage: String? = null
)

data class PodShard(
    val shardId: String,
    val nodeId: String,
    val nodeName: String,
    val isRemote: Boolean,
    val hostEndpoint: String,
    val weightPercent: Int,
    val rangeStart: Long,
    val rangeEnd: Long,
    val iterations: Long,
    val phase: PodPhase,
    val elapsedMs: Long = 0L,
    val itemsProcessed: Long = 0L,
    val throughputLabel: String = "",
    val primaryValue: Double = 0.0,
    val secondaryText: String = "",
    val errorMessage: String? = null,
    val chunksCompleted: Int = 1
)

data class ClusterPod(
    val podId: String,
    val name: String,
    val namespace: String = "compute-mesh",
    val workloadType: WorkloadType,
    val schedulingPolicy: SchedulingPolicy,
    val phase: PodPhase,
    val requestedCores: Int,
    val requestedMemMb: Int,
    val intensityLabel: String,
    val createdAtMs: Long,
    val completedAtMs: Long? = null,
    val totalElapsedMs: Long = 0L,
    val aggregatedResult: String = "Scheduling shards...",
    val speedupNote: String = "",
    val shards: List<PodShard>,
    val logs: List<String>,
    val yamlManifest: String,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val chunkTasks: List<QueuedChunkState> = emptyList()
)

// Wire DTOs for LAN HTTP JSON protocol
@JsonClass(generateAdapter = true)
data class PairRequest(
    val clusterToken: String,
    val peerNode: NodeTelemetry
)

@JsonClass(generateAdapter = true)
data class PairResponse(
    val accepted: Boolean,
    val message: String,
    val hostNode: NodeTelemetry
)

@JsonClass(generateAdapter = true)
data class ShardExecuteRequest(
    val clusterToken: String,
    val podId: String,
    val podName: String,
    val workloadId: String,
    val rangeStart: Long,
    val rangeEnd: Long,
    val iterations: Long,
    val matrixDim: Int = 320
)

@JsonClass(generateAdapter = true)
data class ShardExecuteResponse(
    val podId: String,
    val success: Boolean,
    val elapsedMs: Long,
    val itemsProcessed: Long,
    val primaryValue: Double,
    val secondaryText: String,
    val threadsUsed: Int,
    val errorMessage: String? = null,
    val nodeTelemetry: NodeTelemetry? = null
)

@JsonClass(generateAdapter = true)
data class AiCorePromptRequest(
    val clusterToken: String,
    val prompt: String,
    val systemInstruction: String = "",
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 512
)

@JsonClass(generateAdapter = true)
data class AiCorePromptResponse(
    val success: Boolean,
    val outputText: String,
    val elapsedMs: Long,
    val targetNodeName: String,
    val aiCoreStatus: String,
    val errorMessage: String? = null
)
