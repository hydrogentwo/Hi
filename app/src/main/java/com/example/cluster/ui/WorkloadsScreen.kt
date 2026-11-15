package com.example.cluster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.WorkloadType
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert
import com.example.ui.theme.VioletMesh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkloadsScreen(
    uiState: ClusterUiState,
    onDeployPod: (WorkloadType, SchedulingPolicy, Int, String?) -> Unit,
    onInspectPod: (String?) -> Unit,
    onCancelPod: (String) -> Unit = {},
    onClearCompletedPods: () -> Unit = {},
    onRefreshAiCore: () -> Unit = {},
    onDownloadAiCore: () -> Unit = {},
    onWarmupAiCore: () -> Unit = {},
    onRunAiPrompt: (prompt: String, targetNodeId: String?) -> Unit = { _, _ -> },
    onDiagnoseClusterAi: (targetNodeId: String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDeploySheet by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("workloads_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f))
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
                            Text(
                                text = "Distributed Compute Pods",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (uiState.isUnifiedCluster) {
                                    "Sharding across ${1 + uiState.onlinePeersCount} nodes (${uiState.totalCpuCores} pooled vCPU)"
                                } else {
                                    "Running on ${uiState.totalCpuCores} local vCPU · Pair 2nd phone to split load"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { showDeploySheet = true },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("open_deploy_pod_sheet_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RocketLaunch,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Deploy")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WorkloadType.entries.forEach { preset ->
                            QuickDeployPresetChip(
                                workload = preset,
                                onClick = {
                                    onDeployPod(preset, SchedulingPolicy.PROPORTIONAL_SPLIT, 1, null)
                                }
                            )
                        }
                    }
                }
            }
        }

        item(key = "distributed_task_queue") {
            DistributedTaskQueueCard(
                pods = uiState.pods,
                onInspectPod = onInspectPod
            )
        }

        item {
            AiCoreClusterCard(
                uiState = uiState,
                onRefresh = onRefreshAiCore,
                onDownload = onDownloadAiCore,
                onWarmup = onWarmupAiCore,
                onRunPrompt = onRunAiPrompt,
                onDiagnoseCluster = onDiagnoseClusterAi
            )
        }

        item {
            val hasCompletedPods = uiState.pods.any {
                it.phase == PodPhase.SUCCEEDED || it.phase == PodPhase.FAILED
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "POD WORKLOADS (${uiState.pods.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasCompletedPods) {
                    TextButton(
                        onClick = onClearCompletedPods,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clear_completed_pods_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear History")
                    }
                }
            }
        }

        if (uiState.pods.isEmpty()) {
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
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No Pods Scheduled Yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Launch any compute kernel above to benchmark parallel CPU execution across your unified Android nodes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(uiState.pods, key = { it.podId }) { pod ->
                PodExecutionCard(
                    pod = pod,
                    onInspect = { onInspectPod(pod.podId) },
                    onCancel = { onCancelPod(pod.podId) }
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showDeploySheet) {
        ModalBottomSheet(
            onDismissRequest = { showDeploySheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            DeployPodSheetContent(
                totalCores = uiState.totalCpuCores,
                nodeCount = 1 + uiState.onlinePeersCount,
                onDeploy = { type, policy, scale, name ->
                    onDeployPod(type, policy, scale, name)
                    showDeploySheet = false
                }
            )
        }
    }

    val inspectedPod = uiState.pods.firstOrNull { it.podId == uiState.selectedPodForLogs }
    if (inspectedPod != null) {
        ModalBottomSheet(
            onDismissRequest = { onInspectPod(null) },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            PodInspectorSheetContent(
                pod = inspectedPod,
                onCancelPod = { onCancelPod(inspectedPod.podId) }
            )
        }
    }
}

@Composable
private fun QuickDeployPresetChip(
    workload: WorkloadType,
    onClick: () -> Unit
) {
    val icon = when (workload) {
        WorkloadType.PRIME_SIEVE -> Icons.Rounded.Functions
        WorkloadType.MONTE_CARLO_PI -> Icons.Rounded.PlayArrow
        WorkloadType.SHA256_BENCHMARK -> Icons.Rounded.Lock
        WorkloadType.MATRIX_MATMUL -> Icons.Rounded.GridOn
    }
    Surface(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("quick_deploy_${workload.id}"),
        color = Color(0xFF0B111E),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(16.dp)
            )
            Column {
                Text(
                    text = workload.title,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = "Run now",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldReady
                )
            }
        }
    }
}

