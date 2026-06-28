package com.badger.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.badger.wear.service.WearService
import com.badger.wear.util.WearLogger
import kotlinx.coroutines.delay

class WearMainActivity : ComponentActivity() {

    private val requestAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        WearLogger.i("WearMainActivity", "RECORD_AUDIO granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (!WearService.isRunning) {
            startForegroundService(Intent(this, WearService::class.java))
        }
        setContent { BadgerWatchApp() }
    }
}

data class NotificationEvent(
    val title: String,
    val message: String,
    val color: Color = Color(0xFFF59E0B),
    val icon: String = "🔔"
)

@Composable
fun BadgerWatchApp() {
    val trucks       by WearService.trucks.collectAsState()
    val doors        by WearService.doors.collectAsState()
    val statuses     by WearService.statuses.collectAsState()
    val doorStatuses by WearService.doorStatuses.collectAsState()
    val printroom    by WearService.printroom.collectAsState()
    val pttActive    by WearService.pttActive.collectAsState()

    var selectedTruck    by remember { mutableStateOf<WearTruck?>(null) }
    var selectedDoor     by remember { mutableStateOf<WearDoor?>(null) }
    var showStopConfirm  by remember { mutableStateOf(false) }
    var notification    by remember { mutableStateOf<NotificationEvent?>(null) }
    
    // Track previous state to detect changes
    var previousTrucks by remember { mutableStateOf<List<WearTruck>>(emptyList()) }
    var previousDoors by remember { mutableStateOf<List<WearDoor>>(emptyList()) }

    // Detect truck status changes
    LaunchedEffect(trucks) {
        trucks.forEach { truck ->
            val prevTruck = previousTrucks.find { it.truckNumber == truck.truckNumber }
            if (prevTruck != null && prevTruck.statusId != truck.statusId) {
                // Status changed!
                val statusColor = try { 
                    Color(android.graphics.Color.parseColor(truck.statusColor ?: "#F59E0B")) 
                } catch (_: Exception) { 
                    Color(0xFFF59E0B) 
                }
                notification = NotificationEvent(
                    title = "Truck ${truck.truckNumber}",
                    message = truck.statusName ?: "Updated",
                    color = statusColor,
                    icon = "🚛"
                )
                WearLogger.i("WearMainActivity", "✅ Truck ${truck.truckNumber} status changed to ${truck.statusName}")
            }
        }
        previousTrucks = trucks
    }

    // Detect door status changes
    LaunchedEffect(doors) {
        doors.forEach { door ->
            val prevDoor = previousDoors.find { it.id == door.id }
            if (prevDoor != null && prevDoor.doorStatus != door.doorStatus) {
                // Door status changed!
                notification = NotificationEvent(
                    title = "Door ${door.doorName}",
                    message = door.doorStatus.ifBlank { "Updated" },
                    color = Color(0xFFF59E0B),
                    icon = "🚪"
                )
                WearLogger.i("WearMainActivity", "✅ Door ${door.doorName} status changed to ${door.doorStatus}")
            }
        }
        previousDoors = doors
    }

    // Group trucks under their door via printroom_entries (same as website)
    val doorGroups = remember(doors, trucks, printroom) {
        // Build truck → door mapping from printroom
        val truckToDoor = printroom
            .filter { it.truckNumber != null && it.loadingDoorId != null }
            .associate { it.truckNumber!! to it.loadingDoorId!! }
        doors.sortedBy { it.sortOrder ?: 99 }.map { door ->
            val doorTrucks = trucks
                .filter { truckToDoor[it.truckNumber] == door.id }
                .sortedBy { printroom.find { pr -> pr.truckNumber == it.truckNumber }?.rowOrder ?: 999 }
            WearDoorGroup(door, doorTrucks)
        }
    }
    // Unassigned: trucks not in printroom at all
    val unassigned = remember(trucks, printroom) {
        val assignedTrucks = printroom.mapNotNull { it.truckNumber }.toSet()
        trucks.filter { it.truckNumber !in assignedTrucks }.sortedBy { it.truckNumber }
    }

    val ctx = WearApp.instance

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showStopConfirm -> StopConfirmScreen(
                onConfirm = { ctx.stopService(Intent(ctx, WearService::class.java)); showStopConfirm = false },
                onCancel  = { showStopConfirm = false }
            )
            selectedTruck != null -> StatusPickerScreen(
                title   = "Truck ${selectedTruck!!.truckNumber}",
                current = selectedTruck!!.statusName ?: "",
                options = statuses.map { it.statusName },
                colors  = statuses.associate { it.statusName to it.statusColor },
                onPick  = { statusName ->
                    statuses.find { it.statusName == statusName }?.id?.let { statusId ->
                        ctx.startService(Intent(ctx, WearService::class.java).apply {
                            action = WearService.ACTION_STATUS_CHANGE
                            putExtra("truckNumber", selectedTruck!!.truckNumber)
                            putExtra("statusId", statusId)
                        })
                    }
                    selectedTruck = null
                },
                onCancel = { selectedTruck = null }
            )
            selectedDoor != null -> StatusPickerScreen(
                title    = "Door ${selectedDoor!!.doorName}",
                current  = selectedDoor!!.doorStatus,
                options  = doorStatuses.ifEmpty { listOf("Loading","End Of Truck Tote","EOT+1","Change Truck/Trailer","Waiting","Done for Night","100%","Move to Receiving","Priority Change Truck/Trailer","waiting on dead truck","Smile, almost finished 😁") },
                colors   = emptyMap(),
                onPick   = { status ->
                    ctx.startService(Intent(ctx, WearService::class.java).apply {
                        action = WearService.ACTION_DOOR_CHANGE
                        putExtra("doorId", selectedDoor!!.id)
                        putExtra("status", status)
                    })
                    selectedDoor = null
                },
                onCancel = { selectedDoor = null }
            )
            else -> MainScreen(
                doorGroups   = doorGroups,
                unassigned   = unassigned,
                pttActive    = pttActive,
                onTruckTap   = { selectedTruck = it },
                onDoorTap    = { selectedDoor = it },
                onPttStart   = { ctx.startService(Intent(ctx, WearService::class.java).apply { action = WearService.ACTION_PTT_START }) },
                onPttStop    = { ctx.startService(Intent(ctx, WearService::class.java).apply { action = WearService.ACTION_PTT_STOP }) },
                onStop       = { showStopConfirm = true }
            )
        }

        // Notification overlay - shows for 3 seconds then auto-dismisses
        if (notification != null) {
            LaunchedEffect(notification) {
                delay(3000)  // Show for 3 seconds
                notification = null
            }
            NotificationOverlay(notification = notification!!)
        }
    }
}

