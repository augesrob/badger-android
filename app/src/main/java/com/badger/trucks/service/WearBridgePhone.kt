package com.badger.trucks.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ── Wear data paths — duplicated here to avoid cross-module dependency ────────
object WearPaths {
    const val TRUCKS            = "/badger/trucks"
    const val DOORS             = "/badger/doors"
    const val STATUSES          = "/badger/statuses"
    const val MSG_PTT_START     = "/badger/ptt/start"
    const val MSG_PTT_STOP      = "/badger/ptt/stop"
    const val MSG_STATUS_CHANGE = "/badger/status"
    const val MSG_DOOR_CHANGE   = "/badger/door"
    const val MSG_STOP          = "/badger/stop"
    const val MSG_TTS           = "/badger/tts"
    const val MSG_PHONE_ALIVE   = "/badger/alive"
}

// ── Serializable wire models ───────────────────────────────────────────────────
@Serializable data class WearTruckData(val truckNumber: String, val statusName: String?, val statusColor: String?, val location: String?)
@Serializable data class WearDoorData(val id: Int, val doorName: String, val doorStatus: String, val statusColor: String?)
@Serializable data class WearStatusData(val id: Int, val statusName: String, val statusColor: String)

/**
 * Phone-side bridge — pushes data to the watch via Wearable DataClient.
 * Zero overhead when no watch is paired; all send calls silently no-op.
 */
object WearBridgePhone {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var context: Context? = null

    fun start(ctx: Context) {
        context = ctx.applicationContext
        Log.i("WearBridge", "Phone bridge started")
        startHeartbeat()
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.i("WearBridge", "Phone bridge stopped")
    }

    // ── Push data to watch ────────────────────────────────────────────────────

    fun pushTrucks(trucks: List<com.badger.trucks.data.LiveMovement>) {
        val payload = trucks.map {
            WearTruckData(it.truckNumber, it.statusName, it.statusColor, null)
        }
        sendMessage(WearPaths.TRUCKS, Json.encodeToString(payload).toByteArray())
    }

    fun pushDoors(doors: List<com.badger.trucks.data.LoadingDoor>) {
        val payload = doors.map {
            WearDoorData(it.id, it.doorName, it.doorStatus, null)
        }
        sendMessage(WearPaths.DOORS, Json.encodeToString(payload).toByteArray())
    }

    fun pushStatuses(statuses: List<com.badger.trucks.data.StatusValue>) {
        val payload = statuses.map {
            WearStatusData(it.id, it.statusName ?: "", it.statusColor ?: "#888888")
        }
        sendMessage(WearPaths.STATUSES, Json.encodeToString(payload).toByteArray())
    }

    fun pushTts(text: String) {
        sendMessage(WearPaths.MSG_TTS, text.toByteArray())
    }

    fun pushStop() {
        sendMessage(WearPaths.MSG_STOP, ByteArray(0))
    }

    fun isWatchConnected(ctx: Context): Boolean {
        return try {
            val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes)
            nodes.isNotEmpty()
        } catch (e: Exception) { false }
    }

    // ── Heartbeat — watch detects phone disconnect after 10s ─────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendMessage(WearPaths.MSG_PHONE_ALIVE, ByteArray(0))
                delay(5_000)
            }
        }
    }

    // ── Internal send — silently no-ops if watch not connected ────────────────

    private fun sendMessage(path: String, data: ByteArray) {
        val ctx = context ?: return
        scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes)
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx).sendMessage(node.id, path, data)
                }
            } catch (e: Exception) {
                Log.v("WearBridge", "Watch not reachable: ${e.message}")
            }
        }
    }
}
