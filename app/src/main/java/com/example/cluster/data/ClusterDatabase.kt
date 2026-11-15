package com.example.cluster.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.cluster.model.ClusterPod
import com.example.cluster.model.NodeTelemetry
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "pods")
data class PodEntity(
    @PrimaryKey val podId: String,
    val name: String,
    val namespace: String,
    val workloadType: String,
    val schedulingPolicy: String,
    val phase: String,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val totalElapsedMs: Long,
    @ColumnInfo(name = "pod_json") val podJson: String
)

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: String,
    val nodeName: String,
    val hostIp: String,
    val port: Int,
    val role: String,
    val lastSeenMs: Long,
    @ColumnInfo(name = "telemetry_json") val telemetryJson: String
)

@Entity(tableName = "cluster_logs")
data class ClusterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val formattedLine: String
)

@Dao
interface ClusterDao {
    @Query("SELECT * FROM pods ORDER BY createdAtMs DESC LIMIT :limit")
    fun observePods(limit: Int = 50): Flow<List<PodEntity>>

    @Query("SELECT * FROM pods ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun getRecentPods(limit: Int = 50): List<PodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPod(entity: PodEntity)

    @Query("DELETE FROM pods WHERE podId = :podId")
    suspend fun deletePod(podId: String)

    @Query("DELETE FROM pods WHERE phase IN ('SUCCEEDED', 'FAILED')")
    suspend fun clearCompletedPods()

    @Query("DELETE FROM pods WHERE podId NOT IN (SELECT podId FROM pods ORDER BY createdAtMs DESC LIMIT :keepCount)")
    suspend fun prunePods(keepCount: Int = 50)

    @Query("SELECT * FROM peers ORDER BY nodeName ASC")
    suspend fun getSavedPeers(): List<PeerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(entity: PeerEntity)

    @Query("DELETE FROM peers WHERE nodeId = :nodeId")
    suspend fun deletePeer(nodeId: String)

    @Query("DELETE FROM peers")
    suspend fun clearPeers()

    @Query("SELECT * FROM cluster_logs ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 120): List<ClusterLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entity: ClusterLogEntity)

    @Query("DELETE FROM cluster_logs WHERE id NOT IN (SELECT id FROM cluster_logs ORDER BY id DESC LIMIT :keepCount)")
    suspend fun pruneLogs(keepCount: Int = 200)

    @Query("DELETE FROM cluster_logs")
    suspend fun clearLogs()
}

@Database(
    entities = [PodEntity::class, PeerEntity::class, ClusterLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KubeMeshDatabase : RoomDatabase() {
    abstract fun clusterDao(): ClusterDao

    companion object {
        @Volatile
        private var instance: KubeMeshDatabase? = null

        fun getInstance(context: Context): KubeMeshDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KubeMeshDatabase::class.java,
                    "kubemesh_cluster.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

class ClusterPersistenceRepository(
    private val dao: ClusterDao,
    moshi: Moshi
) {
    private val podAdapter = moshi.adapter(ClusterPod::class.java)
    private val peerAdapter = moshi.adapter(NodeTelemetry::class.java)

    suspend fun loadPods(limit: Int = 50): List<ClusterPod> {
        return dao.getRecentPods(limit).mapNotNull { entity ->
            runCatching { podAdapter.fromJson(entity.podJson) }.getOrNull()
        }
    }

    fun observePods(limit: Int = 50): Flow<List<ClusterPod>> {
        return dao.observePods(limit).map { list ->
            list.mapNotNull { entity ->
                runCatching { podAdapter.fromJson(entity.podJson) }.getOrNull()
            }
        }
    }

    suspend fun savePod(pod: ClusterPod) {
        val json = runCatching { podAdapter.toJson(pod) }.getOrNull() ?: return
        dao.upsertPod(
            PodEntity(
                podId = pod.podId,
                name = pod.name,
                namespace = pod.namespace,
                workloadType = pod.workloadType.name,
                schedulingPolicy = pod.schedulingPolicy.name,
                phase = pod.phase.name,
                createdAtMs = pod.createdAtMs,
                completedAtMs = pod.completedAtMs,
                totalElapsedMs = pod.totalElapsedMs,
                podJson = json
            )
        )
        dao.prunePods(60)
    }

    suspend fun deletePod(podId: String) {
        dao.deletePod(podId)
    }

    suspend fun clearCompletedPods() {
        dao.clearCompletedPods()
    }

    suspend fun loadPeers(): List<NodeTelemetry> {
        return dao.getSavedPeers().mapNotNull { entity ->
            runCatching { peerAdapter.fromJson(entity.telemetryJson)?.copy(isLocal = false) }.getOrNull()
        }
    }

    suspend fun savePeer(peer: NodeTelemetry) {
        val normalized = peer.copy(isLocal = false)
        val json = runCatching { peerAdapter.toJson(normalized) }.getOrNull() ?: return
        dao.upsertPeer(
            PeerEntity(
                nodeId = normalized.nodeId,
                nodeName = normalized.nodeName,
                hostIp = normalized.hostIp,
                port = normalized.port,
                role = normalized.role.name,
                lastSeenMs = normalized.lastHeartbeatMs,
                telemetryJson = json
            )
        )
    }

    suspend fun removePeer(nodeId: String) {
        dao.deletePeer(nodeId)
    }

    suspend fun loadRecentLogs(limit: Int = 80): List<String> {
        return dao.getRecentLogs(limit).map { it.formattedLine }
    }

    suspend fun appendLog(formattedLine: String, timestampMs: Long = System.currentTimeMillis()) {
        dao.insertLog(ClusterLogEntity(timestampMs = timestampMs, formattedLine = formattedLine))
        dao.pruneLogs(200)
    }

    suspend fun clearLogs() {
        dao.clearLogs()
    }
}
