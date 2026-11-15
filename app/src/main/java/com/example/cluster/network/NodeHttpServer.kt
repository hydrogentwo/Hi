package com.example.cluster.network

import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.ClusterSnapshotResponse
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PairRequest
import com.example.cluster.model.PairResponse
import com.example.cluster.model.PodActionResponse
import com.example.cluster.model.PodSubmitRequest
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.ShardExecuteResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class NodeHttpServer(
    private val computeEngine: ComputeEngine,
    private val moshi: Moshi,
    private val getLocalTelemetry: () -> NodeTelemetry,
    private val getClusterToken: () -> String,
    private val onPeerPaired: (NodeTelemetry, remoteIp: String) -> Unit,
    private val onPeerUnpaired: (String) -> Unit,
    private val onServerLog: (String) -> Unit,
    private val onExecuteAiPrompt: (suspend (AiCorePromptRequest) -> AiCorePromptResponse)? = null,
    private val getKnownPeers: (() -> List<NodeTelemetry>)? = null,
    private val onGossipPeersReceived: ((List<NodeTelemetry>) -> Unit)? = null,
    private val getClusterSnapshot: (() -> ClusterSnapshotResponse)? = null,
    private val getPods: (() -> List<ClusterPod>)? = null,
    private val onSubmitPod: (suspend (PodSubmitRequest) -> PodActionResponse)? = null,
    private val onCancelPod: ((String) -> PodActionResponse)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var boundPort: Int = 7443
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private val telemetryAdapter = moshi.adapter(NodeTelemetry::class.java)
    private val nodeListAdapter = moshi.adapter<List<NodeTelemetry>>(
        Types.newParameterizedType(List::class.java, NodeTelemetry::class.java)
    )
    private val podAdapter = moshi.adapter(ClusterPod::class.java)
    private val podListAdapter = moshi.adapter<List<ClusterPod>>(
        Types.newParameterizedType(List::class.java, ClusterPod::class.java)
    )
    private val snapshotAdapter = moshi.adapter(ClusterSnapshotResponse::class.java)
    private val podSubmitReqAdapter = moshi.adapter(PodSubmitRequest::class.java)
    private val podActionRespAdapter = moshi.adapter(PodActionResponse::class.java)
    private val pairReqAdapter = moshi.adapter(PairRequest::class.java)
    private val pairRespAdapter = moshi.adapter(PairResponse::class.java)
    private val shardReqAdapter = moshi.adapter(ShardExecuteRequest::class.java)
    private val shardRespAdapter = moshi.adapter(ShardExecuteResponse::class.java)
    private val aiReqAdapter = moshi.adapter(AiCorePromptRequest::class.java)
    private val aiRespAdapter = moshi.adapter(AiCorePromptResponse::class.java)

    fun start(preferredPort: Int = 7443): Int {
        if (isRunning && serverSocket != null) return boundPort
        val socket = try {
            ServerSocket(preferredPort, 32, InetAddress.getByName("0.0.0.0"))
        } catch (_: Exception) {
            ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"))
        }
        socket.reuseAddress = true
        serverSocket = socket
        boundPort = socket.localPort
        isRunning = true

        onServerLog("kubelet-agent listening on 0.0.0.0:$boundPort")

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
        return boundPort
    }

    fun stop() {
        isRunning = false
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 95_000
                val input = BufferedInputStream(s.getInputStream())
                val rawHeader = readHeaderBytes(input) ?: return
                val headerText = String(rawHeader, StandardCharsets.US_ASCII)
                val lines = headerText.split("\r\n")
                if (lines.isEmpty()) return

                val requestParts = lines[0].split(" ")
                if (requestParts.size < 2) return
                val method = requestParts[0].uppercase()
                val path = requestParts[1].substringBefore("?")

                if (method == "OPTIONS") {
                    writeHttpResponse(s, 204, "No Content", "")
                    return
                }

                val headers = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val k = line.substring(0, colon).trim().lowercase()
                        val v = line.substring(colon + 1).trim()
                        headers[k] = v
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4 * 1024 * 1024) ?: 0
                val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
                val bodyText = String(bodyBytes, StandardCharsets.UTF_8)
                val remoteIp = s.inetAddress?.hostAddress ?: ""
                val headerToken = headers["x-cluster-token"].orEmpty()

                val (statusCode, statusText, responseJson) = try {
                    routeRequest(
                        method = method,
                        path = path,
                        headerToken = headerToken,
                        bodyText = bodyText,
                        remoteIp = remoteIp
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    onServerLog("HTTP 500 on $method $path: ${t.message}")
                    Triple(500, "Internal Server Error", """{"error":"Internal node server error"}""")
                }

                writeHttpResponse(s, statusCode, statusText, responseJson)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Client disconnected or timed out mid-flight; isolate per-connection failure
        }
    }

    private fun writeHttpResponse(
        socket: Socket,
        statusCode: Int,
        statusText: String,
        responseJson: String
    ) {
        val payload = responseJson.toByteArray(StandardCharsets.UTF_8)
        val responseHeader = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Cluster-Token\r\n")
            append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        socket.getOutputStream().apply {
            write(responseHeader)
            if (payload.isNotEmpty()) {
                write(payload)
            }
            flush()
        }
    }

    private fun isTokenAuthorized(headerToken: String, expectedToken: String, allowEmpty: Boolean = false): Boolean {
        if (allowEmpty && headerToken.isEmpty()) return true
        return headerToken.equals(expectedToken, ignoreCase = true)
    }

    private suspend fun routeRequest(
        method: String,
        path: String,
        headerToken: String,
        bodyText: String,
        remoteIp: String
    ): Triple<Int, String, String> {
        val expectedToken = getClusterToken()
        return when {
            method == "GET" && path == "/v1/health" -> {
                if (!isTokenAuthorized(headerToken, expectedToken, allowEmpty = true)) {
                    Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                } else {
                    val local = getLocalTelemetry()
                    Triple(200, "OK", telemetryAdapter.toJson(local))
                }
            }

            method == "GET" && path == "/v1/cluster/state" -> {
                if (!isTokenAuthorized(headerToken, expectedToken)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val local = getLocalTelemetry()
                val snapshot = getClusterSnapshot?.invoke() ?: run {
                    val peers = getKnownPeers?.invoke().orEmpty()
                    val pods = getPods?.invoke().orEmpty()
                    ClusterSnapshotResponse(
                        localNode = local,
                        peers = peers,
                        totalCpuCores = local.cpuCores + peers.sumOf { it.cpuCores },
                        totalRamMb = local.totalRamMb + peers.sumOf { it.totalRamMb },
                        availRamMb = local.availRamMb + peers.sumOf { it.availRamMb },
                        activePodsCount = pods.count { it.phase.name == "RUNNING" },
                        pods = pods
                    )
                }
                Triple(200, "OK", snapshotAdapter.toJson(snapshot))
            }

            method == "GET" && path == "/v1/nodes" -> {
                if (!isTokenAuthorized(headerToken, expectedToken)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val nodes = listOf(getLocalTelemetry()) + getKnownPeers?.invoke().orEmpty()
                Triple(200, "OK", nodeListAdapter.toJson(nodes))
            }

            method == "GET" && path == "/v1/pods" -> {
                if (!isTokenAuthorized(headerToken, expectedToken)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val pods = getPods?.invoke().orEmpty()
                Triple(200, "OK", podListAdapter.toJson(pods))
            }

            method == "GET" && path.startsWith("/v1/pods/") -> {
                if (!isTokenAuthorized(headerToken, expectedToken)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val podId = path.removePrefix("/v1/pods/").trim()
                val pod = getPods?.invoke()?.firstOrNull { it.podId == podId }
                    ?: return Triple(404, "Not Found", """{"error":"Pod $podId not found"}""")
                Triple(200, "OK", podAdapter.toJson(pod))
            }

            method == "POST" && path == "/v1/pods/submit" -> {
                val req = runCatching { podSubmitReqAdapter.fromJson(bodyText) }.getOrNull()
                    ?: return Triple(400, "Bad Request", """{"error":"Invalid pod submit payload"}""")
                val tokenToCheck = req.clusterToken.ifBlank { headerToken }
                if (!tokenToCheck.equals(expectedToken, ignoreCase = true)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val submitHandler = onSubmitPod
                    ?: return Triple(501, "Not Implemented", """{"error":"Pod submission not enabled on this node"}""")
                val resp = submitHandler(req)
                val status = if (resp.success) 200 to "OK" else 400 to "Bad Request"
                Triple(status.first, status.second, podActionRespAdapter.toJson(resp))
            }

            (method == "POST" || method == "DELETE") && path.startsWith("/v1/pods/") && path.endsWith("/cancel") -> {
                if (!isTokenAuthorized(headerToken, expectedToken)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val podId = path.removePrefix("/v1/pods/").removeSuffix("/cancel").trim('/')
                val cancelHandler = onCancelPod
                    ?: return Triple(501, "Not Implemented", """{"error":"Pod cancellation not enabled on this node"}""")
                val resp = cancelHandler(podId)
                val status = if (resp.success) 200 to "OK" else 404 to "Not Found"
                Triple(status.first, status.second, podActionRespAdapter.toJson(resp))
            }

            method == "POST" && path == "/v1/pair" -> {
                val req = runCatching { pairReqAdapter.fromJson(bodyText) }.getOrNull()
                    ?: return Triple(400, "Bad Request", """{"error":"Invalid pair payload"}""")
                if (!req.clusterToken.equals(expectedToken, ignoreCase = true)) {
                    onServerLog("Rejected pair from $remoteIp (invalid cluster token)")
                    val resp = PairResponse(
                        accepted = false,
                        message = "Token mismatch. Expected token from target node.",
                        hostNode = getLocalTelemetry()
                    )
                    Triple(403, "Forbidden", pairRespAdapter.toJson(resp))
                } else {
                    val effectivePeer = if (
                        (req.peerNode.hostIp.isBlank() || req.peerNode.hostIp == "127.0.0.1" || req.peerNode.hostIp == "0.0.0.0") &&
                        remoteIp.isNotEmpty()
                    ) {
                        req.peerNode.copy(hostIp = remoteIp, isLocal = false)
                    } else {
                        req.peerNode.copy(isLocal = false)
                    }
                    onPeerPaired(effectivePeer, remoteIp)
                    if (req.knownPeers.isNotEmpty()) {
                        onGossipPeersReceived?.invoke(req.knownPeers)
                    }
                    val localTelemetry = getLocalTelemetry()
                    val meshPeersForJoiner = getKnownPeers?.invoke().orEmpty().filterNot {
                        it.nodeId == effectivePeer.nodeId || it.nodeId == localTelemetry.nodeId
                    }
                    onServerLog("Paired peer ${effectivePeer.nodeName} (${effectivePeer.hostIp}:${effectivePeer.port})")
                    val resp = PairResponse(
                        accepted = true,
                        message = "Unified with ${localTelemetry.nodeName}",
                        hostNode = localTelemetry,
                        meshPeers = meshPeersForJoiner
                    )
                    Triple(200, "OK", pairRespAdapter.toJson(resp))
                }
            }

            method == "POST" && path == "/v1/unpair" -> {
                if (headerToken.equals(expectedToken, ignoreCase = true)) {
                    val nodeId = bodyText.trim().removeSurrounding("\"")
                    onPeerUnpaired(nodeId)
                    onServerLog("Peer $nodeId drained & disconnected")
                }
                Triple(200, "OK", """{"status":"ok"}""")
            }

            method == "POST" && path == "/v1/pods/execute" -> {
                val req = runCatching { shardReqAdapter.fromJson(bodyText) }.getOrNull()
                    ?: return Triple(400, "Bad Request", """{"error":"Invalid shard payload"}""")
                if (!req.clusterToken.equals(expectedToken, ignoreCase = true)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                onServerLog("Executing remote pod shard ${req.podName} [${req.workloadId}] range=${req.rangeStart}..${req.rangeEnd}")
                val result = computeEngine.executeShard(req).copy(
                    nodeTelemetry = getLocalTelemetry()
                )
                onServerLog("Completed shard ${req.podName} in ${result.elapsedMs}ms (${result.secondaryText})")
                Triple(200, "OK", shardRespAdapter.toJson(result))
            }

            method == "POST" && path == "/v1/aicore/infer" -> {
                val req = runCatching { aiReqAdapter.fromJson(bodyText) }.getOrNull()
                    ?: return Triple(400, "Bad Request", """{"error":"Invalid AICore prompt payload"}""")
                if (!req.clusterToken.equals(expectedToken, ignoreCase = true)) {
                    return Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                }
                val handler = onExecuteAiPrompt
                if (handler == null) {
                    val local = getLocalTelemetry()
                    val fallback = AiCorePromptResponse(
                        success = false,
                        outputText = "",
                        elapsedMs = 0L,
                        targetNodeName = local.nodeName,
                        aiCoreStatus = AiCoreStatus.UNAVAILABLE.name,
                        errorMessage = "AICore endpoint not configured on node"
                    )
                    Triple(503, "Service Unavailable", aiRespAdapter.toJson(fallback))
                } else {
                    onServerLog("Running AICore Gemini Nano prompt from $remoteIp")
                    val resp = handler(req)
                    Triple(200, "OK", aiRespAdapter.toJson(resp))
                }
            }

            else -> Triple(404, "Not Found", """{"error":"Unknown endpoint $path"}""")
        }
    }

    private fun readHeaderBytes(input: BufferedInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < 32 * 1024) {
            val b = input.read()
            if (b == -1) break
            out.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return out.toByteArray()
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun readExactBytes(input: BufferedInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buf, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }
}
