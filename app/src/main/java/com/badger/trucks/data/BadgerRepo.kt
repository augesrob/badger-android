package com.badger.trucks.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.badger.trucks.BadgerApp
import com.badger.trucks.util.RemoteLogger

object BadgerRepo {
    private val client get() = BadgerApp.supabase

    // ===== LOADING DOORS =====
    suspend fun getLoadingDoors(): List<LoadingDoor> {
        return try {
            val result = client.postgrest["loading_doors"]
                .select { order("sort_order", Order.ASCENDING) }
                .decodeList<LoadingDoor>()
            RemoteLogger.d("BadgerRepo", "getLoadingDoors OK — ${result.size} doors")
            result
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "getLoadingDoors FAILED: ${e.message}")
            throw e
        }
    }

    suspend fun updateDoorStatus(id: Int, status: String) {
        try {
            client.postgrest["loading_doors"]
                .update({ set("door_status", status) }) { filter { eq("id", id) } }
            RemoteLogger.i("BadgerRepo", "updateDoorStatus OK — id=$id status=$status")
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "updateDoorStatus FAILED id=$id: ${e.message}")
            throw e
        }
    }

    suspend fun updateDockLockStatus(id: Int, status: String?) {
        client.postgrest["loading_doors"]
            .update({ set("dock_lock_status", status) }) { filter { eq("id", id) } }
    }

    // ===== DOCK LOCK STATUS VALUES (dynamic — managed in Admin) =====
    suspend fun getDockLockStatusValues(): List<DockLockStatusValue> =
        client.postgrest["dock_lock_status_values"]
            .select { filter { eq("is_active", true) }; order("sort_order", Order.ASCENDING) }
            .decodeList()

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

    suspend fun upsertPrintroomEntry(entry: PrintroomEntry): PrintroomEntry {
        // Strip joined fields — only send columns that exist in the table
        val clean = entry.copy(loadingDoor = null)
        return client.postgrest["printroom_entries"]
            .upsert(clean) { select() }
            .decodeSingle()
    }

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
    suspend fun getLiveMovement(): List<LiveMovement> {
        return try {
            val result = client.postgrest["live_movement"]
                .select(Columns.raw("*, status_values(status_name, status_color)"))
                .decodeList<LiveMovement>()
            RemoteLogger.d("BadgerRepo", "getLiveMovement OK — ${result.size} trucks")
            result
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "getLiveMovement FAILED: ${e.message}")
            throw e
        }
    }

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
        try {
            client.postgrest["live_movement"]
                .update({ set("status_id", statusId) }) { filter { eq("truck_number", truckNumber) } }
            RemoteLogger.i("BadgerRepo", "updateMovementStatus OK — truck=$truckNumber statusId=$statusId")
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "updateMovementStatus FAILED truck=$truckNumber: ${e.message}")
            throw e
        }
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

    suspend fun addTrailer(trailerNumber: String, notes: String?) {
        client.postgrest["trailer_list"].insert(
            buildJsonObject {
                put("trailer_number", trailerNumber)
                if (!notes.isNullOrBlank()) put("notes", notes)
            }
        )
    }

    suspend fun setTrailerActive(id: Int, active: Boolean) {
        client.postgrest["trailer_list"]
            .update({ set("is_active", active) }) { filter { eq("id", id) } }
    }

    suspend fun deleteTrailer(id: Int) {
        client.postgrest["trailer_list"]
            .delete { filter { eq("id", id) } }
    }

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

    // ===== AUTH =====
    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }

    fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    suspend fun getCurrentProfile(): UserProfile? {
        val uid = currentUserId() ?: return null
        return try {
            client.postgrest["profiles"]
                .select { filter { eq("id", uid) } }
                .decodeSingleOrNull()
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "getCurrentProfile failed: ${e.message}")
            null
        }
    }

    // ===== CHAT =====
    suspend fun getChatRooms(): List<ChatRoom> =
        client.postgrest["chat_rooms"]
            .select {
                order("sort_order", Order.ASCENDING)
                order("id", Order.ASCENDING)
            }
            .decodeList()

    suspend fun getChatMessages(roomId: Int, limit: Int = 100): List<ChatMessage> =
        client.postgrest["messages"]
            .select(Columns.raw("*, profiles(username, display_name, avatar_color, avatar_url, role)")) {
                filter { eq("room_id", roomId) }
                order("created_at", Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList()

    suspend fun sendChatMessage(roomId: Int, content: String) {
        val uid = currentUserId() ?: return
        client.postgrest["messages"].insert(
            buildJsonObject {
                put("room_id", roomId)
                put("sender_id", uid)
                put("content", content)
            }
        )
    }

    suspend fun getRolePermissions(role: String): Map<String, Boolean> {
        return try {
            val result = client.postgrest["role_permissions"]
                .select { filter { eq("role_name", role) } }
                .decodeList<kotlinx.serialization.json.JsonObject>()
            val row = result.firstOrNull() ?: return emptyMap()
            // pages and features are JSON arrays — parse them
            val pages = (row["pages"] as? kotlinx.serialization.json.JsonArray)
                ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: emptyList()
            pages.associateWith { true }
        } catch (e: Exception) {
            RemoteLogger.e("BadgerRepo", "getRolePermissions failed: ${e.message}")
            emptyMap()
        }
    }

    // ===== REALTIME =====
    fun realtimeChannel(name: String) = client.channel(name)
}
