package com.example.cluster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.NsdStatus
import com.example.cluster.model.PodPhase
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterScreen(
    uiState: ClusterUiState,
    onNavigateToConnect: () -> Unit,
    onNavigateToWorkloads: () -> Unit,
    onSelectNode: (NodeTelemetry?) -> Unit,
    onDisconnectPeer: (String) -> Unit,
    onPairDiscoveredPeer: (DiscoveredPeer) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasRunningPod = uiState.pods.any { it.phase == PodPhase.RUNNING }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ClusterHeroBanner(
                uiState = uiState,
                onConnectClick = onNavigateToConnect
            )
        }

        item {
            MeshTopologyCard(
                localNode = uiState.localNode,
                remotePeers = uiState.remotePeers,
                unreachablePeerIds = uiState.unreachablePeerIds,
                hasRunningPod = hasRunningPod,
                onPairSlotClick = onNavigateToConnect
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNavigateToWorkloads,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cluster_deploy_pod_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Run Pod")
                }
                OutlinedButton(
                    onClick = onNavigateToConnect,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cluster_connect_node_btn"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddLink,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (uiState.isUnifiedCluster) "Manage Mesh" else "Add Worker")
                }
            }
        }

        if (uiState.unpairedDiscoveredPeers.isNotEmpty()) {
            item {
                NsdDetectedPeersBanner(
                    peers = uiState.unpairedDiscoveredPeers,
                    isPairing = uiState.isPairing,
                    onJoinPeer = onPairDiscoveredPeer
                )
            }
        } else if (uiState.isScanning || uiState.nsdStatus == NsdStatus.FAILED) {
            item {
                NsdLiveStatusStrip(
                    nsdStatus = uiState.nsdStatus,
                    isAdvertising = uiState.isAdvertising,
                    errorText = uiState.nsdError,
                    onManageMesh = onNavigateToConnect
                )
            }
        }

        item {
            ActiveClusterNodesVisualizer(
                nodes = uiState.allNodes,
                unreachablePeerIds = uiState.unreachablePeerIds,
                metricHistory = uiState.metricHistory,
                onNodeClick = { onSelectNode(it) },
                onPairWorkerClick = onNavigateToConnect
            )
        }

        item {
            UnifiedStorageCard(uiState)
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    val sheetNode = uiState.selectedNodeForSheet
    if (sheetNode != null) {
        ModalBottomSheet(
            onDismissRequest = { onSelectNode(null) },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            NodeDetailSheetContent(
                node = sheetNode,
                isOffline = !sheetNode.isLocal && sheetNode.nodeId in uiState.unreachablePeerIds,
                onDisconnect = {
                    onDisconnectPeer(sheetNode.nodeId)
                    onSelectNode(null)
                }
            )
        }
    }
}

@Composable
private fun UnifiedStorageCard(uiState: ClusterUiState) {
    val totalStorage = uiState.allNodes.sumOf { it.totalStorageGb.toDouble() }
    val availStorage = uiState.allNodes.sumOf { it.availStorageGb.toDouble() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "Cluster Ephemeral Storage",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Pooled scratch capacity across ${uiState.allNodes.size} node(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "${"%.1f".format(availStorage)} / ${"%.0f".format(totalStorage)} GB",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )
        }
    }
}

@Composable
private fun NodeDetailSheetContent(
    node: NodeTelemetry,
    isOffline: Boolean,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = node.nodeName,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "${node.deviceModel} · ${node.androidVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                label = if (isOffline) "OFFLINE" else "READY",
                color = if (isOffline) RoseAlert else EmeraldReady
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SpecRow("Kubernetes Role", node.role.kubeRoleLabel)
        SpecRow("LAN Endpoint", node.endpoint)
        SpecRow("CPU Cores / ABI", "${node.cpuCores} vCPU (${node.cpuArch})")
        SpecRow("Memory Allocatable", "${node.availRamMb} MB free / ${node.totalRamMb} MB total")
        SpecRow("Ephemeral Storage", "${"%.1f".format(node.availStorageGb)} GB / ${"%.1f".format(node.totalStorageGb)} GB")
        SpecRow("Thermal / Power", "${node.thermalStatusLabel} · ${node.batteryPercent}% ${if (node.isCharging) "(Charging)" else "(Battery)"}")
        SpecRow("AICore / Gemini Nano", AiCoreStatus.fromWire(node.aiCoreStatus).label)
        SpecRow("Heartbeat RTT", if (node.isLocal) "0 ms (loopback)" else "${node.rttMs} ms")

        if (!node.isLocal) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("node_sheet_disconnect_btn"),
                border = BorderStroke(1.dp, RoseAlert.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseAlert)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("Drain & Disconnect Node")
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NsdDetectedPeersBanner(
    peers: List<DiscoveredPeer>,
    isPairing: Boolean,
    onJoinPeer: (DiscoveredPeer) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cluster_nsd_detected_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, EmeraldReady.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        tint = EmeraldReady,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "NSD Node Detected on LAN",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                StatusPill(label = "${peers.size} FOUND", color = EmeraldReady)
            }

            peers.forEach { peer ->
                val hardwareSummary = buildList {
                    peer.role?.let { add(it.displayName) }
                    if (peer.cpuCores > 0) add("${peer.cpuCores} vCPU")
                    if (peer.totalRamMb > 0L) add(formatRamGb(peer.totalRamMb))
                    if (peer.deviceModel.isNotBlank()) add(peer.deviceModel)
                }.joinToString(" · ")

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = peer.displayTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hardwareSummary.isNotBlank()) {
                                Text(
                                    text = hardwareSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${peer.host}:${peer.port}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = ElectricCyan
                            )
                        }
                        Button(
                            onClick = { onJoinPeer(peer) },
                            enabled = !isPairing,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("cluster_nsd_join_${peer.host}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldReady,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Join Cluster")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NsdLiveStatusStrip(
    nsdStatus: NsdStatus,
    isAdvertising: Boolean,
    errorText: String?,
    onManageMesh: () -> Unit
) {
    val isFailed = nsdStatus == NsdStatus.FAILED
    val accent = if (isFailed) RoseAlert else ElectricCyan
    Surface(
        onClick = onManageMesh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cluster_nsd_status_strip"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radar,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = if (isFailed) {
                            errorText ?: "NSD LAN discovery error"
                        } else {
                            val advText = if (isAdvertising) "Advertising & scanning" else "Scanning"
                            "$advText _kubemesh._tcp. on LAN"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFailed) RoseAlert else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tap to inspect NSD discovery or shared cluster token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(
                label = if (isFailed) "NSD ERROR" else "mDNS LIVE",
                color = if (isFailed) AmberWarn else ElectricCyan
            )
        }
    }
}

