package com.example.cluster.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.PodPhase
import com.example.cluster.model.QueuedChunkState
import com.example.cluster.model.TaskQueueItemStatus
import com.example.ui.theme.AmberWarn
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RoseAlert

enum class TaskQueueFilter(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed")
}

@Composable
fun DistributedTaskQueueCard(
    pods: List<ClusterPod>,
    onInspectPod: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(TaskQueueFilter.ALL) }
    var expandedAll by remember { mutableStateOf(false) }

    val allTasks = remember(pods) { resolveQueueTasks(pods) }
    val pendingCount = remember(allTasks) { allTasks.count { it.status == TaskQueueItemStatus.PENDING } }
    val inProgressCount = remember(allTasks) { allTasks.count { it.status == TaskQueueItemStatus.IN_PROGRESS } }
    val completedCount = remember(allTasks) { allTasks.count { it.status == TaskQueueItemStatus.COMPLETED } }
    val failedCount = remember(allTasks) { allTasks.count { it.status == TaskQueueItemStatus.FAILED } }

    val filteredTasks = remember(allTasks, selectedFilter) {
        val base = when (selectedFilter) {
            TaskQueueFilter.ALL -> allTasks
            TaskQueueFilter.PENDING -> allTasks.filter { it.status == TaskQueueItemStatus.PENDING }
            TaskQueueFilter.IN_PROGRESS -> allTasks.filter { it.status == TaskQueueItemStatus.IN_PROGRESS }
            TaskQueueFilter.COMPLETED -> allTasks.filter {
                it.status == TaskQueueItemStatus.COMPLETED || it.status == TaskQueueItemStatus.FAILED
            }
        }
        base.sortedWith(
            compareBy<QueuedChunkState> { statusSortOrder(it.status) }
                .thenBy { it.chunkIndex }
        )
    }

    val maxCollapsedItems = 6
    val visibleTasks = if (expandedAll) filteredTasks else filteredTasks.take(maxCollapsedItems)
    val hiddenCount = (filteredTasks.size - visibleTasks.size).coerceAtLeast(0)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("distributed_task_queue_card"),
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
            // Header
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
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(ElectricCyan.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Layers,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Distributed Task Queue",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (allTasks.isEmpty()) {
                                "Coroutine Channel chunk pipeline"
                            } else {
                                "${allTasks.size} chunks tracked across ${pods.size} pod(s)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                FilterChip(
                    selected = selectedFilter == TaskQueueFilter.ALL,
                    onClick = { selectedFilter = TaskQueueFilter.ALL },
                    label = {
                        Text(
                            text = "All (${allTasks.size})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        )
                    },
                    modifier = Modifier.testTag("queue_filter_all")
                )
            }

            // Three interactive status bucket cards (Pending / In Progress / Completed)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QueueStatusBucketTile(
                    label = "Pending",
                    count = pendingCount,
                    icon = Icons.Rounded.Schedule,
                    accentColor = AmberWarn,
                    selected = selectedFilter == TaskQueueFilter.PENDING,
                    onClick = {
                        selectedFilter = if (selectedFilter == TaskQueueFilter.PENDING) {
                            TaskQueueFilter.ALL
                        } else {
                            TaskQueueFilter.PENDING
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("queue_filter_pending")
                )

                QueueStatusBucketTile(
                    label = "In Progress",
                    count = inProgressCount,
                    icon = Icons.Rounded.Sync,
                    accentColor = ElectricCyan,
                    selected = selectedFilter == TaskQueueFilter.IN_PROGRESS,
                    onClick = {
                        selectedFilter = if (selectedFilter == TaskQueueFilter.IN_PROGRESS) {
                            TaskQueueFilter.ALL
                        } else {
                            TaskQueueFilter.IN_PROGRESS
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("queue_filter_in_progress")
                )

                QueueStatusBucketTile(
                    label = "Completed",
                    count = completedCount,
                    icon = Icons.Rounded.CheckCircle,
                    accentColor = EmeraldReady,
                    selected = selectedFilter == TaskQueueFilter.COMPLETED,
                    badgeSuffix = if (failedCount > 0) " · ${failedCount} err" else null,
                    onClick = {
                        selectedFilter = if (selectedFilter == TaskQueueFilter.COMPLETED) {
                            TaskQueueFilter.ALL
                        } else {
                            TaskQueueFilter.COMPLETED
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("queue_filter_completed")
                )
            }

            // Segmented Pipeline Progress Bar
            if (allTasks.isNotEmpty()) {
                QueuePipelineBar(
                    pendingCount = pendingCount,
                    inProgressCount = inProgressCount,
                    completedCount = completedCount,
                    failedCount = failedCount
                )
            }

            // Task Queue Feed / Empty States
            if (allTasks.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("queue_empty_state"),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "Task Queue Idle",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Deploy a compute pod above to monitor pending, in-progress, and completed chunks across cluster nodes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (visibleTasks.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "No ${selectedFilter.label.lowercase()} items in queue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { selectedFilter = TaskQueueFilter.ALL },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text("Show all")
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleTasks.forEach { task ->
                        QueueTaskRow(
                            task = task,
                            onClick = { onInspectPod(task.podId) }
                        )
                    }
                }

                if (filteredTasks.size > maxCollapsedItems) {
                    OutlinedButton(
                        onClick = { expandedAll = !expandedAll },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("queue_toggle_expand_btn"),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            imageVector = if (expandedAll) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (expandedAll) {
                                "Show fewer items"
                            } else {
                                "Show all ${filteredTasks.size} items (+$hiddenCount more)"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueStatusBucketTile(
    label: String,
    count: Int,
    icon: ImageVector,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeSuffix: String? = null
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = spring(),
        label = "bucket_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) accentColor else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = spring(),
        label = "bucket_border"
    )

    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize(),
        color = bgColor,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedContent(targetState = count, label = "bucket_count_$label") { animatedCount ->
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = animatedCount.toString(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = accentColor
                    )
                    if (badgeSuffix != null) {
                        Text(
                            text = badgeSuffix,
                            style = MaterialTheme.typography.labelSmall,
                            color = RoseAlert,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePipelineBar(
    pendingCount: Int,
    inProgressCount: Int,
    completedCount: Int,
    failedCount: Int
) {
    val total = (pendingCount + inProgressCount + completedCount + failedCount).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (completedCount > 0) {
            Box(
                modifier = Modifier
                    .weight(completedCount.toFloat() / total.toFloat())
                    .fillMaxHeight()
                    .background(EmeraldReady)
            )
        }
        if (inProgressCount > 0) {
            Box(
                modifier = Modifier
                    .weight(inProgressCount.toFloat() / total.toFloat())
                    .fillMaxHeight()
                    .background(ElectricCyan)
            )
        }
        if (pendingCount > 0) {
            Box(
                modifier = Modifier
                    .weight(pendingCount.toFloat() / total.toFloat())
                    .fillMaxHeight()
                    .background(AmberWarn)
            )
        }
        if (failedCount > 0) {
            Box(
                modifier = Modifier
                    .weight(failedCount.toFloat() / total.toFloat())
                    .fillMaxHeight()
                    .background(RoseAlert)
            )
        }
    }
}

@Composable
private fun QueueTaskRow(
    task: QueuedChunkState,
    onClick: () -> Unit
) {
    val statusColor = when (task.status) {
        TaskQueueItemStatus.PENDING -> AmberWarn
        TaskQueueItemStatus.IN_PROGRESS -> ElectricCyan
        TaskQueueItemStatus.COMPLETED -> EmeraldReady
        TaskQueueItemStatus.FAILED -> RoseAlert
    }

    val pulseAlpha = if (task.status == TaskQueueItemStatus.IN_PROGRESS) {
        val transition = rememberInfiniteTransition(label = "chunk_pulse")
        val anim by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "chunk_pulse_alpha"
        )
        anim
    } else {
        1f
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .testTag("queue_item_${task.podId}_${task.chunkIndex}"),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (task.status == TaskQueueItemStatus.IN_PROGRESS) {
                ElectricCyan.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    val icon = when (task.status) {
                        TaskQueueItemStatus.PENDING -> Icons.Rounded.Schedule
                        TaskQueueItemStatus.IN_PROGRESS -> Icons.Rounded.Sync
                        TaskQueueItemStatus.COMPLETED -> Icons.Rounded.CheckCircle
                        TaskQueueItemStatus.FAILED -> Icons.Rounded.ErrorOutline
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = task.status.label,
                        tint = statusColor,
                        modifier = Modifier
                            .size(16.dp)
                            .alpha(pulseAlpha)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "#${task.chunkIndex + 1}/${task.totalChunks} · ${task.podName}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = JetBrainsMonoFontFamily
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (task.attempt > 1) {
                                Surface(
                                    color = AmberWarn.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Retry #${task.attempt}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AmberWarn,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = buildTaskSubtitle(task),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))
                StatusPill(
                    label = task.status.label.uppercase(),
                    color = statusColor
                )
            }

            if (task.status == TaskQueueItemStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                    color = ElectricCyan,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

private fun statusSortOrder(status: TaskQueueItemStatus): Int = when (status) {
    TaskQueueItemStatus.IN_PROGRESS -> 0
    TaskQueueItemStatus.PENDING -> 1
    TaskQueueItemStatus.FAILED -> 2
    TaskQueueItemStatus.COMPLETED -> 3
}

private fun buildTaskSubtitle(task: QueuedChunkState): String {
    val sliceLabel = "${formatCompactNumber(task.rangeStart)}..${formatCompactNumber(task.rangeEnd)}"
    return when (task.status) {
        TaskQueueItemStatus.PENDING -> {
            if (!task.errorMessage.isNullOrBlank()) {
                "${task.errorMessage} · $sliceLabel"
            } else {
                "Queued in channel · ${task.workloadType.title} ($sliceLabel)"
            }
        }
        TaskQueueItemStatus.IN_PROGRESS -> {
            val scope = if (task.isRemoteNode) "Remote LAN" else "Local"
            "Running on ${task.assignedNodeName ?: "worker"} ($scope) · $sliceLabel"
        }
        TaskQueueItemStatus.COMPLETED -> {
            val node = task.assignedNodeName ?: "worker"
            val items = if (task.itemsProcessed > 0L) {
                " · ${formatCompactNumber(task.itemsProcessed)} ${task.workloadType.unitLabel}"
            } else ""
            "Done on $node · ${task.elapsedMs} ms$items"
        }
        TaskQueueItemStatus.FAILED -> {
            "Failed on ${task.assignedNodeName ?: "node"}: ${task.errorMessage ?: "Execution error"}"
        }
    }
}

private fun formatCompactNumber(value: Long): String = when {
    value >= 1_000_000L -> "${"%.1f".format(value / 1_000_000.0)}M"
    value >= 1_000L -> "${"%.1f".format(value / 1_000.0)}K"
    else -> value.toString()
}

internal fun resolveQueueTasks(pods: List<ClusterPod>): List<QueuedChunkState> {
    return pods.flatMap { pod ->
        if (pod.chunkTasks.isNotEmpty()) {
            pod.chunkTasks
        } else if (pod.totalChunks > 0) {
            val completed = pod.completedChunks.coerceIn(0, pod.totalChunks)
            (0 until pod.totalChunks).map { idx ->
                val status = when {
                    idx < completed -> TaskQueueItemStatus.COMPLETED
                    pod.phase == PodPhase.FAILED -> TaskQueueItemStatus.FAILED
                    pod.phase == PodPhase.RUNNING && idx == completed -> TaskQueueItemStatus.IN_PROGRESS
                    pod.phase == PodPhase.SUCCEEDED -> TaskQueueItemStatus.COMPLETED
                    else -> TaskQueueItemStatus.PENDING
                }
                val shard = if (pod.shards.isNotEmpty()) pod.shards[idx % pod.shards.size] else null
                QueuedChunkState(
                    podId = pod.podId,
                    podName = pod.name,
                    workloadType = pod.workloadType,
                    chunkIndex = idx,
                    totalChunks = pod.totalChunks,
                    rangeStart = shard?.rangeStart ?: 0L,
                    rangeEnd = shard?.rangeEnd ?: 0L,
                    iterations = shard?.iterations ?: 0L,
                    status = status,
                    assignedNodeId = shard?.nodeId,
                    assignedNodeName = if (status != TaskQueueItemStatus.PENDING) shard?.nodeName else null,
                    isRemoteNode = shard?.isRemote ?: false,
                    elapsedMs = if (status == TaskQueueItemStatus.COMPLETED) shard?.elapsedMs ?: 0L else 0L
                )
            }
        } else {
            pod.shards.mapIndexed { idx, shard ->
                val status = when (shard.phase) {
                    PodPhase.PENDING -> TaskQueueItemStatus.PENDING
                    PodPhase.RUNNING -> TaskQueueItemStatus.IN_PROGRESS
                    PodPhase.SUCCEEDED -> TaskQueueItemStatus.COMPLETED
                    PodPhase.FAILED -> TaskQueueItemStatus.FAILED
                }
                QueuedChunkState(
                    podId = pod.podId,
                    podName = pod.name,
                    workloadType = pod.workloadType,
                    chunkIndex = idx,
                    totalChunks = pod.shards.size,
                    rangeStart = shard.rangeStart,
                    rangeEnd = shard.rangeEnd,
                    iterations = shard.iterations,
                    status = status,
                    assignedNodeId = shard.nodeId,
                    assignedNodeName = shard.nodeName,
                    isRemoteNode = shard.isRemote,
                    elapsedMs = shard.elapsedMs,
                    itemsProcessed = shard.itemsProcessed,
                    errorMessage = shard.errorMessage
                )
            }
        }
    }
}
