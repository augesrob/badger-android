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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import kotlinx.coroutines.launch
import com.badger.trucks.util.safeLaunch

@Composable
fun PrintRoomScreen(onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var doors         by remember { mutableStateOf<List<LoadingDoor>>(emptyList()) }
    var entries       by remember { mutableStateOf<List<PrintroomEntry>>(emptyList()) }
    var staging       by remember { mutableStateOf<List<StagingDoor>>(emptyList()) }
    var routes        by remember { mutableStateOf<List<Route>>(emptyList()) }
    var loading       by remember { mutableStateOf(true) }
    var addDialogDoor by remember { mutableStateOf<LoadingDoor?>(null) }
    var editEntry     by remember { mutableStateOf<PrintroomEntry?>(null) }

    data class PendingAdd(val entry: PrintroomEntry, val door: LoadingDoor, val conflictMsg: String)
    var pendingAdd by remember { mutableStateOf<PendingAdd?>(null) }

    // ── Data loading ────────────────────────────────────────────────────────
    suspend fun loadData() {
        try {
            doors   = BadgerRepo.getLoadingDoors()
            entries = BadgerRepo.getPrintroomEntries()
            staging = BadgerRepo.getStagingDoors()
            routes  = BadgerRepo.getRoutes()
        } catch (e: Exception) {
            android.util.Log.e("PrintRoom", "loadData error: ${e.message}", e)
            RemoteLogger.e("PrintRoom", "loadData FAILED: ${e.message}")
        }
        loading = false
    }

    // ── Initial load + realtime subscription ────────────────────────────────
    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("printroom-android-v2")
            // Use launchIn so collect doesn't block — subscribe AFTER setting up flow
            channel.postgresChangeFlow<PostgresAction>("public") { table = "printroom_entries" }
                .collect { scope.safeLaunch("PrintRoomScreen") { loadData() } }
            channel.subscribe()
        } catch (e: Exception) {
            android.util.Log.e("PrintRoom", "Realtime error: ${e.message}", e)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    fun preshiftFor(truckNum: String): String? =
        staging.firstOrNull { it.inFront == truckNum || it.inBack == truckNum }?.let { sd ->
            "Dr${sd.doorLabel} ${if (sd.inFront == truckNum) "Front" else "Back"}"
        }

    suspend fun doAddEntry(door: LoadingDoor, entry: PrintroomEntry) {
        try {
            val batchEntries = entries.filter {
                it.loadingDoorId == door.id && it.batchNumber == entry.batchNumber
            }
            val nextRow = (batchEntries.maxOfOrNull { it.rowOrder } ?: 0) + 1
            val withOrder = entry.copy(rowOrder = nextRow) // id=0 → insert path
            val saved = BadgerRepo.upsertPrintroomEntry(withOrder)

            // Add to live_movement if not already there
            if (saved.truckNumber != null) {
                val existing = BadgerRepo.getLiveMovement().find { it.truckNumber == saved.truckNumber }
                if (existing == null) {
                    BadgerRepo.addToMovement(saved.truckNumber, preshiftFor(saved.truckNumber))
                }
            }
            RemoteLogger.i("PrintRoom", "Added truck ${entry.truckNumber} to ${door.doorName} batch=${entry.batchNumber}")
            loadData()
        } catch (e: Exception) {
            android.util.Log.e("PrintRoom", "doAddEntry error: ${e.message}", e)
            RemoteLogger.e("PrintRoom", "doAddEntry FAILED: ${e.message}")
        }
    }

    // ── Add dialog ──────────────────────────────────────────────────────────
    addDialogDoor?.let { door ->
        AddTruckDialog(
            door      = door,
            staging   = staging,
            routes    = routes,
            onDismiss = { addDialogDoor = null },
            onSave    = { entry ->
                val existingElsewhere = entries.find {
                    it.truckNumber == entry.truckNumber && it.loadingDoorId != door.id
                }
                val existingHere = entries.find {
                    it.truckNumber == entry.truckNumber && it.loadingDoorId == door.id
                }
                val conflict = when {
                    existingElsewhere != null -> {
                        val name = doors.find { d -> d.id == existingElsewhere.loadingDoorId }?.doorName ?: "another door"
                        "Truck ${entry.truckNumber} is already at $name."
                    }
                    existingHere != null -> "Truck ${entry.truckNumber} is already in this door."
                    else -> null
                }
                addDialogDoor = null
                if (conflict != null) {
                    pendingAdd = PendingAdd(entry, door, conflict)
                } else {
                    scope.launch { doAddEntry(door, entry) }
                }
            }
        )
    }

    // ── Duplicate confirmation ───────────────────────────────────────────────
    pendingAdd?.let { pending ->
        DuplicateTruckDialog(
            message    = pending.conflictMsg,
            onUseAnyway = {
                pendingAdd = null
                scope.launch { doAddEntry(pending.door, pending.entry) }
            },
            onCancel = { pendingAdd = null }
        )
    }

    // ── Edit dialog ──────────────────────────────────────────────────────────
    editEntry?.let { entry ->
        EditTruckDialog(
            entry     = entry,
            routes    = routes,
            onDismiss = { editEntry = null },
            onSave    = { updated ->
                editEntry = null
                scope.launch {
                    try {
                        BadgerRepo.upsertPrintroomEntry(updated) // id > 0 → update path
                        RemoteLogger.i("PrintRoom", "Updated truck ${updated.truckNumber} (id=${updated.id})")
                        loadData()
                    } catch (e: Exception) {
                        android.util.Log.e("PrintRoom", "Edit error: ${e.message}", e)
                        RemoteLogger.e("PrintRoom", "Edit FAILED: ${e.message}")
                    }
                }
            },
            onDelete = {
                editEntry = null
                scope.launch {
                    try {
                        BadgerRepo.deletePrintroomEntry(entry.id)
                        RemoteLogger.i("PrintRoom", "Deleted entry id=${entry.id} truck=${entry.truckNumber}")
                        loadData()
                    } catch (e: Exception) {
                        android.util.Log.e("PrintRoom", "Delete error: ${e.message}", e)
                        RemoteLogger.e("PrintRoom", "Delete FAILED: ${e.message}")
                    }
                }
            }
        )
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    // ── Main UI ──────────────────────────────────────────────────────────────
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
            Spacer(Modifier.height(2.dp))
            Text("Tap + to add truck • Tap row to edit", color = MutedText, fontSize = 12.sp)
        }

        items(doors) { door ->
            DoorCard(
                door        = door,
                doorEntries = entries
                    .filter { it.loadingDoorId == door.id }
                    .sortedWith(compareBy({ it.batchNumber }, { it.rowOrder })),
                onAddClick   = { addDialogDoor = door },
                onEntryClick = { editEntry = it }
            )
        }
    }
}

