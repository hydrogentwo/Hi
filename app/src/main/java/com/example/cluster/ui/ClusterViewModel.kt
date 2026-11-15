package com.example.cluster.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cluster.ai.AiCoreEngine
import com.example.cluster.ai.AiCoreLocalState
import com.example.cluster.compute.ClusterTaskDistributor
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.compute.JobChunk
import com.example.cluster.data.ClusterPreferences
import com.example.cluster.data.SavedClusterConfig
import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeMetricSample
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.NsdStatus
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.WorkloadType
import com.example.cluster.network.ClusterClient
import com.example.cluster.network.NodeHttpServer
import com.example.cluster.network.NsdDiscoveryManager
import com.example.cluster.service.NodeDaemonService
import com.example.cluster.telemetry.DeviceTelemetryCollector
import com.squareup.moshi.Moshi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

class ClusterViewModel(application: Application) : AndroidViewModel(application) {

    private val moshi = Moshi.Builder().build()
    private val telemetryCollector = DeviceTelemetryCollector(application)
    private val computeEngine = ComputeEngine()
    private val taskDistributor = ClusterTaskDistributor()
    private val aiCoreEngine = AiCoreEngine()
    private val preferences = ClusterPreferences(application)
    private val clusterClient = ClusterClient(moshi)
    private val nsdManager = NsdDiscoveryManager(application) { msg -> appendClusterLog(msg) }

    private val activeLocalPods = AtomicInteger(0)
    private val missedHeartbeats = ConcurrentHashMap<String, Int>()
    private val autoJoinCooldownMs = mutableMapOf<String, Long>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val disconnectedNodeIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    private val _uiState = MutableStateFlow(ClusterUiState())
    val uiState: StateFlow<ClusterUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val nodeServer = NodeHttpServer(
        computeEngine = computeEngine,
        moshi = moshi,
        getLocalTelemetry = { collectFreshLocalTelemetry() },
        getClusterToken = { _uiState.value.config.clusterToken },
        onPeerPaired = { peer, remoteIp ->
            viewModelScope.launch {
                val updatedPeer = peer.copy(
                    hostIp = if (peer.hostIp.isBlank() || peer.hostIp == "127.0.0.1") remoteIp else peer.hostIp,
                    lastHeartbeatMs = System.currentTimeMillis(),
                    isLocal = false
                )
                addOrUpdateRemotePeer(updatedPeer)
                appendClusterLog("Peer joined mesh: ${updatedPeer.nodeName} (${updatedPeer.hostIp}:${updatedPeer.port}, ${updatedPeer.cpuCores} vCPU)")
                _events.tryEmit("Node ${updatedPeer.nodeName} unified into cluster")
            }
        },
        onPeerUnpaired = { nodeId ->
            viewModelScope.launch {
                val peerName = _uiState.value.remotePeers.firstOrNull { it.nodeId == nodeId }?.nodeName ?: nodeId
                disconnectedNodeIdsFlow.update { it + nodeId }
                _uiState.update { state ->
                    state.copy(
                        remotePeers = state.remotePeers.filterNot { it.nodeId == nodeId },
                        unreachablePeerIds = state.unreachablePeerIds - nodeId
                    )
                }
                appendClusterLog("Node $peerName drained from mesh — re-queueing any active tasks")
            }
        },
        onServerLog = { msg -> appendClusterLog(msg) },
        onExecuteAiPrompt = { req ->
            val localName = _uiState.value.config.nodeName
            val res = aiCoreEngine.generate(
                prompt = req.prompt,
                systemInstruction = req.systemInstruction,
                temperature = req.temperature,
                maxOutputTokens = req.maxOutputTokens
            )
            AiCorePromptResponse(
                success = res.success,
                outputText = res.outputText,
                elapsedMs = res.elapsedMs,
                targetNodeName = localName,
                aiCoreStatus = res.status.name,
                errorMessage = res.errorMessage
            )
        }
    )

    init {
        viewModelScope.launch {
            preferences.ensureInitialized()
            val initialConfig = preferences.configFlow.first()
            _uiState.update { it.copy(config = initialConfig) }

            val aiStatus = aiCoreEngine.refreshStatus()
            val boundPort = nodeServer.start(7443)
            val initialLocal = collectFreshLocalTelemetry(initialConfig, boundPort, aiStatus)
            _uiState.update {
                it.copy(
                    serverPort = boundPort,
                    localNode = initialLocal
                )
            }
            registerLocalNsdService(initialLocal)
            // Automatically begin LAN discovery so devices detect each other immediately
            nsdManager.startDiscovery()
            appendClusterLog("Control/Worker agent started on ${telemetryCollector.resolveLanIpv4Address()}:$boundPort (AICore: ${aiStatus.label})")

            if (initialConfig.daemonEnabled) {
                startForegroundDaemon()
            }

            launch { observeAiCore() }
            launch { observePreferences() }
            launch { observeNsd() }
            launch { telemetryAndHeartbeatLoop() }
        }
    }

