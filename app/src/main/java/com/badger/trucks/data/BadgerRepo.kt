package com.badger.trucks.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.badger.trucks.BadgerApp

object BadgerRepo {
    private val client get() = BadgerApp.supabase

    // ===== LOADING DOORS =====
    suspend fun getLoadingDoors(): List<LoadingDoor> =
        client.postgrest["loading_doors"]
            .select { order("sort_order", Order.ASCENDING) }
            .decodeList()

    suspend fun updateDoorStatus(id: Int, status: String) {
        client.postgrest["loading_doors"]
            .update({ set("door_status", status) }) { filter { eq("id", id) } }
    }

    suspend fun updateDockLockStatus(id: Int, status: String?) {
        client.postgrest["loading_doors"]
            .update({ set("dock_lock_status", status) }) { filter { eq("id", id) } }
    }

    // ===== DOOR STATUS VALUES (dynamic — managed in Admin) =====
    suspend fun getDoorStatusValues(): List<DoorStatusValue> =
        client.postgrest["door_status_values"]
            .select { filter { eq("is_active", true) }; order("sort_order", Order.ASCENDING) }
            .decodeList()

    // ===== PRINTROOM ENTRIES =====
    suspend fun getPrintroomEntries(): List<PrintroomEntry> =
        client.postgrest["printroom_entries"]
            .select(Columns.raw("*, loading_doors(*)")) {
                order("loading_door_id", Order.ASCENDING)
                order("batch_number", Order.ASCENDING)
                order("row_order", Order.ASCENDING)
            }
            .decodeList()

    suspend fun upsertPrintroomEntry(entry: PrintroomEntry): PrintroomEntry =
        client.postgrest["printroom_entries"]
            .upsert(entry) { select() }
            .decodeSingle()

    suspend fun deletePrintroomEntry(id: Int) {
        client.postgrest["printroom_entries"]
            .delete { filter { eq("id", id) } }
    }

    suspend fun updatePrintroomField(id: Int, field: String, value: String?) {
        client.postgrest["printroom_entries"]
            .update({ set(field, value) }) { filter { eq("id", id) } }
    }

    suspend fun updatePrintroomFieldInt(id: Int, field: String, value: Int) {
        client.postgrest["printroom_entries"]
            .update({ set(field, value) }) { filter { eq("id", id) } }
    }

    // ===== STAGING DOORS (PreShift) =====
    suspend fun getStagingDoors(): List<StagingDoor> =
        client.postgrest["staging_doors"]
            .select {
                order("door_number", Order.ASCENDING)
                order("door_side", Order.ASCENDING)
            }
            .decodeList()

    suspend fun updateStagingField(id: Int, field: String, value: String?) {
        client.postgrest["staging_doors"]
            .update({ set(field, value) }) { filter { eq("id", id) } }
    }

    // ===== LIVE MOVEMENT =====
    suspend fun getLiveMovement(): List<LiveMovement> =
        client.postgrest["live_movement"]
            .select(Columns.raw("*, status_values(status_name, status_color)"))
            .decodeList()

    suspend fun addToMovement(truckNumber: String, location: String?) {
        client.postgrest["live_movement"]
            .insert(buildJsonObject {
                put("truck_number", truckNumber)
                if (location != null) put("current_location", location)
            })
    }

    suspend fun updateMovementLocation(id: Int, location: String?) {
        client.postgrest["live_movement"]
            .update({ set("current_location", location) }) { filter { eq("id", id) } }
    }

    suspend fun updateMovementStatus(truckNumber: String, statusId: Int?) {
        client.postgrest["live_movement"]
            .update({ set("status_id", statusId) }) { filter { eq("truck_number", truckNumber) } }
    }

    // ===== STATUS VALUES =====
    suspend fun getStatuses(): List<StatusValue> =
        client.postgrest["status_values"]
            .select { order("sort_order", Order.ASCENDING) }
            .decodeList()

    suspend fun addStatus(name: String, color: String) {
        client.postgrest["status_values"]
            .insert(buildJsonObject {
                put("status_name", name)
                put("status_color", color)
            })
    }

    suspend fun updateStatus(id: Int, name: String, color: String) {
        client.postgrest["status_values"]
            .update({
                set("status_name", name)
                set("status_color", color)
            }) { filter { eq("id", id) } }
    }

    suspend fun deleteStatus(id: Int) {
        client.postgrest["status_values"]
            .delete { filter { eq("id", id) } }
    }

    // ===== TRUCKS =====
    suspend fun getTrucks(): List<Truck> =
        client.postgrest["trucks"]
            .select { order("truck_number", Order.ASCENDING) }
            .decodeList()

    suspend fun addTruck(truck: Truck) {
        client.postgrest["trucks"].insert(truck)
    }

    suspend fun updateTruck(id: Int, truck: Truck) {
        client.postgrest["trucks"]
            .update({
                set("truck_number", truck.truckNumber)
                set("truck_type", truck.truckType)
                set("transmission", truck.transmission)
                set("notes", truck.notes)
            }) { filter { eq("id", id) } }
    }

    suspend fun deleteTruck(id: Int) {
        client.postgrest["trucks"]
            .delete { filter { eq("id", id) } }
    }

    // ===== TRACTORS =====
    suspend fun updateTractor(id: Int, tractor: Tractor) {
        client.postgrest["tractors"]
            .update({
                set("driver_name", tractor.driverName)
                set("driver_cell", tractor.driverCell)
                set("trailer_1_id", tractor.trailer1Id)
                set("trailer_2_id", tractor.trailer2Id)
                set("trailer_3_id", tractor.trailer3Id)
                set("trailer_4_id", tractor.trailer4Id)
                set("notes", tractor.notes)
            }) { filter { eq("id", id) } }
    }

    suspend fun getTractors(): List<Tractor> =
        client.postgrest["tractors"]
            .select(Columns.raw("*, trailer_1:trailer_list!tractors_trailer_1_id_fkey(*), trailer_2:trailer_list!tractors_trailer_2_id_fkey(*), trailer_3:trailer_list!tractors_trailer_3_id_fkey(*), trailer_4:trailer_list!tractors_trailer_4_id_fkey(*)")) {
                order("truck_number", Order.ASCENDING)
            }
            .decodeList()

    // ===== TRAILER LIST =====
    suspend fun getTrailerList(): List<TrailerItem> =
        client.postgrest["trailer_list"]
            .select { order("trailer_number", Order.ASCENDING) }
            .decodeList()

    // ===== AUTOMATION RULES =====
    suspend fun getAutomationRules(): List<AutomationRule> =
        client.postgrest["automation_rules"]
            .select { order("sort_order", Order.ASCENDING) }
            .decodeList()

    // ===== ROUTES =====
    suspend fun getRoutes(): List<Route> =
        client.postgrest["routes"]
            .select { order("route_name", Order.ASCENDING) }
            .decodeList()

    // ===== REALTIME =====
    fun realtimeChannel(name: String) = client.channel(name)
}
