package com.badger.trucks.ui.printroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.launch

@Composable
fun PrintRoomScreen() {
    val scope = rememberCoroutineScope()
    var doors by remember { mutableStateOf<List<LoadingDoor>>(emptyList()) }
    var entries by remember { mutableStateOf<List<PrintroomEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun loadData() {
        scope.launch {
            try {
                doors = BadgerRepo.getLoadingDoors()
                entries = BadgerRepo.getPrintroomEntries()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            loading = false
        }
    }

    // Initial load + realtime
    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("printroom-android")
            channel.postgresChangeFlow<PostgresAction>("public") {
                table = "printroom_entries"
            }.collect { loadData() }
            channel.subscribe()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                "🖨️ Print Room",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = LightText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Truck assignments by loading door",
                color = MutedText,
                fontSize = 13.sp
            )
        }

        items(doors) { door ->
            DoorCard(door, entries.filter { it.loadingDoorId == door.id }) { loadData() }
        }
    }
}

@Composable
fun DoorCard(
    door: LoadingDoor,
    doorEntries: List<PrintroomEntry>,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val statusColor = Color(doorStatusColor(door.doorStatus))

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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                door.doorName,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = Amber500
            )
            if (door.doorStatus.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor
                ) {
                    Text(
                        door.doorStatus,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Column headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("TRUCK#", Modifier.weight(1f), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("RT", Modifier.width(40.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PODS", Modifier.width(40.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PAL", Modifier.width(40.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("NOTES", Modifier.weight(1f), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }

        // Entries
        var currentBatch = 0
        doorEntries.forEach { entry ->
            if (entry.batchNumber != currentBatch && currentBatch > 0) {
                // Batch divider
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Divider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                    Text(
                        " Next Wave ",
                        color = Amber500.copy(alpha = 0.4f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Divider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                }
            }
            currentBatch = entry.batchNumber

            EntryRow(entry)
        }

        if (doorEntries.isEmpty()) {
            Text(
                "No trucks",
                modifier = Modifier.padding(16.dp),
                color = MutedText,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun EntryRow(entry: PrintroomEntry) {
    val truckDisplay = when {
        entry.isEndMarker -> "END"
        entry.truckNumber == "999" -> "CPU"
        entry.truckNumber != null -> entry.truckNumber
        else -> "—"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            truckDisplay,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.ExtraBold,
            color = if (entry.isEndMarker) Red500 else Amber500,
            fontSize = 14.sp
        )
        Text(
            entry.routeInfo ?: "",
            modifier = Modifier.width(40.dp),
            color = MutedText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            if (entry.pods > 0) entry.pods.toString() else "",
            modifier = Modifier.width(40.dp),
            color = LightText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            if (entry.palletsTrays > 0) entry.palletsTrays.toString() else "",
            modifier = Modifier.width(40.dp),
            color = LightText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            entry.notes ?: "",
            modifier = Modifier.weight(1f),
            color = MutedText,
            fontSize = 11.sp
        )
    }
    Divider(color = DarkBorder.copy(alpha = 0.3f))
}
