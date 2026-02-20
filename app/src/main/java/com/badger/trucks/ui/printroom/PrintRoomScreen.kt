package com.badger.trucks.ui.printroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    var staging by remember { mutableStateOf<List<StagingDoor>>(emptyList()) }
    var routes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var addDialogDoor by remember { mutableStateOf<LoadingDoor?>(null) }
    var editDialogEntry by remember { mutableStateOf<PrintroomEntry?>(null) }

    fun loadData() {
        scope.launch {
            try {
                doors = BadgerRepo.getLoadingDoors()
                entries = BadgerRepo.getPrintroomEntries()
                staging = BadgerRepo.getStagingDoors()
                routes = BadgerRepo.getRoutes()
            } catch (e: Exception) { e.printStackTrace() }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("printroom-android")
            channel.postgresChangeFlow<PostgresAction>("public") {
                table = "printroom_entries"
            }.collect { loadData() }
            channel.subscribe()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // Add truck dialog
    addDialogDoor?.let { door ->
        AddTruckDialog(
            door = door,
            staging = staging,
            routes = routes,
            onDismiss = { addDialogDoor = null },
            onSave = { entry ->
                scope.launch {
                    try {
                        BadgerRepo.upsertPrintroomEntry(entry)
                        // Auto-add to live_movement if not there yet
                        val existing = BadgerRepo.getLiveMovement().find { it.truckNumber == entry.truckNumber }
                        if (existing == null && entry.truckNumber != null) {
                            val preshiftLoc = staging.firstOrNull {
                                it.inFront == entry.truckNumber || it.inBack == entry.truckNumber
                            }?.let { sd ->
                                val pos = if (sd.inFront == entry.truckNumber) "Front" else "Back"
                                "Dr${sd.doorLabel} $pos"
                            }
                            BadgerRepo.addToMovement(entry.truckNumber, preshiftLoc)
                        }
                        loadData()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                addDialogDoor = null
            }
        )
    }

    // Edit truck dialog
    editDialogEntry?.let { entry ->
        EditTruckDialog(
            entry = entry,
            routes = routes,
            onDismiss = { editDialogEntry = null },
            onSave = { updated ->
                scope.launch {
                    try {
                        BadgerRepo.upsertPrintroomEntry(updated)
                        loadData()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                editDialogEntry = null
            },
            onDelete = {
                scope.launch {
                    try {
                        BadgerRepo.deletePrintroomEntry(entry.id)
                        loadData()
                    } catch (e: Exception) { e.printStackTrace() }
                }
                editDialogEntry = null
            }
        )
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
            Text("🖨️ Print Room", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LightText)
            Spacer(Modifier.height(4.dp))
            Text("Tap truck to edit • Tap + to add", color = MutedText, fontSize = 13.sp)
        }

        items(doors) { door ->
            DoorCard(
                door = door,
                doorEntries = entries.filter { it.loadingDoorId == door.id },
                onAddClick = { addDialogDoor = door },
                onEntryClick = { editDialogEntry = it }
            )
        }
    }
}

@Composable
fun DoorCard(
    door: LoadingDoor,
    doorEntries: List<PrintroomEntry>,
    onAddClick: () -> Unit,
    onEntryClick: (PrintroomEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        // Door header with + button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(door.doorName, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
            IconButton(
                onClick = onAddClick,
                modifier = Modifier
                    .size(32.dp)
                    .background(Amber500, RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add truck", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }

        // Column headers
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("TRUCK#", Modifier.weight(1.2f), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("RT", Modifier.width(36.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PODS", Modifier.width(38.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PAL", Modifier.width(38.dp), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("NOTES", Modifier.weight(1f), color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }

        var currentBatch = 0
        val nonEndEntries = doorEntries.filter { !it.isEndMarker }

        if (nonEndEntries.isEmpty()) {
            Text("No trucks — tap + to add", modifier = Modifier.padding(16.dp), color = MutedText, fontSize = 12.sp)
        }

        nonEndEntries.forEach { entry ->
            if (entry.batchNumber != currentBatch && currentBatch > 0) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                    Divider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                    Text(" Next Wave ", color = Amber500.copy(alpha = 0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Divider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                }
            }
            currentBatch = entry.batchNumber

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntryClick(entry) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.truckNumber ?: "—",
                    modifier = Modifier.weight(1.2f),
                    fontWeight = FontWeight.ExtraBold,
                    color = Amber500,
                    fontSize = 14.sp
                )
                Text(entry.routeInfo ?: "", Modifier.width(36.dp), color = MutedText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if (entry.pods > 0) entry.pods.toString() else "", Modifier.width(38.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if (entry.palletsTrays > 0) entry.palletsTrays.toString() else "", Modifier.width(38.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(entry.notes ?: "", Modifier.weight(1f), color = MutedText, fontSize = 10.sp)
            }
            Divider(color = DarkBorder.copy(alpha = 0.3f))
        }
    }
}

// ─── Add Truck Dialog ───────────────────────────────────────────────────────
@Composable
fun AddTruckDialog(
    door: LoadingDoor,
    staging: List<StagingDoor>,
    routes: List<Route>,
    onDismiss: () -> Unit,
    onSave: (PrintroomEntry) -> Unit
) {
    var truckNum by remember { mutableStateOf("") }
    var routeInfo by remember { mutableStateOf("") }
    var pods by remember { mutableStateOf("") }
    var pallets by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf("1") }
    val focusManager = LocalFocusManager.current

    // Auto-detect preshift location
    val preshiftLoc = remember(truckNum) {
        if (truckNum.length >= 2) {
            staging.firstOrNull { it.inFront == truckNum || it.inBack == truckNum }?.let { sd ->
                val pos = if (sd.inFront == truckNum) "Front" else "Back"
                "Dr${sd.doorLabel} $pos"
            }
        } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Truck to ${door.doorName}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                }

                // Truck Number
                OutlinedTextField(
                    value = truckNum,
                    onValueChange = { truckNum = it },
                    label = { Text("Truck #", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true,
                    colors = fieldColors()
                )

                if (preshiftLoc != null) {
                    Text("📍 PreShift: $preshiftLoc", color = Green400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                // Route
                OutlinedTextField(
                    value = routeInfo,
                    onValueChange = { routeInfo = it },
                    label = { Text("Route", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pods,
                        onValueChange = { pods = it },
                        label = { Text("Pods", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        colors = fieldColors()
                    )
                    OutlinedTextField(
                        value = pallets,
                        onValueChange = { pallets = it },
                        label = { Text("Pallets", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        colors = fieldColors()
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors()
                )

                OutlinedTextField(
                    value = batch,
                    onValueChange = { batch = it },
                    label = { Text("Batch/Wave #", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true,
                    colors = fieldColors()
                )

                Button(
                    onClick = {
                        if (truckNum.isBlank()) return@Button
                        onSave(
                            PrintroomEntry(
                                loadingDoorId = door.id,
                                truckNumber = truckNum.trim(),
                                routeInfo = routeInfo.ifBlank { null },
                                pods = pods.toIntOrNull() ?: 0,
                                palletsTrays = pallets.toIntOrNull() ?: 0,
                                notes = notes.ifBlank { null },
                                batchNumber = batch.toIntOrNull() ?: 1
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) {
                    Text("Add Truck", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Edit Truck Dialog ──────────────────────────────────────────────────────
@Composable
fun EditTruckDialog(
    entry: PrintroomEntry,
    routes: List<Route>,
    onDismiss: () -> Unit,
    onSave: (PrintroomEntry) -> Unit,
    onDelete: () -> Unit
) {
    var truckNum by remember { mutableStateOf(entry.truckNumber ?: "") }
    var routeInfo by remember { mutableStateOf(entry.routeInfo ?: "") }
    var pods by remember { mutableStateOf(if (entry.pods > 0) entry.pods.toString() else "") }
    var pallets by remember { mutableStateOf(if (entry.palletsTrays > 0) entry.palletsTrays.toString() else "") }
    var notes by remember { mutableStateOf(entry.notes ?: "") }
    var batch by remember { mutableStateOf(entry.batchNumber.toString()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit Truck ${entry.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    Row {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red500)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText)
                        }
                    }
                }

                if (showDeleteConfirm) {
                    Card(colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.15f)), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Delete this truck?", color = Red500, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDeleteConfirm = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red500)) { Text("Delete") }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = truckNum,
                    onValueChange = { truckNum = it },
                    label = { Text("Truck #", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true,
                    colors = fieldColors()
                )

                OutlinedTextField(
                    value = routeInfo,
                    onValueChange = { routeInfo = it },
                    label = { Text("Route", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pods,
                        onValueChange = { pods = it },
                        label = { Text("Pods", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        colors = fieldColors()
                    )
                    OutlinedTextField(
                        value = pallets,
                        onValueChange = { pallets = it },
                        label = { Text("Pallets", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine = true,
                        colors = fieldColors()
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = fieldColors()
                )

                OutlinedTextField(
                    value = batch,
                    onValueChange = { batch = it },
                    label = { Text("Batch/Wave #", color = MutedText) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine = true,
                    colors = fieldColors()
                )

                Button(
                    onClick = {
                        onSave(entry.copy(
                            truckNumber = truckNum.trim(),
                            routeInfo = routeInfo.ifBlank { null },
                            pods = pods.toIntOrNull() ?: 0,
                            palletsTrays = pallets.toIntOrNull() ?: 0,
                            notes = notes.ifBlank { null },
                            batchNumber = batch.toIntOrNull() ?: 1
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) {
                    Text("Save Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber500,
    unfocusedBorderColor = DarkBorder,
    cursorColor = Amber500,
    focusedTextColor = LightText,
    unfocusedTextColor = LightText,
    focusedLabelColor = Amber500
)
