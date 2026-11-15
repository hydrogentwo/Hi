package com.example.cluster.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cluster.model.NodeConditionType
import com.example.cluster.model.NodeMetricSample
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert
import com.example.ui.theme.VioletMesh

@Composable
fun ActiveClusterNodesVisualizer(
    nodes: List<NodeTelemetry>,
    unreachablePeerIds: Set<String>,
    metricHistory: Map<String, List<NodeMetricSample>>,
    onNodeClick: (NodeTelemetry) -> Unit,
    onPairWorkerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var onlineOnlyFilter by remember { mutableStateOf(false) }
    var showSparklines by remember { mutableStateOf(true) }

    val activeNodes = remember(nodes, unreachablePeerIds, onlineOnlyFilter) {
        if (onlineOnlyFilter) {
            nodes.filter { it.isLocal || it.nodeId !in unreachablePeerIds }
        } else {
            nodes
        }
    }
    val onlineNodes = remember(nodes, unreachablePeerIds) {
        nodes.filter { it.isLocal || it.nodeId !in unreachablePeerIds }
    }

    val pulseTransition = rememberInfiniteTransition(label = "live_telemetry_pulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_dot_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_nodes_visualizer"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section Header + Live Pulse + Filter Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(liveDotAlpha)
                        .clip(CircleShape)
                        .background(EmeraldReady)
                )
                Text(
                    text = "ACTIVE CLUSTER NODES (${onlineNodes.size}/${nodes.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showSparklines,
                    onClick = { showSparklines = !showSparklines },
                    label = { Text("Trends") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyan.copy(alpha = 0.16f),
                        selectedLabelColor = ElectricCyan,
                        selectedLeadingIconColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("toggle_node_trends_chip")
                )
                if (unreachablePeerIds.isNotEmpty()) {
                    FilterChip(
                        selected = onlineOnlyFilter,
                        onClick = { onlineOnlyFilter = !onlineOnlyFilter },
                        label = { Text("Ready") },
                        modifier = Modifier.testTag("filter_online_nodes_chip")
                    )
                }
            }
        }

        // Aggregated Real-Time Telemetry Strip across active nodes
        if (onlineNodes.isNotEmpty()) {
            ClusterTelemetrySummaryStrip(onlineNodes = onlineNodes)
        }

        // Responsive 1-column (phone) or 2-column (tablet/foldable) node visualizer cards
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 660.dp && activeNodes.size > 1
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    activeNodes.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { node ->
                                val isOffline = !node.isLocal && node.nodeId in unreachablePeerIds
                                ActiveNodeVisualizerCard(
                                    node = node,
                                    isOffline = isOffline,
                                    history = metricHistory[node.nodeId].orEmpty(),
                                    showSparkline = showSparklines,
                                    onClick = { onNodeClick(node) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    activeNodes.forEach { node ->
                        val isOffline = !node.isLocal && node.nodeId in unreachablePeerIds
                        ActiveNodeVisualizerCard(
                            node = node,
                            isOffline = isOffline,
                            history = metricHistory[node.nodeId].orEmpty(),
                            showSparkline = showSparklines,
                            onClick = { onNodeClick(node) }
                        )
                    }
                }
            }
        }

        // Show open worker slot prompt when only the local device is active
        if (nodes.size <= 1) {
            AwaitingWorkerSlotCard(onClick = onPairWorkerClick)
        }
    }
}

