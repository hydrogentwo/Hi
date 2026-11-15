package com.example.cluster.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.NodeConditionType
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert
import com.example.ui.theme.VioletMesh
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ClusterHeroBanner(
    uiState: ClusterUiState,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unified = uiState.isUnifiedCluster
    val statusColor = if (unified) EmeraldReady else ElectricCyan
    val activePodsCount = uiState.pods.count { it.phase.label == "Running" }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_cluster_mesh),
                contentDescription = "Kubernetes mobile compute mesh topology banner",
                modifier = Modifier
                    .matchParentSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.42f
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC090D16),
                                Color(0xE60D1526)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = if (unified) {
                            "${1 + uiState.onlinePeersCount} NODES UNIFIED"
                        } else {
                            "1 NODE READY · STANDALONE"
                        },
                        color = statusColor
                    )
                    Text(
                        text = "k8s-mesh/v1",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (unified) "Unified Compute Cluster" else "Connect 2nd Device to Unify",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (unified) {
                            "Control plane and worker resources pooled over LAN for distributed pod execution."
                        } else {
                            "Pool CPU cores and memory across two Android devices over Wi-Fi or Hotspot."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroStatTile(
                        label = "POOLED vCPU",
                        value = "${uiState.totalCpuCores} Cores",
                        accent = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStatTile(
                        label = "MEMORY POOL",
                        value = formatRamGb(uiState.totalRamMb),
                        accent = EmeraldReady,
                        modifier = Modifier.weight(1f)
                    )
                    HeroStatTile(
                        label = "ACTIVE PODS",
                        value = "$activePodsCount / ${uiState.pods.size}",
                        accent = VioletMesh,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!unified) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onConnectClick)
                            .minimumInteractiveComponentSize()
                            .testTag("hero_pair_banner_cta"),
                        color = ElectricCyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Hub,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Pair 2nd Android Worker over LAN",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan
                                )
                            }
                            Text(
                                text = "MESH →",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0x99111827),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MeshTopologyCard(
    localNode: NodeTelemetry?,
    remotePeers: List<NodeTelemetry>,
    unreachablePeerIds: Set<String>,
    hasRunningPod: Boolean,
    onPairSlotClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mesh_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (hasRunningPod) 1100 else 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_progress"
    )

    val primaryPeer = remotePeers.firstOrNull()
    val peerOnline = primaryPeer != null && primaryPeer.nodeId !in unreachablePeerIds

    Card(
        modifier = modifier.fillMaxWidth(),
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
                Text(
                    text = "LIVE MESH TOPOLOGY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (primaryPeer != null) {
                    StatusPill(
                        label = if (peerOnline) "RTT ${primaryPeer.rttMs} ms" else "LINK LOST",
                        color = if (peerOnline) EmeraldReady else RoseAlert
                    )
                } else {
                    StatusPill(label = "AWAITING WORKER", color = AmberWarn)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(Color(0xFF0B111E))
                    .border(1.dp, Color(0xFF1E293B), MaterialTheme.shapes.small)
                    .then(
                        if (primaryPeer == null) {
                            Modifier.clickable(onClick = onPairSlotClick)
                        } else Modifier
                    )
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val leftCenter = Offset(size.width * 0.22f, size.height * 0.48f)
                    val rightCenter = Offset(size.width * 0.78f, size.height * 0.48f)
                    val hexRadius = (size.minDimension * 0.24f).coerceAtMost(38.dp.toPx())

                    // Subtle grid lines
                    val gridStep = 28.dp.toPx()
                    var gx = gridStep
                    while (gx < size.width) {
                        drawLine(
                            color = Color(0x1438BDF8),
                            start = Offset(gx, 0f),
                            end = Offset(gx, size.height),
                            strokeWidth = 1f
                        )
                        gx += gridStep
                    }

                    // Bridge link between Node A and Node B
                    val linkColor = when {
                        primaryPeer == null -> Color(0x4D94A3B8)
                        peerOnline -> EmeraldReady
                        else -> RoseAlert
                    }
                    val dashEffect = if (primaryPeer == null || !peerOnline) {
                        PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    } else null

                    drawLine(
                        color = linkColor.copy(alpha = 0.55f),
                        start = Offset(leftCenter.x + hexRadius, leftCenter.y),
                        end = Offset(rightCenter.x - hexRadius, rightCenter.y),
                        strokeWidth = if (hasRunningPod && peerOnline) 4.dp.toPx() else 2.dp.toPx(),
                        pathEffect = dashEffect,
                        cap = StrokeCap.Round
                    )

                    // Animated data packets on active link
                    if (peerOnline) {
                        val startX = leftCenter.x + hexRadius
                        val endX = rightCenter.x - hexRadius
                        val px1 = startX + (endX - startX) * pulse
                        val px2 = endX - (endX - startX) * pulse
                        drawCircle(
                            color = ElectricCyan,
                            radius = if (hasRunningPod) 5.dp.toPx() else 3.5.dp.toPx(),
                            center = Offset(px1, leftCenter.y)
                        )
                        if (hasRunningPod) {
                            drawCircle(
                                color = EmeraldReady,
                                radius = 4.5.dp.toPx(),
                                center = Offset(px2, leftCenter.y)
                            )
                        }
                    }

                    // Left Hexagon (Local Node)
                    drawHexNode(
                        center = leftCenter,
                        radius = hexRadius,
                        strokeColor = ElectricCyan,
                        fillColor = Color(0xFF0F2942)
                    )

                    // Right Hexagon (Peer Node or Empty Slot)
                    drawHexNode(
                        center = rightCenter,
                        radius = hexRadius,
                        strokeColor = when {
                            primaryPeer == null -> Color(0x6694A3B8)
                            peerOnline -> EmeraldReady
                            else -> RoseAlert
                        },
                        fillColor = if (peerOnline) Color(0xFF0A2E25) else Color(0xFF131B2B),
                        dashed = primaryPeer == null
                    )
                }

                // Node Labels below hexagons
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = localNode?.nodeName ?: "local-node",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${localNode?.cpuCores ?: 0} vCPU · ${localNode?.role?.displayName ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(24.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = primaryPeer?.nodeName ?: "+ Tap to Pair",
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                primaryPeer == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                peerOnline -> EmeraldReady
                                else -> RoseAlert
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (primaryPeer != null) {
                                "${primaryPeer.cpuCores} vCPU · ${primaryPeer.hostIp}"
                            } else {
                                "Worker slot open"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHexNode(
    center: Offset,
    radius: Float,
    strokeColor: Color,
    fillColor: Color,
    dashed: Boolean = false
) {
    val path = Path()
    for (i in 0 until 6) {
        val angleRad = Math.toRadians((60 * i - 30).toDouble())
        val x = center.x + (radius * cos(angleRad)).toFloat()
        val y = center.y + (radius * sin(angleRad)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path = path, color = fillColor)
    drawPath(
        path = path,
        color = strokeColor,
        style = Stroke(
            width = 2.5.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) else null
        )
    )
    drawCircle(
        color = strokeColor.copy(alpha = 0.85f),
        radius = 5.dp.toPx(),
        center = center
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodeTelemetryCard(
    node: NodeTelemetry,
    isOffline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val roleColor = if (node.role == NodeRole.CONTROL_PLANE) ElectricCyan else EmeraldReady
    val borderColor = if (isOffline) RoseAlert.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("node_card_${node.nodeId}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, borderColor)
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(roleColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (node.role == NodeRole.CONTROL_PLANE) {
                                Icons.Rounded.Dns
                            } else {
                                Icons.Rounded.PhoneAndroid
                            },
                            contentDescription = node.role.displayName,
                            tint = roleColor
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = node.nodeName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (node.isLocal) {
                                Text(
                                    text = "THIS DEVICE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                        }
                        Text(
                            text = "${node.deviceModel} · ${node.endpoint}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                StatusPill(
                    label = if (isOffline) "NotReady" else node.role.displayName.uppercase(),
                    color = if (isOffline) RoseAlert else roleColor
                )
            }

            // CPU & RAM Bars
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricProgressRow(
                    icon = Icons.Rounded.Speed,
                    label = "CPU (${node.cpuCores} cores · ${node.cpuArch})",
                    valueLabel = "${node.cpuLoadPercent}%",
                    progress = (node.cpuLoadPercent / 100f).coerceIn(0f, 1f),
                    barColor = ElectricCyan
                )
                MetricProgressRow(
                    icon = Icons.Rounded.Memory,
                    label = "RAM (${node.usedRamMb} / ${node.totalRamMb} MB)",
                    valueLabel = "${(node.ramUsageFraction * 100).toInt()}%",
                    progress = node.ramUsageFraction,
                    barColor = EmeraldReady
                )
            }

            // Bottom Condition & Hardware Strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val effectiveConditions = if (isOffline) {
                    listOf(NodeConditionType.OFFLINE)
                } else {
                    node.conditions
                }
                effectiveConditions.forEach { cond ->
                    ConditionChip(cond)
                }
                HardwareInfoChip(
                    icon = if (node.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                    text = "${node.batteryPercent}%",
                    tint = if (node.batteryPercent < 20 && !node.isCharging) AmberWarn else MaterialTheme.colorScheme.onSurfaceVariant
                )
                HardwareInfoChip(
                    icon = Icons.Rounded.Thermostat,
                    text = node.thermalStatusLabel,
                    tint = if (node.isThermalThrottled) RoseAlert else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val aiStatus = AiCoreStatus.fromWire(node.aiCoreStatus)
                if (aiStatus != AiCoreStatus.UNKNOWN) {
                    HardwareInfoChip(
                        icon = Icons.Rounded.AutoAwesome,
                        text = "AICore: ${aiStatus.label}",
                        tint = when (aiStatus) {
                            AiCoreStatus.AVAILABLE -> EmeraldReady
                            AiCoreStatus.CLOUD_READY,
                            AiCoreStatus.DOWNLOADABLE,
                            AiCoreStatus.DOWNLOADING -> ElectricCyan
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricProgressRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    valueLabel: String,
    progress: Float,
    barColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = barColor
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = barColor,
            trackColor = Color(0xFF1F2A3E)
        )
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ConditionChip(condition: NodeConditionType) {
    val (color, icon) = when (condition) {
        NodeConditionType.READY -> EmeraldReady to Icons.Rounded.CheckCircle
        NodeConditionType.MEMORY_PRESSURE,
        NodeConditionType.BATTERY_LOW -> AmberWarn to Icons.Rounded.Warning
        NodeConditionType.THERMAL_PRESSURE,
        NodeConditionType.OFFLINE -> RoseAlert to Icons.Rounded.Warning
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = condition.label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun HardwareInfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
        }
    }
}

fun formatRamGb(mb: Long): String {
    val gb = mb / 1024.0
    return if (gb >= 1.0) "${"%.1f".format(gb)} GB" else "$mb MB"
}
