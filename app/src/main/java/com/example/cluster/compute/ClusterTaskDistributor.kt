package com.example.cluster.compute

import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.QueuedChunkState
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.ShardExecuteResponse
import com.example.cluster.model.TaskQueueItemStatus
import com.example.cluster.model.WorkloadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class JobChunk(
    val chunkIndex: Int,
    val totalChunks: Int,
    val rangeStart: Long,
    val rangeEnd: Long,
    val iterations: Long,
    val matrixDim: Int,
    val attempt: Int = 1
)

data class ChunkExecutionResult(
    val chunk: JobChunk,
    val node: NodeTelemetry,
    val response: ShardExecuteResponse
)

private sealed interface WorkerEvent {
    data class Started(val chunk: JobChunk, val node: NodeTelemetry) : WorkerEvent
    data class Finished(
        val chunk: JobChunk,
        val node: NodeTelemetry,
        val response: ShardExecuteResponse
    ) : WorkerEvent
    data class NodeDisconnected(
        val node: NodeTelemetry,
        val interruptedChunk: JobChunk?
    ) : WorkerEvent
}

private class WorkerHandle(val node: NodeTelemetry) {
    var job: Job? = null
    private var disconnected = false
    private var currentChunk: JobChunk? = null

    @Synchronized
    fun tryStartChunk(chunk: JobChunk): Boolean {
        if (disconnected) return false
        currentChunk = chunk
        return true
    }

    @Synchronized
    fun finishChunkIfActive(): Boolean {
        if (disconnected) return false
        currentChunk = null
        return true
    }

    @Synchronized
    fun disconnectAndStealChunk(): Pair<Boolean, JobChunk?> {
        if (disconnected) return false to null
        disconnected = true
        val stolen = currentChunk
        currentChunk = null
        return true to stolen
    }

    @Synchronized
    fun isDisconnected(): Boolean = disconnected
}

data class TaskProgressSnapshot(
    val totalChunks: Int,
    val completedChunks: Int,
    val failedChunks: Int,
    val nodeShards: List<PodShard>,
    val latestEventLog: String? = null,
    val chunkTasks: List<QueuedChunkState> = emptyList()
)

data class TaskDistributionOutcome(
    val totalChunks: Int,
    val completedChunks: Int,
    val wallElapsedMs: Long,
    val nodeShards: List<PodShard>,
    val eventLogs: List<String>,
    val chunkTasks: List<QueuedChunkState> = emptyList()
)

class ClusterTaskDistributor {

    fun buildInitialChunkStates(
        podId: String,
        podName: String,
        workloadType: WorkloadType,
        chunks: List<JobChunk>
    ): List<QueuedChunkState> = chunks.map { chunk ->
        QueuedChunkState(
            podId = podId,
            podName = podName,
            workloadType = workloadType,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            rangeStart = chunk.rangeStart,
            rangeEnd = chunk.rangeEnd,
            iterations = chunk.iterations,
            status = TaskQueueItemStatus.PENDING,
            attempt = chunk.attempt
        )
    }

    fun sliceJobIntoChunks(
        workloadType: WorkloadType,
        totalWork: Long,
        nodeCount: Int,
        chunksPerNode: Int = 4
    ): List<JobChunk> {
        val safeTotalWork = totalWork.coerceAtLeast(1L)
        val desiredChunks = (nodeCount.coerceAtLeast(1) * chunksPerNode).coerceIn(4, 24)
        val chunkCount = desiredChunks.toLong().coerceAtMost(safeTotalWork).toInt().coerceAtLeast(1)

        var cursor = when (workloadType) {
            WorkloadType.PRIME_SIEVE -> 2L
            else -> 0L
        }
        val endLimit = when (workloadType) {
            WorkloadType.PRIME_SIEVE -> safeTotalWork + 1L
            else -> safeTotalWork
        }
        val matrixDim = if (workloadType == WorkloadType.MATRIX_MATMUL) safeTotalWork.toInt() else 320

        val chunks = mutableListOf<JobChunk>()
        val baseSlice = safeTotalWork / chunkCount
        val remainder = safeTotalWork % chunkCount

        for (i in 0 until chunkCount) {
            val sliceCount = baseSlice + if (i < remainder) 1L else 0L
            val start = cursor
            val end = if (workloadType == WorkloadType.MATRIX_MATMUL) {
                (start + sliceCount).coerceAtMost(endLimit)
            } else {
                (start + sliceCount - 1L).coerceAtMost(endLimit)
            }
            cursor = if (workloadType == WorkloadType.MATRIX_MATMUL) end else end + 1L

            chunks.add(
                JobChunk(
                    chunkIndex = i,
                    totalChunks = chunkCount,
                    rangeStart = start,
                    rangeEnd = end,
                    iterations = sliceCount,
                    matrixDim = matrixDim
                )
            )
        }
        return chunks
    }

