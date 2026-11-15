package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.cluster.ai.AiCoreEngine
import com.example.cluster.ai.AiCoreLocalState
import com.example.cluster.compute.ComputeEngine
import com.example.cluster.model.AiCorePromptRequest
import com.example.cluster.model.AiCorePromptResponse
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.network.ClusterClient
import com.example.cluster.network.NodeHttpServer
import com.example.cluster.network.NsdDiscoveryManager
import com.example.cluster.ui.AiCoreInferenceState
import com.example.cluster.ui.ClusterUiState
import com.example.cluster.ui.WorkloadsScreen
import com.example.ui.theme.KubeMeshTheme
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiCoreIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleNode(
        id: String,
        name: String,
        role: NodeRole,
        aiStatus: AiCoreStatus = AiCoreStatus.AVAILABLE,
        isLocal: Boolean = true
    ) = NodeTelemetry(
        nodeId = id,
        nodeName = name,
        role = role,
        hostIp = if (isLocal) "192.168.1.10" else "192.168.1.25",
        port = 7443,
        cpuCores = 8,
        cpuLoadPercent = 31,
        cpuArch = "arm64-v8a",
        totalRamMb = 8192,
        availRamMb = 4096,
        totalStorageGb = 128f,
        availStorageGb = 64f,
        batteryPercent = 82,
        isCharging = true,
        thermalStatusLabel = "Nominal",
        isThermalThrottled = false,
        androidVersion = "Android 15",
        deviceModel = "Pixel 9",
        activePods = 0,
        uptimeSeconds = 200L,
        isLocal = isLocal,
        aiCoreStatus = aiStatus.name
    )

    @Test
    fun `AiCoreEngine reports UNAVAILABLE gracefully when AICore binder is absent`() = runBlocking {
        val engine = AiCoreEngine(modelFactory = { null })
        val status = engine.refreshStatus()
        assertEquals(AiCoreStatus.UNAVAILABLE, status)

        val res = engine.generate("Summarize cluster status")
        assertFalse(res.success)
        assertEquals(AiCoreStatus.UNAVAILABLE, res.status)
        engine.close()
    }

    @Test
    fun `NSD TXT parser decodes AICore readiness attribute`() {
        val attrs = mapOf(
            NsdDiscoveryManager.ATTR_NODE_ID to "peer-nano".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_NODE_NAME to "pixel9-worker".toByteArray(StandardCharsets.UTF_8),
            NsdDiscoveryManager.ATTR_AICORE to "AVAILABLE".toByteArray(StandardCharsets.UTF_8)
        )
        val peer = NsdDiscoveryManager.parsePeerFromAttributes(
            serviceName = "KubeMesh-pixel9-peer",
            host = "192.168.1.50",
            port = 7443,
            attributes = attrs
        )
        assertEquals(AiCoreStatus.AVAILABLE, peer.aiCoreStatus)
    }

    @Test
    fun `NodeHttpServer and ClusterClient route remote AICore prompt over TCP with token validation`() = runBlocking {
        val moshi = Moshi.Builder().build()
        lateinit var server: NodeHttpServer
        server = NodeHttpServer(
            computeEngine = ComputeEngine(),
            moshi = moshi,
            getLocalTelemetry = { sampleNode("w1", "pixel9-nano", NodeRole.WORKER, AiCoreStatus.AVAILABLE, false) },
            getClusterToken = { "KM5544" },
            onPeerPaired = { _, _ -> },
            onPeerUnpaired = {},
            onServerLog = {},
            onExecuteAiPrompt = { req ->
                AiCorePromptResponse(
                    success = true,
                    outputText = "Gemini Nano processed: ${req.prompt}",
                    elapsedMs = 42L,
                    targetNodeName = "pixel9-nano",
                    aiCoreStatus = AiCoreStatus.AVAILABLE.name
                )
            }
        )
        val port = server.start(0)
        try {
            val client = ClusterClient(moshi)

            val badTokenResp = client.executeRemoteAiPrompt(
                host = "127.0.0.1",
                port = port,
                request = AiCorePromptRequest(clusterToken = "BAD000", prompt = "Hello")
            )
            assertTrue(badTokenResp.isFailure)

            val okResp = client.executeRemoteAiPrompt(
                host = "127.0.0.1",
                port = port,
                request = AiCorePromptRequest(clusterToken = "KM5544", prompt = "Check cluster thermal headroom")
            )
            assertTrue(okResp.isSuccess)
            val body = okResp.getOrThrow()
            assertTrue(body.success)
            assertEquals("pixel9-nano", body.targetNodeName)
            assertEquals("Gemini Nano processed: Check cluster thermal headroom", body.outputText)
        } finally {
            server.destroy()
        }
    }

    @Test
    fun `WorkloadsScreen renders AICore card controls target nodes and output`() {
        val local = sampleNode("n1", "pixel-master", NodeRole.CONTROL_PLANE, AiCoreStatus.AVAILABLE, true)
        val remote = sampleNode("n2", "pixel9-worker", NodeRole.WORKER, AiCoreStatus.DOWNLOADABLE, false)
        var capturedPrompt = ""
        var capturedTarget: String? = "unset"
        var diagnosed = false

        val uiState = ClusterUiState(
            localNode = local,
            remotePeers = listOf(remote),
            aiCoreLocal = AiCoreLocalState(
                status = AiCoreStatus.AVAILABLE,
                detail = "Gemini Nano ready in AICore",
                isWarmedUp = false
            ),
            aiCoreInference = AiCoreInferenceState(
                isRunning = false,
                outputText = "Cluster ready for proportional CPU split.",
                targetNodeName = "pixel-master",
                elapsedMs = 115L
            )
        )

        composeRule.setContent {
            KubeMeshTheme(darkTheme = true) {
                WorkloadsScreen(
                    uiState = uiState,
                    onDeployPod = { _, _, _, _ -> },
                    onInspectPod = {},
                    onRunAiPrompt = { p, target ->
                        capturedPrompt = p
                        capturedTarget = target
                    },
                    onDiagnoseClusterAi = { diagnosed = true }
                )
            }
        }

        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_card"))
        composeRule.onNodeWithTag("aicore_status_pill").assertIsDisplayed()
        composeRule.onNodeWithText("NANO READY").assertIsDisplayed()
        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_warmup_btn"))
        composeRule.onNodeWithTag("aicore_warmup_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_output_box"))
        composeRule.onNodeWithTag("aicore_output_box").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cluster ready for proportional CPU split.").assertIsDisplayed()

        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_prompt_input"))
        composeRule.onNodeWithTag("aicore_prompt_input").performScrollTo().performTextInput("Plan pod split")
        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_run_btn"))
        composeRule.onNodeWithTag("aicore_run_btn").performScrollTo().performClick()
        assertEquals("Plan pod split", capturedPrompt)
        assertEquals(null, capturedTarget)

        composeRule.onNodeWithTag("workloads_list").performScrollToNode(hasTestTag("aicore_diagnose_btn"))
        composeRule.onNodeWithTag("aicore_diagnose_btn").performScrollTo().performClick()
        assertTrue(diagnosed)
    }
}
