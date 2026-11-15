package com.example.cluster.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NsdStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert

@Composable
fun MeshConnectScreen(
    uiState: ClusterUiState,
    onSetRole: (NodeRole) -> Unit,
    onRegenerateToken: () -> Unit,
    onUpdateClusterToken: (String) -> Unit,
    onToggleScan: () -> Unit,
    onSetAutoJoinNsd: (Boolean) -> Unit,
    onPairWithPeer: (host: String, port: Int, token: String) -> Unit,
    onSetDaemonEnabled: (Boolean) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val local = uiState.localNode
    var peerHost by remember(uiState.config.lastPeerHost) {
        mutableStateOf(uiState.config.lastPeerHost)
    }
    var peerPort by remember(uiState.config.lastPeerPort) {
        mutableStateOf(uiState.config.lastPeerPort.toString())
    }
    var peerToken by remember(uiState.config.clusterToken) {
        mutableStateOf(uiState.config.clusterToken)
    }
    var customLocalToken by remember(uiState.config.clusterToken) {
        mutableStateOf(uiState.config.clusterToken)
    }
    var showLogs by remember { mutableStateOf(false) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onSetDaemonEnabled(true)
        if (!granted) {
            onShowMessage("Background node running; enable notifications in Settings to see status")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Role Selector Card
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "DEVICE KUBERNETES ROLE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RoleChoiceCard(
                        title = "Control Plane",
                        subtitle = "Schedules pods & unifies both devices",
                        icon = Icons.Rounded.Dns,
                        selected = uiState.config.role == NodeRole.CONTROL_PLANE,
                        accent = ElectricCyan,
                        onClick = { onSetRole(NodeRole.CONTROL_PLANE) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("role_control_plane_card")
                    )
                    RoleChoiceCard(
                        title = "Worker Node",
                        subtitle = "Contributes CPU & RAM to master",
                        icon = Icons.Rounded.PhoneAndroid,
                        selected = uiState.config.role == NodeRole.WORKER,
                        accent = EmeraldReady,
                        onClick = { onSetRole(NodeRole.WORKER) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("role_worker_card")
                    )
                }
            }
        }

        // 2. Local Node Endpoint & Token Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                imageVector = Icons.Rounded.WifiTethering,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                            Text(
                                text = "This Device Endpoint",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        StatusPill(
                            label = if (uiState.isAdvertising) "mDNS LIVE" else "TCP READY",
                            color = EmeraldReady
                        )
                    }

                    // Endpoint Copy Box
                    val endpoint = local?.endpoint ?: "0.0.0.0:${uiState.serverPort}"
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "LAN IP:PORT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = endpoint,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = ElectricCyan
                                )
                            }
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "KubeMesh Endpoint", endpoint)
                                    onShowMessage("Copied $endpoint")
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_endpoint_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy device IP and port",
                                    tint = ElectricCyan
                                )
                            }
                        }
                    }

                    // Token Copy, Rotate & Shared Token Box
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "CLUSTER JOIN TOKEN",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uiState.config.clusterToken,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = EmeraldReady
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = {
                                            copyToClipboard(context, "Cluster Token", uiState.config.clusterToken)
                                            onShowMessage("Copied token ${uiState.config.clusterToken}")
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("copy_token_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy cluster token",
                                            tint = EmeraldReady
                                        )
                                    }
                                    IconButton(
                                        onClick = onRegenerateToken,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("rotate_token_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Rotate cluster join token",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customLocalToken,
                                    onValueChange = { customLocalToken = it.uppercase().take(12) },
                                    label = { Text("Set Shared Token") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_token_input")
                                )
                                OutlinedButton(
                                    onClick = { onUpdateClusterToken(customLocalToken) },
                                    enabled = customLocalToken.trim().length >= 4 &&
                                        customLocalToken.trim().uppercase() != uiState.config.clusterToken,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("apply_token_btn")
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }

                    // Background Foreground Service Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep Node Daemon Alive",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Runs foreground service so the second phone stays connected in background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.config.daemonEnabled,
                            onCheckedChange = { enable ->
                                if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        onSetDaemonEnabled(true)
                                    } else {
                                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                } else {
                                    onSetDaemonEnabled(enable)
                                }
                            },
                            modifier = Modifier.testTag("daemon_switch")
                        )
                    }
                }
            }
        }

        // 3. LAN NSD Discovery Radar Card
        item {
            val nsdStatusLabel = when {
                uiState.nsdStatus == NsdStatus.FAILED -> "NSD ERROR"
                uiState.isScanning && uiState.isAdvertising -> "ADVERTISING · SCANNING"
                uiState.isScanning -> "SCANNING"
                uiState.isAdvertising -> "ADVERTISING · PAUSED"
                else -> "IDLE"
            }
            val nsdStatusColor = when {
                uiState.nsdStatus == NsdStatus.FAILED -> RoseAlert
                uiState.isScanning && uiState.isAdvertising -> EmeraldReady
                uiState.isScanning -> ElectricCyan
                uiState.isAdvertising -> AmberWarn
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "LAN Auto-Discovery (NSD)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                text = "Detects KubeMesh nodes over _kubemesh._tcp. on Wi-Fi or Hotspot",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(
                            label = nsdStatusLabel,
                            color = nsdStatusColor,
                            modifier = Modifier.testTag("nsd_status_pill")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Form Cluster on Detect",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Automatically unifies peers sharing the same cluster token",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.config.autoJoinNsd,
                            onCheckedChange = onSetAutoJoinNsd,
                            modifier = Modifier.testTag("auto_join_nsd_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = onToggleScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("scan_lan_btn"),
                            border = BorderStroke(
                                1.dp,
                                if (uiState.isScanning) EmeraldReady else ElectricCyan
                            )
                        ) {
                            if (uiState.isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = EmeraldReady
                                )
                                Spacer(Modifier.size(8.dp))
                                Text("Pause NSD Scan")
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Radar,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(6.dp))
                                Text("Restart NSD Scan")
                            }
                        }
                    }

                    if (!uiState.nsdError.isNullOrBlank()) {
                        Surface(
                            color = RoseAlert.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, RoseAlert.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = uiState.nsdError,
                                style = MaterialTheme.typography.bodySmall,
                                color = RoseAlert,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (uiState.discoveredPeers.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (uiState.isScanning) {
                                    "Broadcasting & listening for _kubemesh._tcp. nodes on Wi-Fi..."
                                } else {
                                    "Tap Restart NSD Scan on one device while KubeMesh is open on the second Android device."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        uiState.discoveredPeers.forEach { peer ->
                            val isAlreadyPaired = uiState.remotePeers.any { rp ->
                                (peer.nodeId.isNotEmpty() && rp.nodeId == peer.nodeId) ||
                                    (rp.hostIp == peer.host && rp.port == peer.port)
                            }
                            val specSummary = buildList {
                                peer.role?.let { add(it.displayName) }
                                if (peer.cpuCores > 0) add("${peer.cpuCores} vCPU")
                                if (peer.totalRamMb > 0L) add(formatRamGb(peer.totalRamMb))
                                if (peer.deviceModel.isNotBlank()) add(peer.deviceModel)
                            }.joinToString(" · ")

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, EmeraldReady.copy(alpha = 0.45f))
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
                                            .padding(end = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = peer.displayTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (specSummary.isNotBlank()) {
                                            Text(
                                                text = specSummary,
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
                                    if (isAlreadyPaired) {
                                        StatusPill(label = "PAIRED", color = EmeraldReady)
                                    } else {
                                        Button(
                                            onClick = {
                                                peerHost = peer.host
                                                peerPort = peer.port.toString()
                                                onPairWithPeer(peer.host, peer.port, peerToken)
                                            },
                                            enabled = !uiState.isPairing,
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("pair_discovered_${peer.host}")
                                        ) {
                                            Text("Unify")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Direct IP:Port Pairing Form
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Direct Endpoint Pairing",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Enter the second device's IP, port, and cluster token shown on its Mesh screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = peerHost,
                            onValueChange = { peerHost = it },
                            label = { Text("Peer IPv4") },
                            placeholder = { Text("192.168.1.42") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(2f)
                                .testTag("peer_ip_input")
                        )
                        OutlinedTextField(
                            value = peerPort,
                            onValueChange = { peerPort = it.filter(Char::isDigit) },
                            label = { Text("Port") },
                            placeholder = { Text("7443") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("peer_port_input")
                        )
                    }
                    OutlinedTextField(
                        value = peerToken,
                        onValueChange = { peerToken = it.uppercase() },
                        label = { Text("Cluster Token") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("peer_token_input")
                    )
                    Button(
                        onClick = {
                            val portInt = peerPort.toIntOrNull() ?: 7443
                            onPairWithPeer(peerHost, portInt, peerToken)
                        },
                        enabled = !uiState.isPairing && peerHost.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("connect_peer_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (uiState.isPairing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Handshaking...")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Connect & Unify Device")
                        }
                    }
                }
            }
        }

        // 5. Collapsible Kubelet Event Console
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogs = !showLogs }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("toggle_cluster_logs_btn"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = EmeraldReady,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "kubelet Event Stream (${uiState.clusterLogs.size})",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Text(
                            text = if (showLogs) "HIDE" else "VIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                    AnimatedVisibility(visible = showLogs) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF070B12))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            uiState.clusterLogs.take(18).forEach { logLine ->
                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun RoleChoiceCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
