package com.badger.trucks.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Truck(
    val id: Int = 0,
    @SerialName("truck_number") val truckNumber: Int,
    @SerialName("truck_type") val truckType: String = "box_truck",
    val transmission: String = "automatic",
    @SerialName("is_active") val isActive: Boolean = true,
    val notes: String? = null
)

@Serializable
data class StatusValue(
    val id: Int = 0,
    @SerialName("status_name") val statusName: String,
    @SerialName("status_color") val statusColor: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class LoadingDoor(
    val id: Int = 0,
    @SerialName("door_name") val doorName: String,
    @SerialName("door_status") val doorStatus: String = "",
    @SerialName("is_done_for_night") val isDoneForNight: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class PrintroomEntry(
    val id: Int = 0,
    @SerialName("loading_door_id") val loadingDoorId: Int,
    @SerialName("batch_number") val batchNumber: Int = 1,
    @SerialName("row_order") val rowOrder: Int = 0,
    @SerialName("route_info") val routeInfo: String? = null,
    @SerialName("truck_number") val truckNumber: String? = null,
    val pods: Int? = 0,
    @SerialName("pallets_trays") val palletsTrays: Int? = 0,
    val notes: String? = null,
    @SerialName("is_end_marker") val isEndMarker: Boolean = false,
    // Joined
    @SerialName("loading_doors") val loadingDoor: LoadingDoor? = null
)

@Serializable
data class StagingDoor(
    val id: Int = 0,
    @SerialName("door_label") val doorLabel: String,
    @SerialName("door_number") val doorNumber: Int,
    @SerialName("door_side") val doorSide: String,
    @SerialName("in_front") val inFront: String? = null,
    @SerialName("in_back") val inBack: String? = null
)

@Serializable
data class LiveMovementStatus(
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("status_color") val statusColor: String? = null
)

@Serializable
data class LiveMovement(
    val id: Int = 0,
    @SerialName("truck_number") val truckNumber: String,
    @SerialName("current_location") val currentLocation: String? = null,
    @SerialName("status_id") val statusId: Int? = null,
    @SerialName("in_front_of") val inFrontOf: String? = null,
    val notes: String? = null,
    @SerialName("loading_door_id") val loadingDoorId: Int? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
    // Joined
    @SerialName("status_values") val statusValues: LiveMovementStatus? = null,
    @SerialName("door_name") val doorName: String? = null
) {
    val statusName: String? get() = statusValues?.statusName
    val statusColor: String? get() = statusValues?.statusColor
}

@Serializable
data class TrailerItem(
    val id: Int = 0,
    @SerialName("trailer_number") val trailerNumber: String,
    val notes: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class Tractor(
    val id: Int = 0,
    @SerialName("truck_number") val truckNumber: Int,
    @SerialName("driver_name") val driverName: String? = null,
    @SerialName("driver_cell") val driverCell: String? = null,
    @SerialName("trailer_1_id") val trailer1Id: Int? = null,
    @SerialName("trailer_2_id") val trailer2Id: Int? = null,
    @SerialName("trailer_3_id") val trailer3Id: Int? = null,
    @SerialName("trailer_4_id") val trailer4Id: Int? = null,
    val notes: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    // Joined
    @SerialName("trailer_1") val trailer1: TrailerItem? = null,
    @SerialName("trailer_2") val trailer2: TrailerItem? = null,
    @SerialName("trailer_3") val trailer3: TrailerItem? = null,
    @SerialName("trailer_4") val trailer4: TrailerItem? = null
)

@Serializable
data class AutomationRule(
    val id: Int = 0,
    @SerialName("rule_name") val ruleName: String,
    val description: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("trigger_type") val triggerType: String,
    @SerialName("trigger_field") val triggerField: String? = null,
    @SerialName("trigger_value") val triggerValue: String? = null,
    @SerialName("action_type") val actionType: String,
    @SerialName("action_value") val actionValue: String,
    @SerialName("sort_order") val sortOrder: Int = 0
)

@Serializable
data class Route(
    val id: Int = 0,
    @SerialName("route_name") val routeName: String,
    @SerialName("route_number") val routeNumber: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
)

// Door status constants
val DOOR_STATUSES = listOf(
    "Loading", "End Of Tote", "EOT+1", "Change Truck/Trailer",
    "Waiting", "Done for Night", "100%"
)

fun doorStatusColor(status: String): Long = when (status) {
    "Loading" -> 0xFF3B82F6
    "End Of Tote" -> 0xFFF59E0B
    "EOT+1" -> 0xFFF97316
    "Change Truck/Trailer" -> 0xFF8B5CF6
    "Waiting" -> 0xFF6B7280
    "Done for Night" -> 0xFF22C55E
    "100%" -> 0xFF22C55E
    else -> 0xFF6B7280
}
