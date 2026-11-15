package com.example

import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.WorkloadType
import com.example.cluster.network.ClusterClient
import com.example.cluster.network.NodeHttpServer
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterProtocolTest {

    private fun sampleNode(id: String, name: String, role: NodeRole, port: Int) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = role,
        hostIp = "127.0.0.1",
        port = port,
        cpuCores = 4,
        cpuLoadPercent = 12,
        cpuArch = "arm64-v8a",
        totalRamMb = 8192,
        availRamMb = 4096,
        totalStorageGb = 128f,
        availStorageGb = 64f,
        batteryPercent = 88,
        isCharging = true,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Test Pixel",
        activePods = 0,
        uptimeSeconds = 120L,
        isLocal = true
    )

    @Test
    fun `compute engine executes prime sieve accurately`() = runBlocking {
        val engine = ComputeEngine()
        val resp = engine.executeShard(
            ShardExecuteRequest(
                clusterToken = "KM1234",
                podId = "pod-1",
                podName = "prime-test",
                workloadId = WorkloadType.PRIME_SIEVE.id,
                rangeStart = 2L,
                rangeEnd = 1000L,
                iterations = 999L
            )
        )
        assertTrue(resp.success)
        assertEquals(168, resp.primaryValue.toInt())
        assertEquals("maxPrime=997", resp.secondaryText)
    }

    @Test
    fun `server and client pair and execute shard over loopback tcp`() = runBlocking {
        val moshi = Moshi.Builder().build()
        val engine = ComputeEngine()
        var pairedPeerId: String? = null

        lateinit var server: NodeHttpServer
        server = NodeHttpServer(
            computeEngine = engine,
            moshi = moshi,
            getLocalTelemetry = { sampleNode("worker-1", "pixel-worker", NodeRole.WORKER, server.boundPort) },
            getClusterToken = { "KM9988" },
            onPeerPaired = { peer, _ -> pairedPeerId = peer.nodeId },
            onPeerUnpaired = {},
            onServerLog = {}
        )

        val port = server.start(0)
        try {
            val client = ClusterClient(moshi)
            val masterNode = sampleNode("master-1", "pixel-master", NodeRole.CONTROL_PLANE, 7443)

            // Reject invalid token
            val badPair = client.pairWithNode("127.0.0.1", port, "WRONG1", masterNode)
            assertTrue(badPair.isFailure)

            // Accept matching token
            val goodPair = client.pairWithNode("127.0.0.1", port, "KM9988", masterNode)
            assertTrue(goodPair.isSuccess)
            assertEquals("master-1", pairedPeerId)
            assertEquals("pixel-worker", goodPair.getOrThrow().hostNode.nodeName)

            // Execute Monte Carlo Pi shard remotely
            val shardResp = client.executeRemoteShard(
                host = "127.0.0.1",
                port = port,
                request = ShardExecuteRequest(
                    clusterToken = "KM9988",
                    podId = "pod-mc",
                    podName = "mc-pi",
                    workloadId = WorkloadType.MONTE_CARLO_PI.id,
                    rangeStart = 0L,
                    rangeEnd = 200_000L,
                    iterations = 200_000L
                )
            )
            assertTrue(shardResp.isSuccess)
            val result = shardResp.getOrThrow()
            assertTrue(result.success)
            assertEquals(200_000L, result.itemsProcessed)
            val piEst = 4.0 * result.primaryValue / result.itemsProcessed.toDouble()
            assertTrue("Expected Pi near 3.14, got $piEst", piEst in 3.05..3.25)
        } finally {
            server.destroy()
        }
    }
}
