package com.example.cluster.network

import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PairRequest
import com.example.cluster.model.PairResponse
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.ShardExecuteResponse
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClusterClient(private val moshi: Moshi) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .writeTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    private val computeClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val telemetryAdapter = moshi.adapter(NodeTelemetry::class.java)
    private val pairReqAdapter = moshi.adapter(PairRequest::class.java)
    private val pairRespAdapter = moshi.adapter(PairResponse::class.java)
    private val shardReqAdapter = moshi.adapter(ShardExecuteRequest::class.java)
    private val shardRespAdapter = moshi.adapter(ShardExecuteResponse::class.java)
    private val aiReqAdapter = moshi.adapter(AiCorePromptRequest::class.java)
    private val aiRespAdapter = moshi.adapter(AiCorePromptResponse::class.java)

    suspend fun pairWithNode(
        host: String,
        port: Int,
        clusterToken: String,
        localNode: NodeTelemetry
    ): Result<PairResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = pairReqAdapter.toJson(PairRequest(clusterToken, localNode))
            val req = Request.Builder()
                .url("http://$host:$port/v1/pair")
                .addHeader("X-Cluster-Token", clusterToken)
                .post(payload.toRequestBody(jsonMedia))
                .build()
            val startNs = System.nanoTime()
            fastClient.newCall(req).execute().use { resp ->
                val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val body = resp.body?.string().orEmpty()
                val parsed = runCatching { pairRespAdapter.fromJson(body) }.getOrNull()
                if (!resp.isSuccessful || parsed == null || !parsed.accepted) {
                    val msg = parsed?.message ?: "HTTP ${resp.code}: ${body.ifBlank { resp.message }}"
                    throw IllegalStateException(msg)
                }
                parsed.copy(
                    hostNode = parsed.hostNode.copy(
                        hostIp = host,
                        port = port,
                        rttMs = rttMs,
                        lastHeartbeatMs = System.currentTimeMillis(),
                        isLocal = false
                    )
                )
            }
        }
    }

    suspend fun fetchHealth(
        host: String,
        port: Int,
        clusterToken: String
    ): Result<NodeTelemetry> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://$host:$port/v1/health")
                .addHeader("X-Cluster-Token", clusterToken)
                .get()
                .build()
            val startNs = System.nanoTime()
            fastClient.newCall(req).execute().use { resp ->
                val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Health check HTTP ${resp.code}")
                }
                val node = telemetryAdapter.fromJson(body)
                    ?: throw IllegalStateException("Malformed health JSON")
                node.copy(
                    hostIp = host,
                    port = port,
                    rttMs = rttMs,
                    lastHeartbeatMs = System.currentTimeMillis(),
                    isLocal = false
                )
            }
        }
    }

    suspend fun executeRemoteShard(
        host: String,
        port: Int,
        request: ShardExecuteRequest
    ): Result<ShardExecuteResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = shardReqAdapter.toJson(request)
            val req = Request.Builder()
                .url("http://$host:$port/v1/pods/execute")
                .addHeader("X-Cluster-Token", request.clusterToken)
                .post(payload.toRequestBody(jsonMedia))
                .build()
            computeClient.newCall(req).awaitResponse().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Remote shard HTTP ${resp.code}: $body")
                }
                shardRespAdapter.fromJson(body)
                    ?: throw IllegalStateException("Malformed shard execution response")
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
        }
    }

    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response) { _, value, _ ->
                            value.close()
                        }
                    } else {
                        response.close()
                    }
                }
            })
        }

    suspend fun executeRemoteAiPrompt(
        host: String,
        port: Int,
        request: AiCorePromptRequest
    ): Result<AiCorePromptResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = aiReqAdapter.toJson(request)
            val req = Request.Builder()
                .url("http://$host:$port/v1/aicore/infer")
                .addHeader("X-Cluster-Token", request.clusterToken)
                .post(payload.toRequestBody(jsonMedia))
                .build()
            computeClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Remote AICore HTTP ${resp.code}: $body")
                }
                aiRespAdapter.fromJson(body)
                    ?: throw IllegalStateException("Malformed remote AICore response")
            }
        }
    }

    suspend fun notifyUnpair(
        host: String,
        port: Int,
        clusterToken: String,
        localNodeId: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("http://$host:$port/v1/unpair")
                .addHeader("X-Cluster-Token", clusterToken)
                .post(localNodeId.toRequestBody(jsonMedia))
                .build()
            fastClient.newCall(req).execute().close()
        }
    }
}
