package com.badger.trucks.ui.movement

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.badger.trucks.data.*
import com.badger.trucks.service.BadgerService
import com.badger.trucks.service.NotificationPrefsStore
import com.badger.trucks.ui.theme.*
import com.badger.trucks.voice.*
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    // trucks and doors come directly from the service's live cache —
    // updates are instant (optimistic on voice command, then confirmed by Realtime)
    val trucks by BadgerService.liveTrucks.collectAsState()
    val doors  by BadgerService.liveDoors.collectAsState()
    var printroom by remember { mutableStateOf<List<PrintroomEntry>>(emptyList()) }
    var staging  by remember { mutableStateOf<List<StagingDoor>>(emptyList()) }
    var statuses by remember { mutableStateOf<List<StatusValue>>(emptyList()) }
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var search   by remember { mutableStateOf("") }
    var filter   by remember { mutableStateOf("all") }
    // loading is false once the service has data (non-empty trucks from StateFlow)
    val loading = trucks.isEmpty() && printroom.isEmpty()
    var ttsOn    by remember { mutableStateOf(BadgerService.ttsEnabled) }

    // Button visibility from settings
    val prefs = remember { NotificationPrefsStore.getAll(context) }
    val showPtt    = prefs[NotificationPrefsStore.KEY_SHOW_PTT]    != false
    val showMic    = prefs[NotificationPrefsStore.KEY_SHOW_MIC]    != false
    val showFixAll = prefs[NotificationPrefsStore.KEY_SHOW_FIXALL] != false

    var statusDialogTruck    by remember { mutableStateOf<LiveMovement?>(null) }
    var doorStatusDialogDoor by remember { mutableStateOf<LoadingDoor?>(null) }

    // Voice state — observe from service so hotword and mic button share same UI
    val hotwordActive   by BadgerService.hotwordActive.collectAsState()
    val voiceProcessing by BadgerService.voiceProcessing.collectAsState()
    val voiceFeedback   by BadgerService.voiceFeedback.collectAsState()
    // Local speech recognizer only for the manual mic FAB
    val speechRecognizer = remember { BadgerSpeechRecognizer(context) }

    // ── PTT state comes from the service — always alive even when screen is off ──
    val pttRecording by BadgerService.pttRecording.collectAsState()
    val pttIncoming  by BadgerService.pttIncoming.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        Toast.makeText(context, if (granted) "✅ Mic permission granted — press 📻 to talk" else "❌ Mic permission denied", Toast.LENGTH_LONG).show()
    }

    fun loadData() {
        // trucks and doors are owned by BadgerService.liveTrucks/liveDoors StateFlows —
        // no need to fetch them here; they update automatically and instantly
        scope.launch {
            try {
                printroom = BadgerRepo.getPrintroomEntries()
                staging   = BadgerRepo.getStagingDoors()
                statuses  = BadgerRepo.getStatuses()
                tractors  = BadgerRepo.getTractors()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    fun startVoiceCommand() {
        // If hotword or processing already active, ignore tap
        if (hotwordActive || voiceProcessing) return
        // Trigger the same hotword-detected flow in the service so UI state is unified
        context.startService(Intent(context, BadgerService::class.java).apply {
            action = BadgerService.ACTION_MANUAL_VOICE
        })
    }

    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("movement-android")
            launch { channel.postgresChangeFlow<PostgresAction>("public") { table = "live_movement" }.collect { loadData() } }
            launch { channel.postgresChangeFlow<PostgresAction>("public") { table = "loading_doors"  }.collect { loadData() } }
            channel.subscribe()
            Log.d("MovementScreen", "Realtime subscribed")
        } catch (e: Exception) {
            Log.e("MovementScreen", "Realtime error: ${e.message}")
        }
        launch {
            while (isActive) { delay(30_000L); loadData() }
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    val truckToDoor = mutableMapOf<String, DoorInfo>()
    val sortedPrintroom = printroom.sortedWith(compareBy({ it.loadingDoorId }, { it.batchNumber }, { it.rowOrder }))
    sortedPrintroom.forEachIndexed { orderIdx, pe ->
        if (pe.truckNumber != null && pe.truckNumber != "end" && !pe.isEndMarker) {
            truckToDoor[pe.truckNumber] = DoorInfo(
                doorName = pe.loadingDoor?.doorName ?: "?",
                route = pe.routeInfo ?: "", batch = pe.batchNumber, order = orderIdx,
                pods = pe.pods ?: 0, pallets = pe.palletsTrays ?: 0, notes = pe.notes ?: ""
            )
        }
    }

    val preshiftLookup = mutableMapOf<String, String>()
    staging.forEach { d -> d.inFront?.let { preshiftLookup[it] = d.doorLabel }; d.inBack?.let { preshiftLookup[it] = d.doorLabel } }
    val behindLookup = mutableMapOf<String, String>()
    staging.forEach { d -> if (d.inBack != null && d.inFront != null) behindLookup[d.inBack] = d.inFront }

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
    doorGroups.replaceAll { _, list ->
        list.sortedWith(compareBy({ truckToDoor[it.truckNumber]?.batch }, { truckToDoor[it.truckNumber]?.order })).toMutableList()
    }
    val sortedDoors = doors.map { it.doorName }.filter { doorGroups.containsKey(it) }

    statusDialogTruck?.let { truck ->
        TruckStatusDialog(truck = truck, statuses = statuses, onDismiss = { statusDialogTruck = null }, onSelect = { status ->
            scope.launch { try { BadgerRepo.updateMovementStatus(truck.truckNumber, status.id); loadData() } catch (e: Exception) { e.printStackTrace() } }
            statusDialogTruck = null
        })
    }
    doorStatusDialogDoor?.let { door ->
        DoorStatusDialog(door = door, onDismiss = { doorStatusDialogDoor = null }, onSelect = { newStatus ->
            scope.launch { try { BadgerRepo.updateDoorStatus(door.id, newStatus); loadData() } catch (e: Exception) { e.printStackTrace() } }
            doorStatusDialogDoor = null
        })
    }

    val voiceBannerText = when {
        hotwordActive   -> "🎙 Listening for command..."
        voiceProcessing -> "⚙️ Processing..."
        voiceFeedback != null -> voiceFeedback ?: ""
        else            -> ""
    }
    val voiceBannerVisible = hotwordActive || voiceProcessing || voiceFeedback != null

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DarkBg).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("🚚 Live Movement", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LightText)
                        Text("${filtered.size} trucks • Tap status to change", color = MutedText, fontSize = 13.sp)
                    }
                    val ttsColor = if (ttsOn) Amber500 else MutedText
                    OutlinedButton(
                        onClick = {
                            context.startService(Intent(context, BadgerService::class.java).apply { action = BadgerService.ACTION_TOGGLE_TTS })
                            ttsOn = !ttsOn
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ttsColor),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ttsColor),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(if (ttsOn) "🔊" else "🔇", fontSize = 14.sp) }
                }

                // PTT incoming banner
                AnimatedVisibility(visible = pttIncoming) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1A3A2A)
                    ) {
                        Text("📻 Someone is talking...", modifier = Modifier.padding(12.dp),
                            color = Color(0xFF4ADE80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Voice feedback banner
                AnimatedVisibility(visible = voiceBannerVisible) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            hotwordActive   -> Color(0xFF1E3A5F)
                            voiceProcessing -> Color(0xFF2D2A1A)
                            voiceFeedback?.startsWith("✅") == true -> Color(0xFF1A2D1A)
                            voiceFeedback != null -> Color(0xFF2D1A1A)
                            else -> DarkCard
                        }
                    ) {
                        Text(voiceBannerText, modifier = Modifier.padding(12.dp), color = LightText, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(selected = filter == "all", onClick = { filter = "all" },
                            label = { Text("All", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber500, selectedLabelColor = Color.Black))
                    }
                    items(statuses) { s ->
                        FilterChip(selected = filter == s.statusName, onClick = { filter = if (filter == s.statusName) "all" else s.statusName },
                            label = { Text(s.statusName, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { Amber500 },
                                selectedLabelColor = Color.White))
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("🔍 Search truck #, door, or location...", color = MutedText) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amber500, unfocusedBorderColor = DarkBorder, cursorColor = Amber500),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            items(sortedDoors) { doorName ->
                val group   = doorGroups[doorName] ?: emptyList()
                val doorObj = doors.find { it.doorName == doorName }
                DoorSection(
                    door = doorObj, doorName = doorName, trucks = group, truckToDoor = truckToDoor,
                    preshiftLookup = preshiftLookup, behindLookup = behindLookup, tractors = tractors,
                    onTruckTap = { statusDialogTruck = it },
                    onDoorHeaderTap = { doorObj?.let { doorStatusDialogDoor = it } }
                )
            }
        }

        // ── FABs ──────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Push-to-talk
            if (showPtt) {
            val pttColor = if (pttRecording) Color(0xFF4ADE80) else Color(0xFF374151)
            val pttScale by rememberInfiniteTransition(label = "ptt-pulse").animateFloat(
                initialValue = 1f, targetValue = if (pttRecording) 1.12f else 1f,
                animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
                label = "ptt-scale"
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .scale(pttScale)
                    .clip(CircleShape)
                    .background(pttColor)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (!hasMicPermission) {
                                    Toast.makeText(context, "Requesting mic permission...", Toast.LENGTH_SHORT).show()
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    Toast.makeText(context, "🔴 Recording...", Toast.LENGTH_SHORT).show()
                                    // Tell the service to start/stop recording
                                    context.startService(Intent(context, BadgerService::class.java).apply { action = BadgerService.ACTION_PTT_START })
                                    tryAwaitRelease()
                                    context.startService(Intent(context, BadgerService::class.java).apply { action = BadgerService.ACTION_PTT_STOP })
                                    Toast.makeText(context, "📤 Sending...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
            ) {
                Text(if (pttRecording) "🔴" else "📻", fontSize = 20.sp)
            }
            } // end showPtt

            // Voice command mic
            if (showMic) {
            val micScale by rememberInfiniteTransition(label = "mic-pulse").animateFloat(
                initialValue = 1f, targetValue = 1.15f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "pulse"
            )
            val micAnimScale = if (hotwordActive) micScale else 1f
            val micColor = when {
                hotwordActive   -> Color(0xFFEF4444)
                voiceProcessing -> Color(0xFFF59E0B)
                else -> Amber500
            }
            FloatingActionButton(
                onClick = { startVoiceCommand() },
                modifier = Modifier.scale(micAnimScale),
                containerColor = micColor, shape = CircleShape
            ) {
                Icon(if (hotwordActive) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Voice command", tint = Color.Black)
            }
            } // end showMic

            // Fix All
            if (showFixAll) {
            FloatingActionButton(
                onClick = {
                    Toast.makeText(context, "🔧 Restarting service...", Toast.LENGTH_SHORT).show()
                    // Restart the entire BadgerService — PTT, WakeLock, channels all reset cleanly
                    context.stopService(Intent(context, BadgerService::class.java))
                    scope.launch {
                        delay(600)
                        ContextCompat.startForegroundService(context, Intent(context, BadgerService::class.java))
                        delay(1000)
                        loadData()
                        Toast.makeText(context, "✅ Service restarted", Toast.LENGTH_SHORT).show()
                    }
                },
                containerColor = Color(0xFF374151), shape = CircleShape
            ) {
                Icon(Icons.Default.Build, contentDescription = "Fix All", tint = Color.White)
            }
            } // end showFixAll
        }
    }
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
fun TruckStatusDialog(truck: LiveMovement, statuses: List<StatusValue>, onDismiss: () -> Unit, onSelect: (StatusValue) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp).heightIn(max = 520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Truck ${truck.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                }
                Text("Select new status:", color = MutedText, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    statuses.forEach { s ->
                        val isSelected = truck.statusName == s.statusName
                        val color = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { MutedText }
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) color.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                            Text(s.statusName, color = if (isSelected) color else LightText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                            if (isSelected) { Spacer(Modifier.weight(1f)); Text("✓", color = color, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoorStatusDialog(door: LoadingDoor, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
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
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) color.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(status) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
                        Text(status, color = if (isSelected) color else LightText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                        if (isSelected) { Spacer(Modifier.weight(1f)); Text("✓", color = color, fontWeight = FontWeight.ExtraBold) }
                    }
                }
                Spacer(Modifier.height(4.dp)); Divider(color = DarkBorder); Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect("") }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("Clear status", color = MutedText, fontSize = 14.sp)
                }
            }
        }
    }
}

// ─── Door Section ──────────────────────────────────────────────────────────────

@Composable
fun DoorSection(door: LoadingDoor?, doorName: String, trucks: List<LiveMovement>, truckToDoor: Map<String, DoorInfo>,
                preshiftLookup: Map<String, String>, behindLookup: Map<String, String>, tractors: List<Tractor>,
                onTruckTap: (LiveMovement) -> Unit, onDoorHeaderTap: () -> Unit) {
    val doorStatusStr = door?.doorStatus ?: ""
    val statusColor = Color(doorStatusColor(doorStatusStr))
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().background(DarkCard).clickable { onDoorHeaderTap() }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Door $doorName", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = LightText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doorStatusStr.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(6.dp), color = statusColor) {
                        Text(doorStatusStr, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else { Text("Tap to set status", color = MutedText, fontSize = 10.sp) }
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MutedText, modifier = Modifier.size(14.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("TRUCK#", Modifier.width(60.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("RT", Modifier.width(30.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("LOC", Modifier.width(50.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("STATUS", Modifier.weight(1f), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("PODS", Modifier.width(35.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PAL", Modifier.width(35.dp), color = Amber500.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        trucks.forEachIndexed { idx, t ->
            val di  = truckToDoor[t.truckNumber]
            val loc = t.currentLocation ?: preshiftLookup[t.truckNumber] ?: ""
            val behind = behindLookup[t.truckNumber]
            val trailerNum = resolveTrailer(t.truckNumber, tractors)
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
            Row(modifier = Modifier.fillMaxWidth().clickable { onTruckTap(t) }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(60.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(20.dp).background(
                            try { Color(android.graphics.Color.parseColor(t.statusColor ?: "#6b7280")) } catch (_: Exception) { MutedText }
                        ))
                        Spacer(Modifier.width(4.dp))
                        Text(t.truckNumber, fontWeight = FontWeight.ExtraBold, color = Amber500, fontSize = 13.sp)
                    }
                    if (trailerNum != null) Text("TR:$trailerNum", color = Purple400, fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
                }
                Text(di?.route ?: "", Modifier.width(30.dp), color = MutedText, fontSize = 11.sp)
                Column(Modifier.width(50.dp)) {
                    Text(loc, color = LightText, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    if (behind != null) Text("↑$behind", color = MutedText, fontSize = 8.sp)
                }
                Box(Modifier.weight(1f)) {
                    Surface(shape = RoundedCornerShape(4.dp),
                        color = try { Color(android.graphics.Color.parseColor(t.statusColor ?: "#6b7280")) } catch (_: Exception) { DarkCard }) {
                        Text(t.statusName ?: "—", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(if ((di?.pods ?: 0) > 0) di?.pods.toString() else "", Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if ((di?.pallets ?: 0) > 0) di?.pallets.toString() else "", Modifier.width(35.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
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
    return when (slot) { 1 -> tractor.trailer1?.trailerNumber; 2 -> tractor.trailer2?.trailerNumber; 3 -> tractor.trailer3?.trailerNumber; 4 -> tractor.trailer4?.trailerNumber; else -> null }
}
