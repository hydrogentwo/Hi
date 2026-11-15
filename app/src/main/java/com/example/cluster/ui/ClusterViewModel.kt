package com.example.cluster.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NodeTelemetry
import com.example.cluster.model.SchedulingPolicy
import com.example.cluster.model.WorkloadType
import com.example.cluster.service.ClusterBackendManager
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ClusterViewModel(application: Application) : AndroidViewModel(application) {

    private val backend = ClusterBackendManager.getInstance(application)

    val uiState: StateFlow<ClusterUiState> = backend.state
    val events: SharedFlow<String> = backend.events

    init {
        backend.ensureStarted()
    }

    fun setNodeRole(role: NodeRole) = backend.setNodeRole(role)

    fun updateNodeName(name: String) = backend.updateNodeName(name)

    fun updateClusterToken(token: String) = backend.updateClusterToken(token)

    fun regenerateToken() = backend.regenerateToken()

    fun toggleLanScan() = backend.toggleLanScan()

    fun setAutoJoinNsd(enabled: Boolean) = backend.setAutoJoinNsd(enabled)

    fun onAppLifecycleChanged(isForeground: Boolean) = backend.onAppLifecycleChanged(isForeground)

    fun pairWithPeer(hostInput: String, portInput: Int, tokenInput: String) =
        backend.pairWithPeer(hostInput, portInput, tokenInput)

    fun disconnectPeer(nodeId: String) = backend.disconnectPeer(nodeId)

    fun setDaemonEnabled(enabled: Boolean) = backend.setDaemonEnabled(enabled)

    fun selectNodeForSheet(node: NodeTelemetry?) = backend.selectNodeForSheet(node)

    fun selectPodForLogs(podId: String?) = backend.selectPodForLogs(podId)

    fun deployPod(
        workloadType: WorkloadType,
        schedulingPolicy: SchedulingPolicy,
        scaleMultiplier: Int = 1,
        customPodName: String? = null
    ) = backend.deployPod(workloadType, schedulingPolicy, scaleMultiplier, customPodName)

    fun cancelPod(podId: String) = backend.cancelPod(podId)

    fun clearCompletedPods() = backend.clearCompletedPods()

    fun refreshAiCoreStatus() = backend.refreshAiCoreStatus()

    fun downloadAiCoreModel() = backend.downloadAiCoreModel()

    fun warmupAiCore() = backend.warmupAiCore()

    fun diagnoseClusterWithAiCore(targetNodeId: String? = null) =
        backend.diagnoseClusterWithAiCore(targetNodeId)

    fun runClusterAiPrompt(prompt: String, targetNodeId: String? = null) =
        backend.runClusterAiPrompt(prompt, targetNodeId)
}