    suspend fun distributeAndExecute(
        podId: String,
        podName: String,
        workloadType: WorkloadType,
        clusterToken: String,
        targetNodes: List<NodeTelemetry>,
        chunks: List<JobChunk>,
        executeChunk: suspend (NodeTelemetry, ShardExecuteRequest) -> Result<ShardExecuteResponse>,
        onProgress: suspend (TaskProgressSnapshot) -> Unit = {},
        disconnectedNodeIds: Flow<Set<String>> = emptyFlow(),
        fallbackNodesProvider: () -> List<NodeTelemetry> = { emptyList() },
        maxAttempts: Int = 3
    ): TaskDistributionOutcome = coroutineScope {
        val startMs = System.currentTimeMillis()
        if (chunks.isEmpty() || targetNodes.isEmpty()) {
            return@coroutineScope TaskDistributionOutcome(
                totalChunks = chunks.size,
                completedChunks = 0,
                wallElapsedMs = 1L,
                nodeShards = emptyList(),
                eventLogs = emptyList(),
                chunkTasks = emptyList()
            )
        }

        val taskChannel = Channel<JobChunk>(Channel.UNLIMITED)
        val eventChannel = Channel<WorkerEvent>(Channel.UNLIMITED)
        val unhealthyNodeIds = ConcurrentHashMap.newKeySet<String>()
        val activeNodes = CopyOnWriteArrayList(targetNodes)
        val workerHandles = ConcurrentHashMap<String, WorkerHandle>()
        val logs = mutableListOf<String>()
        val chunkStates = buildInitialChunkStates(podId, podName, workloadType, chunks).toMutableList()
        val nodeResults = linkedMapOf<String, MutableList<ChunkExecutionResult>>()
        targetNodes.forEach { nodeResults[it.nodeId] = mutableListOf() }

        fun launchWorker(node: NodeTelemetry) {
            val handle = WorkerHandle(node)
            workerHandles[node.nodeId] = handle
            handle.job = launch {
                for (chunk in taskChannel) {
                    if (unhealthyNodeIds.contains(node.nodeId) || !handle.tryStartChunk(chunk)) {
                        taskChannel.trySend(chunk)
                        break
                    }

                    eventChannel.send(WorkerEvent.Started(chunk = chunk, node = node))

                    val req = ShardExecuteRequest(
                        clusterToken = clusterToken,
                        podId = podId,
                        podName = podName,
                        workloadId = workloadType.id,
                        rangeStart = chunk.rangeStart,
                        rangeEnd = chunk.rangeEnd,
                        iterations = chunk.iterations,
                        matrixDim = chunk.matrixDim
                    )

                    val outcome = try {
                        executeChunk(node, req)
                    } catch (ce: CancellationException) {
                        if (handle.isDisconnected()) {
                            break
                        }
                        throw ce
                    }

                    val response = outcome.getOrElse { err ->
                        ShardExecuteResponse(
                            podId = podId,
                            success = false,
                            elapsedMs = 0L,
                            itemsProcessed = 0L,
                            primaryValue = 0.0,
                            secondaryText = "",
                            threadsUsed = 0,
                            errorMessage = err.message ?: "Chunk RPC failure"
                        )
                    }
                    if (!handle.finishChunkIfActive()) {
                        break
                    }
                    eventChannel.send(
                        WorkerEvent.Finished(
                            chunk = chunk,
                            node = node,
                            response = response
                        )
                    )
                }
            }
        }

        // Seed task channel with all sliced chunks
        launch {
            chunks.forEach { chunk ->
                taskChannel.send(chunk)
            }
        }

        // Launch one worker coroutine per cluster node consuming from the shared taskChannel
        targetNodes.forEach { node ->
            launchWorker(node)
        }

        // Observe unexpected node disconnects (from heartbeat/NSD/unpair) and abort hung workers immediately
        val disconnectObserverJob = launch {
            disconnectedNodeIds.collect { disconnectedSet ->
                disconnectedSet.forEach { nodeId ->
                    unhealthyNodeIds.add(nodeId)
                    val handle = workerHandles[nodeId]
                    if (handle != null) {
                        val (justDisconnected, stolenChunk) = handle.disconnectAndStealChunk()
                        if (justDisconnected) {
                            handle.job?.cancel(CancellationException("Node ${handle.node.nodeName} disconnected"))
                            eventChannel.send(
                                WorkerEvent.NodeDisconnected(
                                    node = handle.node,
                                    interruptedChunk = stolenChunk
                                )
                            )
                        }
                    }
                }
            }
        }

        fun ensureFallbackWorkersIfNeeded(): Boolean {
            if (activeNodes.any { it.nodeId !in unhealthyNodeIds }) return true
            var activated = false
            fallbackNodesProvider().forEach { candidate ->
                if (candidate.nodeId !in unhealthyNodeIds && workerHandles[candidate.nodeId] == null) {
                    activeNodes.add(candidate)
                    nodeResults.putIfAbsent(candidate.nodeId, mutableListOf())
                    launchWorker(candidate)
                    val fallbackLog = "Failover: activated ${candidate.nodeName} to absorb pending channel chunks"
                    logs.add(fallbackLog)
                    activated = true
                }
            }
            return activated || activeNodes.any { it.nodeId !in unhealthyNodeIds }
        }

        val completedOrFailedChunkIndices = mutableSetOf<Int>()
        val inFlightAttemptByChunk = mutableMapOf<Int, Int>()
        val notifiedDisconnectedNodes = mutableSetOf<String>()
        var completedCount = 0
        var failedCount = 0

        suspend fun failAllUnresolvedChunks(reason: String) {
            while (taskChannel.tryReceive().isSuccess) {
                // Drain remaining unstarted chunks so channel stays clean
            }
            for (idx in chunkStates.indices) {
                if (idx !in completedOrFailedChunkIndices) {
                    completedOrFailedChunkIndices.add(idx)
                    failedCount++
                    chunkStates[idx] = chunkStates[idx].copy(
                        status = TaskQueueItemStatus.FAILED,
                        errorMessage = reason
                    )
                }
            }
            logs.add(reason)
            onProgress(
                TaskProgressSnapshot(
                    totalChunks = chunks.size,
                    completedChunks = completedCount,
                    failedChunks = failedCount,
                    nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                    latestEventLog = reason,
                    chunkTasks = chunkStates.toList()
                )
            )
        }

        for (event in eventChannel) {
            when (event) {
                is WorkerEvent.Started -> {
                    val idx = event.chunk.chunkIndex
                    if (idx !in completedOrFailedChunkIndices) {
                        inFlightAttemptByChunk[idx] = event.chunk.attempt
                        if (idx in chunkStates.indices) {
                            chunkStates[idx] = chunkStates[idx].copy(
                                status = TaskQueueItemStatus.IN_PROGRESS,
                                attempt = event.chunk.attempt,
                                assignedNodeId = event.node.nodeId,
                                assignedNodeName = event.node.nodeName,
                                isRemoteNode = !event.node.isLocal,
                                errorMessage = null
                            )
                        }
                        onProgress(
                            TaskProgressSnapshot(
                                totalChunks = chunks.size,
                                completedChunks = completedCount,
                                failedChunks = failedCount,
                                nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                latestEventLog = null,
                                chunkTasks = chunkStates.toList()
                            )
                        )
                    }
                }

                is WorkerEvent.NodeDisconnected -> {
                    unhealthyNodeIds.add(event.node.nodeId)
                    val hasHealthyWorker = ensureFallbackWorkersIfNeeded()
                    val chunk = event.interruptedChunk

                    if (chunk != null) {
                        val idx = chunk.chunkIndex
                        val activeAttempt = inFlightAttemptByChunk[idx] ?: chunk.attempt
                        if (idx !in completedOrFailedChunkIndices && activeAttempt == chunk.attempt) {
                            if (hasHealthyWorker && chunk.attempt < maxAttempts) {
                                val retryChunk = chunk.copy(attempt = chunk.attempt + 1)
                                inFlightAttemptByChunk[idx] = retryChunk.attempt
                                val retryLog = "Node ${event.node.nodeName} disconnected; re-queued chunk #${idx + 1} (attempt #${retryChunk.attempt})"
                                logs.add(retryLog)
                                notifiedDisconnectedNodes.add(event.node.nodeId)
                                if (idx in chunkStates.indices) {
                                    chunkStates[idx] = chunkStates[idx].copy(
                                        status = TaskQueueItemStatus.PENDING,
                                        attempt = retryChunk.attempt,
                                        assignedNodeId = null,
                                        assignedNodeName = null,
                                        isRemoteNode = false,
                                        errorMessage = "${event.node.nodeName} disconnected; re-queued"
                                    )
                                }
                                taskChannel.send(retryChunk)
                                onProgress(
                                    TaskProgressSnapshot(
                                        totalChunks = chunks.size,
                                        completedChunks = completedCount,
                                        failedChunks = failedCount,
                                        nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                        latestEventLog = retryLog,
                                        chunkTasks = chunkStates.toList()
                                    )
                                )
                            } else {
                                completedOrFailedChunkIndices.add(idx)
                                failedCount++
                                nodeResults.getOrPut(event.node.nodeId) { mutableListOf() }.add(
                                    ChunkExecutionResult(
                                        chunk = chunk,
                                        node = event.node,
                                        response = ShardExecuteResponse(
                                            podId = podId,
                                            success = false,
                                            elapsedMs = 0L,
                                            itemsProcessed = 0L,
                                            primaryValue = 0.0,
                                            secondaryText = "",
                                            threadsUsed = 0,
                                            errorMessage = "${event.node.nodeName} disconnected"
                                        )
                                    )
                                )
                                if (idx in chunkStates.indices) {
                                    chunkStates[idx] = chunkStates[idx].copy(
                                        status = TaskQueueItemStatus.FAILED,
                                        attempt = chunk.attempt,
                                        assignedNodeId = event.node.nodeId,
                                        assignedNodeName = event.node.nodeName,
                                        isRemoteNode = !event.node.isLocal,
                                        errorMessage = "${event.node.nodeName} disconnected; no healthy nodes remaining"
                                    )
                                }
                                if (!hasHealthyWorker) {
                                    failAllUnresolvedChunks("Node ${event.node.nodeName} disconnected; no healthy nodes left in mesh")
                                }
                            }
                        }
                    } else {
                        if (!hasHealthyWorker) {
                            failAllUnresolvedChunks("Node ${event.node.nodeName} disconnected; no healthy nodes left in mesh")
                        } else if (notifiedDisconnectedNodes.add(event.node.nodeId)) {
                            val disconnectLog = "Node ${event.node.nodeName} disconnected unexpectedly; pending chunks routed to healthy nodes"
                            logs.add(disconnectLog)
                            onProgress(
                                TaskProgressSnapshot(
                                    totalChunks = chunks.size,
                                    completedChunks = completedCount,
                                    failedChunks = failedCount,
                                    nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                    latestEventLog = disconnectLog,
                                    chunkTasks = chunkStates.toList()
                                )
                            )
                        }
                    }
                }

                is WorkerEvent.Finished -> {
                    val idx = event.chunk.chunkIndex
                    val activeAttempt = inFlightAttemptByChunk[idx] ?: event.chunk.attempt
                    if (idx in completedOrFailedChunkIndices || activeAttempt != event.chunk.attempt) {
                        // Stale completion from an attempt that was already cancelled & re-queued
                        continue
                    }

                    if (event.response.success) {
                        completedOrFailedChunkIndices.add(idx)
                        completedCount++
                        nodeResults.getOrPut(event.node.nodeId) { mutableListOf() }.add(
                            ChunkExecutionResult(event.chunk, event.node, event.response)
                        )
                        if (idx in chunkStates.indices) {
                            chunkStates[idx] = chunkStates[idx].copy(
                                status = TaskQueueItemStatus.COMPLETED,
                                attempt = event.chunk.attempt,
                                assignedNodeId = event.node.nodeId,
                                assignedNodeName = event.node.nodeName,
                                isRemoteNode = !event.node.isLocal,
                                elapsedMs = event.response.elapsedMs,
                                itemsProcessed = event.response.itemsProcessed,
                                errorMessage = null
                            )
                        }

                        val eventLog = "chunk #${event.chunk.chunkIndex + 1}/${chunks.size} -> ${event.node.nodeName} (${event.response.elapsedMs}ms)"
                        logs.add(eventLog)

                        onProgress(
                            TaskProgressSnapshot(
                                totalChunks = chunks.size,
                                completedChunks = completedCount,
                                failedChunks = failedCount,
                                nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                latestEventLog = eventLog,
                                chunkTasks = chunkStates.toList()
                            )
                        )
                    } else {
                        unhealthyNodeIds.add(event.node.nodeId)
                        workerHandles[event.node.nodeId]?.disconnectAndStealChunk()
                        val hasHealthyWorker = ensureFallbackWorkersIfNeeded()
                        val canDelegate = event.chunk.attempt < maxAttempts && hasHealthyWorker
                        if (canDelegate) {
                            val retryChunk = event.chunk.copy(attempt = event.chunk.attempt + 1)
                            inFlightAttemptByChunk[idx] = retryChunk.attempt
                            val retryLog = "chunk #${event.chunk.chunkIndex + 1} failed on ${event.node.nodeName}; re-queued to channel"
                            logs.add(retryLog)
                            if (idx in chunkStates.indices) {
                                chunkStates[idx] = chunkStates[idx].copy(
                                    status = TaskQueueItemStatus.PENDING,
                                    attempt = retryChunk.attempt,
                                    assignedNodeId = null,
                                    assignedNodeName = null,
                                    isRemoteNode = false,
                                    errorMessage = "Re-queued after ${event.node.nodeName} error"
                                )
                            }
                            taskChannel.send(retryChunk)
                            onProgress(
                                TaskProgressSnapshot(
                                    totalChunks = chunks.size,
                                    completedChunks = completedCount,
                                    failedChunks = failedCount,
                                    nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                    latestEventLog = retryLog,
                                    chunkTasks = chunkStates.toList()
                                )
                            )
                        } else {
                            completedOrFailedChunkIndices.add(idx)
                            failedCount++
                            nodeResults.getOrPut(event.node.nodeId) { mutableListOf() }.add(
                                ChunkExecutionResult(event.chunk, event.node, event.response)
                            )
                            if (idx in chunkStates.indices) {
                                chunkStates[idx] = chunkStates[idx].copy(
                                    status = TaskQueueItemStatus.FAILED,
                                    attempt = event.chunk.attempt,
                                    assignedNodeId = event.node.nodeId,
                                    assignedNodeName = event.node.nodeName,
                                    isRemoteNode = !event.node.isLocal,
                                    errorMessage = event.response.errorMessage ?: "Chunk RPC failure"
                                )
                            }
                            val failLog = "chunk #${event.chunk.chunkIndex + 1} FAILED on ${event.node.nodeName}: ${event.response.errorMessage}"
                            logs.add(failLog)
                            onProgress(
                                TaskProgressSnapshot(
                                    totalChunks = chunks.size,
                                    completedChunks = completedCount,
                                    failedChunks = failedCount,
                                    nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
                                    latestEventLog = failLog,
                                    chunkTasks = chunkStates.toList()
                                )
                            )
                            if (!hasHealthyWorker) {
                                failAllUnresolvedChunks("All cluster nodes failed or disconnected")
                            }
                        }
                    }
                }
            }

            if (completedOrFailedChunkIndices.size >= chunks.size) {
                disconnectObserverJob.cancel()
                taskChannel.close()
                eventChannel.close()
            }
        }

        disconnectObserverJob.cancel()
        val wallElapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        TaskDistributionOutcome(
            totalChunks = chunks.size,
            completedChunks = completedCount,
            wallElapsedMs = wallElapsed,
            nodeShards = buildNodeShards(podId, workloadType, activeNodes, nodeResults, chunks.size),
            eventLogs = logs,
            chunkTasks = chunkStates.toList()
        )
    }