// ─── Door Card ───────────────────────────────────────────────────────────────
@Composable
fun DoorCard(
    door: LoadingDoor,
    doorEntries: List<PrintroomEntry>,
    onAddClick: () -> Unit,
    onEntryClick: (PrintroomEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = DarkSurface),
        shape    = RoundedCornerShape(12.dp)
    ) {
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
                onClick  = onAddClick,
                modifier = Modifier.size(32.dp).background(Amber500, RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add truck", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("TRUCK#", Modifier.weight(1.2f),   color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("RT",     Modifier.width(36.dp),   color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PODS",   Modifier.width(38.dp),   color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("PAL",    Modifier.width(38.dp),   color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("NOTES",  Modifier.weight(1f),     color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }

        val nonEnd = doorEntries.filter { !it.isEndMarker }
        if (nonEnd.isEmpty()) {
            Text("No trucks — tap + to add", modifier = Modifier.padding(16.dp), color = MutedText, fontSize = 12.sp)
        }

        nonEnd.forEachIndexed { index, entry ->
            if (index > 0 && entry.batchNumber != nonEnd[index - 1].batchNumber) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
                    HorizontalDivider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                    Text(" Next Wave ", color = Amber500.copy(alpha = 0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.weight(1f).padding(top = 8.dp), color = Amber500.copy(alpha = 0.3f))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntryClick(entry) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(entry.truckNumber ?: "—", Modifier.weight(1.2f), fontWeight = FontWeight.ExtraBold, color = Amber500, fontSize = 14.sp)
                Text(entry.routeInfo ?: "",     Modifier.width(36.dp), color = MutedText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if ((entry.pods ?: 0) > 0) entry.pods.toString() else "",           Modifier.width(38.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if ((entry.palletsTrays ?: 0) > 0) entry.palletsTrays.toString() else "", Modifier.width(38.dp), color = LightText, fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(entry.notes ?: "", Modifier.weight(1f), color = MutedText, fontSize = 10.sp)
            }
            HorizontalDivider(color = DarkBorder.copy(alpha = 0.3f))
        }
    }
}

// ─── Add Truck Dialog ─────────────────────────────────────────────────────────
@Composable
fun AddTruckDialog(
    door: LoadingDoor,
    staging: List<StagingDoor>,
    routes: List<Route>,
    onDismiss: () -> Unit,
    onSave: (PrintroomEntry) -> Unit
) {
    var truckNum  by remember { mutableStateOf("") }
    var routeInfo by remember { mutableStateOf("") }
    var pods      by remember { mutableStateOf("") }
    var pallets   by remember { mutableStateOf("") }
    var notes     by remember { mutableStateOf("") }
    var batch     by remember { mutableStateOf("1") }
    var saving    by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val preshiftLoc = remember(truckNum) {
        if (truckNum.length >= 2) {
            staging.firstOrNull { it.inFront == truckNum || it.inBack == truckNum }?.let { sd ->
                "Dr${sd.doorLabel} ${if (sd.inFront == truckNum) "Front" else "Back"}"
            }
        } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Truck to ${door.doorName}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText)
                    }
                }

                OutlinedTextField(
                    value         = truckNum,
                    onValueChange = { truckNum = it.trim() },
                    label         = { Text("Truck #", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                if (preshiftLoc != null) {
                    Text("📍 PreShift: $preshiftLoc", color = Green400, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedTextField(
                    value         = routeInfo,
                    onValueChange = { routeInfo = it },
                    label         = { Text("Route", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = pods,
                        onValueChange = { pods = it },
                        label         = { Text("Pods", color = MutedText) },
                        modifier      = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine    = true,
                        colors        = fieldColors()
                    )
                    OutlinedTextField(
                        value         = pallets,
                        onValueChange = { pallets = it },
                        label         = { Text("Pallets", color = MutedText) },
                        modifier      = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine    = true,
                        colors        = fieldColors()
                    )
                }

                OutlinedTextField(
                    value         = notes,
                    onValueChange = { notes = it },
                    label         = { Text("Notes", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                OutlinedTextField(
                    value         = batch,
                    onValueChange = { batch = it },
                    label         = { Text("Batch/Wave #", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                Button(
                    onClick = {
                        if (truckNum.isBlank() || saving) return@Button
                        saving = true
                        onSave(
                            PrintroomEntry(
                                id            = 0,  // explicit 0 → insert path in repo
                                loadingDoorId = door.id,
                                truckNumber   = truckNum.trim(),
                                routeInfo     = routeInfo.ifBlank { null },
                                pods          = pods.toIntOrNull() ?: 0,
                                palletsTrays  = pallets.toIntOrNull() ?: 0,
                                notes         = notes.ifBlank { null },
                                batchNumber   = batch.toIntOrNull() ?: 1
                            )
                        )
                    },
                    enabled  = truckNum.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "Adding..." else "Add Truck", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Edit Truck Dialog ────────────────────────────────────────────────────────
@Composable
fun EditTruckDialog(
    entry: PrintroomEntry,
    routes: List<Route>,
    onDismiss: () -> Unit,
    onSave: (PrintroomEntry) -> Unit,
    onDelete: () -> Unit
) {
    var truckNum  by remember { mutableStateOf(entry.truckNumber ?: "") }
    var routeInfo by remember { mutableStateOf(entry.routeInfo ?: "") }
    var pods      by remember { mutableStateOf(if ((entry.pods ?: 0) > 0) entry.pods.toString() else "") }
    var pallets   by remember { mutableStateOf(if ((entry.palletsTrays ?: 0) > 0) entry.palletsTrays.toString() else "") }
    var notes     by remember { mutableStateOf(entry.notes ?: "") }
    var batch     by remember { mutableStateOf(entry.batchNumber.toString()) }
    var saving    by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit Truck ${entry.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    Row {
                        IconButton(onClick = { showDelete = !showDelete }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red500)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText)
                        }
                    }
                }

                if (showDelete) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.15f)),
                        shape  = RoundedCornerShape(10.dp)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Delete this truck?", color = Red500, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDelete = false }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                                Button(
                                    onClick  = onDelete,
                                    modifier = Modifier.weight(1f),
                                    colors   = ButtonDefaults.buttonColors(containerColor = Red500)
                                ) { Text("Delete") }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value         = truckNum,
                    onValueChange = { truckNum = it.trim() },
                    label         = { Text("Truck #", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                OutlinedTextField(
                    value         = routeInfo,
                    onValueChange = { routeInfo = it },
                    label         = { Text("Route", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = pods,
                        onValueChange = { pods = it },
                        label         = { Text("Pods", color = MutedText) },
                        modifier      = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine    = true,
                        colors        = fieldColors()
                    )
                    OutlinedTextField(
                        value         = pallets,
                        onValueChange = { pallets = it },
                        label         = { Text("Pallets", color = MutedText) },
                        modifier      = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        singleLine    = true,
                        colors        = fieldColors()
                    )
                }

                OutlinedTextField(
                    value         = notes,
                    onValueChange = { notes = it },
                    label         = { Text("Notes", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                OutlinedTextField(
                    value         = batch,
                    onValueChange = { batch = it },
                    label         = { Text("Batch/Wave #", color = MutedText) },
                    modifier      = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    singleLine    = true,
                    colors        = fieldColors()
                )

                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        onSave(
                            entry.copy(
                                truckNumber  = truckNum.trim(),
                                routeInfo    = routeInfo.ifBlank { null },
                                pods         = pods.toIntOrNull() ?: 0,
                                palletsTrays = pallets.toIntOrNull() ?: 0,
                                notes        = notes.ifBlank { null },
                                batchNumber  = batch.toIntOrNull() ?: 1
                            )
                        )
                    },
                    enabled  = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (saving) "Saving..." else "Save Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Duplicate Truck Dialog ───────────────────────────────────────────────────
@Composable
fun DuplicateTruckDialog(message: String, onUseAnyway: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("⚠️ Truck Already In Use", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Color(0xFFF59E0B))
                Text(message, color = LightText, fontSize = 14.sp)
                Text("Add anyway or cancel?", color = MutedText, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedText)
                    ) { Text("Cancel") }
                    Button(onClick = onUseAnyway, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black)
                    ) { Text("Use Anyway", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = Amber500,
    unfocusedBorderColor = DarkBorder,
    cursorColor          = Amber500,
    focusedTextColor     = LightText,
    unfocusedTextColor   = LightText,
    focusedLabelColor    = Amber500
)