@Composable
private fun ClusterTelemetrySummaryStrip(onlineNodes: List<NodeTelemetry>) {
    val avgCpu = remember(onlineNodes) {
        onlineNodes.map { it.cpuLoadPercent }.average().toInt().coerceIn(0, 100)
    }
    val totalUsedRam = remember(onlineNodes) { onlineNodes.sumOf { it.usedRamMb } }
    val totalRam = remember(onlineNodes) { onlineNodes.sumOf { it.totalRamMb }.coerceAtLeast(1L) }
    val ramPct = ((totalUsedRam.toFloat() / totalRam.toFloat()) * 100f).toInt().coerceIn(0, 100)
    val minBattery = remember(onlineNodes) { onlineNodes.minOf { it.batteryPercent } }
    val anyCharging = remember(onlineNodes) { onlineNodes.any { it.isCharging } }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryMetricPill(
                label = "AVG CPU",
                value = "$avgCpu%",
                accent = cpuSeverityColor(avgCpu)
            )
            SummaryMetricPill(
                label = "POOL RAM",
                value = "$ramPct% (${formatRamGb(totalUsedRam)})",
                accent = ramSeverityColor(ramPct)
            )
            SummaryMetricPill(
                label = "MIN BATT",
                value = "$minBattery%${if (anyCharging) " ⚡" else ""}",
                accent = batterySeverityColor(minBattery, anyCharging)
            )
        }
    }
}

