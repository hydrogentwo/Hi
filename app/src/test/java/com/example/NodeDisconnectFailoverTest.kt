package com.example

import com.example.cluster.compute.ClusterTaskDistributor
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.TaskQueueItemStatus
import com.example.cluster.model.WorkloadType
import com.example.cluster.network.ClusterClient
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class NodeDisconnectFailoverTest {

    private fun testNode(id: String, name: String, isLocal: Boolean) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = if (isLocal) NodeRole.CONTROL_PLANE else NodeRole.WORKER,
        hostIp = if (isLocal) "192.168.1.10" else "192.168.1.25",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = 20,
        cpuArch = "arm64-v8a",
        totalRamMb = 8192,
        availRamMb = 4096,
        totalStorageGb = 128f,
        availStorageGb = 64f,
        batteryPercent = 90,
        isCharging = true,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Pixel",
        activePods = 1,
        uptimeSeconds = 100L,
        isLocal = isLocal
    )

    @Test
    fun `unexpected node disconnect cancels hung chunk and requeues pending work to healthy peer`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val computeEngine = ComputeEngine()
        val master = testNode("master-1", "pixel-master", isLocal = true)
        val worker = testNode("worker-2", "pixel-worker", isLocal = false)

        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 1_000L, // exact 168 primes in 2..1001
            nodeCount = 2,
            chunksPerNode = 4
        )
        val disconnectedFlow = MutableStateFlow<Set<String>>(emptySet())
        val workerStartedChunk = CompletableDeferred<Unit>()

        val triggerDisconnectJob = launch {
            workerStartedChunk.await()
            delay(25L)
            disconnectedFlow.value = setOf(worker.nodeId)
        }

        val startMs = System.currentTimeMillis()
        val outcome = distributor.distributeAndExecute(
            podId = "pod-dc",
            podName = "prime-dc",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(master, worker),
            chunks = chunks,
            executeChunk = { node, req ->
                if (node.nodeId == worker.nodeId) {
                    workerStartedChunk.complete(Unit)
                    // Simulate hung TCP socket on dead worker
                    delay(15_000L)
                } else {
                    delay(10L)
                }
                Result.success(computeEngine.executeShard(req))
            },
            disconnectedNodeIds = disconnectedFlow
        )
        triggerDisconnectJob.cancel()
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue("Expected fast failover without waiting 15s timeout, took ${elapsedMs}ms", elapsedMs < 3_000L)
        assertEquals(8, outcome.totalChunks)
        assertEquals(8, outcome.completedChunks)
        assertTrue(outcome.chunkTasks.all { it.status == TaskQueueItemStatus.COMPLETED })
        assertTrue(outcome.chunkTasks.all { it.assignedNodeId == master.nodeId })
        assertTrue(outcome.chunkTasks.any { it.attempt >= 2 })

        val totalPrimes = outcome.nodeShards.sumOf { it.primaryValue.toLong() }
        assertEquals(168L, totalPrimes)
        assertTrue(outcome.eventLogs.any { it.contains("disconnected") && it.contains("re-queued") })
    }

    @Test
    fun `offloaded job activates fallback local node when sole worker disconnects unexpectedly`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val computeEngine = ComputeEngine()
        val master = testNode("master-1", "pixel-master", isLocal = true)
        val worker = testNode("worker-2", "pixel-worker", isLocal = false)

        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 1_000L,
            nodeCount = 1,
            chunksPerNode = 4
        )
        val disconnectedFlow = MutableStateFlow<Set<String>>(emptySet())
        val workerStarted = CompletableDeferred<Unit>()

        launch {
            workerStarted.await()
            delay(20L)
            disconnectedFlow.value = setOf(worker.nodeId)
        }

        val outcome = distributor.distributeAndExecute(
            podId = "pod-offload",
            podName = "prime-offload",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(worker),
            chunks = chunks,
            executeChunk = { node, req ->
                if (node.nodeId == worker.nodeId) {
                    workerStarted.complete(Unit)
                    delay(15_000L)
                } else {
                    delay(5L)
                }
                Result.success(computeEngine.executeShard(req))
            },
            disconnectedNodeIds = disconnectedFlow,
            fallbackNodesProvider = { listOf(master) }
        )

        assertEquals(4, outcome.totalChunks)
        assertEquals(4, outcome.completedChunks)
        val masterShard = outcome.nodeShards.first { it.nodeId == master.nodeId }
        assertEquals(168L, masterShard.primaryValue.toLong())
        assertTrue(outcome.eventLogs.any { it.contains("Failover: activated pixel-master") })
    }

    @Test
    fun `total cluster disconnect drains pending queue cleanly without deadlock`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val master = testNode("master-1", "pixel-master", isLocal = true)
        val worker = testNode("worker-2", "pixel-worker", isLocal = false)

        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 400L,
            nodeCount = 2,
            chunksPerNode = 4
        )
        val disconnectedFlow = MutableStateFlow<Set<String>>(emptySet())
        val started = CompletableDeferred<Unit>()

        launch {
            started.await()
            delay(25L)
            disconnectedFlow.value = setOf(master.nodeId, worker.nodeId)
        }

        val outcome = distributor.distributeAndExecute(
            podId = "pod-loss",
            podName = "prime-loss",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(master, worker),
            chunks = chunks,
            executeChunk = { _, _ ->
                started.complete(Unit)
                delay(15_000L)
                Result.failure(IllegalStateException("Unreachable"))
            },
            disconnectedNodeIds = disconnectedFlow
        )

        assertEquals(8, outcome.totalChunks)
        assertEquals(0, outcome.completedChunks)
        assertEquals(8, outcome.chunkTasks.count { it.status == TaskQueueItemStatus.FAILED })
    }

    @Test
    fun `ClusterClient executeRemoteShard aborts immediately when worker job is cancelled`() = runBlocking {
        val client = ClusterClient(Moshi.Builder().build())
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        val accepted = CompletableDeferred<Unit>()
        val keepRunning = AtomicBoolean(true)

        val acceptThread = Thread {
            runCatching {
                val sock = serverSocket.accept()
                accepted.complete(Unit)
                while (keepRunning.get()) {
                    Thread.sleep(50L)
                }
                sock.close()
            }
        }.apply { start() }

        try {
            val job = launch {
                client.executeRemoteShard(
                    host = "127.0.0.1",
                    port = port,
                    request = ShardExecuteRequest(
                        clusterToken = "KM7443",
                        podId = "p-cancel",
                        podName = "cancel-test",
                        workloadId = WorkloadType.PRIME_SIEVE.id,
                        rangeStart = 2L,
                        rangeEnd = 100L,
                        iterations = 99L
                    )
                )
            }
            accepted.await()
            val t0 = System.currentTimeMillis()
            job.cancel()
            job.join()
            val cancelDuration = System.currentTimeMillis() - t0
            assertTrue("Expected immediate socket cancellation, took ${cancelDuration}ms", cancelDuration < 1_000L)
        } finally {
            keepRunning.set(false)
            runCatching { serverSocket.close() }
            acceptThread.join(1_000L)
        }
    }
}