    private fun registerLocalNsdService(local: NodeTelemetry) {
        nsdManager.registerService(
            nodeName = local.nodeName,
            nodeId = local.nodeId,
            role = local.role,
            cpuCores = local.cpuCores,
            totalRamMb = local.totalRamMb,
            deviceModel = local.deviceModel,
            port = local.port,
            aiCoreStatus = AiCoreStatus.fromWire(local.aiCoreStatus)
        )
    }

    private suspend fun observeAiCore() {
        aiCoreEngine.state.collect { aiState ->
            val prevStatus = _uiState.value.aiCoreLocal.status
            _uiState.update { it.copy(aiCoreLocal = aiState) }
            if (prevStatus != aiState.status && nodeServer.isRunning) {
                registerLocalNsdService(collectFreshLocalTelemetry())
            }
        }
    }

    private suspend fun observePreferences() {
        preferences.configFlow.collect { cfg ->
            val prevName = _uiState.value.config.nodeName
            val prevRole = _uiState.value.config.role
            _uiState.update { it.copy(config = cfg) }
            if ((prevName != cfg.nodeName || prevRole != cfg.role) && nodeServer.isRunning) {
                registerLocalNsdService(collectFreshLocalTelemetry(cfg, nodeServer.boundPort))
            }
        }
    }

