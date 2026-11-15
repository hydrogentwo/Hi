package com.example.cluster.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.cluster.model.AiCoreStatus
import com.example.cluster.model.DiscoveredPeer
import com.example.cluster.model.NodeRole
import com.example.cluster.model.NsdStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NsdDiscoveryManager(
    private val context: Context,
    private val onEventLog: (String) -> Unit = {}
) {

    companion object {
        const val SERVICE_TYPE = "_kubemesh._tcp."
        const val ATTR_NODE_ID = "nid"
        const val ATTR_NODE_NAME = "nname"
        const val ATTR_ROLE = "role"
        const val ATTR_CORES = "cores"
        const val ATTR_RAM_MB = "ram"
        const val ATTR_MODEL = "model"
        const val ATTR_AICORE = "ai"

        fun parsePeerFromAttributes(
            serviceName: String,
            host: String,
            port: Int,
            attributes: Map<String, ByteArray?>
        ): DiscoveredPeer {
            fun attr(key: String): String =
                attributes[key]?.toString(StandardCharsets.UTF_8)?.trim().orEmpty()

            val role = attr(ATTR_ROLE).let { raw ->
                if (raw.isEmpty()) null else runCatching { NodeRole.valueOf(raw) }.getOrNull()
            }
            return DiscoveredPeer(
                serviceName = serviceName,
                host = host,
                port = port,
                nodeId = attr(ATTR_NODE_ID),
                nodeName = attr(ATTR_NODE_NAME),
                role = role,
                cpuCores = attr(ATTR_CORES).toIntOrNull() ?: 0,
                totalRamMb = attr(ATTR_RAM_MB).toLongOrNull() ?: 0L,
                deviceModel = attr(ATTR_MODEL),
                aiCoreStatus = AiCoreStatus.fromWire(attr(ATTR_AICORE)),
                discoveredAtMs = System.currentTimeMillis()
            )
        }
    }

    private data class RegistrationSpec(
        val nodeName: String,
        val nodeId: String,
        val role: NodeRole,
        val cpuCores: Int,
        val totalRamMb: Long,
        val deviceModel: String,
        val aiCoreStatus: AiCoreStatus,
        val port: Int
    )

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registeredServiceName: String? = null
    private var lastRegistrationSpec: RegistrationSpec? = null
    private var localNodeId: String = ""

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val modernCallbacks = ConcurrentHashMap<String, Any>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _nsdStatus = MutableStateFlow(NsdStatus.IDLE)
    val nsdStatus: StateFlow<NsdStatus> = _nsdStatus.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lostPeers = MutableSharedFlow<DiscoveredPeer>(extraBufferCapacity = 16)
    val lostPeers: SharedFlow<DiscoveredPeer> = _lostPeers.asSharedFlow()

    init {
        registerWifiNetworkWatcher()
    }

    fun registerService(
        nodeName: String,
        nodeId: String,
        role: NodeRole,
        cpuCores: Int,
        totalRamMb: Long,
        deviceModel: String,
        port: Int,
        aiCoreStatus: AiCoreStatus = AiCoreStatus.UNKNOWN
    ) {
        val manager = nsdManager ?: return
        val spec = RegistrationSpec(
            nodeName = nodeName,
            nodeId = nodeId,
            role = role,
            cpuCores = cpuCores,
            totalRamMb = totalRamMb,
            deviceModel = deviceModel,
            aiCoreStatus = aiCoreStatus,
            port = port
        )
        lastRegistrationSpec = spec
        localNodeId = nodeId
        unregisterService()

        val safeName = "KubeMesh-${nodeName.take(14)}-${nodeId.take(4)}"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = safeName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTR_NODE_ID, nodeId.take(16))
            setAttribute(ATTR_NODE_NAME, nodeName.take(24))
            setAttribute(ATTR_ROLE, role.name)
            setAttribute(ATTR_CORES, cpuCores.toString())
            setAttribute(ATTR_RAM_MB, totalRamMb.toString())
            setAttribute(ATTR_MODEL, deviceModel.take(28))
            setAttribute(ATTR_AICORE, aiCoreStatus.name)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                _isAdvertising.value = true
                _lastError.value = null
                onEventLog("NSD advertising ${info.serviceName} ($SERVICE_TYPE) on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _isAdvertising.value = false
                _lastError.value = "NSD register failed (code $errorCode)"
                onEventLog("NSD registration failed (error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _isAdvertising.value = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _isAdvertising.value = false
            }
        }
        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { err ->
            _isAdvertising.value = false
            _lastError.value = err.message
        }
    }

    fun unregisterService() {
        val listener = registrationListener ?: return
        registrationListener = null
        registeredServiceName = null
        _isAdvertising.value = false
        runCatching { nsdManager?.unregisterService(listener) }
    }

    fun startDiscovery(clearExisting: Boolean = false) {
        val manager = nsdManager ?: return
        if (_isScanning.value) return

        acquireMulticastLock()
        if (clearExisting) {
            _discoveredPeers.value = emptyList()
        }
        _lastError.value = null

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isScanning.value = true
                _nsdStatus.value = NsdStatus.DISCOVERING
                _lastError.value = null
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("kubemesh", ignoreCase = true)) return
                if (isSelfServiceName(service.serviceName)) return
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                removePeerByServiceName(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isScanning.value = false
                if (_nsdStatus.value != NsdStatus.FAILED) {
                    _nsdStatus.value = NsdStatus.IDLE
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
                _nsdStatus.value = NsdStatus.FAILED
                _lastError.value = "NSD discovery failed (code $errorCode)"
                onEventLog("NSD startDiscovery failed (error $errorCode)")
                runCatching { manager.stopServiceDiscovery(this) }
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
                _nsdStatus.value = NsdStatus.IDLE
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { err ->
            _isScanning.value = false
            _nsdStatus.value = NsdStatus.FAILED
            _lastError.value = err.message
            releaseMulticastLock()
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        _isScanning.value = false
        _nsdStatus.value = NsdStatus.IDLE
        resolveQueue.clear()
        if (listener != null) {
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            modernCallbacks.values.forEach { cb ->
                runCatching {
                    nsdManager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
                }
            }
            modernCallbacks.clear()
        }
        releaseMulticastLock()
    }

    fun refreshDiscovery() {
        stopDiscovery()
        lastRegistrationSpec?.let { spec ->
            registerService(
                nodeName = spec.nodeName,
                nodeId = spec.nodeId,
                role = spec.role,
                cpuCores = spec.cpuCores,
                totalRamMb = spec.totalRamMb,
                deviceModel = spec.deviceModel,
                port = spec.port,
                aiCoreStatus = spec.aiCoreStatus
            )
        }
        startDiscovery(clearExisting = true)
    }

    private fun isSelfServiceName(name: String): Boolean {
        val reg = registeredServiceName ?: return false
        return name == reg
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (modernCallbacks.containsKey(serviceInfo.serviceName)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    modernCallbacks.remove(serviceInfo.serviceName)
                }

                override fun onServiceUpdated(info: NsdServiceInfo) {
                    val host = info.hostAddresses
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                        ?.hostAddress ?: return
                    if (info.port > 0) {
                        val peer = parsePeerFromAttributes(
                            serviceName = info.serviceName,
                            host = host,
                            port = info.port,
                            attributes = info.attributes
                        )
                        addOrUpdatePeer(peer)
                    }
                }

                override fun onServiceLost() {
                    removePeerByServiceName(serviceInfo.serviceName)
                }

                override fun onServiceInfoCallbackUnregistered() {
                    modernCallbacks.remove(serviceInfo.serviceName)
                }
            }
            modernCallbacks[serviceInfo.serviceName] = callback
            runCatching {
                nsdManager?.registerServiceInfoCallback(serviceInfo, executor, callback)
            }.onFailure {
                modernCallbacks.remove(serviceInfo.serviceName)
            }
        } else {
            resolveQueue.offer(serviceInfo)
            drainResolveQueue()
        }
    }

    @Suppress("DEPRECATION")
    private fun drainResolveQueue() {
        if (!isResolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            isResolving.set(false)
            return
        }
        val manager = nsdManager
        if (manager == null) {
            isResolving.set(false)
            return
        }
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    isResolving.set(false)
                    drainResolveQueue()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (!host.isNullOrBlank() && info.port > 0) {
                        val peer = parsePeerFromAttributes(
                            serviceName = info.serviceName,
                            host = host,
                            port = info.port,
                            attributes = info.attributes
                        )
                        addOrUpdatePeer(peer)
                    }
                    isResolving.set(false)
                    drainResolveQueue()
                }
            })
        }.onFailure {
            isResolving.set(false)
            drainResolveQueue()
        }
    }

    private fun addOrUpdatePeer(peer: DiscoveredPeer) {
        if (peer.nodeId.isNotEmpty() && peer.nodeId == localNodeId) return
        if (isSelfServiceName(peer.serviceName)) return
        _discoveredPeers.update { current ->
            val filtered = current.filterNot {
                (peer.nodeId.isNotEmpty() && it.nodeId == peer.nodeId) ||
                    (it.host == peer.host && it.port == peer.port)
            }
            filtered + peer
        }
        onEventLog("NSD resolved peer ${peer.displayTitle} at ${peer.host}:${peer.port}")
    }

    private fun removePeerByServiceName(serviceName: String) {
        val removed = _discoveredPeers.value.filter { it.serviceName == serviceName }
        _discoveredPeers.update { list ->
            list.filterNot { it.serviceName == serviceName }
        }
        removed.forEach { peer ->
            _lostPeers.tryEmit(peer)
            onEventLog("NSD lost service ${peer.displayTitle} (${peer.host}:${peer.port})")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val cb = modernCallbacks.remove(serviceName)
            if (cb != null) {
                runCatching {
                    nsdManager?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback)
                }
            }
        }
    }

    private fun registerWifiNetworkWatcher() {
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Re-advertise and restart discovery when Wi-Fi interface comes up or switches
                if (lastRegistrationSpec != null) {
                    val spec = lastRegistrationSpec!!
                    registerService(
                        nodeName = spec.nodeName,
                        nodeId = spec.nodeId,
                        role = spec.role,
                        cpuCores = spec.cpuCores,
                        totalRamMb = spec.totalRamMb,
                        deviceModel = spec.deviceModel,
                        port = spec.port,
                        aiCoreStatus = spec.aiCoreStatus
                    )
                    if (!_isScanning.value) {
                        startDiscovery()
                    }
                }
            }
        }
        networkCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("kubemesh_nsd_lock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    fun release() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        stopDiscovery()
        unregisterService()
        executor.shutdownNow()
    }
}
