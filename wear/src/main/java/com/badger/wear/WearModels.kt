package com.badger.wear

import kotlinx.serialization.Serializable

// ── Shared data paths (phone <-> watch Wearable DataClient) ──────────────────
object WearPaths {
    const val TRUCKS       = "/badger/trucks"        // full truck list
    const val DOORS        = "/badger/doors"          // full door list
    const val STATUSES     = "/badger/statuses"       // status value list
    const val DOOR_STATUSES = "/badger/door_statuses" // door status values
    const val MSG_PTT_START = "/badger/ptt/start"    // watch -> phone: start PTT
    const val MSG_PTT_STOP  = "/badger/ptt/stop"     // watch -> phone: stop PTT
    const val MSG_PTT_AUDIO = "/badger/ptt/audio"    // phone -> watch: incoming PTT audio
    const val MSG_STATUS_CHANGE = "/badger/status"   // watch -> phone: change truck status
    const val MSG_DOOR_CHANGE   = "/badger/door"     // watch -> phone: change door status
    const val MSG_STOP          = "/badger/stop"     // phone -> watch: stop watch service
    const val MSG_TTS           = "/badger/tts"      // phone -> watch: speak this text
    const val MSG_PHONE_ALIVE   = "/badger/alive"    // phone -> watch: heartbeat
    const val WEAR_MODE         = "/badger/mode"     // watch -> phone: current mode
}

// ── Serializable models sent over Wearable DataClient ───────────────────────

@Serializable
data class WearTruck(
    val truckNumber: String,
    val statusName: String?,
    val statusColor: String?,
    val location: String?
)

@Serializable
data class WearDoor(
    val id: Int,
    val doorName: String,
    val doorStatus: String,
    val statusColor: String?
)

@Serializable
data class WearStatus(
    val id: Int,
    val statusName: String,
    val statusColor: String
)

@Serializable
data class WearStatusChange(
    val truckNumber: String,
    val statusId: Int
)

@Serializable
data class WearDoorChange(
    val doorId: Int,
    val status: String
)

enum class WearMode { PHONE_RELAY, STANDALONE }