@Composable
private fun PodExecutionCard(
    pod: ClusterPod,
    onInspect: () -> Unit,
    onCancel: () -> Unit = {}
) {
    val phaseColor = when (pod.phase) {
        PodPhase.PENDING -> AmberWarn
        PodPhase.RUNNING -> ElectricCyan
        PodPhase.SUCCEEDED -> EmeraldReady
        PodPhase.FAILED -> RoseAlert
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onInspect)
            .testTag("pod_card_${pod.podId}"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, phaseColor.copy(alpha = 0.4f))
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pod.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pod.workloadType.kubeImage} · ${pod.intensityLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(label = pod.phase.label.uppercase(), color = phaseColor)
            }

            if (pod.phase == PodPhase.RUNNING) {
                if (pod.totalChunks > 0) {
                    val fraction = (pod.completedChunks.toFloat() / pod.totalChunks.toFloat()).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .testTag("pod_chunk_progress_${pod.podId}"),
                            color = ElectricCyan,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        )
                        Text(
                            text = "Channel Queue: ${pod.completedChunks}/${pod.totalChunks} chunks",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            ),
                            color = ElectricCyan
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape),
                        color = ElectricCyan,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                }
            }

            // Shard Distribution Bar
            ShardSplitBar(shards = pod.shards)

            // Aggregated Result Box
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = pod.aggregatedResult,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = if (pod.phase == PodPhase.FAILED) RoseAlert else EmeraldReady
                    )
                    if (pod.speedupNote.isNotEmpty()) {
                        Text(
                            text = pod.speedupNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        pod.totalElapsedMs > 0 && pod.totalChunks > 0 ->
                            "Wall ${pod.totalElapsedMs} ms · ${pod.completedChunks}/${pod.totalChunks} chunks"
                        pod.totalElapsedMs > 0 -> "Wall ${pod.totalElapsedMs} ms"
                        else -> "${pod.completedChunks}/${pod.totalChunks.coerceAtLeast(pod.shards.size)} chunks queued"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pod.phase == PodPhase.RUNNING || pod.phase == PodPhase.PENDING) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_pod_btn_${pod.podId}")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Cancel,
                                contentDescription = "Cancel pod",
                                tint = RoseAlert,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel", color = RoseAlert)
                        }
                    }
                    Text(
                        text = "Inspect YAML & Logs →",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun ShardSplitBar(shards: List<PodShard>) {
    if (shards.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E293B))
        ) {
            shards.forEachIndexed { idx, shard ->
                val color = when (idx % 3) {
                    0 -> ElectricCyan
                    1 -> EmeraldReady
                    else -> VioletMesh
                }
                Box(
                    modifier = Modifier
                        .weight(shard.weightPercent.coerceAtLeast(1).toFloat())
                        .fillMaxHeight()
                        .background(color)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            shards.forEachIndexed { idx, shard ->
                val color = when (idx % 3) {
                    0 -> ElectricCyan
                    1 -> EmeraldReady
                    else -> VioletMesh
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        text = "${shard.nodeName} (${shard.weightPercent}% · ${shard.chunksCompleted}ch)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DeployPodSheetContent(
    totalCores: Int,
    nodeCount: Int,
    onDeploy: (WorkloadType, SchedulingPolicy, Int, String?) -> Unit
) {
    var selectedType by remember { mutableStateOf(WorkloadType.PRIME_SIEVE) }
    var selectedPolicy by remember { mutableStateOf(SchedulingPolicy.PROPORTIONAL_SPLIT) }
    var scaleMultiplier by remember { mutableIntStateOf(1) }
    var customName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Deploy Kubernetes Compute Pod",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Cluster capacity: $totalCores vCPU across $nodeCount node(s)",
            style = MaterialTheme.typography.bodySmall,
            color = ElectricCyan
        )

        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text("Pod Name (optional)") },
            placeholder = { Text("${selectedType.id}-job") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("deploy_pod_name_input")
        )

        Text(
            text = "COMPUTE KERNEL",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkloadType.entries.forEach { type ->
                val selected = type == selectedType
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { selectedType = type }
                        .minimumInteractiveComponentSize(),
                    color = if (selected) ElectricCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (selected) ElectricCyan else MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = type.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) ElectricCyan else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = type.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = "SCHEDULING POLICY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SchedulingPolicy.entries.forEach { policy ->
                FilterChip(
                    selected = selectedPolicy == policy,
                    onClick = { selectedPolicy = policy },
                    label = { Text(policy.label) }
                )
            }
        }

        Text(
            text = "WORKLOAD INTENSITY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1× Standard", 2 to "2× Heavy", 4 to "4× Stress").forEach { (factor, label) ->
                FilterChip(
                    selected = scaleMultiplier == factor,
                    onClick = { scaleMultiplier = factor },
                    label = { Text(label) }
                )
            }
        }

        Button(
            onClick = { onDeploy(selectedType, selectedPolicy, scaleMultiplier, customName) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("confirm_deploy_pod_btn"),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Apply Pod Manifest & Execute")
        }
    }
}

