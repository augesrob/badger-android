package com.badger.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.badger.wear.service.WearService

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start service if not running
        if (!WearService.isRunning) {
            startForegroundService(Intent(this, WearService::class.java))
        }

        setContent { BadgerWatchApp() }
    }
}

@Composable
fun BadgerWatchApp() {
    val trucks    by WearService.trucks.collectAsState()
    val doors     by WearService.doors.collectAsState()
    val statuses  by WearService.statuses.collectAsState()
    val pttActive by WearService.pttActive.collectAsState()

    var selectedTruck by remember { mutableStateOf<WearTruck?>(null) }
    var selectedDoor  by remember { mutableStateOf<WearDoor?>(null) }
    var showStopConfirm by remember { mutableStateOf(false) }

    val darkBg   = Color(0xFF0F0F0F)
    val amber    = Color(0xFFF59E0B)
    val surface  = Color(0xFF1A1A1A)
    val modeColor = Color(0xFF22C55E)
    val modeLabel = "📡 Live"

    when {
        showStopConfirm -> StopConfirmScreen(
            onConfirm = {
                WearApp.instance.stopService(Intent(WearApp.instance, WearService::class.java))
                showStopConfirm = false
            },
            onCancel = { showStopConfirm = false }
        )
        selectedTruck != null -> StatusPickerScreen(
            title = "Truck ${selectedTruck!!.truckNumber}",
            current = selectedTruck!!.statusName ?: "",
            options = statuses.map { it.statusName },
            colors = statuses.associate { it.statusName to it.statusColor },
            onPick = { statusName ->
                val statusId = statuses.find { it.statusName == statusName }?.id ?: return@StatusPickerScreen
                WearService.trucks  // trigger change via service instance
                selectedTruck = null
            },
            onCancel = { selectedTruck = null }
        )
        selectedDoor != null -> StatusPickerScreen(
            title = "Door ${selectedDoor!!.doorName}",
            current = selectedDoor!!.doorStatus,
            options = listOf("Loading", "Empty", "End Of Truck Tote", "Closed", "Open"),
            colors = emptyMap(),
            onPick = { status ->
                selectedDoor = null
            },
            onCancel = { selectedDoor = null }
        )
        else -> MainScreen(
            trucks = trucks,
            doors = doors,
            mode = modeLabel,
            modeColor = modeColor,
            pttActive = pttActive,
            darkBg = darkBg,
            amber = amber,
            surface = surface,
            onTruckTap = { selectedTruck = it },
            onDoorTap = { selectedDoor = it },
            onPttStart = {
                WearApp.instance.startService(
                    Intent(WearApp.instance, WearService::class.java).apply { action = WearService.ACTION_PTT_START })
            },
            onPttStop = {
                WearApp.instance.startService(
                    Intent(WearApp.instance, WearService::class.java).apply { action = WearService.ACTION_PTT_STOP })
            },
            onStop = { showStopConfirm = true }
        )
    }
}

@Composable
fun MainScreen(
    trucks: List<WearTruck>,
    doors: List<WearDoor>,
    mode: String,
    modeColor: Color,
    pttActive: Boolean,
    darkBg: Color,
    amber: Color,
    surface: Color,
    onTruckTap: (WearTruck) -> Unit,
    onDoorTap: (WearDoor) -> Unit,
    onPttStart: () -> Unit,
    onPttStop: () -> Unit,
    onStop: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(darkBg),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🫌 Badger", color = amber, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text(mode, color = modeColor, fontSize = 10.sp)
            }
        }

        // Doors section
        if (doors.isNotEmpty()) {
            item {
                Text("DOORS", color = Color(0xFF888888), fontSize = 9.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            }
            items(doors) { door ->
                DoorRow(door = door, surface = surface, onTap = { onDoorTap(door) })
            }
        }

        // Trucks section
        if (trucks.isNotEmpty()) {
            item {
                Text("TRUCKS", color = Color(0xFF888888), fontSize = 9.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            items(trucks) { truck ->
                TruckRow(truck = truck, surface = surface, onTap = { onTruckTap(truck) })
            }
        }

        // PTT Button
        item {
            Spacer(Modifier.height(8.dp))
            PttButton(active = pttActive, onStart = onPttStart, onStop = onPttStop)
        }

        // Stop button
        item {
            Chip(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF3F0000)),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Badger", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
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
        Text(truck.statusName ?: "—", color = statusColor, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DoorRow(door: WearDoor, surface: Color, onTap: () -> Unit) {
    val statusColor = when {
        door.doorStatus.contains("Loading", ignoreCase = true) -> Color(0xFFF59E0B)
        door.doorStatus.contains("Empty", ignoreCase = true)   -> Color(0xFF6B7280)
        door.doorStatus.contains("Closed", ignoreCase = true)  -> Color(0xFF6B7280)
        door.doorStatus.isBlank()                              -> Color(0xFF6B7280)
        else                                                   -> Color(0xFF22C55E)
    }

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(surface).clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(door.doorName, color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(door.doorStatus.ifBlank { "—" }, color = statusColor, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PttButton(active: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val bg = if (active) Color(0xFF7F1D1D) else Color(0xFF1C3A1C)
    val ic = if (active) Icons.Default.MicOff else Icons.Default.Mic
    val label = if (active) "Release PTT" else "Push to Talk"

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(bg)
                .clickable { if (active) onStop() else onStart() },
            contentAlignment = Alignment.Center
        ) {
            Icon(ic, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFF888888), fontSize = 10.sp)
    }
}

@Composable
fun StatusPickerScreen(
    title: String,
    current: String,
    options: List<String>,
    colors: Map<String, String>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit
) {
    val darkBg  = Color(0xFF0F0F0F)
    val amber   = Color(0xFFF59E0B)
    val surface = Color(0xFF1A1A1A)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().background(darkBg),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            Text(title, color = amber, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        items(options) { option ->
            val isSelected = option == current
            val optColor = try { Color(android.graphics.Color.parseColor(colors[option] ?: "#888888")) }
            catch (_: Exception) { Color(0xFF888888) }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color(0xFF292524) else surface)
                    .clickable { onPick(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(option, color = if (isSelected) optColor else Color.White,
                    fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isSelected) Text("✓", color = optColor, fontSize = 12.sp)
            }
        }
        item {
            Chip(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1A1A1A)),
                label = { Text("Cancel", color = Color(0xFF888888), fontSize = 11.sp) }
            )
        }
    }
}

@Composable
fun StopConfirmScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val darkBg = Color(0xFF0F0F0F)
    Column(
        modifier = Modifier.fillMaxSize().background(darkBg).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Stop Badger?", color = Color.Red, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text("Stops all monitoring\non this watch", color = Color(0xFF888888),
            fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip(
                onClick = onCancel,
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1A1A1A)),
                label = { Text("No", color = Color.White, fontSize = 12.sp) }
            )
            Chip(
                onClick = onConfirm,
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF7F1D1D)),
                label = { Text("Yes, Stop", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            )
        }
    }
}
