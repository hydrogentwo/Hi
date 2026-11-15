package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.cluster.data.SavedClusterConfig
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.NsdStatus
import com.example.cluster.network.NsdDiscoveryManager
import com.example.cluster.ui.ClusterScreen
import com.example.cluster.ui.ClusterUiState
import com.example.cluster.ui.MeshConnectScreen
import com.example.ui.theme.KubeMeshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NsdDiscoveryManagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleLocalNode() = NodeTelemetry(
        nodeId = "local-1",
        nodeName = "pixel-master",
        role = NodeRole.CONTROL_PLANE,
        hostIp = "192.168.1.10",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = 24,
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
        deviceModel = "Pixel 9",
        activePods = 0,
        uptimeSeconds = 120L,
        isLocal = true
    )

    @Test
    fun `parsePeerFromAttributes decodes TXT records accurately`() {
        val attrs = mapOf(
            NsdDiscoveryManager.ATTR_NODE_ID to "worker-xyz".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_NODE_NAME to "galaxy-s24".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_ROLE to "WORKER".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_CORES to "8".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_RAM_MB to "12288".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_MODEL to "Galaxy S24".toByteArray(StandardCharsets.UTF_8)
        )

        val peer = NsdDiscoveryManager.parsePeerFromAttributes(
            serviceName = "KubeMesh-galaxy-s24-work",
            host = "192.168.1.44",
            port = 7443,
            attributes = attrs
        )

        assertEquals("worker-xyz", peer.nodeId)
        assertEquals("galaxy-s24", peer.nodeName)
        assertEquals("galaxy-s24", peer.displayTitle)
        assertEquals(NodeRole.WORKER, peer.role)
        assertEquals(8, peer.cpuCores)
        assertEquals(12288L, peer.totalRamMb)
        assertEquals("Galaxy S24", peer.deviceModel)
        assertEquals("192.168.1.44", peer.host)
        assertEquals(7443, peer.port)
    }

    @Test
    fun `parsePeerFromAttributes falls back gracefully on malformed or missing attributes`() {
        val attrs = mapOf<String, ByteArray?>(
            NsdDiscoveryManager.ATTR_ROLE to "UNKNOWN_ROLE".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_CORES to "nan".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_RAM_MB to null
        )

        val peer = NsdDiscoveryManager.parsePeerFromAttributes(
            serviceName = "KubeMesh-node-1234",
            host = "192.168.1.77",
            port = 7444,
            attributes = attrs
        )

        assertEquals("", peer.nodeId)
        assertEquals("KubeMesh-node-1234", peer.displayTitle)
        assertNull(peer.role)
        assertEquals(0, peer.cpuCores)
        assertEquals(0L, peer.totalRamMb)
    }

    @Test
    fun `unpairedDiscoveredPeers filters peers already unified by nodeId or host and port`() {
        val pairedById = DiscoveredPeer(
            serviceName = "KubeMesh-a",
            host = "192.168.1.30",
            port = 7443,
            nodeId = "remote-1",
            nodeName = "worker-a"
        )
        val pairedByEndpoint = DiscoveredPeer(
            serviceName = "KubeMesh-b",
            host = "192.168.1.31",
            port = 7443,
            nodeId = "",
            nodeName = "worker-b"
        )
        val freshPeer = DiscoveredPeer(
            serviceName = "KubeMesh-c",
            host = "192.168.1.55",
            port = 7443,
            nodeId = "remote-3",
            nodeName = "worker-c"
        )

        val remote = sampleLocalNode().copy(
            nodeId = "remote-1",
            nodeName = "worker-a",
            hostIp = "192.168.1.30",
            port = 7443,
            isLocal = false
        )
        val remote2 = sampleLocalNode().copy(
            nodeId = "remote-2",
            nodeName = "worker-b",
            hostIp = "192.168.1.31",
            port = 7443,
            isLocal = false
        )

        val state = ClusterUiState(
            localNode = sampleLocalNode(),
            remotePeers = listOf(remote, remote2),
            discoveredPeers = listOf(pairedById, pairedByEndpoint, freshPeer)
        )

        assertEquals(listOf(freshPeer), state.unpairedDiscoveredPeers)
    }

    @Test
    fun `ClusterScreen shows NSD detected peer card and invokes join callback on tap`() {
        val detected = DiscoveredPeer(
            serviceName = "KubeMesh-oneplus-abcd",
            host = "192.168.1.88",
            port = 7443,
            nodeId = "peer-88",
            nodeName = "oneplus-worker",
            role = NodeRole.WORKER,
            cpuCores = 8,
            totalRamMb = 8192,
            deviceModel = "OnePlus 12"
        )
        var joinedPeer: DiscoveredPeer? = null

        val uiState = ClusterUiState(
            config = SavedClusterConfig(
                nodeId = "local-1",
                nodeName = "pixel-master",
                role = NodeRole.CONTROL_PLANE,
                clusterToken = "KM7443",
                lastPeerHost = "",
                lastPeerPort = 7443,
                daemonEnabled = false,
                autoJoinNsd = true
            ),
            localNode = sampleLocalNode(),
            discoveredPeers = listOf(detected),
            isScanning = true,
            isAdvertising = true,
            nsdStatus = NsdStatus.DISCOVERING
        )

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                ClusterScreen(
                    uiState = uiState,
                    onNavigateToConnect = {},
                    onNavigateToWorkloads = {},
                    onSelectNode = {},
                    onDisconnectPeer = {},
                    onPairDiscoveredPeer = { joinedPeer = it }
                )
            }
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("cluster_nsd_detected_card"))
        composeRule.onNodeWithTag("cluster_nsd_detected_card").assertIsDisplayed()
        composeRule.onNodeWithText("oneplus-worker").assertIsDisplayed()
        composeRule.onNodeWithText("Worker Node · 8 vCPU · 8.0 GB · OnePlus 12").assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("cluster_nsd_join_192.168.1.88"))
        composeRule.onNodeWithTag("cluster_nsd_join_192.168.1.88").performScrollTo().performClick()

        assertEquals(detected, joinedPeer)
    }

    @Test
    fun `MeshConnectScreen displays NSD status pill auto join switch and shared token input`() {
        var autoJoinToggled: Boolean? = null
        val detected = DiscoveredPeer(
            serviceName = "KubeMesh-moto-9999",
            host = "192.168.1.99",
            port = 7443,
            nodeId = "peer-99",
            nodeName = "moto-worker",
            role = NodeRole.WORKER,
            cpuCores = 8,
            totalRamMb = 6144,
            deviceModel = "Edge 50"
        )
        val uiState = ClusterUiState(
            config = SavedClusterConfig(
                nodeId = "local-1",
                nodeName = "pixel-master",
                role = NodeRole.CONTROL_PLANE,
                clusterToken = "KM7443",
                lastPeerHost = "",
                lastPeerPort = 7443,
                daemonEnabled = false,
                autoJoinNsd = true
            ),
            localNode = sampleLocalNode(),
            discoveredPeers = listOf(detected),
            isScanning = true,
            isAdvertising = true,
            nsdStatus = NsdStatus.DISCOVERING
        )

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                MeshConnectScreen(
                    uiState = uiState,
                    onSetRole = {},
                    onRegenerateToken = {},
                    onUpdateClusterToken = {},
                    onToggleScan = {},
                    onSetAutoJoinNsd = { autoJoinToggled = it },
                    onPairWithPeer = { _, _, _ -> },
                    onSetDaemonEnabled = {},
                    onShowMessage = {}
                )
            }
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("custom_token_input"))
        composeRule.onNodeWithTag("custom_token_input").performScrollTo().assertIsDisplayed()

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("nsd_status_pill"))
        composeRule.onNodeWithTag("nsd_status_pill").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ADVERTISING · SCANNING").assertIsDisplayed()

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("auto_join_nsd_switch"))
        composeRule.onNodeWithTag("auto_join_nsd_switch").performScrollTo().performClick()
        assertEquals(false, autoJoinToggled)

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("pair_discovered_192.168.1.99"))
        composeRule.onNodeWithTag("pair_discovered_192.168.1.99").performScrollTo().assertIsDisplayed()
    }
}
