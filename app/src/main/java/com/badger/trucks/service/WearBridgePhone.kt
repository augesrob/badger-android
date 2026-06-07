package com.badger.trucks.service

import android.content.Context
import android.util.Log
import com.badger.wear.WearDoor
import com.badger.wear.WearPaths
import com.badger.wear.WearStatus
import com.badger.wear.WearTruck
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone-side bridge — pushes data to the watch and handles watch messages.
 * Started by BadgerService when watch is connected, stopped when disconnected.
 * Adds zero overhead when no watch is paired.
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
        val wearTrucks = trucks.map {
            WearTruck(
                truckNumber = it.truckNumber,
                statusName  = it.statusName,
                statusColor = it.statusColor,
                location    = null
            )
        }
        sendMessage(WearPaths.TRUCKS, Json.encodeToString(wearTrucks).toByteArray())
    }

    fun pushDoors(doors: List<com.badger.trucks.data.LoadingDoor>) {
        val wearDoors = doors.map {
            WearDoor(
                id          = it.id,
                doorName    = it.doorName,
                doorStatus  = it.doorStatus,
                statusColor = null
            )
        }
        sendMessage(WearPaths.DOORS, Json.encodeToString(wearDoors).toByteArray())
    }

    fun pushStatuses(statuses: List<com.badger.trucks.data.StatusValue>) {
        val wearStatuses = statuses.map {
            WearStatus(id = it.id, statusName = it.statusName ?: "", statusColor = it.statusColor ?: "#888888")
        }
        sendMessage(WearPaths.STATUSES, Json.encodeToString(wearStatuses).toByteArray())
    }

    /** Called when BadgerService announces TTS — mirror to watch speaker */
    fun pushTts(text: String) {
        sendMessage(WearPaths.MSG_TTS, text.toByteArray())
    }

    /** Tell watch to stop its service (e.g. user stopped phone service) */
    fun pushStop() {
        sendMessage(WearPaths.MSG_STOP, ByteArray(0))
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                sendMessage(WearPaths.MSG_PHONE_ALIVE, ByteArray(0))
                delay(5_000) // every 5s — watch times out at 10s
            }
        }
    }

    // ── Wearable messaging ────────────────────────────────────────────────────

    fun isWatchConnected(ctx: Context): Boolean {
        return try {
            val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes)
            nodes.isNotEmpty()
        } catch (e: Exception) { false }
    }

    private fun sendMessage(path: String, data: ByteArray) {
        val ctx = context ?: return
        scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(ctx).connectedNodes)
                nodes.forEach { node ->
                    Wearable.getMessageClient(ctx).sendMessage(node.id, path, data)
                }
            } catch (e: Exception) {
                // Watch not connected — silently ignore, zero impact on phone
                Log.v("WearBridge", "Watch not reachable: ${e.message}")
            }
        }
    }
}
