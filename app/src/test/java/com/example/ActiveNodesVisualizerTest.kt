package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.cluster.model.NodeMetricSample
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.ui.ActiveClusterNodesVisualizer
import com.example.ui.theme.KubeMeshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveNodesVisualizerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun testNode(
        id: String,
        name: String,
        role: NodeRole,
        cpuPct: Int,
        usedRamMb: Long,
        totalRamMb: Long,
        batteryPct: Int,
        isCharging: Boolean,
        isLocal: Boolean
    ) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = role,
        hostIp = if (isLocal) "192.168.1.10" else "192.168.1.22",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = cpuPct,
        cpuArch = "arm64-v8a",
        totalRamMb = totalRamMb,
        availRamMb = totalRamMb - usedRamMb,
        totalStorageGb = 128f,
        availStorageGb = 64f,
        batteryPercent = batteryPct,
        isCharging = isCharging,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Pixel Test",
        activePods = 1,
        uptimeSeconds = 300L,
        rttMs = if (isLocal) 0L else 4L,
        isLocal = isLocal
    )

    @Test
    fun `renders real-time CPU RAM and battery gauges for active cluster nodes`() {
        val local = testNode(
            id = "n1",
            name = "pixel-master",
            role = NodeRole.CONTROL_PLANE,
            cpuPct = 37,
            usedRamMb = 4096,
            totalRamMb = 8192,
            batteryPct = 84,
            isCharging = true,
            isLocal = true
        )
        val worker = testNode(
            id = "n2",
            name = "galaxy-worker",
            role = NodeRole.WORKER,
            cpuPct = 62,
            usedRamMb = 3072,
            totalRamMb = 6144,
            batteryPct = 49,
            isCharging = false,
            isLocal = false
        )
        val history = mapOf(
            "n1" to listOf(
                NodeMetricSample(30, 48, 84),
                NodeMetricSample(37, 50, 84)
            ),
            "n2" to listOf(
                NodeMetricSample(55, 49, 50),
                NodeMetricSample(62, 50, 49)
            )
        )

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ActiveClusterNodesVisualizer(
                        nodes = listOf(local, worker),
                        unreachablePeerIds = emptySet(),
                        metricHistory = history,
                        onNodeClick = {},
                        onPairWorkerClick = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("active_nodes_visualizer").assertIsDisplayed()
        composeRule.onNodeWithText("ACTIVE CLUSTER NODES (2/2)").assertIsDisplayed()

        // Node 1: CPU 37%, RAM 50% (4096 MB), Battery 84%
        composeRule.onNodeWithTag("visualizer_node_card_n1").assertIsDisplayed()
        composeRule.onNodeWithTag("visualizer_node_card_n1", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("pixel-master")))
        composeRule.onNodeWithTag("gauge_cpu_n1", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("37%")))
        composeRule.onNodeWithTag("gauge_ram_n1", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("4096 MB")))
        composeRule.onNodeWithTag("gauge_battery_n1", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("84%")))

        // Node 2: CPU 62%, RAM 50% (3072 MB), Battery 49%
        composeRule.onNodeWithTag("visualizer_node_card_n2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("visualizer_node_card_n2", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("galaxy-worker")))
        composeRule.onNodeWithTag("gauge_cpu_n2", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("62%")))
        composeRule.onNodeWithTag("gauge_ram_n2", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("3072 MB")))
        composeRule.onNodeWithTag("gauge_battery_n2", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("49%")))

        composeRule.onNodeWithTag("toggle_node_trends_chip").performScrollTo().performClick()
    }
}
