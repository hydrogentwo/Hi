package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.cluster.compute.ClusterTaskDistributor
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PodPhase
import com.example.cluster.model.QueuedChunkState
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.TaskQueueItemStatus
import com.example.cluster.model.WorkloadType
import com.example.cluster.ui.DistributedTaskQueueCard
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
class DistributedTaskQueueCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleNode(id: String, name: String, isLocal: Boolean) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = if (isLocal) NodeRole.CONTROL_PLANE else NodeRole.WORKER,
        hostIp = if (isLocal) "192.168.1.10" else "192.168.1.25",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = 30,
        cpuArch = "arm64-v8a",
        totalRamMb = 8192,
        availRamMb = 4096,
        totalStorageGb = 128f,
        availStorageGb = 64f,
        batteryPercent = 85,
        isCharging = true,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Pixel",
        activePods = 1,
        uptimeSeconds = 120L,
        isLocal = isLocal
    )

    private fun buildQueuePod(
        podId: String = "qpod1",
        podName: String = "prime-queue",
        tasks: List<QueuedChunkState>
    ) = ClusterPod(
        podId = podId,
        name = podName,
        workloadType = WorkloadType.PRIME_SIEVE,
        schedulingPolicy = SchedulingPolicy.PROPORTIONAL_SPLIT,
        phase = PodPhase.RUNNING,
        requestedCores = 16,
        requestedMemMb = 256,
        intensityLabel = "1.6M integers",
        createdAtMs = 1_000L,
        shards = emptyList(),
        logs = emptyList(),
        yamlManifest = "kind: Pod",
        totalChunks = tasks.size,
        completedChunks = tasks.count { it.status == TaskQueueItemStatus.COMPLETED },
        chunkTasks = tasks
    )

    @Test
    fun `ClusterTaskDistributor emits PENDING to IN_PROGRESS to COMPLETED chunk states`() = runBlocking {
        val distributor = ClusterTaskDistributor()
        val computeEngine = ComputeEngine()
        val local = sampleNode("n1", "master", true)
        val chunks = distributor.sliceJobIntoChunks(
            workloadType = WorkloadType.PRIME_SIEVE,
            totalWork = 400L,
            nodeCount = 1,
            chunksPerNode = 4
        )

        var sawInProgress = false
        var sawPendingDuringExecution = false

        val outcome = distributor.distributeAndExecute(
            podId = "p-track",
            podName = "prime-track",
            workloadType = WorkloadType.PRIME_SIEVE,
            clusterToken = "KM7443",
            targetNodes = listOf(local),
            chunks = chunks,
            executeChunk = { _, req ->
                delay(8L)
                Result.success(computeEngine.executeShard(req))
            },
            onProgress = { snapshot ->
                if (snapshot.chunkTasks.any { it.status == TaskQueueItemStatus.IN_PROGRESS }) {
                    sawInProgress = true
                }
                if (snapshot.chunkTasks.any { it.status == TaskQueueItemStatus.PENDING }) {
                    sawPendingDuringExecution = true
                }
            }
        )

        assertTrue("Expected IN_PROGRESS chunk state during execution", sawInProgress)
        assertTrue("Expected PENDING chunk states while queue drains", sawPendingDuringExecution)
        assertEquals(4, outcome.chunkTasks.size)
        assertTrue(outcome.chunkTasks.all { it.status == TaskQueueItemStatus.COMPLETED })
        assertTrue(outcome.chunkTasks.all { it.assignedNodeName == "master" })
    }

    @Test
    fun `DistributedTaskQueueCard displays idle empty state when no pods exist`() {
        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DistributedTaskQueueCard(
                        pods = emptyList(),
                        onInspectPod = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("queue_empty_state").assertIsDisplayed()
        composeRule.onNodeWithText("Task Queue Idle").assertIsDisplayed()
        composeRule.onNodeWithText("All (0)").assertIsDisplayed()
    }

    @Test
    fun `DistributedTaskQueueCard tracks Pending InProgress Completed counts filters and row clicks`() {
        val tasks = listOf(
            QueuedChunkState(
                podId = "p1",
                podName = "prime-1",
                workloadType = WorkloadType.PRIME_SIEVE,
                chunkIndex = 0,
                totalChunks = 4,
                rangeStart = 2L,
                rangeEnd = 250_000L,
                iterations = 250_000L,
                status = TaskQueueItemStatus.COMPLETED,
                assignedNodeId = "n1",
                assignedNodeName = "pixel-master",
                elapsedMs = 42L,
                itemsProcessed = 250_000L
            ),
            QueuedChunkState(
                podId = "p1",
                podName = "prime-1",
                workloadType = WorkloadType.PRIME_SIEVE,
                chunkIndex = 1,
                totalChunks = 4,
                rangeStart = 250_001L,
                rangeEnd = 500_000L,
                iterations = 250_000L,
                status = TaskQueueItemStatus.IN_PROGRESS,
                assignedNodeId = "n2",
                assignedNodeName = "pixel-worker",
                isRemoteNode = true
            ),
            QueuedChunkState(
                podId = "p1",
                podName = "prime-1",
                workloadType = WorkloadType.PRIME_SIEVE,
                chunkIndex = 2,
                totalChunks = 4,
                rangeStart = 500_001L,
                rangeEnd = 750_000L,
                iterations = 250_000L,
                status = TaskQueueItemStatus.PENDING
            ),
            QueuedChunkState(
                podId = "p1",
                podName = "prime-1",
                workloadType = WorkloadType.PRIME_SIEVE,
                chunkIndex = 3,
                totalChunks = 4,
                rangeStart = 750_001L,
                rangeEnd = 1_000_001L,
                iterations = 250_000L,
                status = TaskQueueItemStatus.PENDING,
                attempt = 2,
                errorMessage = "Re-queued after pixel-worker error"
            )
        )
        val pod = buildQueuePod(podId = "p1", podName = "prime-1", tasks = tasks)
        var inspectedPodId: String? = null

        composeRule.setContent {
            KubeMeshTheme(darkTheme = false) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DistributedTaskQueueCard(
                        pods = listOf(pod),
                        onInspectPod = { inspectedPodId = it }
                    )
                }
            }
        }

        // Verify summary bucket counts: Pending=2, In Progress=1, Completed=1, All=4
        composeRule.onNodeWithText("All (4)").assertIsDisplayed()
        composeRule.onNode(hasTestTag("queue_filter_pending") and hasText("2")).assertIsDisplayed()
        composeRule.onNode(hasTestTag("queue_filter_in_progress") and hasText("1")).assertIsDisplayed()
        composeRule.onNode(hasTestTag("queue_filter_completed") and hasText("1")).assertIsDisplayed()

        // Filter to In Progress only
        composeRule.onNodeWithTag("queue_filter_in_progress").performClick()
        composeRule.onNodeWithTag("queue_item_p1_1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("queue_item_p1_0").assertDoesNotExist()
        composeRule.onNodeWithTag("queue_item_p1_2").assertDoesNotExist()

        // Filter to Pending only
        composeRule.onNodeWithTag("queue_filter_pending").performScrollTo().performClick()
        composeRule.onNodeWithTag("queue_item_p1_2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("queue_item_p1_3").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("queue_item_p1_1").assertDoesNotExist()

        // Filter to Completed only and click item to inspect pod
        composeRule.onNodeWithTag("queue_filter_completed").performScrollTo().performClick()
        composeRule.onNodeWithTag("queue_item_p1_0").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("queue_item_p1_0").performClick()
        assertEquals("p1", inspectedPodId)
    }

    @Test
    fun `DistributedTaskQueueCard supports expanding more than 6 queued items`() {
        val tasks = (0 until 8).map { idx ->
            QueuedChunkState(
                podId = "p8",
                podName = "matrix-8",
                workloadType = WorkloadType.MATRIX_MATMUL,
                chunkIndex = idx,
                totalChunks = 8,
                rangeStart = idx * 40L,
                rangeEnd = (idx + 1) * 40L,
                iterations = 40L,
                status = if (idx < 2) TaskQueueItemStatus.COMPLETED else TaskQueueItemStatus.PENDING,
                assignedNodeName = if (idx < 2) "master" else null,
                elapsedMs = if (idx < 2) 15L else 0L
            )
        }
        val pod = buildQueuePod(podId = "p8", podName = "matrix-8", tasks = tasks)

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DistributedTaskQueueCard(
                        pods = listOf(pod),
                        onInspectPod = {}
                    )
                }
            }
        }

        // First 6 sorted items visible, 7th & 8th (COMPLETED chunk #1) collapsed initially
        composeRule.onNodeWithTag("queue_toggle_expand_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("queue_item_p8_1").assertDoesNotExist()

        composeRule.onNodeWithTag("queue_toggle_expand_btn").performClick()
        composeRule.onNodeWithTag("queue_item_p8_1").performScrollTo().assertIsDisplayed()
    }
}
