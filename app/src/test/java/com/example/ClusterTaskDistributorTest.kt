package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.cluster.compute.ClusterTaskDistributor
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.WorkloadType
import com.example.cluster.ui.ClusterUiState
import com.example.cluster.ui.WorkloadsScreen
import com.example.ui.theme.KubeMeshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClusterTaskDistributorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun testNode(id: String, name: String, isLocal: Boolean): NodeTelemetry = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = if (isLocal) NodeRole.CONTROL_PLANE else NodeRole.WORKER,
        hostIp = if (isLocal) "192.168.1.10" else "192.168.1.22",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = 25,
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
        uptimeSeconds = 120L,
        isLocal = isLocal
    )

    @Test
    fun `sliceJobIntoChunks covers contiguous non-overlapping ranges across all workload types`() {
        val distributor = ClusterTaskDistributor()

        // PRIME_SIEVE: inclusive integer ranges starting at 2 .. totalWork + 1
        val primeChunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 1_000L,
            nodeCount = 2,
            chunksPerNode = 4
        )
        assertEquals(8, primeChunks.size)
        assertEquals(2L, primeChunks.first().rangeStart)
        assertEquals(1_001L, primeChunks.last().rangeEnd)
        assertEquals(1_000L, primeChunks.sumOf { it.iterations })
        for (i in 1 until primeChunks.size) {
            assertEquals(primeChunks[i - 1].rangeEnd + 1L, primeChunks[i].rangeStart)
        }

        // MATRIX_MATMUL: exclusive row slices [start, end)
        val matrixChunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.MATRIX_MATMUL,
            totalWork = 320L,
            nodeCount = 2,
            chunksPerNode = 4
        )
        assertEquals(8, matrixChunks.size)
        assertEquals(0L, matrixChunks.first().rangeStart)
        assertEquals(320L, matrixChunks.last().rangeEnd)
        assertEquals(320L, matrixChunks.sumOf { it.iterations })
        for (i in 1 until matrixChunks.size) {
            assertEquals(matrixChunks[i - 1].rangeEnd, matrixChunks[i].rangeStart)
        }

        // MONTE_CARLO_PI & SHA256_BENCHMARK: inclusive [0 .. totalWork - 1]
        listOf(WorkloadType.MONTE_CARLO_PI, WorkloadType.SHA256_BENCHMARK).forEach { type ->
            val chunks = distributor.sliceJobIntoChunks(
                workloadType = type,
                totalWork = 10_005L,
                nodeCount = 2,
                chunksPerNode = 4
            )
            assertEquals(8, chunks.size)
            assertEquals(0L, chunks.first().rangeStart)
            assertEquals(10_004L, chunks.last().rangeEnd)
            assertEquals(10_005L, chunks.sumOf { it.iterations })
            for (i in 1 until chunks.size) {
                assertEquals(chunks[i - 1].rangeEnd + 1L, chunks[i].rangeStart)
            }
        }
    }

    @Test
    fun `distributeAndExecute steals work across channels so faster node processes more chunks`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val computeEngine = ComputeEngine()
        val fastNode = testNode("fast-1", "pixel-fast", isLocal = true)
        val slowNode = testNode("slow-2", "pixel-slow", isLocal = false)

        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 1_000L, // 2..1001 contains exact 168 primes
            nodeCount = 2,
            chunksPerNode = 4
        )
        val progressCounts = mutableListOf<Int>()

        val outcome = distributor.distributeAndExecute(
            podId = "pod-lb",
            podName = "prime-lb",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(fastNode, slowNode),
            chunks = chunks,
            executeChunk = { node, req ->
                if (node.nodeId == fastNode.nodeId) {
                    delay(5L)
                } else {
                    delay(65L)
                }
                Result.success(computeEngine.executeShard(req))
            },
            onProgress = { snapshot ->
                if (snapshot.latestEventLog != null) {
                    progressCounts.add(snapshot.completedChunks)
                }
            }
        )

        assertEquals(8, outcome.totalChunks)
        assertEquals(8, outcome.completedChunks)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), progressCounts)

        val fastShard = outcome.nodeShards.first { it.nodeId == fastNode.nodeId }
        val slowShard = outcome.nodeShards.firstOrNull { it.nodeId == slowNode.nodeId }
        val slowCount = slowShard?.chunksCompleted ?: 0
        assertTrue(
            "Expected fast node (${fastShard.chunksCompleted}) to claim more chunks than slow node ($slowCount)",
            fastShard.chunksCompleted > slowCount
        )

        val totalPrimes = outcome.nodeShards.sumOf { it.primaryValue.toLong() }
        val totalItems = outcome.nodeShards.sumOf { it.itemsProcessed }
        assertEquals(168L, totalPrimes)
        assertEquals(1_000L, totalItems)
    }

    @Test
    fun `distributeAndExecute requeues failed chunk on channel for healthy peer completion`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val computeEngine = ComputeEngine()
        val healthyNode = testNode("healthy-1", "control-plane", isLocal = true)
        val flakyNode = testNode("flaky-2", "flaky-worker", isLocal = false)

        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 1_000L,
            nodeCount = 2,
            chunksPerNode = 4
        )

        val outcome = distributor.distributeAndExecute(
            podId = "pod-failover",
            podName = "prime-failover",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(healthyNode, flakyNode),
            chunks = chunks,
            executeChunk = { node, req ->
                if (node.nodeId == flakyNode.nodeId) {
                    Result.failure(IllegalStateException("Simulated socket timeout"))
                } else {
                    delay(4L)
                    Result.success(computeEngine.executeShard(req))
                }
            }
        )

        assertEquals(8, outcome.totalChunks)
        assertEquals(8, outcome.completedChunks)
        val healthyShard = outcome.nodeShards.first { it.nodeId == healthyNode.nodeId }
        assertEquals(8, healthyShard.chunksCompleted)
        assertEquals(PodPhase.SUCCEEDED, healthyShard.phase)
        assertEquals(168L, healthyShard.primaryValue.toLong())
        assertTrue(outcome.eventLogs.any { it.contains("re-queued to channel") })
    }

    @Test
    fun `WorkloadsScreen renders live Channel Queue progress bar and chunk counts for running pod`() {
        val local = testNode("n1", "master-phone", isLocal = true)
        val remote = testNode("n2", "worker-phone", isLocal = false)

        val runningPod = ClusterPod(
            podId = "ch01",
            name = "prime-sieve-ch01",
            workloadType = WorkloadType.PRIME_SIEVE,
            schedulingPolicy = SchedulingPolicy.PROPORTIONAL_SPLIT,
            phase = PodPhase.RUNNING,
            requestedCores = 16,
            requestedMemMb = 256,
            intensityLabel = "1.6M integers",
            createdAtMs = 1_000L,
            aggregatedResult = "Channel pipeline: 3/8 chunks complete",
            shards = listOf(
                PodShard(
                    shardId = "ch01-0",
                    nodeId = local.nodeId,
                    nodeName = local.nodeName,
                    isRemote = false,
                    hostEndpoint = local.endpoint,
                    weightPercent = 66,
                    rangeStart = 2L,
                    rangeEnd = 800_000L,
                    iterations = 800_000L,
                    phase = PodPhase.SUCCEEDED,
                    chunksCompleted = 2
                ),
                PodShard(
                    shardId = "ch01-1",
                    nodeId = remote.nodeId,
                    nodeName = remote.nodeName,
                    isRemote = true,
                    hostEndpoint = remote.endpoint,
                    weightPercent = 34,
                    rangeStart = 800_001L,
                    rangeEnd = 1_600_001L,
                    iterations = 800_000L,
                    phase = PodPhase.SUCCEEDED,
                    chunksCompleted = 1
                )
            ),
            logs = listOf("[12:00:00] channel-queue: Sliced job into 8 chunks"),
            yamlManifest = "kind: Pod",
            totalChunks = 8,
            completedChunks = 3
        )

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                WorkloadsScreen(
                    uiState = ClusterUiState(
                        localNode = local,
                        remotePeers = listOf(remote),
                        pods = listOf(runningPod)
                    ),
                    onDeployPod = { _, _, _, _ -> },
                    onInspectPod = {}
                )
            }
        }

        composeRule.onNodeWithTag("workloads_list")
            .performScrollToNode(hasTestTag("pod_card_ch01"))
        composeRule.onNodeWithTag("pod_chunk_progress_ch01", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Channel Queue: 3/8 chunks", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("master-phone (66% · 2ch)", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("worker-phone (34% · 1ch)", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
