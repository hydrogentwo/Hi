package com.example.cluster.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldReady
import kotlinx.coroutines.launch

enum class KubeTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CLUSTER("cluster", R.string.nav_cluster, Icons.Rounded.Dns, Icons.Outlined.Dns),
    MESH("mesh", R.string.nav_connect, Icons.Rounded.Hub, Icons.Outlined.Hub),
    PODS("pods", R.string.nav_workloads, Icons.Rounded.Memory, Icons.Outlined.Memory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KubeMeshApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: ClusterViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by remember { mutableStateOf(KubeTab.CLUSTER) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onAppLifecycleChanged(isForeground = true)
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppLifecycleChanged(isForeground = false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    BackHandler(enabled = currentTab != KubeTab.CLUSTER) {
        currentTab = KubeTab.CLUSTER
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "KubeMesh",
                                style = MaterialTheme.typography.titleLarge
                            )
                            StatusPill(
                                label = if (uiState.isUnifiedCluster) {
                                    "${1 + uiState.onlinePeersCount} Nodes"
                                } else {
                                    uiState.config.role.displayName
                                },
                                color = if (uiState.isUnifiedCluster) EmeraldReady else ElectricCyan
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("theme_toggle_btn")
                        ) {
                            Icon(
                                imageVector = if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = if (darkTheme) "Switch to light theme" else "Switch to dark theme"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWide) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        KubeTab.entries.forEach { tab ->
                            val selected = tab == currentTab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        KubeTab.entries.forEach { tab ->
                            val selected = tab == currentTab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_tab_${tab.route}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 920.dp)
                    ) {
                        when (currentTab) {
                            KubeTab.CLUSTER -> ClusterScreen(
                                uiState = uiState,
                                onNavigateToConnect = { currentTab = KubeTab.MESH },
                                onNavigateToWorkloads = { currentTab = KubeTab.PODS },
                                onSelectNode = viewModel::selectNodeForSheet,
                                onDisconnectPeer = viewModel::disconnectPeer,
                                onPairDiscoveredPeer = { peer ->
                                    viewModel.pairWithPeer(peer.host, peer.port, uiState.config.clusterToken)
                                }
                            )

                            KubeTab.MESH -> MeshConnectScreen(
                                uiState = uiState,
                                onSetRole = viewModel::setNodeRole,
                                onRegenerateToken = viewModel::regenerateToken,
                                onUpdateClusterToken = viewModel::updateClusterToken,
                                onToggleScan = viewModel::toggleLanScan,
                                onSetAutoJoinNsd = viewModel::setAutoJoinNsd,
                                onPairWithPeer = viewModel::pairWithPeer,
                                onSetDaemonEnabled = viewModel::setDaemonEnabled,
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )

                            KubeTab.PODS -> WorkloadsScreen(
                                uiState = uiState,
                                onDeployPod = viewModel::deployPod,
                                onInspectPod = viewModel::selectPodForLogs,
                                onCancelPod = viewModel::cancelPod,
                                onClearCompletedPods = viewModel::clearCompletedPods,
                                onRefreshAiCore = viewModel::refreshAiCoreStatus,
                                onDownloadAiCore = viewModel::downloadAiCoreModel,
                                onWarmupAiCore = viewModel::warmupAiCore,
                                onRunAiPrompt = viewModel::runClusterAiPrompt,
                                onDiagnoseClusterAi = viewModel::diagnoseClusterWithAiCore
                            )
                        }
                    }
                }
            }
        }
    }
}
