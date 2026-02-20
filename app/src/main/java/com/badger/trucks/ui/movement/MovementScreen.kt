package com.badger.trucks.ui.movement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
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
            channel.subscribe()
        } catch (e: Exception) { e.printStackTrace() }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    // Build truck→door mapping from printroom
    val truckToDoor = mutableMapOf<String, DoorInfo>()
    var orderIdx = 0
    printroom.forEach { pe ->
        if (pe.truckNumber != null && pe.truckNumber != "end") {
            val doorName = pe.loadingDoor?.doorName ?: "?"
            truckToDoor[pe.truckNumber] = DoorInfo(
                doorName = doorName,
                route = pe.routeInfo ?: "",
                batch = pe.batchNumber,
                order = orderIdx++,
                pods = pe.pods,
                pallets = pe.palletsTrays,
                notes = pe.notes ?: ""
            )
        }
    }

    // PreShift location lookup
    val preshiftLookup = mutableMapOf<String, String>()
    staging.forEach { d ->
        d.inFront?.let { preshiftLookup[it] = d.doorLabel }
        d.inBack?.let { preshiftLookup[it] = d.doorLabel }
    }

    // Behind lookup
    val behindLookup = mutableMapOf<String, String>()
    staging.forEach { d ->
        if (d.inBack != null && d.inFront != null) {
            behindLookup[d.inBack] = d.inFront
        }
    }

    // Filter trucks to only those in printroom
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

    // Group by door
    val doorGroups = mutableMapOf<String, MutableList<LiveMovement>>()
    filtered.forEach { t ->
        val di = truckToDoor[t.truckNumber] ?: return@forEach
        doorGroups.getOrPut(di.doorName) { mutableListOf() }.add(t)
    }

    // Sort doors and create pairs
    val sortedDoors = doors.map { it.doorName }.filter { doorGroups.containsKey(it) }
    val statusCounts = mutableMapOf<String, Int>()
    filtered.forEach { t -> statusCounts[t.statusName ?: "No Status"] = (statusCounts[t.statusName ?: "No Status"] ?: 0) + 1 }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("🚚 Live Movement", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LightText)
            Spacer(Modifier.height(4.dp))
            Text("${filtered.size} trucks active", color = MutedText, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            // Status filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
            Spacer(Modifier.height(8.dp))

            // Search
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
            val doorStatusStr = doorObj?.doorStatus ?: ""

            DoorSection(
                doorName = doorName,
                doorStatus = doorStatusStr,
                trucks = group,
                truckToDoor = truckToDoor,
                preshiftLookup = preshiftLookup,
                behindLookup = behindLookup,
                tractors = tractors
            )
        }
    }
}

@Composable
fun DoorSection(
    doorName: String,
    doorStatus: String,
    trucks: List<LiveMovement>,
    truckToDoor: Map<String, DoorInfo>,
    preshiftLookup: Map<String, String>,
    behindLookup: Map<String, String>,
    tractors: List<Tractor>
) {
    val statusColor = Color(doorStatusColor(doorStatus))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        // Door header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Door $doorName", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = LightText)
            if (doorStatus.isNotBlank()) {
                Surface(shape = RoundedCornerShape(6.dp), color = statusColor) {
                    Text(doorStatus, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Column headers
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
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

            // Resolve trailer
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
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Truck # with status color bar
                Column(Modifier.width(60.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(
                                    Color(
                                        android.graphics.Color.parseColor(t.statusColor ?: "#6b7280")
                                    )
                                )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(t.truckNumber, fontWeight = FontWeight.ExtraBold, color = Amber500, fontSize = 13.sp)
                    }
                    if (trailerNum != null) {
                        Text("TR: $trailerNum", color = Purple400, fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
                    }
                }

                Text(di?.route ?: "", Modifier.width(30.dp), color = MutedText, fontSize = 11.sp)

                Column(Modifier.width(50.dp)) {
                    Text(loc, color = LightText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    if (behind != null) {
                        Text("Behind $behind", color = MutedText, fontSize = 8.sp)
                    }
                }

                // Status badge
                Box(Modifier.weight(1f)) {
                    if (t.statusName != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(android.graphics.Color.parseColor(t.statusColor ?: "#6b7280"))
                        ) {
                            Text(
                                t.statusName,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    if ((di?.pods ?: 0) > 0) di?.pods.toString() else "",
                    Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center
                )
                Text(
                    if ((di?.pallets ?: 0) > 0) di?.pallets.toString() else "",
                    Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center
                )
            }
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
