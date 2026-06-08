package com.badger.wear

import kotlinx.serialization.Serializable

// ── Shared data paths (must match WearBridgePhone.WearPaths on phone side) ───
object WearPaths {
    const val TRUCKS            = "/badger/trucks"
    const val DOORS             = "/badger/doors"
    const val STATUSES          = "/badger/statuses"
    const val DOOR_STATUSES     = "/badger/door_statuses"
    const val MSG_PTT_START     = "/badger/ptt/start"
    const val MSG_PTT_STOP      = "/badger/ptt/stop"
    const val MSG_PTT_AUDIO     = "/badger/ptt/audio"
    const val MSG_STATUS_CHANGE = "/badger/status"
    const val MSG_DOOR_CHANGE   = "/badger/door"
    const val MSG_STOP          = "/badger/stop"
    const val MSG_TTS           = "/badger/tts"
    const val MSG_PHONE_ALIVE   = "/badger/alive"
    const val WEAR_MODE         = "/badger/mode"
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

