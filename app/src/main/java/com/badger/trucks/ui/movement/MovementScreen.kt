package com.badger.trucks.ui.movement

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.badger.trucks.service.BadgerService
import kotlinx.coroutines.launch

data class DoorInfo(
    val doorName: String,
    val route: String,
    val batch: Int,
    val order: Int,
    val pods: Int,
    val pallets: Int,
    val notes: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementScreen() {
    val scope = rememberCoroutineScope()
    var trucks by remember { mutableStateOf<List<LiveMovement>>(emptyList()) }
    var printroom by remember { mutableStateOf<List<PrintroomEntry>>(emptyList()) }
    var doors by remember { mutableStateOf<List<LoadingDoor>>(emptyList()) }
    var staging by remember { mutableStateOf<List<StagingDoor>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<StatusValue>>(emptyList()) }
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var ttsOn by remember { mutableStateOf(BadgerService.ttsEnabled) }
    val context = LocalContext.current

    // Dialog state
    var statusDialogTruck by remember { mutableStateOf<LiveMovement?>(null) }
    var doorStatusDialogDoor by remember { mutableStateOf<LoadingDoor?>(null) }

    fun loadData() {
        scope.launch {
            try {
                trucks = BadgerRepo.getLiveMovement()
                printroom = BadgerRepo.getPrintroomEntries()
                doors = BadgerRepo.getLoadingDoors()
                staging = BadgerRepo.getStagingDoors()
                statuses = BadgerRepo.getStatuses()
                tractors = BadgerRepo.getTractors()
            } catch (e: Exception) { e.printStackTrace() }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("movement-android")
            channel.postgresChangeFlow<PostgresAction>("public") {
                table = "live_movement"
            }.collect { loadData() }
            channel.postgresChangeFlow<PostgresAction>("public") {
                table = "loading_doors"
            }.collect { loadData() }
            channel.subscribe()
        } catch (e: Exception) { e.printStackTrace() }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    // Build truck→door mapping from printroom (sorted so order is deterministic)
    val truckToDoor = mutableMapOf<String, DoorInfo>()
    val sortedPrintroom = printroom.sortedWith(compareBy({ it.loadingDoorId }, { it.batchNumber }, { it.rowOrder }))
    sortedPrintroom.forEachIndexed { orderIdx, pe ->
        if (pe.truckNumber != null && pe.truckNumber != "end" && !pe.isEndMarker) {
            val doorName = pe.loadingDoor?.doorName ?: "?"
            truckToDoor[pe.truckNumber] = DoorInfo(
                doorName = doorName,
                route = pe.routeInfo ?: "",
                batch = pe.batchNumber,
                order = orderIdx,
                pods = pe.pods ?: 0,
                pallets = pe.palletsTrays ?: 0,
                notes = pe.notes ?: ""
            )
        }
    }

    val preshiftLookup = mutableMapOf<String, String>()
    staging.forEach { d ->
        d.inFront?.let { preshiftLookup[it] = d.doorLabel }
        d.inBack?.let { preshiftLookup[it] = d.doorLabel }
    }

    val behindLookup = mutableMapOf<String, String>()
    staging.forEach { d ->
        if (d.inBack != null && d.inFront != null) behindLookup[d.inBack] = d.inFront
    }

    var filtered = trucks.filter { truckToDoor.containsKey(it.truckNumber) }
    if (filter != "all") filtered = filtered.filter { (it.statusName ?: "No Status") == filter }
    if (search.isNotBlank()) {
        val q = search.lowercase()
        filtered = filtered.filter { t ->
            t.truckNumber.lowercase().contains(q) ||
            (truckToDoor[t.truckNumber]?.doorName?.lowercase()?.contains(q) == true) ||
            (t.currentLocation ?: preshiftLookup[t.truckNumber] ?: "").lowercase().contains(q)
        }
    }

    val doorGroups = mutableMapOf<String, MutableList<LiveMovement>>()
    filtered.forEach { t ->
        val di = truckToDoor[t.truckNumber] ?: return@forEach
        doorGroups.getOrPut(di.doorName) { mutableListOf() }.add(t)
    }
    // Sort trucks within each door by batch then row order
    doorGroups.replaceAll { _, list -> list.sortedWith(compareBy({ truckToDoor[it.truckNumber]?.batch }, { truckToDoor[it.truckNumber]?.order })).toMutableList() }

    val sortedDoors = doors.map { it.doorName }.filter { doorGroups.containsKey(it) }

    // ─── Status picker dialog ───────────────────────────────────────────────
    statusDialogTruck?.let { truck ->
        TruckStatusDialog(
            truck = truck,
            statuses = statuses,
            onDismiss = { statusDialogTruck = null },
            onSelect = { status ->
                scope.launch {
                    try {
                        BadgerRepo.updateMovementStatus(truck.truckNumber, status.id)
                        loadData()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                statusDialogTruck = null
            }
        )
    }

    // ─── Door status picker dialog ──────────────────────────────────────────
    doorStatusDialogDoor?.let { door ->
        DoorStatusDialog(
            door = door,
            onDismiss = { doorStatusDialogDoor = null },
            onSelect = { newStatus ->
                scope.launch {
                    try {
                        BadgerRepo.updateDoorStatus(door.id, newStatus)
                        loadData()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                doorStatusDialogDoor = null
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("🚚 Live Movement", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LightText)
                    Text("${filtered.size} trucks • Tap status to change", color = MutedText, fontSize = 13.sp)
                }
                // TTS Toggle button
                val ttsColor = if (ttsOn) Amber500 else MutedText
                OutlinedButton(
                    onClick = {
                        context.startService(
                            Intent(context, BadgerService::class.java).apply {
                                action = BadgerService.ACTION_TOGGLE_TTS
                            }
                        )
                        ttsOn = !ttsOn
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ttsColor),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ttsColor)
                ) {
                    Text(if (ttsOn) "🔊 TTS" else "🔇 TTS", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            // Status filter chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = filter == "all",
                        onClick = { filter = "all" },
                        label = { Text("All", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber500,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
                items(statuses) { s ->
                    FilterChip(
                        selected = filter == s.statusName,
                        onClick = { filter = if (filter == s.statusName) "all" else s.statusName },
                        label = { Text(s.statusName, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { Amber500 },
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("🔍 Search truck #, door, or location...", color = MutedText) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber500,
                    unfocusedBorderColor = DarkBorder,
                    cursorColor = Amber500
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        items(sortedDoors) { doorName ->
            val group = doorGroups[doorName] ?: emptyList()
            val doorObj = doors.find { it.doorName == doorName }

            DoorSection(
                door = doorObj,
                doorName = doorName,
                trucks = group,
                truckToDoor = truckToDoor,
                preshiftLookup = preshiftLookup,
                behindLookup = behindLookup,
                tractors = tractors,
                onTruckTap = { statusDialogTruck = it },
                onDoorHeaderTap = { doorObj?.let { doorStatusDialogDoor = it } }
            )
        }
    }
}

// ─── Truck Status Dialog ────────────────────────────────────────────────────
@Composable
fun TruckStatusDialog(
    truck: LiveMovement,
    statuses: List<StatusValue>,
    onDismiss: () -> Unit,
    onSelect: (StatusValue) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Truck ${truck.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                }
                Text("Select new status:", color = MutedText, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                statuses.forEach { s ->
                    val isSelected = truck.statusName == s.statusName
                    val color = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { MutedText }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) color.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(s) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                        Text(s.statusName, color = if (isSelected) color else LightText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                        if (isSelected) {
                            Spacer(Modifier.weight(1f))
                            Text("✓", color = color, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

// ─── Door Status Dialog ─────────────────────────────────────────────────────
@Composable
fun DoorStatusDialog(
    door: LoadingDoor,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Door ${door.doorName}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                }
                Text("Select door status:", color = MutedText, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                DOOR_STATUSES.forEach { status ->
                    val isSelected = door.doorStatus == status
                    val color = Color(doorStatusColor(status))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) color.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(status) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                        Text(status, color = if (isSelected) color else LightText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                        if (isSelected) {
                            Spacer(Modifier.weight(1f))
                            Text("✓", color = color, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                // Clear option
                Spacer(Modifier.height(4.dp))
                Divider(color = DarkBorder)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect("") }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text("Clear status", color = MutedText, fontSize = 14.sp)
                }
            }
        }
    }
}

// ─── Door Section ───────────────────────────────────────────────────────────
@Composable
fun DoorSection(
    door: LoadingDoor?,
    doorName: String,
    trucks: List<LiveMovement>,
    truckToDoor: Map<String, DoorInfo>,
    preshiftLookup: Map<String, String>,
    behindLookup: Map<String, String>,
    tractors: List<Tractor>,
    onTruckTap: (LiveMovement) -> Unit,
    onDoorHeaderTap: () -> Unit
) {
    val doorStatusStr = door?.doorStatus ?: ""
    val statusColor = Color(doorStatusColor(doorStatusStr))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        // Door header — tappable to change door status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard)
                .clickable { onDoorHeaderTap() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Door $doorName", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = LightText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doorStatusStr.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(6.dp), color = statusColor) {
                        Text(doorStatusStr, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Tap to set status", color = MutedText, fontSize = 10.sp)
                }
                Icon(Icons.Default.Edit, contentDescription = "Edit door status", tint = MutedText, modifier = Modifier.size(14.dp))
            }
        }

        // Column headers
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("TRUCK#", Modifier.width(60.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("RT", Modifier.width(30.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("LOC", Modifier.width(50.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("STATUS", Modifier.weight(1f), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("PODS", Modifier.width(35.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PAL", Modifier.width(35.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        trucks.forEachIndexed { idx, t ->
            val di = truckToDoor[t.truckNumber]
            val loc = t.currentLocation ?: preshiftLookup[t.truckNumber] ?: ""
            val behind = behindLookup[t.truckNumber]
            val trailerNum = resolveTrailer(t.truckNumber, tractors)

            // Batch divider
            if (idx > 0) {
                val prevDi = truckToDoor[trucks[idx - 1].truckNumber]
                if (di != null && prevDi != null && di.batch != prevDi.batch) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                        Divider(Modifier.weight(1f).padding(top = 6.dp), color = Amber500.copy(alpha = 0.3f))
                        Text(" Next Wave ", color = Amber500.copy(alpha = 0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Divider(Modifier.weight(1f).padding(top = 6.dp), color = Amber500.copy(alpha = 0.3f))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTruckTap(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Truck # with color bar
                Column(Modifier.width(60.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(
                                    try { Color(android.graphics.Color.parseColor(t.statusColor ?: "#6b7280")) }
                                    catch (_: Exception) { MutedText }
                                )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(t.truckNumber, fontWeight = FontWeight.ExtraBold, color = Amber500, fontSize = 13.sp)
                    }
                    if (trailerNum != null) {
                        Text("TR:$trailerNum", color = Purple400, fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
                    }
                }

                Text(di?.route ?: "", Modifier.width(30.dp), color = MutedText, fontSize = 11.sp)

                Column(Modifier.width(50.dp)) {
                    Text(loc, color = LightText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    if (behind != null) Text("↑$behind", color = MutedText, fontSize = 8.sp)
                }

                // Status badge — tappable hint
                Box(Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = try { Color(android.graphics.Color.parseColor(t.statusColor ?: "#6b7280")) }
                               catch (_: Exception) { DarkCard }
                    ) {
                        Text(
                            t.statusName ?: "—",
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(if ((di?.pods ?: 0) > 0) di?.pods.toString() else "", Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if ((di?.pallets ?: 0) > 0) di?.pallets.toString() else "", Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)            }

            if (idx < trucks.size - 1) Divider(color = DarkBorder.copy(alpha = 0.2f))
        }
    }
}

fun resolveTrailer(truckNum: String, tractors: List<Tractor>): String? {
    val match = Regex("^(\\d+)-(\\d+)$").find(truckNum) ?: return null
    val tractorNum = match.groupValues[1].toIntOrNull() ?: return null
    val slot = match.groupValues[2].toIntOrNull() ?: return null
    if (slot !in 1..4) return null
    val tractor = tractors.find { it.truckNumber == tractorNum } ?: return null
    return when (slot) {
        1 -> tractor.trailer1?.trailerNumber
        2 -> tractor.trailer2?.trailerNumber
        3 -> tractor.trailer3?.trailerNumber
        4 -> tractor.trailer4?.trailerNumber
        else -> null
    }
}
