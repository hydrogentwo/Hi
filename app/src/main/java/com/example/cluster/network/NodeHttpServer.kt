package com.example.cluster.network

import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PairRequest
import com.example.cluster.model.PairResponse
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.ShardExecuteResponse
import com.squareup.moshi.Moshi
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
    private val onExecuteAiPrompt: (suspend (AiCorePromptRequest) -> AiCorePromptResponse)? = null
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
        socket.use { s ->
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val rawHeader = readHeaderBytes(input) ?: return
            val headerText = String(rawHeader, StandardCharsets.US_ASCII)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return

            val requestParts = lines[0].split(" ")
            if (requestParts.size < 2) return
            val method = requestParts[0].uppercase()
            val path = requestParts[1].substringBefore("?")

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

            val (statusCode, statusText, responseJson) = routeRequest(
                method = method,
                path = path,
                headerToken = headerToken,
                bodyText = bodyText,
                remoteIp = remoteIp
            )

            val payload = responseJson.toByteArray(StandardCharsets.UTF_8)
            val responseHeader = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)

            s.getOutputStream().apply {
                write(responseHeader)
                write(payload)
                flush()
            }
        }
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
                if (headerToken.isNotEmpty() && !headerToken.equals(expectedToken, ignoreCase = true)) {
                    Triple(401, "Unauthorized", """{"error":"Cluster token mismatch"}""")
                } else {
                    val local = getLocalTelemetry()
                    Triple(200, "OK", telemetryAdapter.toJson(local))
                }
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
                    val effectivePeer = if (req.peerNode.hostIp == "127.0.0.1" && remoteIp.isNotEmpty()) {
                        req.peerNode.copy(hostIp = remoteIp, isLocal = false)
                    } else {
                        req.peerNode.copy(isLocal = false)
                    }
                    onPeerPaired(effectivePeer, remoteIp)
                    onServerLog("Paired peer ${effectivePeer.nodeName} (${effectivePeer.hostIp}:${effectivePeer.port})")
                    val resp = PairResponse(
                        accepted = true,
                        message = "Unified with ${getLocalTelemetry().nodeName}",
                        hostNode = getLocalTelemetry()
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
