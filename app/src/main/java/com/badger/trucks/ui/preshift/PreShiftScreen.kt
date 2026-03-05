package com.badger.trucks.ui.preshift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
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

@Composable
fun PreShiftScreen(onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var doors by remember { mutableStateOf<List<StagingDoor>>(emptyList()) }
    var activeTrucks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var printroomEntries by remember { mutableStateOf<List<PrintroomEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Pending duplicate confirmation: Triple(doorId, field, newValue)
    data class PendingStaging(val doorId: Int, val field: String, val value: String, val conflictMsg: String)
    var pendingStaging by remember { mutableStateOf<PendingStaging?>(null) }

    fun loadData() {
        scope.launch {
            try {
                doors = BadgerRepo.getStagingDoors()
                val movement = BadgerRepo.getLiveMovement()
                activeTrucks = movement.map { it.truckNumber }.toSet()
                printroomEntries = BadgerRepo.getPrintroomEntries()
            } catch (e: Exception) { e.printStackTrace() }
            loading = false
        }
    }

    fun doSave(doorId: Int, field: String, value: String) {
        val door = doors.find { it.id == doorId }
        val slot = if (field == "in_front") "front" else "back"
        if (value.isBlank()) {
            RemoteLogger.i("PreShift", "Truck cleared from door ${door?.doorLabel} $slot")
        } else {
            RemoteLogger.i("PreShift", "Truck $value staged at door ${door?.doorLabel} $slot")
        }
        scope.launch {
            try { BadgerRepo.updateStagingField(doorId, field, value.ifBlank { null }) }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun checkAndSave(door: StagingDoor, field: String, value: String) {
        if (value.isBlank()) { doSave(door.id, field, value); return }
        // Check if truck number already used in another staging cell
        val usedInStaging = doors.any { d ->
            d.id != door.id && (d.inFront == value || d.inBack == value)
        }
        // Check same door other field
        val usedSameRow = (field == "in_front" && door.inBack == value) ||
                          (field == "in_back" && door.inFront == value)
        // Check printroom
        val usedInPrintroom = printroomEntries.any { it.truckNumber == value }

        val conflictMsg = when {
            usedSameRow -> "Truck $value is already in the other slot of door ${door.doorLabel}."
            usedInStaging -> {
                val other = doors.first { d -> d.id != door.id && (d.inFront == value || d.inBack == value) }
                "Truck $value is already staged at door ${other.doorLabel}."
            }
            usedInPrintroom -> "Truck $value is already assigned in the Print Room."
            else -> null
        }

        if (conflictMsg != null) {
            pendingStaging = PendingStaging(door.id, field, value, conflictMsg)
        } else {
            doSave(door.id, field, value)
        }
    }

    LaunchedEffect(Unit) {
        loadData()
        try {
            val channel = BadgerRepo.realtimeChannel("preshift-android")
            channel.postgresChangeFlow<PostgresAction>("public") {
                table = "staging_doors"
            }.collect { loadData() }
            channel.subscribe()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // Duplicate confirmation dialog
    pendingStaging?.let { pending ->
        DuplicateStagingDialog(
            message = pending.conflictMsg,
            onUseAnyway = {
                RemoteLogger.w("PreShift", "Duplicate truck ${pending.value} force-staged (override) — ${pending.conflictMsg}")
                doSave(pending.doorId, pending.field, pending.value)
                pendingStaging = null
            },
            onCancel = { pendingStaging = null }
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
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("📋 PreShift Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = LightText)
            Spacer(Modifier.height(4.dp))
            Text("Truck Order – Door Placement", color = MutedText, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))

            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text("DOOR", Modifier.width(48.dp), color = Amber500, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("IN FRONT", Modifier.weight(1f), color = Amber500, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("IN BACK", Modifier.weight(1f), color = Amber500, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Divider(color = Amber500, thickness = 2.dp)
        }

        items(doors) { door ->
            val prevDoor = doors.getOrNull(doors.indexOf(door) - 1)
            val isNewGroup = prevDoor != null && prevDoor.doorNumber != door.doorNumber

            if (isNewGroup) {
                Divider(color = DarkBorder, thickness = 1.dp)
            }

            StagingRow(
                door = door,
                isActive = { truck -> activeTrucks.contains(truck) },
                onSave = { field, value -> checkAndSave(door, field, value) }
            )
        }
    }
}

@Composable
fun StagingRow(
    door: StagingDoor,
    isActive: (String) -> Boolean,
    onSave: (field: String, value: String) -> Unit
) {
    val bgColor = if (door.doorSide == "A") DarkSurface.copy(alpha = 0.6f) else DarkSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            door.doorLabel,
            modifier = Modifier.width(48.dp).padding(start = 8.dp),
            fontWeight = FontWeight.Bold,
            color = MutedText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        StagingCell(
            value = door.inFront ?: "",
            color = Green400,
            active = door.inFront?.let { isActive(it) } == true,
            modifier = Modifier.weight(1f),
            onSave = { onSave("in_front", it) }
        )

        StagingCell(
            value = door.inBack ?: "",
            color = Blue400,
            active = door.inBack?.let { isActive(it) } == true,
            modifier = Modifier.weight(1f),
            onSave = { onSave("in_back", it) }
        )
    }
    Divider(color = DarkBorder.copy(alpha = 0.3f))
}

@Composable
fun DuplicateStagingDialog(
    message: String,
    onUseAnyway: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("⚠️ Truck Already In Use", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFFF59E0B))
                Text(message, color = LightText, fontSize = 14.sp)
                Text("Do you want to use it anyway, or cancel?", color = MutedText, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedText)
                    ) { Text("Cancel") }
                    Button(
                        onClick = onUseAnyway,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black)
                    ) { Text("Use Anyway", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
@Composable
fun StagingCell(
    value: String,
    color: Color,
    active: Boolean,
    modifier: Modifier,
    onSave: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = modifier
            .height(40.dp)
            .background(if (active) Amber500.copy(alpha = 0.15f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .onFocusChanged { if (!it.isFocused && text != value) onSave(text) },
            textStyle = TextStyle(
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            singleLine = true,
            cursorBrush = SolidColor(Amber500),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (text.isEmpty()) {
                        Text("—", color = MutedText.copy(alpha = 0.3f), fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    inner()
                }
            }
        )
    }
}
