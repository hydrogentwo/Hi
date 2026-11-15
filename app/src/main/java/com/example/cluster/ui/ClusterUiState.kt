package com.example.cluster.ui

import com.example.cluster.ai.AiCoreLocalState
import com.example.cluster.data.SavedClusterConfig
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeMetricSample
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.NsdStatus

data class AiCoreInferenceState(
    val isRunning: Boolean = false,
    val outputText: String = "",
    val targetNodeName: String = "",
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null
)

data class ClusterUiState(
    val config: SavedClusterConfig = SavedClusterConfig(
        nodeId = "init",
        nodeName = "droid-node",
        role = NodeRole.CONTROL_PLANE,
        clusterToken = "KM7443",
        lastPeerHost = "",
        lastPeerPort = 7443,
        daemonEnabled = false,
        autoJoinNsd = true
    ),
    val localNode: NodeTelemetry? = null,
    val remotePeers: List<NodeTelemetry> = emptyList(),
    val unreachablePeerIds: Set<String> = emptySet(),
    val discoveredPeers: List<DiscoveredPeer> = emptyList(),
    val isScanning: Boolean = false,
    val isAdvertising: Boolean = false,
    val nsdStatus: NsdStatus = NsdStatus.IDLE,
    val nsdError: String? = null,
    val isPairing: Boolean = false,
    val serverPort: Int = 7443,
    val pods: List<ClusterPod> = emptyList(),
    val clusterLogs: List<String> = emptyList(),
    val metricHistory: Map<String, List<NodeMetricSample>> = emptyMap(),
    val selectedNodeForSheet: NodeTelemetry? = null,
    val selectedPodForLogs: String? = null,
    val aiCoreLocal: AiCoreLocalState = AiCoreLocalState(),
    val aiCoreInference: AiCoreInferenceState = AiCoreInferenceState()
) {
    val allNodes: List<NodeTelemetry>
        get() = listOfNotNull(localNode) + remotePeers

    val unpairedDiscoveredPeers: List<DiscoveredPeer>
        get() = discoveredPeers.filter { dp ->
            remotePeers.none { rp ->
                (dp.nodeId.isNotEmpty() && rp.nodeId == dp.nodeId) ||
                    (rp.hostIp == dp.host && rp.port == dp.port)
            }
        }

    val onlinePeersCount: Int
        get() = remotePeers.count { it.nodeId !in unreachablePeerIds }

    val totalCpuCores: Int
        get() = (localNode?.cpuCores ?: 0) +
            remotePeers.filter { it.nodeId !in unreachablePeerIds }.sumOf { it.cpuCores }

    val totalRamMb: Long
        get() = (localNode?.totalRamMb ?: 0L) +
            remotePeers.filter { it.nodeId !in unreachablePeerIds }.sumOf { it.totalRamMb }

    val availRamMb: Long
        get() = (localNode?.availRamMb ?: 0L) +
            remotePeers.filter { it.nodeId !in unreachablePeerIds }.sumOf { it.availRamMb }

    val isUnifiedCluster: Boolean
        get() = onlinePeersCount > 0
}