@Composable
fun NotificationOverlay(notification: NotificationEvent) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1A1A).copy(alpha = 0.95f))
                .border(2.dp, notification.color, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Badger Logo at top
                Text(
                    text = "🦡",
                    fontSize = 48.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Notification Icon
                Text(
                    text = notification.icon,
                    fontSize = 32.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Title
                Text(
                    text = notification.title,
                    color = notification.color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Message
                Text(
                    text = notification.message,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    doorGroups: List<WearDoorGroup>, unassigned: List<WearTruck>,
    pttActive: Boolean,
    onTruckTap: (WearTruck) -> Unit, onDoorTap: (WearDoor) -> Unit,
    onPttStart: () -> Unit, onPttStop: () -> Unit, onStop: () -> Unit
) {
    val darkBg  = Color(0xFF0F0F0F)
    val amber   = Color(0xFFF59E0B)
    val surface = Color(0xFF1A1A1A)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(darkBg),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Header
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("🦡 Badger", color = amber, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text("📡 Live", color = Color(0xFF22C55E), fontSize = 10.sp)
            }
        }

        // Door groups
        doorGroups.forEach { group ->
            item {
                DoorGroupCard(
                    group    = group,
                    surface  = surface,
                    onDoorTap  = { onDoorTap(group.door) },
                    onTruckTap = onTruckTap
                )
            }
        }

        // Unassigned trucks section
        if (unassigned.isNotEmpty()) {
            item {
                Text("UNASSIGNED", color = Color(0xFF666666), fontSize = 9.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            }
            unassigned.forEach { truck ->
                item { TruckRow(truck = truck, surface = surface, onTap = { onTruckTap(truck) }) }
            }
        }

        // PTT + Stop
        item { Spacer(Modifier.height(6.dp)) }
        item { PttButton(active = pttActive, onStart = onPttStart, onStop = onPttStop) }
        item {
            Chip(onClick = onStop, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF3F0000)),
                label = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Stop, null, tint = Color.Red, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Badger", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                })
        }
    }
}

@Composable
fun DoorGroupCard(
    group: WearDoorGroup, surface: Color,
    onDoorTap: () -> Unit, onTruckTap: (WearTruck) -> Unit
) {
    val door = group.door
    val doorStatusColor = when {
        door.doorStatus.contains("Loading", ignoreCase = true) -> Color(0xFFFB923C)
        door.doorStatus.contains("Done", ignoreCase = true)    -> Color(0xFF6B7280)
        door.doorStatus.isBlank()                              -> Color(0xFF6B7280)
        else                                                   -> Color(0xFF22C55E)
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
    ) {
        // Door header row — tap to change door status
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClick = onDoorTap)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = door.doorName,
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = door.doorStatus.ifBlank { "—" },
                color = doorStatusColor,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
            )
        }

        // Trucks assigned to this door
        if (group.trucks.isEmpty()) {
            Text(
                text = "No trucks",
                color = Color(0xFF444444),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
            )
        } else {
            group.trucks.forEach { truck ->
                val statusColor = try { Color(android.graphics.Color.parseColor(truck.statusColor ?: "#888888")) }
                catch (_: Exception) { Color(0xFF888888) }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onTruckTap(truck) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("↳ ${truck.truckNumber}", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    Text(
                        text = truck.statusName ?: "—",
                        color = statusColor,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun TruckRow(truck: WearTruck, surface: Color, onTap: () -> Unit) {
    val statusColor = try { Color(android.graphics.Color.parseColor(truck.statusColor ?: "#888888")) }
    catch (_: Exception) { Color(0xFF888888) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(surface).clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(truck.truckNumber, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(truck.statusName ?: "—", color = statusColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PttButton(active: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape)
                .background(if (active) Color(0xFF7F1D1D) else Color(0xFF1C3A1C))
                .clickable { if (active) onStop() else onStart() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (active) Icons.Default.MicOff else Icons.Default.Mic,
                null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(if (active) "Release PTT" else "Push to Talk", color = Color(0xFF888888), fontSize = 10.sp)
    }
}

@Composable
fun StatusPickerScreen(title: String, current: String, options: List<String>,
    colors: Map<String, String>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item { Text(title, color = Color(0xFFF59E0B), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 4.dp)) }
        items(options) { option ->
            val isSelected = option == current
            val optColor = try { Color(android.graphics.Color.parseColor(colors[option] ?: "#888888")) }
            catch (_: Exception) { Color(0xFF888888) }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color(0xFF292524) else Color(0xFF1A1A1A))
                    .clickable { onPick(option) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(option, color = if (isSelected) optColor else Color.White, fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isSelected) Text("✓", color = optColor, fontSize = 12.sp)
            }
        }
        item {
            Chip(onClick = onCancel, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1A1A1A)),
                label = { Text("Cancel", color = Color(0xFF888888), fontSize = 11.sp) })
        }
    }
}

@Composable
fun StopConfirmScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("Stop Badger?", color = Color.Red, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("Stops all monitoring\non this watch", color = Color(0xFF888888), fontSize = 11.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(onClick = onCancel, colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1A1A1A)),
                label = { Text("No", color = Color.White, fontSize = 12.sp) })
            Chip(onClick = onConfirm, colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF7F1D1D)),
                label = { Text("Yes, Stop", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold) })
        }
    }
}