@Composable
private fun PodInspectorSheetContent(
    pod: ClusterPod,
    onCancelPod: () -> Unit = {}
) {
    var tabIndex by remember { mutableIntStateOf(0) }

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
            Text(
                text = pod.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (pod.phase == PodPhase.RUNNING || pod.phase == PodPhase.PENDING) {
                OutlinedButton(
                    onClick = onCancelPod,
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("sheet_cancel_pod_btn"),
                    border = BorderStroke(1.dp, RoseAlert.copy(alpha = 0.6f))
                ) {
                    Text("Cancel Pod", color = RoseAlert)
                }
            }
        }
        Text(
            text = pod.aggregatedResult,
            style = MaterialTheme.typography.labelMedium,
            color = EmeraldReady
        )

        TabRow(selectedTabIndex = tabIndex) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("kubectl logs") },
                icon = { Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("Pod YAML") },
                icon = { Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        Surface(
            color = Color(0xFF070B12),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (tabIndex == 0) {
                    pod.logs.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    HorizontalDivider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 4.dp))
                    pod.shards.forEach { shard ->
                        Text(
                            text = "• ${shard.nodeName} [${shard.phase.label}] ${shard.elapsedMs}ms · ${shard.throughputLabel} · ${shard.secondaryText}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = ElectricCyan
                        )
                    }
                } else {
                    Text(
                        text = pod.yamlManifest,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = Color(0xFFBAE6FD)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiCoreClusterCard(
    uiState: ClusterUiState,
    onRefresh: () -> Unit,
    onDownload: () -> Unit,
    onWarmup: () -> Unit,
    onRunPrompt: (prompt: String, targetNodeId: String?) -> Unit,
    onDiagnoseCluster: (targetNodeId: String?) -> Unit
) {
    val localAi = uiState.aiCoreLocal
    val inference = uiState.aiCoreInference
    var promptText by remember { mutableStateOf("") }
    var selectedTargetNodeId by remember { mutableStateOf<String?>(null) }

    val statusColor = when (localAi.status) {
        AiCoreStatus.AVAILABLE -> EmeraldReady
        AiCoreStatus.CLOUD_READY -> ElectricCyan
        AiCoreStatus.DOWNLOADABLE, AiCoreStatus.DOWNLOADING -> ElectricCyan
        AiCoreStatus.UNAVAILABLE -> AmberWarn
        AiCoreStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("aicore_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, VioletMesh.copy(alpha = 0.45f))
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = VioletMesh,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "AICore · Gemini Nano",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = localAi.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        label = localAi.status.label.uppercase(),
                        color = statusColor,
                        modifier = Modifier.testTag("aicore_status_pill")
                    )
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("aicore_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh AICore status",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Target Node Selector (Auto Mesh vs Specific Device)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTargetNodeId == null,
                    onClick = { selectedTargetNodeId = null },
                    label = { Text("Auto Mesh") },
                    modifier = Modifier.testTag("aicore_target_auto")
                )
                uiState.allNodes.forEach { node ->
                    val nodeAi = AiCoreStatus.fromWire(node.aiCoreStatus)
                    FilterChip(
                        selected = selectedTargetNodeId == node.nodeId,
                        onClick = { selectedTargetNodeId = node.nodeId },
                        label = {
                            Text("${node.nodeName} (${nodeAi.label})")
                        },
                        modifier = Modifier.testTag("aicore_target_${node.nodeId}")
                    )
                }
            }

            if (localAi.status == AiCoreStatus.DOWNLOADABLE) {
                OutlinedButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("aicore_download_btn"),
                    border = BorderStroke(1.dp, ElectricCyan)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Download Gemini Nano Model")
                }
            } else if (localAi.status == AiCoreStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = ElectricCyan
                )
            } else if (localAi.status == AiCoreStatus.AVAILABLE && !localAi.isWarmedUp) {
                OutlinedButton(
                    onClick = onWarmup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("aicore_warmup_btn"),
                    border = BorderStroke(1.dp, EmeraldReady.copy(alpha = 0.6f))
                ) {
                    Text("Warm Up Gemini Nano Weights")
                }
            }

            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("On-device AICore Prompt") },
                placeholder = { Text("Ask Gemini Nano to analyze or schedule workloads...") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("aicore_prompt_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { onDiagnoseCluster(selectedTargetNodeId) },
                    enabled = !inference.isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aicore_diagnose_btn"),
                    border = BorderStroke(1.dp, VioletMesh.copy(alpha = 0.65f))
                ) {
                    Text("Analyze Cluster", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { onRunPrompt(promptText, selectedTargetNodeId) },
                    enabled = !inference.isRunning && promptText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aicore_run_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VioletMesh,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (inference.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Running...")
                    } else {
                        Text("Run AICore")
                    }
                }
            }

            if (inference.outputText.isNotBlank() || !inference.errorMessage.isNullOrBlank() || inference.isRunning) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(
                        1.dp,
                        if (!inference.errorMessage.isNullOrBlank()) RoseAlert.copy(alpha = 0.55f)
                        else VioletMesh.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("aicore_output_box")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Node: ${inference.targetNodeName.ifBlank { "local" }}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                color = ElectricCyan
                            )
                            if (inference.elapsedMs > 0L) {
                                Text(
                                    text = "${inference.elapsedMs} ms",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily
                                    ),
                                    color = EmeraldReady
                                )
                            }
                        }
                        if (!inference.errorMessage.isNullOrBlank()) {
                            Text(
                                text = inference.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = RoseAlert
                            )
                        }
                        if (inference.outputText.isNotBlank()) {
                            Text(
                                text = inference.outputText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