    private suspend fun observeNsd() = coroutineScope {
        launch {
            nsdManager.discoveredPeers.collect { peers ->
                val localPort = nodeServer.boundPort
                val localIp = _uiState.value.localNode?.hostIp
                val localId = _uiState.value.config.nodeId
                val filtered = peers.filterNot {
                    (it.nodeId.isNotEmpty() && it.nodeId == localId) ||
                        (it.host == localIp && it.port == localPort)
                }
                _uiState.update { it.copy(discoveredPeers = filtered) }
                attemptAutoJoinDiscoveredPeers(filtered)
            }
        }
        launch {
            nsdManager.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
        launch {
            nsdManager.isAdvertising.collect { adv ->
                _uiState.update { it.copy(isAdvertising = adv) }
            }
        }
        launch {
            nsdManager.nsdStatus.collect { status ->
                _uiState.update { it.copy(nsdStatus = status) }
            }
        }
        launch {
            nsdManager.lastError.collect { err ->
                _uiState.update { it.copy(nsdError = err) }
            }
        }
        launch {
            nsdManager.lostPeers.collect { lost ->
                val pairedPeer = _uiState.value.remotePeers.firstOrNull { rp ->
                    (lost.nodeId.isNotEmpty() && rp.nodeId == lost.nodeId) ||
                        (rp.hostIp == lost.host && rp.port == lost.port)
                } ?: return@collect
                if (pairedPeer.nodeId !in _uiState.value.unreachablePeerIds) {
                    val token = _uiState.value.config.clusterToken
                    launch {
                        val health = clusterClient.fetchHealth(pairedPeer.hostIp, pairedPeer.port, token)
                        if (health.isFailure) {
                            markPeerDisconnected(pairedPeer, "NSD service lost")
                        }
                    }
                }
            }
        }
    }

    private fun attemptAutoJoinDiscoveredPeers(peers: List<DiscoveredPeer>) {
        val state = _uiState.value
        if (!state.config.autoJoinNsd || state.isPairing) return
        val now = System.currentTimeMillis()
        val candidate = peers.firstOrNull { peer ->
            val alreadyPaired = state.remotePeers.any { rp ->
                (peer.nodeId.isNotEmpty() && rp.nodeId == peer.nodeId) ||
                    (rp.hostIp == peer.host && rp.port == peer.port)
            }
            val key = "${peer.host}:${peer.port}"
            val lastAttempt = autoJoinCooldownMs[key] ?: 0L
            !alreadyPaired && (now - lastAttempt) > 20_000L
        } ?: return

        val endpointKey = "${candidate.host}:${candidate.port}"
        autoJoinCooldownMs[endpointKey] = now
        val local = collectFreshLocalTelemetry()
        val token = state.config.clusterToken

        viewModelScope.launch {
            val result = clusterClient.pairWithNode(candidate.host, candidate.port, token, local)
            result.onSuccess { resp ->
                preferences.saveLastPeer(candidate.host, candidate.port)
                addOrUpdateRemotePeer(resp.hostNode)
                appendClusterLog("NSD auto-unified ${resp.hostNode.nodeName} ($endpointKey)")
                _events.tryEmit("Auto-unified with ${resp.hostNode.nodeName} via NSD")
            }.onFailure {
                // Silent if token differs; peer remains listed in NSD discovered peers for manual join
                appendClusterLog("NSD detected ${candidate.displayTitle} ($endpointKey) — awaiting matching token")
            }
        }
    }

    private suspend fun telemetryAndHeartbeatLoop() {
        while (viewModelScope.isActive) {
            val freshLocal = collectFreshLocalTelemetry()
            _uiState.update { state ->
                state.copy(
                    localNode = freshLocal,
                    metricHistory = appendSample(state.metricHistory, freshLocal)
                )
            }

            val peers = _uiState.value.remotePeers
            val token = _uiState.value.config.clusterToken
            val hasActivePods = _uiState.value.pods.any { it.phase == PodPhase.RUNNING }
            val failureThreshold = if (hasActivePods) 2 else 3

            if (peers.isNotEmpty()) {
                coroutineScope {
                    peers.forEach { peer ->
                        launch {
                            val result = clusterClient.fetchHealth(peer.hostIp, peer.port, token)
                            result.onSuccess { updated ->
                                val wasUnreachable = peer.nodeId in _uiState.value.unreachablePeerIds
                                missedHeartbeats[peer.nodeId] = 0
                                addOrUpdateRemotePeer(updated)
                                if (wasUnreachable) {
                                    appendClusterLog("Node ${peer.nodeName} reconnected to mesh")
                                    _events.tryEmit("${peer.nodeName} reconnected")
                                }
                            }.onFailure {
                                val misses = (missedHeartbeats[peer.nodeId] ?: 0) + 1
                                missedHeartbeats[peer.nodeId] = misses
                                if (misses >= failureThreshold) {
                                    markPeerDisconnected(peer, "Heartbeat timeout ($misses misses)")
                                }
                            }
                        }
                    }
                }
            }
            delay(if (hasActivePods) 1000L else 2200L)
        }
    }

    private fun markPeerDisconnected(peer: NodeTelemetry, reason: String) {
        val newlyDisconnected = peer.nodeId !in _uiState.value.unreachablePeerIds
        disconnectedNodeIdsFlow.update { it + peer.nodeId }
        _uiState.update { s ->
            s.copy(unreachablePeerIds = s.unreachablePeerIds + peer.nodeId)
        }
        if (newlyDisconnected) {
            val hasRunningPods = _uiState.value.pods.any { it.phase == PodPhase.RUNNING }
            if (hasRunningPods) {
                appendClusterLog("Node ${peer.nodeName} disconnected unexpectedly ($reason) — re-queueing pending tasks")
                _events.tryEmit("${peer.nodeName} disconnected · re-queueing tasks")
            } else {
                appendClusterLog("Node ${peer.nodeName} marked NotReady ($reason)")
            }
        }
    }

    private fun appendSample(
        history: Map<String, List<NodeMetricSample>>,
        node: NodeTelemetry
    ): Map<String, List<NodeMetricSample>> {
        val sample = NodeMetricSample(
            cpuLoadPercent = node.cpuLoadPercent,
            ramUsagePercent = (node.ramUsageFraction * 100f).toInt().coerceIn(0, 100),
            batteryPercent = node.batteryPercent
        )
        val updatedList = (history[node.nodeId].orEmpty() + sample).takeLast(24)
        return history + (node.nodeId to updatedList)
    }

    private fun collectFreshLocalTelemetry(
        cfg: SavedClusterConfig = _uiState.value.config,
        port: Int = nodeServer.boundPort,
        aiCoreStatus: AiCoreStatus = aiCoreEngine.state.value.status
    ): NodeTelemetry {
        return telemetryCollector.collect(
            nodeId = cfg.nodeId,
            nodeName = cfg.nodeName,
            role = cfg.role,
            serverPort = port,
            activePods = activeLocalPods.get(),
            aiCoreStatus = aiCoreStatus
        )
    }

    private fun addOrUpdateRemotePeer(peer: NodeTelemetry) {
        missedHeartbeats[peer.nodeId] = 0
        disconnectedNodeIdsFlow.update { it - peer.nodeId }
        _uiState.update { state ->
            val others = state.remotePeers.filterNot {
                it.nodeId == peer.nodeId || (it.hostIp == peer.hostIp && it.port == peer.port)
            }
            state.copy(
                remotePeers = (others + peer).sortedBy { it.nodeName },
                unreachablePeerIds = state.unreachablePeerIds - peer.nodeId,
                metricHistory = appendSample(state.metricHistory, peer)
            )
        }
    }

    fun setNodeRole(role: NodeRole) {
        viewModelScope.launch {
            preferences.updateRole(role)
            appendClusterLog("Node role switched to ${role.displayName}")
            if (_uiState.value.config.daemonEnabled) {
                startForegroundDaemon()
            }
        }
    }

    fun updateNodeName(name: String) {
        viewModelScope.launch {
            preferences.updateNodeName(name)
            appendClusterLog("Node hostname updated to $name")
        }
    }

    fun updateClusterToken(token: String) {
        viewModelScope.launch {
            preferences.updateClusterToken(token)
            appendClusterLog("Cluster token updated")
            _events.tryEmit("Cluster token updated")
        }
    }

    fun regenerateToken() {
        viewModelScope.launch {
            val next = preferences.regenerateClusterToken()
            appendClusterLog("Rotated cluster join token to $next")
            _events.tryEmit("New join token: $next")
        }
    }

    fun toggleLanScan() {
        if (_uiState.value.isScanning) {
            nsdManager.stopDiscovery()
            appendClusterLog("Paused LAN NSD discovery")
        } else {
            nsdManager.refreshDiscovery()
            appendClusterLog("Scanning LAN for _kubemesh._tcp. nodes...")
        }
    }

    fun setAutoJoinNsd(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoJoinNsd(enabled)
            appendClusterLog("NSD auto-join ${if (enabled) "enabled" else "disabled"}")
            if (enabled) {
                autoJoinCooldownMs.clear()
                attemptAutoJoinDiscoveredPeers(_uiState.value.discoveredPeers)
            }
        }
    }

