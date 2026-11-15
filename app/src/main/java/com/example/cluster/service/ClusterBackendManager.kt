package com.example.cluster.service

import android.content.Context
import com.example.cluster.ai.AiCoreEngine
import com.example.cluster.compute.ClusterTaskDistributor
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.compute.JobChunk
import com.example.cluster.data.ClusterPersistenceRepository
import com.example.cluster.data.ClusterPreferences
import com.example.cluster.data.KubeMeshDatabase
import com.example.cluster.data.SavedClusterConfig
import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.ClusterSnapshotResponse
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeMetricSample
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PodActionResponse
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.PodSubmitRequest
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.TaskQueueItemStatus
import com.example.cluster.model.WorkloadType
import com.example.cluster.network.ClusterClient
import com.example.cluster.network.NodeHttpServer
import com.example.cluster.network.NsdDiscoveryManager
import com.example.cluster.telemetry.DeviceTelemetryCollector
import com.example.cluster.ui.AiCoreInferenceState
import com.example.cluster.ui.ClusterUiState
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ClusterBackendManager(
    context: Context,
    private val moshi: Moshi = Moshi.Builder().build(),
    private val telemetryCollector: DeviceTelemetryCollector = DeviceTelemetryCollector(context.applicationContext),
    private val computeEngine: ComputeEngine = ComputeEngine(),
    private val taskDistributor: ClusterTaskDistributor = ClusterTaskDistributor(),
    private val aiCoreEngine: AiCoreEngine = AiCoreEngine(),
    private val preferences: ClusterPreferences = ClusterPreferences(context.applicationContext),
    private val clusterClient: ClusterClient = ClusterClient(moshi),
    private val persistence: ClusterPersistenceRepository? = ClusterPersistenceRepository(
        KubeMeshDatabase.getInstance(context.applicationContext).clusterDao(),
        moshi
    ),
    private val nsdManagerFactory: ((String) -> Unit) -> NsdDiscoveryManager = { logger ->
        NsdDiscoveryManager(context.applicationContext, logger)
    },
    private val daemonController: (enabled: Boolean, endpoint: String, roleLabel: String) -> Unit = { enabled, endpoint, roleLabel ->
        if (enabled) {
            NodeDaemonService.start(context.applicationContext, endpoint, roleLabel)
        } else {
            NodeDaemonService.stop(context.applicationContext)
        }
    },
    autoStart: Boolean = true
) {
    companion object {
        @Volatile
        private var sharedInstance: ClusterBackendManager? = null

        fun getInstance(context: Context): ClusterBackendManager {
            sharedInstance?.let { return it }
            return synchronized(this) {
                sharedInstance ?: ClusterBackendManager(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val activeLocalPods = AtomicInteger(0)
    private val missedHeartbeats = ConcurrentHashMap<String, Int>()
    private val autoJoinCooldownMs = ConcurrentHashMap<String, Long>()
    private val runningPodJobs = ConcurrentHashMap<String, Job>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val disconnectedNodeIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    private val nsdManager: NsdDiscoveryManager by lazy {
        nsdManagerFactory { msg -> appendClusterLog(msg) }
    }

    private val _state = MutableStateFlow(ClusterUiState())
    val state: StateFlow<ClusterUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private lateinit var nodeServer: NodeHttpServer

    init {
        nodeServer = NodeHttpServer(
            computeEngine = computeEngine,
            moshi = moshi,
            getLocalTelemetry = { collectFreshLocalTelemetry() },
            getClusterToken = { _state.value.config.clusterToken },
            onPeerPaired = { peer, remoteIp ->
                scope.launch {
                    val updatedPeer = peer.copy(
                        hostIp = if (peer.hostIp.isBlank() || peer.hostIp == "127.0.0.1" || peer.hostIp == "0.0.0.0") {
                            remoteIp.ifBlank { peer.hostIp }
                        } else {
                            peer.hostIp
                        },
                        lastHeartbeatMs = System.currentTimeMillis(),
                        isLocal = false
                    )
                    addOrUpdateRemotePeer(updatedPeer, persist = true)
                    appendClusterLog(
                        "Peer joined mesh: ${updatedPeer.nodeName} (${updatedPeer.hostIp}:${updatedPeer.port}, ${updatedPeer.cpuCores} vCPU)"
                    )
                    _events.tryEmit("Node ${updatedPeer.nodeName} unified into cluster")
                }
            },
            onPeerUnpaired = { nodeId ->
                scope.launch {
                    val peerName = _state.value.remotePeers.firstOrNull { it.nodeId == nodeId }?.nodeName ?: nodeId
                    disconnectedNodeIdsFlow.update { it + nodeId }
                    _state.update { s ->
                        s.copy(
                            remotePeers = s.remotePeers.filterNot { it.nodeId == nodeId },
                            unreachablePeerIds = s.unreachablePeerIds - nodeId
                        )
                    }
                    runCatching { persistence?.removePeer(nodeId) }
                    appendClusterLog("Node $peerName drained from mesh — re-queueing any active tasks")
                }
            },
            onServerLog = { msg -> appendClusterLog(msg) },
            onExecuteAiPrompt = { req ->
                val localName = _state.value.config.nodeName
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
            },
            getKnownPeers = {
                val s = _state.value
                s.remotePeers.filter { it.nodeId !in s.unreachablePeerIds }
            },
            onGossipPeersReceived = { peers ->
                scope.launch { integrateGossipPeers(peers) }
            },
            getClusterSnapshot = { buildClusterSnapshot() },
            getPods = { _state.value.pods },
            onSubmitPod = { req -> handleRemotePodSubmit(req) },
            onCancelPod = { podId -> handleCancelPodRequest(podId) }
        )

        if (autoStart) {
            ensureStarted()
        }
    }

    fun ensureStarted(preferredPort: Int = 7443) {
        if (!started.compareAndSet(false, true)) {
            if (!nodeServer.isRunning) {
                val bound = nodeServer.start(preferredPort)
                _state.update { it.copy(serverPort = bound) }
            }
            return
        }

        scope.launch {
            preferences.ensureInitialized()
            val initialConfig = preferences.configFlow.first()
            _state.update { it.copy(config = initialConfig) }

            restorePersistedState()

            val aiStatus = aiCoreEngine.refreshStatus()
            val boundPort = nodeServer.start(preferredPort)
            val initialLocal = collectFreshLocalTelemetry(initialConfig, boundPort, aiStatus)
            _state.update {
                it.copy(
                    serverPort = boundPort,
                    localNode = initialLocal
                )
            }

            registerLocalNsdService(initialLocal)
            nsdManager.startDiscovery()
            appendClusterLog(
                "Control/Worker agent started on ${telemetryCollector.resolveLanIpv4Address()}:$boundPort (AICore: ${aiStatus.label})"
            )

            if (initialConfig.daemonEnabled) {
                daemonController(true, initialLocal.endpoint, initialConfig.role.displayName)
            }

            launch { reconnectSavedPeers(initialConfig, initialLocal) }
            launch { observeAiCore() }
            launch { observePreferences() }
            launch { observeNsd() }
            launch { telemetryAndHeartbeatLoop() }
        }
    }

    private suspend fun restorePersistedState() {
        val repo = persistence ?: return
        runCatching {
            val savedLogs = repo.loadRecentLogs(80)
            val savedPods = repo.loadPods(50).map { pod ->
                if (pod.phase == PodPhase.RUNNING || pod.phase == PodPhase.PENDING) {
                    val interrupted = pod.copy(
                        phase = PodPhase.FAILED,
                        completedAtMs = System.currentTimeMillis(),
                        aggregatedResult = "Interrupted by process restart (${pod.completedChunks}/${pod.totalChunks} chunks finished)",
                        chunkTasks = pod.chunkTasks.map { task ->
                            if (task.status == TaskQueueItemStatus.IN_PROGRESS || task.status == TaskQueueItemStatus.PENDING) {
                                task.copy(
                                    status = TaskQueueItemStatus.FAILED,
                                    errorMessage = "Node process restarted before completion"
                                )
                            } else task
                        }
                    )
                    repo.savePod(interrupted)
                    interrupted
                } else {
                    pod
                }
            }
            val savedPeers = repo.loadPeers()
            _state.update { current ->
                current.copy(
                    pods = if (current.pods.isEmpty()) savedPods else current.pods,
                    clusterLogs = if (current.clusterLogs.isEmpty()) savedLogs else current.clusterLogs,
                    remotePeers = if (current.remotePeers.isEmpty()) savedPeers else current.remotePeers,
                    unreachablePeerIds = if (current.remotePeers.isEmpty()) {
                        savedPeers.map { it.nodeId }.toSet()
                    } else {
                        current.unreachablePeerIds
                    }
                )
            }
        }
    }

    private suspend fun reconnectSavedPeers(config: SavedClusterConfig, local: NodeTelemetry) {
        val saved = _state.value.remotePeers
        saved.forEach { peer ->
            if (peer.hostIp.isNotBlank() && peer.port in 1..65535) {
                val res = clusterClient.pairWithNode(
                    host = peer.hostIp,
                    port = peer.port,
                    clusterToken = config.clusterToken,
                    localNode = local
                )
                res.onSuccess { pairResp ->
                    addOrUpdateRemotePeer(pairResp.hostNode, persist = true)
                    if (pairResp.meshPeers.isNotEmpty()) {
                        integrateGossipPeers(pairResp.meshPeers)
                    }
                    appendClusterLog("Reconnected saved peer ${pairResp.hostNode.nodeName} (${peer.endpoint})")
                }
            }
        }
    }

    private suspend fun integrateGossipPeers(candidates: List<NodeTelemetry>) {
        val stateSnap = _state.value
        val localId = stateSnap.config.nodeId
        val localIp = stateSnap.localNode?.hostIp
        val localPort = nodeServer.boundPort
        val token = stateSnap.config.clusterToken
        val localNode = collectFreshLocalTelemetry()

        candidates.forEach { candidate ->
            if (candidate.nodeId == localId) return@forEach
            if (candidate.hostIp == localIp && candidate.port == localPort) return@forEach
            if (candidate.hostIp.isBlank() || candidate.port !in 1..65535) return@forEach
            val alreadyHealthy = _state.value.remotePeers.any {
                (it.nodeId == candidate.nodeId || (it.hostIp == candidate.hostIp && it.port == candidate.port)) &&
                    it.nodeId !in _state.value.unreachablePeerIds
            }
            if (!alreadyHealthy) {
                val res = clusterClient.pairWithNode(
                    host = candidate.hostIp,
                    port = candidate.port,
                    clusterToken = token,
                    localNode = localNode
                )
                res.onSuccess { resp ->
                    addOrUpdateRemotePeer(resp.hostNode, persist = true)
                    appendClusterLog("Mesh gossip unified peer ${resp.hostNode.nodeName} (${candidate.endpoint})")
                }
            }
        }
    }

    private fun buildClusterSnapshot(): ClusterSnapshotResponse {
        val s = _state.value
        val local = collectFreshLocalTelemetry()
        return ClusterSnapshotResponse(
            localNode = local,
            peers = s.remotePeers,
            unreachablePeerIds = s.unreachablePeerIds.toList(),
            totalCpuCores = local.cpuCores + s.remotePeers.filter { it.nodeId !in s.unreachablePeerIds }.sumOf { it.cpuCores },
            totalRamMb = local.totalRamMb + s.remotePeers.filter { it.nodeId !in s.unreachablePeerIds }.sumOf { it.totalRamMb },
            availRamMb = local.availRamMb + s.remotePeers.filter { it.nodeId !in s.unreachablePeerIds }.sumOf { it.availRamMb },
            activePodsCount = s.pods.count { it.phase == PodPhase.RUNNING },
            pods = s.pods.take(25),
            recentLogs = s.clusterLogs.take(40)
        )
    }

    private fun handleRemotePodSubmit(req: PodSubmitRequest): PodActionResponse {
        val workload = WorkloadType.entries.firstOrNull {
            it.id.equals(req.workloadId, ignoreCase = true) || it.name.equals(req.workloadId, ignoreCase = true)
        } ?: return PodActionResponse(
            success = false,
            message = "Unknown workload '${req.workloadId}'"
        )

        val policy = SchedulingPolicy.entries.firstOrNull {
            it.name.equals(req.schedulingPolicy, ignoreCase = true)
        } ?: SchedulingPolicy.PROPORTIONAL_SPLIT

        val pod = deployPodInternal(
            workloadType = workload,
            schedulingPolicy = policy,
            scaleMultiplier = req.scaleMultiplier.coerceIn(1, 8),
            customPodName = req.customPodName
        )
        return PodActionResponse(
            success = true,
            message = "Scheduled pod ${pod.name}",
            pod = pod
        )
    }

    private fun handleCancelPodRequest(podId: String): PodActionResponse {
        val pod = _state.value.pods.firstOrNull { it.podId == podId }
            ?: return PodActionResponse(
                success = false,
                message = "Pod $podId not found"
            )
        cancelPod(podId)
        val updated = _state.value.pods.firstOrNull { it.podId == podId } ?: pod
        return PodActionResponse(
            success = true,
            message = "Cancelled pod ${pod.name}",
            pod = updated
        )
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
            val prevStatus = _state.value.aiCoreLocal.status
            _state.update { it.copy(aiCoreLocal = aiState) }
            if (prevStatus != aiState.status && nodeServer.isRunning) {
                registerLocalNsdService(collectFreshLocalTelemetry())
            }
        }
    }

    private suspend fun observePreferences() {
        preferences.configFlow.collect { cfg ->
            val prevName = _state.value.config.nodeName
            val prevRole = _state.value.config.role
            _state.update { it.copy(config = cfg) }
            if ((prevName != cfg.nodeName || prevRole != cfg.role) && nodeServer.isRunning) {
                registerLocalNsdService(collectFreshLocalTelemetry(cfg, nodeServer.boundPort))
            }
        }
    }

    private suspend fun observeNsd() = coroutineScope {
        launch {
            nsdManager.discoveredPeers.collect { peers ->
                val localPort = nodeServer.boundPort
                val localIp = _state.value.localNode?.hostIp
                val localId = _state.value.config.nodeId
                val filtered = peers.filterNot {
                    (it.nodeId.isNotEmpty() && it.nodeId == localId) ||
                        (it.host == localIp && it.port == localPort)
                }
                _state.update { it.copy(discoveredPeers = filtered) }
                attemptAutoJoinDiscoveredPeers(filtered)
            }
        }
        launch {
            nsdManager.isScanning.collect { scanning ->
                _state.update { it.copy(isScanning = scanning) }
            }
        }
        launch {
            nsdManager.isAdvertising.collect { adv ->
                _state.update { it.copy(isAdvertising = adv) }
            }
        }
        launch {
            nsdManager.nsdStatus.collect { status ->
                _state.update { it.copy(nsdStatus = status) }
            }
        }
        launch {
            nsdManager.lastError.collect { err ->
                _state.update { it.copy(nsdError = err) }
            }
        }
        launch {
            nsdManager.lostPeers.collect { lost ->
                val pairedPeer = _state.value.remotePeers.firstOrNull { rp ->
                    (lost.nodeId.isNotEmpty() && rp.nodeId == lost.nodeId) ||
                        (rp.hostIp == lost.host && rp.port == lost.port)
                } ?: return@collect
                if (pairedPeer.nodeId !in _state.value.unreachablePeerIds) {
                    val token = _state.value.config.clusterToken
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
        val current = _state.value
        if (!current.config.autoJoinNsd || current.isPairing) return
        val now = System.currentTimeMillis()
        val candidate = peers.firstOrNull { peer ->
            val alreadyPairedHealthy = current.remotePeers.any { rp ->
                ((peer.nodeId.isNotEmpty() && rp.nodeId == peer.nodeId) ||
                    (rp.hostIp == peer.host && rp.port == peer.port)) &&
                    rp.nodeId !in current.unreachablePeerIds
            }
            val key = "${peer.host}:${peer.port}"
            val lastAttempt = autoJoinCooldownMs[key] ?: 0L
            !alreadyPairedHealthy && (now - lastAttempt) > 20_000L
        } ?: return

        val endpointKey = "${candidate.host}:${candidate.port}"
        autoJoinCooldownMs[endpointKey] = now
        val local = collectFreshLocalTelemetry()
        val token = current.config.clusterToken
        val knownPeers = current.remotePeers.filter { it.nodeId !in current.unreachablePeerIds }

        scope.launch {
            val result = clusterClient.pairWithNode(candidate.host, candidate.port, token, local, knownPeers)
            result.onSuccess { resp ->
                preferences.saveLastPeer(candidate.host, candidate.port)
                addOrUpdateRemotePeer(resp.hostNode, persist = true)
                if (resp.meshPeers.isNotEmpty()) {
                    integrateGossipPeers(resp.meshPeers)
                }
                appendClusterLog("NSD auto-unified ${resp.hostNode.nodeName} ($endpointKey)")
                _events.tryEmit("Auto-unified with ${resp.hostNode.nodeName} via NSD")
            }.onFailure {
                appendClusterLog("NSD detected ${candidate.displayTitle} ($endpointKey) — awaiting matching token")
            }
        }
    }

    private suspend fun telemetryAndHeartbeatLoop() {
        var tickCount = 0
        while (scope.isActive) {
            tickCount++
            val freshLocal = collectFreshLocalTelemetry()
            _state.update { s ->
                s.copy(
                    localNode = freshLocal,
                    metricHistory = appendSample(s.metricHistory, freshLocal)
                )
            }

            val peers = _state.value.remotePeers
            val token = _state.value.config.clusterToken
            val hasActivePods = _state.value.pods.any { it.phase == PodPhase.RUNNING }
            val failureThreshold = if (hasActivePods) 2 else 3

            if (peers.isNotEmpty()) {
                coroutineScope {
                    peers.forEach { peer ->
                        launch {
                            val result = clusterClient.fetchHealth(peer.hostIp, peer.port, token)
                            result.onSuccess { updated ->
                                val wasUnreachable = peer.nodeId in _state.value.unreachablePeerIds
                                missedHeartbeats[peer.nodeId] = 0
                                addOrUpdateRemotePeer(updated, persist = wasUnreachable || tickCount % 15 == 0)
                                if (wasUnreachable) {
                                    appendClusterLog("Node ${peer.nodeName} reconnected to mesh")
                                    _events.tryEmit("${peer.nodeName} reconnected")
                                }
                                if (tickCount % 8 == 0) {
                                    clusterClient.fetchMeshNodes(peer.hostIp, peer.port, token)
                                        .onSuccess { meshNodes -> integrateGossipPeers(meshNodes) }
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
        val newlyDisconnected = peer.nodeId !in _state.value.unreachablePeerIds
        disconnectedNodeIdsFlow.update { it + peer.nodeId }
        _state.update { s ->
            s.copy(unreachablePeerIds = s.unreachablePeerIds + peer.nodeId)
        }
        if (newlyDisconnected) {
            val hasRunningPods = _state.value.pods.any { it.phase == PodPhase.RUNNING }
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

    fun collectFreshLocalTelemetry(
        cfg: SavedClusterConfig = _state.value.config,
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

    private fun addOrUpdateRemotePeer(peer: NodeTelemetry, persist: Boolean = false) {
        missedHeartbeats[peer.nodeId] = 0
        disconnectedNodeIdsFlow.update { it - peer.nodeId }
        _state.update { s ->
            val others = s.remotePeers.filterNot {
                it.nodeId == peer.nodeId || (it.hostIp == peer.hostIp && it.port == peer.port)
            }
            s.copy(
                remotePeers = (others + peer).sortedBy { it.nodeName },
                unreachablePeerIds = s.unreachablePeerIds - peer.nodeId,
                metricHistory = appendSample(s.metricHistory, peer)
            )
        }
        if (persist) {
            scope.launch {
                runCatching { persistence?.savePeer(peer) }
            }
        }
    }

    fun setNodeRole(role: NodeRole) {
        scope.launch {
            preferences.updateRole(role)
            appendClusterLog("Node role switched to ${role.displayName}")
            if (_state.value.config.daemonEnabled) {
                val local = _state.value.localNode ?: collectFreshLocalTelemetry()
                daemonController(true, local.endpoint, role.displayName)
            }
        }
    }

    fun updateNodeName(name: String) {
        scope.launch {
            preferences.updateNodeName(name)
            appendClusterLog("Node hostname updated to $name")
        }
    }

    fun updateClusterToken(token: String) {
        scope.launch {
            preferences.updateClusterToken(token)
            appendClusterLog("Cluster token updated")
            _events.tryEmit("Cluster token updated")
        }
    }

    fun regenerateToken() {
        scope.launch {
            val next = preferences.regenerateClusterToken()
            appendClusterLog("Rotated cluster join token to $next")
            _events.tryEmit("New join token: $next")
        }
    }

    fun toggleLanScan() {
        if (_state.value.isScanning) {
            nsdManager.stopDiscovery()
            appendClusterLog("Paused LAN NSD discovery")
        } else {
            nsdManager.refreshDiscovery()
            appendClusterLog("Scanning LAN for _kubemesh._tcp. nodes...")
        }
    }

    fun setAutoJoinNsd(enabled: Boolean) {
        scope.launch {
            preferences.setAutoJoinNsd(enabled)
            appendClusterLog("NSD auto-join ${if (enabled) "enabled" else "disabled"}")
            if (enabled) {
                autoJoinCooldownMs.clear()
                attemptAutoJoinDiscoveredPeers(_state.value.discoveredPeers)
            }
        }
    }

    fun onAppLifecycleChanged(isForeground: Boolean) {
        val local = _state.value.localNode ?: return
        if (isForeground) {
            if (!nsdManager.isAdvertising.value && nodeServer.isRunning) {
                registerLocalNsdService(local)
            }
            if (!nsdManager.isScanning.value) {
                nsdManager.startDiscovery()
            }
        } else if (!_state.value.config.daemonEnabled) {
            nsdManager.stopDiscovery()
        }
    }

    fun pairWithPeer(hostInput: String, portInput: Int, tokenInput: String) {
        val host = hostInput.trim()
        if (host.isEmpty() || portInput !in 1..65535) {
            _events.tryEmit("Enter a valid LAN IPv4 address and port")
            return
        }
        val token = tokenInput.trim().uppercase().ifEmpty { _state.value.config.clusterToken }
        val local = collectFreshLocalTelemetry()
        if (host == local.hostIp && portInput == local.port) {
            _events.tryEmit("Cannot pair a device with its own endpoint")
            return
        }

        scope.launch {
            _state.update { it.copy(isPairing = true) }
            if (_state.value.config.role == NodeRole.WORKER && token != _state.value.config.clusterToken) {
                preferences.updateClusterToken(token)
            }
            val knownPeers = _state.value.remotePeers.filter { it.nodeId !in _state.value.unreachablePeerIds }
            val result = clusterClient.pairWithNode(host, portInput, token, local, knownPeers)
            result.onSuccess { resp ->
                preferences.saveLastPeer(host, portInput)
                addOrUpdateRemotePeer(resp.hostNode, persist = true)
                if (resp.meshPeers.isNotEmpty()) {
                    integrateGossipPeers(resp.meshPeers)
                }
                appendClusterLog(
                    "Unified with ${resp.hostNode.nodeName} ($host:$portInput · ${resp.hostNode.cpuCores} vCPU, ${resp.hostNode.totalRamMb} MB RAM)"
                )
                _events.tryEmit("Connected to ${resp.hostNode.nodeName}")
            }.onFailure { err ->
                appendClusterLog("Pair failed ($host:$portInput): ${err.message}")
                _events.tryEmit(err.message ?: "Failed to connect to peer")
            }
            _state.update { it.copy(isPairing = false) }
        }
    }

    fun disconnectPeer(nodeId: String) {
        val peer = _state.value.remotePeers.firstOrNull { it.nodeId == nodeId } ?: return
        val token = _state.value.config.clusterToken
        val localId = _state.value.config.nodeId
        scope.launch {
            disconnectedNodeIdsFlow.update { it + nodeId }
            clusterClient.notifyUnpair(peer.hostIp, peer.port, token, localId)
            _state.update { s ->
                s.copy(
                    remotePeers = s.remotePeers.filterNot { it.nodeId == nodeId },
                    unreachablePeerIds = s.unreachablePeerIds - nodeId
                )
            }
            runCatching { persistence?.removePeer(nodeId) }
            appendClusterLog("Drained and disconnected ${peer.nodeName}")
            _events.tryEmit("Disconnected ${peer.nodeName}")
        }
    }

    fun setDaemonEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setDaemonEnabled(enabled)
            val local = _state.value.localNode ?: collectFreshLocalTelemetry()
            daemonController(enabled, local.endpoint, _state.value.config.role.displayName)
            if (enabled) {
                appendClusterLog("Foreground NodeDaemonService started")
            } else {
                appendClusterLog("Foreground NodeDaemonService stopped")
            }
        }
    }

    fun onDaemonStoppedExternally() {
        scope.launch {
            preferences.setDaemonEnabled(false)
            appendClusterLog("Foreground NodeDaemonService stopped by user")
        }
    }

    fun selectNodeForSheet(node: NodeTelemetry?) {
        _state.update { it.copy(selectedNodeForSheet = node) }
    }

    fun selectPodForLogs(podId: String?) {
        _state.update { it.copy(selectedPodForLogs = podId) }
    }

    fun deployPod(
        workloadType: WorkloadType,
        schedulingPolicy: SchedulingPolicy,
        scaleMultiplier: Int = 1,
        customPodName: String? = null
    ) {
        deployPodInternal(workloadType, schedulingPolicy, scaleMultiplier, customPodName)
    }

    internal fun deployPodInternal(
        workloadType: WorkloadType,
        schedulingPolicy: SchedulingPolicy,
        scaleMultiplier: Int = 1,
        customPodName: String? = null
    ): ClusterPod {
        val local = _state.value.localNode ?: collectFreshLocalTelemetry()
        val onlineRemotes = _state.value.remotePeers.filter { it.nodeId !in _state.value.unreachablePeerIds }

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

        _state.update { it.copy(pods = listOf(pod) + it.pods) }
        appendClusterLog("kubectl apply: Pod $podName (${chunks.size} channel chunks) -> ${targetNodes.joinToString { it.nodeName }}")
        scope.launch { runCatching { persistence?.savePod(pod) } }

        val job = scope.launch {
            try {
                executePodChunks(pod, targetNodes, chunks)
            } catch (_: CancellationException) {
                // Handled in cancelPod()
            } finally {
                runningPodJobs.remove(podId)
            }
        }
        runningPodJobs[podId] = job
        return pod
    }

    fun cancelPod(podId: String) {
        val job = runningPodJobs.remove(podId)
        job?.cancel(CancellationException("Cancelled by user"))

        var cancelledPod: ClusterPod? = null
        _state.update { s ->
            s.copy(
                pods = s.pods.map { current ->
                    if (current.podId == podId && (current.phase == PodPhase.RUNNING || current.phase == PodPhase.PENDING)) {
                        val elapsed = (System.currentTimeMillis() - current.createdAtMs).coerceAtLeast(1L)
                        val updated = current.copy(
                            phase = PodPhase.FAILED,
                            completedAtMs = System.currentTimeMillis(),
                            totalElapsedMs = elapsed,
                            aggregatedResult = "Pod cancelled (${current.completedChunks}/${current.totalChunks} chunks completed)",
                            logs = current.logs + "[${nowTime()}] controller: Pod ${current.name} cancelled by operator",
                            chunkTasks = current.chunkTasks.map { task ->
                                if (task.status == TaskQueueItemStatus.PENDING || task.status == TaskQueueItemStatus.IN_PROGRESS) {
                                    task.copy(
                                        status = TaskQueueItemStatus.FAILED,
                                        errorMessage = "Cancelled by operator"
                                    )
                                } else task
                            }
                        )
                        cancelledPod = updated
                        updated
                    } else current
                }
            )
        }
        cancelledPod?.let { pod ->
            appendClusterLog("kubectl delete pod/${pod.name} — execution aborted")
            _events.tryEmit("Cancelled pod ${pod.name}")
            scope.launch { runCatching { persistence?.savePod(pod) } }
        }
    }

    fun clearCompletedPods() {
        _state.update { s ->
            val remaining = s.pods.filter { it.phase == PodPhase.RUNNING || it.phase == PodPhase.PENDING }
            val keepSelected = if (remaining.any { it.podId == s.selectedPodForLogs }) s.selectedPodForLogs else null
            s.copy(pods = remaining, selectedPodForLogs = keepSelected)
        }
        scope.launch {
            runCatching { persistence?.clearCompletedPods() }
            appendClusterLog("Cleared completed pod history")
            _events.tryEmit("Cleared completed pods")
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
            WorkloadType.MATRIX_MATMUL -> totalWork
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
        val token = _state.value.config.clusterToken

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
                _state.update { s ->
                    s.copy(
                        pods = s.pods.map { current ->
                            if (current.podId == pod.podId && current.phase == PodPhase.RUNNING) {
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
                    val s = _state.value
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

        var persistedFinalPod: ClusterPod? = null
        _state.update { s ->
            s.copy(
                pods = s.pods.map { current ->
                    if (current.podId == pod.podId) {
                        val finished = current.copy(
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
                        persistedFinalPod = finished
                        finished
                    } else current
                }
            )
        }
        persistedFinalPod?.let { finalPod ->
            runCatching { persistence?.savePod(finalPod) }
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
        _state.update { s ->
            s.copy(clusterLogs = (listOf(line) + s.clusterLogs).take(100))
        }
        scope.launch {
            runCatching { persistence?.appendLog(line) }
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
        scope.launch {
            val status = aiCoreEngine.refreshStatus()
            appendClusterLog("AICore / Gemini status: ${status.label}")
        }
    }

    fun downloadAiCoreModel() {
        scope.launch {
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
        scope.launch {
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
        val nodes = _state.value.allNodes
        val nodeLines = nodes.joinToString("\n") { n ->
            val offline = !n.isLocal && n.nodeId in _state.value.unreachablePeerIds
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

        val current = _state.value
        val onlineRemotes = current.remotePeers.filter { it.nodeId !in current.unreachablePeerIds }
        val localReady = current.aiCoreLocal.status == AiCoreStatus.AVAILABLE ||
            current.aiCoreLocal.status == AiCoreStatus.CLOUD_READY
        val selectedPeer = when {
            targetNodeId == null -> {
                if (localReady) null
                else onlineRemotes.firstOrNull {
                    val peerStatus = AiCoreStatus.fromWire(it.aiCoreStatus)
                    peerStatus == AiCoreStatus.AVAILABLE || peerStatus == AiCoreStatus.CLOUD_READY
                }
            }
            targetNodeId == current.localNode?.nodeId -> null
            else -> onlineRemotes.firstOrNull { it.nodeId == targetNodeId }
        }

        val systemPrompt = "You are KubeMesh cluster advisor for an Android Kubernetes-style compute mesh. Be concise and technical."

        scope.launch {
            val startMs = System.currentTimeMillis()
            if (selectedPeer == null) {
                val localName = current.localNode?.nodeName ?: current.config.nodeName
                _state.update {
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
                appendClusterLog("AI inference started locally on $localName")
                val builder = StringBuilder()
                try {
                    aiCoreEngine.generateStream(
                        prompt = cleanPrompt,
                        systemInstruction = systemPrompt
                    ).collect { chunk ->
                        builder.append(chunk)
                        _state.update { s ->
                            s.copy(
                                aiCoreInference = s.aiCoreInference.copy(
                                    outputText = builder.toString(),
                                    elapsedMs = System.currentTimeMillis() - startMs
                                )
                            )
                        }
                    }
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    _state.update { s ->
                        s.copy(
                            aiCoreInference = s.aiCoreInference.copy(
                                isRunning = false,
                                elapsedMs = elapsed
                            )
                        )
                    }
                    appendClusterLog("AI local inference completed in ${elapsed}ms")
                } catch (t: Throwable) {
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    _state.update { s ->
                        s.copy(
                            aiCoreInference = s.aiCoreInference.copy(
                                isRunning = false,
                                elapsedMs = elapsed,
                                errorMessage = t.message ?: "AI inference failed"
                            )
                        )
                    }
                    appendClusterLog("AI inference error: ${t.message}")
                }
            } else {
                _state.update {
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
                appendClusterLog("Routing AI prompt over LAN to ${selectedPeer.nodeName} (${selectedPeer.endpoint})")
                val req = AiCorePromptRequest(
                    clusterToken = current.config.clusterToken,
                    prompt = cleanPrompt,
                    systemInstruction = systemPrompt
                )
                clusterClient.executeRemoteAiPrompt(selectedPeer.hostIp, selectedPeer.port, req)
                    .onSuccess { resp ->
                        _state.update { s ->
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
                        appendClusterLog("Peer ${resp.targetNodeName} AI finished in ${resp.elapsedMs}ms")
                    }
                    .onFailure { err ->
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        _state.update { s ->
                            s.copy(
                                aiCoreInference = AiCoreInferenceState(
                                    isRunning = false,
                                    outputText = "",
                                    targetNodeName = selectedPeer.nodeName,
                                    elapsedMs = elapsed,
                                    errorMessage = err.message ?: "Remote AI request failed"
                                )
                            )
                        }
                        appendClusterLog("Remote AI failed on ${selectedPeer.nodeName}: ${err.message}")
                    }
            }
        }
    }

    fun shutdown() {
        runningPodJobs.values.forEach { it.cancel() }
        runningPodJobs.clear()
        aiCoreEngine.close()
        nsdManager.release()
        nodeServer.destroy()
        scope.cancel()
        started.set(false)
    }
}
