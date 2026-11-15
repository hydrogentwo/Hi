package com.example.cluster.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.cluster.model.NodeRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.clusterDataStore: DataStore<Preferences> by preferencesDataStore(name = "kubemesh_prefs")

data class SavedClusterConfig(
    val nodeId: String,
    val nodeName: String,
    val role: NodeRole,
    val clusterToken: String,
    val lastPeerHost: String,
    val lastPeerPort: Int,
    val daemonEnabled: Boolean,
    val autoJoinNsd: Boolean
)

class ClusterPreferences(private val context: Context) {

    private object Keys {
        val NODE_ID = stringPreferencesKey("node_id")
        val NODE_NAME = stringPreferencesKey("node_name")
        val NODE_ROLE = stringPreferencesKey("node_role")
        val CLUSTER_TOKEN = stringPreferencesKey("cluster_token")
        val LAST_PEER_HOST = stringPreferencesKey("last_peer_host")
        val LAST_PEER_PORT = intPreferencesKey("last_peer_port")
        val DAEMON_ENABLED = booleanPreferencesKey("daemon_enabled")
        val AUTO_JOIN_NSD = booleanPreferencesKey("auto_join_nsd")
    }

    private fun defaultNodeName(): String {
        val raw = Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return "droid-${raw.take(12).ifEmpty { "node" }}"
    }

    private fun generateToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    val configFlow: Flow<SavedClusterConfig> = context.clusterDataStore.data.map { prefs ->
        val nodeId = prefs[Keys.NODE_ID] ?: UUID.randomUUID().toString().take(8)
        val nodeName = prefs[Keys.NODE_NAME] ?: defaultNodeName()
        val role = prefs[Keys.NODE_ROLE]?.let {
            runCatching { NodeRole.valueOf(it) }.getOrNull()
        } ?: NodeRole.CONTROL_PLANE
        val token = prefs[Keys.CLUSTER_TOKEN] ?: "KM7443"
        val lastHost = prefs[Keys.LAST_PEER_HOST] ?: ""
        val lastPort = prefs[Keys.LAST_PEER_PORT] ?: 7443
        val daemon = prefs[Keys.DAEMON_ENABLED] ?: false
        val autoJoinNsd = prefs[Keys.AUTO_JOIN_NSD] ?: true

        SavedClusterConfig(
            nodeId = nodeId,
            nodeName = nodeName,
            role = role,
            clusterToken = token,
            lastPeerHost = lastHost,
            lastPeerPort = lastPort,
            daemonEnabled = daemon,
            autoJoinNsd = autoJoinNsd
        )
    }

    suspend fun ensureInitialized() {
        context.clusterDataStore.edit { prefs ->
            if (prefs[Keys.NODE_ID] == null) {
                prefs[Keys.NODE_ID] = UUID.randomUUID().toString().take(8)
            }
            if (prefs[Keys.NODE_NAME] == null) {
                prefs[Keys.NODE_NAME] = defaultNodeName()
            }
            if (prefs[Keys.CLUSTER_TOKEN] == null) {
                prefs[Keys.CLUSTER_TOKEN] = generateToken()
            }
        }
    }

    suspend fun updateRole(role: NodeRole) {
        context.clusterDataStore.edit { it[Keys.NODE_ROLE] = role.name }
    }

    suspend fun updateNodeName(name: String) {
        val sanitized = name.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").take(24)
        if (sanitized.isNotEmpty()) {
            context.clusterDataStore.edit { it[Keys.NODE_NAME] = sanitized }
        }
    }

    suspend fun updateClusterToken(token: String) {
        val clean = token.trim().uppercase().take(12)
        if (clean.isNotEmpty()) {
            context.clusterDataStore.edit { it[Keys.CLUSTER_TOKEN] = clean }
        }
    }

    suspend fun regenerateClusterToken(): String {
        val next = generateToken()
        context.clusterDataStore.edit { it[Keys.CLUSTER_TOKEN] = next }
        return next
    }

    suspend fun saveLastPeer(host: String, port: Int) {
        context.clusterDataStore.edit {
            it[Keys.LAST_PEER_HOST] = host
            it[Keys.LAST_PEER_PORT] = port
        }
    }

    suspend fun setDaemonEnabled(enabled: Boolean) {
        context.clusterDataStore.edit { it[Keys.DAEMON_ENABLED] = enabled }
    }

    suspend fun setAutoJoinNsd(enabled: Boolean) {
        context.clusterDataStore.edit { it[Keys.AUTO_JOIN_NSD] = enabled }
    }
}