@Composable
private fun SummaryMetricPill(label: String, value: String, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = accent
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveNodeVisualizerCard(
    node: NodeTelemetry,
    isOffline: Boolean,
    history: List<NodeMetricSample>,
    showSparkline: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val roleColor = if (node.role == NodeRole.CONTROL_PLANE) ElectricCyan else EmeraldReady
    val borderColor by animateColorAsState(
        targetValue = when {
            isOffline -> RoseAlert.copy(alpha = 0.65f)
            node.isLocal -> ElectricCyan.copy(alpha = 0.45f)
            else -> EmeraldReady.copy(alpha = 0.45f)
        },
        label = "node_border_color"
    )
    val ramPercent = (node.ramUsageFraction * 100f).toInt().coerceIn(0, 100)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("visualizer_node_card_${node.nodeId}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Identity Row
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
                            .size(42.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(roleColor.copy(alpha = 0.14f))
                            .border(1.dp, roleColor.copy(alpha = 0.35f), MaterialTheme.shapes.small),
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
                                Surface(
                                    color = ElectricCyan.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "LOCAL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
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

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatusPill(
                        label = if (isOffline) "OFFLINE" else node.role.displayName.uppercase(),
                        color = if (isOffline) RoseAlert else roleColor
                    )
                    Text(
                        text = if (node.isLocal) "0 ms" else "${node.rttMs} ms RTT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3 Radial Gauges: CPU, RAM, Battery
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricRadialGauge(
                    title = "CPU",
                    percent = if (isOffline) 0 else node.cpuLoadPercent,
                    subtitle = "${node.cpuCores} vCPU",
                    detail = node.cpuArch,
                    accent = if (isOffline) RoseAlert else cpuSeverityColor(node.cpuLoadPercent),
                    icon = Icons.Rounded.Speed,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gauge_cpu_${node.nodeId}")
                )

                MetricRadialGauge(
                    title = "RAM",
                    percent = if (isOffline) 0 else ramPercent,
                    subtitle = "${node.usedRamMb} MB",
                    detail = "/ ${formatRamGb(node.totalRamMb)}",
                    accent = if (isOffline) RoseAlert else ramSeverityColor(ramPercent),
                    icon = Icons.Rounded.Memory,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gauge_ram_${node.nodeId}")
                )

                MetricRadialGauge(
                    title = "BATTERY",
                    percent = if (isOffline) 0 else node.batteryPercent,
                    subtitle = if (node.isCharging) "Charging" else "Battery",
                    detail = node.thermalStatusLabel,
                    accent = if (isOffline) RoseAlert else batterySeverityColor(node.batteryPercent, node.isCharging),
                    icon = if (node.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Bolt,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("gauge_battery_${node.nodeId}")
                )
            }

            // Live CPU & RAM Sparkline Trend Chart
            if (showSparkline) {
                NodeMetricSparklineCard(
                    samples = history,
                    isOffline = isOffline
                )
            }

            // Bottom Conditions & Thermal/Pod Strip
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
                    icon = Icons.Rounded.Thermostat,
                    text = "Thermal: ${node.thermalStatusLabel}",
                    tint = if (node.isThermalThrottled) RoseAlert else MaterialTheme.colorScheme.onSurfaceVariant
                )
                HardwareInfoChip(
                    icon = Icons.Rounded.Memory,
                    text = "${node.activePods} active pod(s)",
                    tint = if (node.activePods > 0) VioletMesh else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetricRadialGauge(
    title: String,
    percent: Int,
    subtitle: String,
    detail: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val clamped = percent.coerceIn(0, 100)
    val animatedSweep by animateFloatAsState(
        targetValue = (clamped / 100f) * 250f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "gauge_sweep_$title"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center
        ) {
            val trackColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 7.dp.toPx()
                val pad = strokeWidth / 2f
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(pad, pad)

                // Track arc
                drawArc(
                    color = trackColor,
                    startAngle = 145f,
                    sweepAngle = 250f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Active value arc
                if (animatedSweep > 0.5f) {
                    drawArc(
                        color = accent,
                        startAngle = 145f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$title metric",
                    tint = accent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "$clamped%",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = JetBrainsMonoFontFamily
                ),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NodeMetricSparklineCard(
    samples: List<NodeMetricSample>,
    isOffline: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
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
                Text(
                    text = "REAL-TIME TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendDotLabel(color = ElectricCyan, label = "CPU")
                    LegendDotLabel(color = EmeraldReady, label = "RAM")
                    LegendDotLabel(color = AmberWarn, label = "BATT")
                }
            }

            if (samples.size < 2) {
                Text(
                    text = if (isOffline) {
                        "Telemetry paused — waiting for node heartbeat..."
                    } else {
                        "Collecting live 2.2s telemetry samples (${samples.size}/2)..."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    // Horizontal 50% reference line
                    drawLine(
                        color = Color(0x2694A3B8),
                        start = Offset(0f, size.height * 0.5f),
                        end = Offset(size.width, size.height * 0.5f),
                        strokeWidth = 1.dp.toPx()
                    )

                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)

                    fun buildSeriesPath(valueSelector: (NodeMetricSample) -> Int): Path {
                        val p = Path()
                        samples.forEachIndexed { idx, sample ->
                            val x = idx * stepX
                            val v = valueSelector(sample).coerceIn(0, 100) / 100f
                            val y = size.height - (v * (size.height - 6.dp.toPx())) - 3.dp.toPx()
                            if (idx == 0) p.moveTo(x, y) else p.lineTo(x, y)
                        }
                        return p
                    }

                    // CPU shaded area + line
                    val cpuPath = buildSeriesPath { it.cpuLoadPercent }
                    val cpuFill = Path().apply {
                        addPath(cpuPath)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(
                        path = cpuFill,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                ElectricCyan.copy(alpha = 0.28f),
                                Color.Transparent
                            )
                        )
                    )
                    drawPath(
                        path = cpuPath,
                        color = ElectricCyan,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // RAM line
                    drawPath(
                        path = buildSeriesPath { it.ramUsagePercent },
                        color = EmeraldReady,
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Battery line
                    drawPath(
                        path = buildSeriesPath { it.batteryPercent },
                        color = AmberWarn.copy(alpha = 0.85f),
                        style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDotLabel(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AwaitingWorkerSlotCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("awaiting_worker_slot_card"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.3f))
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddCircleOutline,
                    contentDescription = "Pair second Android worker node",
                    tint = ElectricCyan
                )
                Column {
                    Text(
                        text = "Pair 2nd Device to Visualize Dual-Node Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Open Mesh tab to discover or link a worker phone over Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "LINK →",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )
        }
    }
}

private fun cpuSeverityColor(percent: Int): Color = when {
    percent >= 90 -> RoseAlert
    percent >= 72 -> AmberWarn
    else -> ElectricCyan
}

private fun ramSeverityColor(percent: Int): Color = when {
    percent >= 90 -> RoseAlert
    percent >= 78 -> AmberWarn
    else -> EmeraldReady
}

private fun batterySeverityColor(percent: Int, isCharging: Boolean): Color = when {
    isCharging -> EmeraldReady
    percent < 20 -> RoseAlert
    percent < 40 -> AmberWarn
    else -> EmeraldReady
}