    private fun buildNodeShards(
        podId: String,
        workloadType: WorkloadType,
        targetNodes: List<NodeTelemetry>,
        nodeResults: Map<String, List<ChunkExecutionResult>>,
        totalChunks: Int
    ): List<PodShard> {
        val allCompletedItems = nodeResults.values.flatten().sumOf { it.response.itemsProcessed }.coerceAtLeast(1L)
        return targetNodes.mapIndexedNotNull { index, node ->
            val list = nodeResults[node.nodeId].orEmpty()
            if (list.isEmpty()) return@mapIndexedNotNull null

            val succeeded = list.filter { it.response.success }
            val failed = list.filterNot { it.response.success }
            val totalItems = succeeded.sumOf { it.response.itemsProcessed }
            val totalElapsed = succeeded.sumOf { it.response.elapsedMs }.coerceAtLeast(1L)

            val aggregatedPrimary = when (workloadType) {
                WorkloadType.PRIME_SIEVE,
                WorkloadType.MONTE_CARLO_PI,
                WorkloadType.MATRIX_MATMUL -> succeeded.sumOf { it.response.primaryValue }
                WorkloadType.SHA256_BENCHMARK -> succeeded.maxOfOrNull { it.response.primaryValue } ?: 0.0
            }

            val aggregatedSecondary = when (workloadType) {
                WorkloadType.PRIME_SIEVE -> {
                    val maxP = succeeded.mapNotNull {
                        it.response.secondaryText.substringAfter("maxPrime=", "").toLongOrNull()
                    }.maxOrNull() ?: 0L
                    "maxPrime=$maxP"
                }
                WorkloadType.SHA256_BENCHMARK -> {
                    succeeded.maxByOrNull { it.response.primaryValue }?.response?.secondaryText.orEmpty()
                }
                else -> "${succeeded.size} chunk(s)"
            }

            val weightPct = if (totalItems > 0L) {
                ((totalItems.toDouble() / allCompletedItems.toDouble()) * 100.0).toInt().coerceIn(1, 100)
            } else {
                ((list.size.toDouble() / totalChunks.coerceAtLeast(1)) * 100.0).toInt().coerceIn(1, 100)
            }

            val throughput = if (totalItems > 0L) {
                val perSec = (totalItems * 1000.0) / totalElapsed.toDouble()
                formatRate(perSec, workloadType)
            } else ""

            val phase = when {
                succeeded.isNotEmpty() -> PodPhase.SUCCEEDED
                failed.isNotEmpty() -> PodPhase.FAILED
                else -> PodPhase.RUNNING
            }

            val spanSource = succeeded.ifEmpty { list }
            PodShard(
                shardId = "$podId-$index",
                nodeId = node.nodeId,
                nodeName = node.nodeName,
                isRemote = !node.isLocal,
                hostEndpoint = node.endpoint,
                weightPercent = weightPct,
                rangeStart = spanSource.minOf { it.chunk.rangeStart },
                rangeEnd = spanSource.maxOf { it.chunk.rangeEnd },
                iterations = spanSource.sumOf { it.chunk.iterations },
                phase = phase,
                elapsedMs = totalElapsed,
                itemsProcessed = totalItems,
                throughputLabel = throughput,
                primaryValue = aggregatedPrimary,
                secondaryText = aggregatedSecondary,
                errorMessage = failed.firstOrNull()?.response?.errorMessage,
                chunksCompleted = succeeded.size
            )
        }
    }

    private fun formatRate(perSec: Double, type: WorkloadType): String {
        val unit = when (type) {
            WorkloadType.PRIME_SIEVE -> "int/s"
            WorkloadType.MONTE_CARLO_PI -> "samples/s"
            WorkloadType.SHA256_BENCHMARK -> "H/s"
            WorkloadType.MATRIX_MATMUL -> "rows/s"
        }
        return when {
            perSec >= 1_000_000.0 -> "${"%.2f".format(perSec / 1_000_000.0)} M$unit"
            perSec >= 1_000.0 -> "${"%.1f".format(perSec / 1_000.0)} K$unit"
            else -> "${"%.0f".format(perSec)} $unit"
        }
    }
}
