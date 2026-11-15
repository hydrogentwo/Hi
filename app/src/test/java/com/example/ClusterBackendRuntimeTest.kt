package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cluster.ai.AiCoreEngine
import com.example.cluster.ai.GeminiCloudClient
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.data.ClusterPersistenceRepository
import com.example.cluster.data.KubeMeshDatabase
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.ClusterSnapshotResponse
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.PodActionResponse
import com.example.cluster.model.PodPhase
import com.example.cluster.model.PodShard
import com.example.cluster.model.PodSubmitRequest
import com.example.cluster.model.QueuedChunkState
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.TaskQueueItemStatus
import com.example.cluster.model.WorkloadType
import com.example.cluster.network.ClusterClient
import com.example.cluster.network.NodeHttpServer
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClusterBackendRuntimeTest {

    private lateinit var database: KubeMeshDatabase
    private lateinit var repository: ClusterPersistenceRepository
    private val moshi = Moshi.Builder().build()

    private fun sampleNode(
        id: String,
        name: String,
        role: NodeRole,
        host: String = "127.0.0.1",
        port: Int = 7443,
        isLocal: Boolean = false
    ) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = role,
        hostIp = host,
        port = port,
        cpuCores = 8,
        cpuLoadPercent = 28,
        cpuArch = "arm64-v8a",
        totalRamMb = 8192,
        availRamMb = 5120,
        totalStorageGb = 128f,
        availStorageGb = 80f,
        batteryPercent = 91,
        isCharging = true,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Pixel 9",
        activePods = 0,
        uptimeSeconds = 400L,
        isLocal = isLocal
    )

    private fun samplePod(podId: String, name: String, phase: PodPhase): ClusterPod = ClusterPod(
        podId = podId,
        name = name,
        workloadType = WorkloadType.PRIME_SIEVE,
        schedulingPolicy = SchedulingPolicy.PROPORTIONAL_SPLIT,
        phase = phase,
        requestedCores = 8,
        requestedMemMb = 256,
        intensityLabel = "1.6M integers",
        createdAtMs = System.currentTimeMillis(),
        completedAtMs = if (phase == PodPhase.SUCCEEDED) System.currentTimeMillis() else null,
        totalElapsedMs = 85L,
        aggregatedResult = "121,100 primes found",
        shards = listOf(
            PodShard(
                shardId = "$podId-0",
                nodeId = "n1",
                nodeName = "master",
                isRemote = false,
                hostEndpoint = "127.0.0.1:7443",
                weightPercent = 100,
                rangeStart = 2L,
                rangeEnd = 1000L,
                iterations = 999L,
                phase = phase
            )
        ),
        logs = listOf("[12:00:00] Completed pod $name"),
        yamlManifest = "kind: Pod",
        totalChunks = 1,
        completedChunks = if (phase == PodPhase.SUCCEEDED) 1 else 0,
        chunkTasks = listOf(
            QueuedChunkState(
                podId = podId,
                podName = name,
                workloadType = WorkloadType.PRIME_SIEVE,
                chunkIndex = 0,
                totalChunks = 1,
                rangeStart = 2L,
                rangeEnd = 1000L,
                iterations = 999L,
                status = if (phase == PodPhase.SUCCEEDED) TaskQueueItemStatus.COMPLETED else TaskQueueItemStatus.IN_PROGRESS
            )
        )
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KubeMeshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ClusterPersistenceRepository(database.clusterDao(), moshi)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Room persistence saves and loads pods peers and logs`() = runBlocking {
        val podDone = samplePod("p-done", "prime-done", PodPhase.SUCCEEDED)
        val podRun = samplePod("p-run", "prime-running", PodPhase.RUNNING)
        repository.savePod(podDone)
        repository.savePod(podRun)

        val peer = sampleNode("peer-1", "galaxy-worker", NodeRole.WORKER, "192.168.1.44", 7443)
        repository.savePeer(peer)
        repository.appendLog("[12:01:00] Peer joined mesh")

        val loadedPods = repository.loadPods()
        assertEquals(2, loadedPods.size)
        assertTrue(loadedPods.any { it.podId == "p-done" })

        val loadedPeers = repository.loadPeers()
        assertEquals(1, loadedPeers.size)
        assertEquals("galaxy-worker", loadedPeers.first().nodeName)
        assertFalse(loadedPeers.first().isLocal)

        val logs = repository.loadRecentLogs()
        assertEquals(listOf("[12:01:00] Peer joined mesh"), logs)

        repository.clearCompletedPods()
        val remainingPods = repository.loadPods()
        assertEquals(1, remainingPods.size)
        assertEquals("p-run", remainingPods.first().podId)

        repository.removePeer("peer-1")
        assertTrue(repository.loadPeers().isEmpty())
    }

    @Test
    fun `NodeHttpServer exposes cluster state nodes pod submit cancel and mesh peer gossip`() = runBlocking {
        val pods = mutableListOf<ClusterPod>()
        val gossipReceived = mutableListOf<NodeTelemetry>()
        val existingWorker = sampleNode("w-existing", "worker-alpha", NodeRole.WORKER, "192.168.1.20", 7443)

        lateinit var server: NodeHttpServer
        server = NodeHttpServer(
            computeEngine = ComputeEngine(),
            moshi = moshi,
            getLocalTelemetry = { sampleNode("cp-1", "control-master", NodeRole.CONTROL_PLANE, "127.0.0.1", server.boundPort, true) },
            getClusterToken = { "KM8899" },
            onPeerPaired = { _, _ -> },
            onPeerUnpaired = {},
            onServerLog = {},
            getKnownPeers = { listOf(existingWorker) },
            onGossipPeersReceived = { gossipReceived.addAll(it) },
            getClusterSnapshot = {
                val local = sampleNode("cp-1", "control-master", NodeRole.CONTROL_PLANE, "127.0.0.1", server.boundPort, true)
                ClusterSnapshotResponse(
                    localNode = local,
                    peers = listOf(existingWorker),
                    totalCpuCores = 16,
                    totalRamMb = 16384,
                    availRamMb = 10240,
                    activePodsCount = pods.count { it.phase == PodPhase.RUNNING },
                    pods = pods.toList()
                )
            },
            getPods = { pods.toList() },
            onSubmitPod = { req ->
                val created = samplePod("pod-remote-1", req.customPodName ?: "submitted-pod", PodPhase.RUNNING)
                pods.add(0, created)
                PodActionResponse(success = true, message = "Scheduled", pod = created)
            },
            onCancelPod = { podId ->
                val idx = pods.indexOfFirst { it.podId == podId }
                if (idx < 0) {
                    PodActionResponse(success = false, message = "Not found")
                } else {
                    val cancelled = pods[idx].copy(phase = PodPhase.FAILED, aggregatedResult = "Cancelled")
                    pods[idx] = cancelled
                    PodActionResponse(success = true, message = "Cancelled", pod = cancelled)
                }
            }
        )

        val port = server.start(0)
        try {
            val client = ClusterClient(moshi)
            val joiner = sampleNode("w-joiner", "worker-beta", NodeRole.WORKER, "127.0.0.1", 7445)
            val extraKnown = sampleNode("w-gamma", "worker-gamma", NodeRole.WORKER, "192.168.1.33", 7443)

            // Pair exchanges mesh peers in both directions
            val pairResp = client.pairWithNode("127.0.0.1", port, "KM8899", joiner, listOf(extraKnown)).getOrThrow()
            assertEquals(1, pairResp.meshPeers.size)
            assertEquals("w-existing", pairResp.meshPeers.first().nodeId)
            assertEquals(1, gossipReceived.size)
            assertEquals("w-gamma", gossipReceived.first().nodeId)

            // Fetch mesh nodes
            val meshNodes = client.fetchMeshNodes("127.0.0.1", port, "KM8899").getOrThrow()
            assertEquals(2, meshNodes.size)

            // Submit pod remotely
            val submitResp = client.submitRemotePod(
                host = "127.0.0.1",
                port = port,
                request = PodSubmitRequest(
                    clusterToken = "KM8899",
                    workloadId = WorkloadType.PRIME_SIEVE.id,
                    customPodName = "remote-sieve"
                )
            ).getOrThrow()
            assertTrue(submitResp.success)
            assertNotNull(submitResp.pod)

            // Fetch cluster snapshot
            val snapshot = client.fetchClusterState("127.0.0.1", port, "KM8899").getOrThrow()
            assertEquals(16, snapshot.totalCpuCores)
            assertEquals(1, snapshot.activePodsCount)
            assertEquals(1, snapshot.pods.size)

            // Cancel pod remotely
            val cancelResp = client.cancelRemotePod("127.0.0.1", port, "KM8899", "pod-remote-1").getOrThrow()
            assertTrue(cancelResp.success)
            assertEquals(PodPhase.FAILED, cancelResp.pod?.phase)
        } finally {
            server.destroy()
        }
    }

    @Test
    fun `AiCoreEngine falls back to GeminiCloudClient when on-device AICore is unavailable`() = runBlocking {
        val mockGeminiServer = ServerSocket(0)
        val geminiPort = mockGeminiServer.localPort
        val serverThread = thread {
            runCatching {
                mockGeminiServer.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val jsonBody = """
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  { "text": "Cluster healthy: use Proportional CPU Pool across 16 cores." }
                                ]
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                    val bytes = jsonBody.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply {
                        write(header.toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        try {
            val cloudClient = GeminiCloudClient(
                apiKeyProvider = { "test-gemini-api-key" },
                baseUrl = "http://127.0.0.1:$geminiPort/v1beta"
            )
            val engine = AiCoreEngine(
                modelFactory = { null },
                cloudFallback = cloudClient
            )

            val status = engine.refreshStatus()
            assertEquals(AiCoreStatus.CLOUD_READY, status)

            val res = engine.generate("Diagnose cluster")
            assertTrue(res.success)
            assertEquals(AiCoreStatus.CLOUD_READY, res.status)
            assertEquals("Cluster healthy: use Proportional CPU Pool across 16 cores.", res.outputText)
            engine.close()
        } finally {
            runCatching { mockGeminiServer.close() }
            serverThread.join(1_000L)
        }
    }
}