    fun onAppLifecycleChanged(isForeground: Boolean) {
        val local = _uiState.value.localNode ?: return
        if (isForeground) {
            if (!nsdManager.isAdvertising.value && nodeServer.isRunning) {
                registerLocalNsdService(local)
            }
            if (!nsdManager.isScanning.value) {
                nsdManager.startDiscovery()
            }
        } else if (!_uiState.value.config.daemonEnabled) {
            nsdManager.stopDiscovery()
        }
    }

    fun pairWithPeer(hostInput: String, portInput: Int, tokenInput: String) {
        val host = hostInput.trim()
        if (host.isEmpty() || portInput !in 1..65535) {
            _events.tryEmit("Enter a valid LAN IPv4 address and port")
            return
        }
        val token = tokenInput.trim().uppercase().ifEmpty { _uiState.value.config.clusterToken }
        val local = collectFreshLocalTelemetry()
        if (host == local.hostIp && portInput == local.port) {
            _events.tryEmit("Cannot pair a device with its own endpoint")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isPairing = true) }
            // Align our token with entered cluster token when joining a Control Plane as Worker
            if (_uiState.value.config.role == NodeRole.WORKER && token != _uiState.value.config.clusterToken) {
                preferences.updateClusterToken(token)
            }
            val result = clusterClient.pairWithNode(host, portInput, token, local)
            result.onSuccess { resp ->
                preferences.saveLastPeer(host, portInput)
                addOrUpdateRemotePeer(resp.hostNode)
                appendClusterLog("Unified with ${resp.hostNode.nodeName} ($host:$portInput · ${resp.hostNode.cpuCores} vCPU, ${resp.hostNode.totalRamMb} MB RAM)")
                _events.tryEmit("Connected to ${resp.hostNode.nodeName}")
            }.onFailure { err ->
                appendClusterLog("Pair failed ($host:$portInput): ${err.message}")
                _events.tryEmit(err.message ?: "Failed to connect to peer")
            }
            _uiState.update { it.copy(isPairing = false) }
        }
    }

    fun disconnectPeer(nodeId: String) {
        val peer = _uiState.value.remotePeers.firstOrNull { it.nodeId == nodeId } ?: return
        val token = _uiState.value.config.clusterToken
        val localId = _uiState.value.config.nodeId
        viewModelScope.launch {
            disconnectedNodeIdsFlow.update { it + nodeId }
            clusterClient.notifyUnpair(peer.hostIp, peer.port, token, localId)
            _uiState.update { state ->
                state.copy(
                    remotePeers = state.remotePeers.filterNot { it.nodeId == nodeId },
                    unreachablePeerIds = state.unreachablePeerIds - nodeId
                )
            }
            appendClusterLog("Drained and disconnected ${peer.nodeName}")
            _events.tryEmit("Disconnected ${peer.nodeName}")
        }
    }

    fun setDaemonEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDaemonEnabled(enabled)
            if (enabled) {
                startForegroundDaemon()
                appendClusterLog("Foreground NodeDaemonService started")
            } else {
                NodeDaemonService.stop(getApplication())
                appendClusterLog("Foreground NodeDaemonService stopped")
            }
        }
    }

    private fun startForegroundDaemon() {
        val local = _uiState.value.localNode ?: collectFreshLocalTelemetry()
        NodeDaemonService.start(
            context = getApplication(),
            endpoint = local.endpoint,
            roleLabel = _uiState.value.config.role.displayName
        )
    }

    fun selectNodeForSheet(node: NodeTelemetry?) {
        _uiState.update { it.copy(selectedNodeForSheet = node) }
    }

    fun selectPodForLogs(podId: String?) {
        _uiState.update { it.copy(selectedPodForLogs = podId) }
    }

    fun deployPod(
        workloadType: WorkloadType,
        schedulingPolicy: SchedulingPolicy,
        scaleMultiplier: Int = 1,
        customPodName: String? = null
    ) {
        val local = _uiState.value.localNode ?: collectFreshLocalTelemetry()
        val onlineRemotes = _uiState.value.remotePeers.filter { it.nodeId !in _uiState.value.unreachablePeerIds }

        val targetNodes: List<NodeTelemetry> = when (schedulingPolicy) {
            SchedulingPolicy.PROPORTIONAL_SPLIT -> listOf(local) + onlineRemotes
            SchedulingPolicy.WORKER_PREFERRED -> if (onlineRemotes.isNotEmpty()) onlineRemotes else listOf(local)
            SchedulingPolicy.CONTROL_PLANE_ONLY -> listOf(local)
        }

        val podId = UUID.randomUUID().toString().take(8)
        val podName = customPodName?.trim()?.lowercase()?.replace(Regex("[^a-z0-9-]"), "-")
            ?.takeIf { it.isNotEmpty() }
            ?: "${workloadType.id}-${podId.take(4)}"

        val totalWeight = targetNodes.sumOf { it.cpuCores.coerceAtLeast(1) }.coerceAtLeast(1)
        val totalWork = when (workloadType) {
            WorkloadType.PRIME_SIEVE -> workloadType.defaultIterations * scaleMultiplier
            WorkloadType.MONTE_CARLO_PI -> workloadType.defaultIterations * scaleMultiplier
            WorkloadType.SHA256_BENCHMARK -> workloadType.defaultIterations * scaleMultiplier
            WorkloadType.MATRIX_MATMUL -> if (scaleMultiplier >= 2) 400L else 320L
        }

        val chunks = taskDistributor.sliceJobIntoChunks(
            workloadType = workloadType,
            totalWork = totalWork,
            nodeCount = targetNodes.size,
            chunksPerNode = 4
        )
        val initialShards = buildShards(
            podId = podId,
            workloadType = workloadType,
            targetNodes = targetNodes,
            totalWeight = totalWeight,
            totalWork = totalWork
        )

        val requestedCores = targetNodes.sumOf { it.cpuCores }
        val requestedMemMb = when (workloadType) {
            WorkloadType.MATRIX_MATMUL -> 256 * targetNodes.size
            WorkloadType.PRIME_SIEVE -> 128 * targetNodes.size
            else -> 96 * targetNodes.size
        }

        val yaml = buildPodYaml(
            podName = podName,
            workloadType = workloadType,
            schedulingPolicy = schedulingPolicy,
            requestedCores = requestedCores,
            requestedMemMb = requestedMemMb,
            shards = initialShards,
            chunkCount = chunks.size
        )

        val initialLogs = listOf(
            "[${nowTime()}] scheduler: Assigned pod $podName to ${targetNodes.size} node(s) via ${schedulingPolicy.label}",
            "[${nowTime()}] channel-queue: Sliced job into ${chunks.size} chunks on Coroutine Channel for ${targetNodes.size} worker(s)",
            "[${nowTime()}] kubelet: Pulling kernel ${workloadType.kubeImage} (cached in APK)"
        )

        val pod = ClusterPod(
            podId = podId,
            name = podName,
            workloadType = workloadType,
            schedulingPolicy = schedulingPolicy,
            phase = PodPhase.RUNNING,
            requestedCores = requestedCores,
            requestedMemMb = requestedMemMb,
            intensityLabel = "${formatCount(totalWork)} ${workloadType.unitLabel}",
            createdAtMs = System.currentTimeMillis(),
            aggregatedResult = "Dispatching ${chunks.size} chunks across ${targetNodes.size} node(s)...",
            shards = initialShards,
            logs = initialLogs,
            yamlManifest = yaml,
            totalChunks = chunks.size,
            completedChunks = 0,
            chunkTasks = taskDistributor.buildInitialChunkStates(podId, podName, workloadType, chunks)
        )

        _uiState.update { it.copy(pods = listOf(pod) + it.pods) }
        appendClusterLog("kubectl apply: Pod $podName (${chunks.size} channel chunks) -> ${targetNodes.joinToString { it.nodeName }}")

        viewModelScope.launch {
            executePodChunks(pod, targetNodes, chunks)
        }
    }

    private fun buildShards(
        podId: String,
        workloadType: WorkloadType,
        targetNodes: List<NodeTelemetry>,
        totalWeight: Int,
        totalWork: Long
    ): List<PodShard> {
        var cursor = when (workloadType) {
            WorkloadType.PRIME_SIEVE -> 2L
            WorkloadType.MATRIX_MATMUL -> 0L
            else -> 0L
        }
        val endLimit = when (workloadType) {
            WorkloadType.PRIME_SIEVE -> totalWork + 1L
            WorkloadType.MATRIX_MATMUL -> totalWork // exclusive row end
            else -> totalWork
        }

        return targetNodes.mapIndexed { index, node ->
            val isLast = index == targetNodes.lastIndex
            val fraction = node.cpuCores.coerceAtLeast(1).toDouble() / totalWeight.toDouble()
            val weightPct = ((fraction * 100.0).toInt()).coerceIn(1, 100)

            val sliceCount = if (isLast) {
                (endLimit - cursor).coerceAtLeast(1L)
            } else {
                (totalWork * fraction).toLong().coerceAtLeast(1L)
            }

            val start = cursor
            val end = if (workloadType == WorkloadType.MATRIX_MATMUL) {
                (start + sliceCount).coerceAtMost(endLimit)
            } else {
                (start + sliceCount - 1L).coerceAtMost(endLimit)
            }
            cursor = if (workloadType == WorkloadType.MATRIX_MATMUL) end else end + 1L

            PodShard(
                shardId = "${podId}-${index}",
                nodeId = node.nodeId,
                nodeName = node.nodeName,
                isRemote = !node.isLocal,
                hostEndpoint = node.endpoint,
                weightPercent = weightPct,
                rangeStart = start,
                rangeEnd = end,
                iterations = sliceCount,
                phase = PodPhase.RUNNING,
                chunksCompleted = 0
            )
        }
    }

    private suspend fun executePodChunks(
        pod: ClusterPod,
        targetNodes: List<NodeTelemetry>,
        chunks: List<JobChunk>
    ) {
        val token = _uiState.value.config.clusterToken

        val outcome = taskDistributor.distributeAndExecute(
            podId = pod.podId,
            podName = pod.name,
            workloadType = pod.workloadType,
            clusterToken = token,
            targetNodes = targetNodes,
            chunks = chunks,
            executeChunk = { node, req ->
                if (node.isLocal) {
                    activeLocalPods.incrementAndGet()
                    try {
                        Result.success(computeEngine.executeShard(req))
                    } finally {
                        activeLocalPods.decrementAndGet()
                    }
                } else {
                    val res = clusterClient.executeRemoteShard(node.hostIp, node.port, req)
                    res.onSuccess { resp ->
                        resp.nodeTelemetry?.let { addOrUpdateRemotePeer(it.copy(isLocal = false)) }
                    }
                    res
                }
            },
            onProgress = { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        pods = state.pods.map { current ->
                            if (current.podId == pod.podId) {
                                val updatedLogs = if (snapshot.latestEventLog != null) {
                                    current.logs + "[${nowTime()}] ${snapshot.latestEventLog}"
                                } else {
                                    current.logs
                                }
                                current.copy(
                                    completedChunks = snapshot.completedChunks,
                                    aggregatedResult = "Channel pipeline: ${snapshot.completedChunks}/${snapshot.totalChunks} chunks complete",
                                    shards = if (snapshot.nodeShards.isNotEmpty()) snapshot.nodeShards else current.shards,
                                    logs = updatedLogs,
                                    chunkTasks = if (snapshot.chunkTasks.isNotEmpty()) snapshot.chunkTasks else current.chunkTasks
                                )
                            } else current
                        }
                    )
                }
            },
            disconnectedNodeIds = disconnectedNodeIdsFlow,
            fallbackNodesProvider = {
                if (pod.schedulingPolicy == SchedulingPolicy.CONTROL_PLANE_ONLY) {
                    emptyList()
                } else {
                    val s = _uiState.value
                    val onlinePeers = s.remotePeers.filter { it.nodeId !in s.unreachablePeerIds }
                    listOfNotNull(s.localNode) + onlinePeers
                }
            }
        )

        val wallElapsed = outcome.wallElapsedMs
        val completedShards = outcome.nodeShards
        val allOk = outcome.completedChunks == outcome.totalChunks &&
            completedShards.any { it.phase == PodPhase.SUCCEEDED }
        val (summary, speedupNote) = aggregatePodResult(pod.workloadType, completedShards, wallElapsed)

        val finalLogLine = "[${nowTime()}] controller: Pod ${pod.name} ${if (allOk) "Succeeded" else "Failed"} " +
            "(${outcome.completedChunks}/${outcome.totalChunks} chunks in ${wallElapsed}ms) → $summary"

        _uiState.update { state ->
            state.copy(
                pods = state.pods.map { current ->
                    if (current.podId == pod.podId) {
                        current.copy(
                            phase = if (allOk) PodPhase.SUCCEEDED else PodPhase.FAILED,
                            completedAtMs = System.currentTimeMillis(),
                            totalElapsedMs = wallElapsed,
                            completedChunks = outcome.completedChunks,
                            aggregatedResult = summary,
                            speedupNote = speedupNote,
                            shards = completedShards,
                            logs = current.logs + finalLogLine,
                            chunkTasks = if (outcome.chunkTasks.isNotEmpty()) outcome.chunkTasks else current.chunkTasks
                        )
                    } else current
                }
            )
        }
        appendClusterLog("Pod ${pod.name} finished (${outcome.completedChunks}/${outcome.totalChunks} chunks, ${wallElapsed}ms): $summary")
    }

    private fun aggregatePodResult(
        type: WorkloadType,
        shards: List<PodShard>,
        wallMs: Long
    ): Pair<String, String> {
        val succeeded = shards.filter { it.phase == PodPhase.SUCCEEDED }
        if (succeeded.isEmpty()) {
            return "All shards failed: ${shards.firstOrNull()?.errorMessage ?: "Network error"}" to ""
        }
        val totalItems = succeeded.sumOf { it.itemsProcessed }
        val totalChunks = succeeded.sumOf { it.chunksCompleted }
        val combinedThroughput = (totalItems * 1000.0) / wallMs.toDouble()
        val sumNodeTimes = succeeded.sumOf { it.elapsedMs }
        val parallelNote = if (succeeded.size > 1) {
            "Channel-balanced $totalChunks chunks across ${succeeded.size} nodes in ${wallMs}ms (${sumNodeTimes}ms CPU sum)"
        } else {
            "$totalChunks chunks on 1 node (${wallMs}ms) · Connect 2nd device to distribute"
        }

        val summary = when (type) {
            WorkloadType.PRIME_SIEVE -> {
                val totalPrimes = succeeded.sumOf { it.primaryValue.toLong() }
                val maxPrime = succeeded.mapNotNull {
                    it.secondaryText.substringAfter("maxPrime=", "").toLongOrNull()
                }.maxOrNull() ?: 0L
                "${formatCount(totalPrimes)} primes found · max $maxPrime"
            }

            WorkloadType.MONTE_CARLO_PI -> {
                val hits = succeeded.sumOf { it.primaryValue }
                val pi = 4.0 * hits / totalItems.coerceAtLeast(1L).toDouble()
                "π ≈ ${"%.7f".format(pi)} (${formatRate(combinedThroughput, type)})"
            }

            WorkloadType.SHA256_BENCHMARK -> {
                val bestBits = succeeded.maxOf { it.primaryValue.toInt() }
                "${formatCount(totalItems)} hashes · ${formatRate(combinedThroughput, type)} · best $bestBits-bit zero prefix"
            }

            WorkloadType.MATRIX_MATMUL -> {
                val checksum = succeeded.sumOf { it.primaryValue }
                "${totalItems} rows multiplied · checksum ${"%.2f".format(checksum)}"
            }
        }
        return summary to parallelNote
    }

    private fun buildPodYaml(
        podName: String,
        workloadType: WorkloadType,
        schedulingPolicy: SchedulingPolicy,
        requestedCores: Int,
        requestedMemMb: Int,
        shards: List<PodShard>,
        chunkCount: Int
    ): String {
        val nodeLines = shards.joinToString("\n") {
            "    - node: ${it.nodeName} # ${it.weightPercent}% (${it.rangeStart}..${it.rangeEnd})"
        }
        return """
apiVersion: v1
kind: Pod
metadata:
  name: $podName
  namespace: compute-mesh
  annotations:
    kubemesh.io/scheduler: "${schedulingPolicy.name}"
    kubemesh.io/channel-chunks: "$chunkCount"
spec:
  restartPolicy: Never
  topologySpreadConstraints:
$nodeLines
  containers:
    - name: compute-kernel
      image: ${workloadType.kubeImage}
      resources:
        requests:
          cpu: "${requestedCores}000m"
          memory: "${requestedMemMb}Mi"
        """.trimIndent()
    }

    private fun appendClusterLog(message: String) {
        val line = "[${nowTime()}] $message"
        _uiState.update { state ->
            state.copy(clusterLogs = (listOf(line) + state.clusterLogs).take(80))
        }
    }

    private fun nowTime(): String = timeFormatter.format(Date())

    private fun formatCount(value: Long): String = when {
        value >= 1_000_000L -> "${"%.2f".format(value / 1_000_000.0)}M"
        value >= 1_000L -> "${"%.1f".format(value / 1_000.0)}K"
        else -> value.toString()
    }

    private fun formatRate(perSec: Double, type: WorkloadType): String {
        val unit = when (type) {
            WorkloadType.PRIME_SIEVE -> "int/s"
            WorkloadType.MONTE_CARLO_PI -> "samples/s"
            WorkloadType.SHA256_BENCHMARK -> "H/s"
            WorkloadType.MATRIX_MATMUL -> "rows/s"
        }
        return when {
            perSec >= 1_000_000.0 -> "${"%.2f".format(perSec / 1_000_000.0)} M$unit"
            perSec >= 1_000.0 -> "${"%.1f".format(perSec / 1_000.0)} K$unit"
            else -> "${"%.0f".format(perSec)} $unit"
        }
    }

    fun refreshAiCoreStatus() {
        viewModelScope.launch {
            val status = aiCoreEngine.refreshStatus()
            appendClusterLog("AICore Gemini Nano status: ${status.label}")
        }
    }

    fun downloadAiCoreModel() {
        viewModelScope.launch {
            appendClusterLog("Requesting Gemini Nano download via AICore...")
            aiCoreEngine.downloadModel()
                .onSuccess {
                    appendClusterLog("Gemini Nano ready in AICore")
                    _events.tryEmit("Gemini Nano downloaded and ready")
                }
                .onFailure { err ->
                    appendClusterLog("AICore download failed: ${err.message}")
                    _events.tryEmit(err.message ?: "AICore download failed")
                }
        }
    }

    fun warmupAiCore() {
        viewModelScope.launch {
            aiCoreEngine.warmup()
                .onSuccess {
                    appendClusterLog("AICore Gemini Nano warmed up in memory")
                    _events.tryEmit("Gemini Nano warmed up")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "AICore warmup failed")
                }
        }
    }

    fun diagnoseClusterWithAiCore(targetNodeId: String? = null) {
        val nodes = _uiState.value.allNodes
        val nodeLines = nodes.joinToString("\n") { n ->
            val offline = !n.isLocal && n.nodeId in _uiState.value.unreachablePeerIds
            "- ${n.nodeName} (${n.role.displayName}, ${if (offline) "Offline" else "Ready"}): " +
                "${n.cpuCores} vCPU @ ${n.cpuLoadPercent}% load, " +
                "RAM ${n.availRamMb}/${n.totalRamMb} MB free, " +
                "Battery ${n.batteryPercent}% ${if (n.isCharging) "charging" else "battery"}, " +
                "Thermal ${n.thermalStatusLabel}, RTT ${n.rttMs}ms"
        }
        val prompt = """
            Analyze this live Android KubeMesh compute cluster telemetry and give:
            1) Overall cluster readiness in 1 sentence.
            2) Best node & scheduling policy recommendation for the next compute pod.
            3) Any thermal, memory, or battery bottleneck to watch.

            Cluster Nodes:
            $nodeLines
        """.trimIndent()
        runClusterAiPrompt(prompt = prompt, targetNodeId = targetNodeId)
    }

    fun runClusterAiPrompt(prompt: String, targetNodeId: String? = null) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            _events.tryEmit("Enter a prompt for AICore")
            return
        }

        val state = _uiState.value
        val onlineRemotes = state.remotePeers.filter { it.nodeId !in state.unreachablePeerIds }
        val localReady = state.aiCoreLocal.status == AiCoreStatus.AVAILABLE
        val selectedPeer = when {
            targetNodeId == null -> {
                if (localReady) null
                else onlineRemotes.firstOrNull {
                    AiCoreStatus.fromWire(it.aiCoreStatus) == AiCoreStatus.AVAILABLE
                }
            }
            targetNodeId == state.localNode?.nodeId -> null
            else -> onlineRemotes.firstOrNull { it.nodeId == targetNodeId }
        }

        val systemPrompt = "You are KubeMesh on-device cluster advisor running via Android AICore Gemini Nano. Be concise and technical."

        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            if (selectedPeer == null) {
                val localName = state.localNode?.nodeName ?: state.config.nodeName
                _uiState.update {
                    it.copy(
                        aiCoreInference = AiCoreInferenceState(
                            isRunning = true,
                            outputText = "",
                            targetNodeName = localName,
                            elapsedMs = 0L,
                            errorMessage = null
                        )
                    )
                }
                appendClusterLog("AICore inference started locally on $localName")
                val builder = StringBuilder()
                try {
                    aiCoreEngine.generateStream(
                        prompt = cleanPrompt,
                        systemInstruction = systemPrompt
                    ).collect { chunk ->
                        builder.append(chunk)
                        _uiState.update { s ->
                            s.copy(
                                aiCoreInference = s.aiCoreInference.copy(
                                    outputText = builder.toString(),
                                    elapsedMs = System.currentTimeMillis() - startMs
                                )
                            )
                        }
                    }
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    _uiState.update { s ->
                        s.copy(
                            aiCoreInference = s.aiCoreInference.copy(
                                isRunning = false,
                                elapsedMs = elapsed
                            )
                        )
                    }
                    appendClusterLog("AICore local inference completed in ${elapsed}ms")
                } catch (t: Throwable) {
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    _uiState.update { s ->
                        s.copy(
                            aiCoreInference = s.aiCoreInference.copy(
                                isRunning = false,
                                elapsedMs = elapsed,
                                errorMessage = t.message ?: "AICore inference failed"
                            )
                        )
                    }
                    appendClusterLog("AICore inference error: ${t.message}")
                }
            } else {
                _uiState.update {
                    it.copy(
                        aiCoreInference = AiCoreInferenceState(
                            isRunning = true,
                            outputText = "",
                            targetNodeName = selectedPeer.nodeName,
                            elapsedMs = 0L,
                            errorMessage = null
                        )
                    )
                }
                appendClusterLog("Routing AICore prompt over LAN to ${selectedPeer.nodeName} (${selectedPeer.endpoint})")
                val req = AiCorePromptRequest(
                    clusterToken = state.config.clusterToken,
                    prompt = cleanPrompt,
                    systemInstruction = systemPrompt
                )
                clusterClient.executeRemoteAiPrompt(selectedPeer.hostIp, selectedPeer.port, req)
                    .onSuccess { resp ->
                        _uiState.update { s ->
                            s.copy(
                                aiCoreInference = AiCoreInferenceState(
                                    isRunning = false,
                                    outputText = resp.outputText,
                                    targetNodeName = resp.targetNodeName,
                                    elapsedMs = resp.elapsedMs,
                                    errorMessage = if (resp.success) null else resp.errorMessage
                                )
                            )
                        }
                        appendClusterLog("Peer ${resp.targetNodeName} AICore finished in ${resp.elapsedMs}ms")
                    }
                    .onFailure { err ->
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        _uiState.update { s ->
                            s.copy(
                                aiCoreInference = AiCoreInferenceState(
                                    isRunning = false,
                                    outputText = "",
                                    targetNodeName = selectedPeer.nodeName,
                                    elapsedMs = elapsed,
                                    errorMessage = err.message ?: "Remote AICore request failed"
                                )
                            )
                        }
                        appendClusterLog("Remote AICore failed on ${selectedPeer.nodeName}: ${err.message}")
                    }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        aiCoreEngine.close()
        nsdManager.release()
        nodeServer.destroy()
    }
}
