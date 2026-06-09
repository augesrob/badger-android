package com.badger.wear

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WearPaths {
    const val MSG_PTT_START     = "/badger/ptt/start"
    const val MSG_PTT_STOP      = "/badger/ptt/stop"
    const val MSG_STATUS_CHANGE = "/badger/status"
    const val MSG_DOOR_CHANGE   = "/badger/door"
}

@Serializable
data class WearTruck(
    @SerialName("truck_number")    val truckNumber: String,
    @SerialName("status_id")       val statusId: Int? = null,
    @SerialName("current_location") val location: String? = null,
    @SerialName("loading_door_id") val loadingDoorId: Int? = null,
    @SerialName("status_values")   val statusValues: WearStatusEmbed? = null
) {
    val statusName:  String? get() = statusValues?.statusName
    val statusColor: String? get() = statusValues?.statusColor
}

@Serializable
data class WearStatusEmbed(
    @SerialName("status_name")  val statusName: String,
    @SerialName("status_color") val statusColor: String
)

@Serializable
data class WearDoor(
    @SerialName("id")          val id: Int,
    @SerialName("door_name")   val doorName: String,
    @SerialName("door_status") val doorStatus: String,
    @SerialName("sort_order")  val sortOrder: Int? = null
)

@Serializable
data class WearStatus(
    @SerialName("id")           val id: Int,
    @SerialName("status_name")  val statusName: String,
    @SerialName("status_color") val statusColor: String
)

// Door with its assigned trucks — for the grouped layout
data class WearDoorGroup(
    val door: WearDoor,
    val trucks: List<WearTruck>
)

@Serializable
data class WearPrintroomEntry(
    @SerialName("truck_number")   val truckNumber: String? = null,
    @SerialName("loading_door_id") val loadingDoorId: Int? = null,
    @SerialName("batch_number")   val batchNumber: Int? = null,
    @SerialName("row_order")      val rowOrder: Int? = null,
    @SerialName("is_end_marker")  val isEndMarker: Boolean? = null
)
