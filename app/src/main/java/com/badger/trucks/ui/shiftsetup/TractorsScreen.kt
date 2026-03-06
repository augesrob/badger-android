package com.badger.trucks.ui.shiftsetup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.BadgerRepo
import com.badger.trucks.data.Tractor
import com.badger.trucks.data.TrailerItem
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TractorsSubScreen() {
    val scope = rememberCoroutineScope()

    var tractors    by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var trailerList by remember { mutableStateOf<List<TrailerItem>>(emptyList()) }
    var loading     by remember { mutableStateOf(true) }
    var error       by remember { mutableStateOf<String?>(null) }
    var editingId   by remember { mutableStateOf<Int?>(null) }
    var activeTab   by remember { mutableStateOf(0) } // 0 = Tractors, 1 = Trailers

    fun reload() {
        scope.launch {
            loading = true; error = null
            try {
                tractors    = BadgerRepo.getTractors()
                trailerList = BadgerRepo.getTrailerList()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(DarkBg)) {

        // ── Tab row ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("🚛 Tractors", "🔗 Trailers").forEachIndexed { i, label ->
                val selected = activeTab == i
                Surface(
                    color = if (selected) Amber500 else DarkCard,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable(
                        remember { MutableInteractionSource() }, indication = ripple()
                    ) { activeTab = i }
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        color = if (selected) Color.Black else MutedText,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal
                    )
                }
            }
        }
        HorizontalDivider(color = DarkBorder)

        // ── Content ──────────────────────────────────────────────────────
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Amber500, strokeWidth = 2.dp)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Load failed", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    Text(error ?: "", color = MutedText, fontSize = 11.sp)
                    Button(
                        onClick = { reload() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                    ) { Text("Retry") }
                }
            }
            activeTab == 0 -> TractorsList(
                tractors    = tractors,
                trailerList = trailerList,
                editingId   = editingId,
                onToggleEdit = { id -> editingId = if (editingId == id) null else id },
                onSave = { tractor ->
                    scope.launch {
                        try {
                            BadgerRepo.updateTractor(tractor.id, tractor)
                            reload()
                        } catch (e: Exception) {
                            error = e.message
                        }
                        editingId = null
                    }
                }
            )
            else -> TrailersList(
                trailerList = trailerList,
                onAdd = { number, notes ->
                    scope.launch {
                        try {
                            BadgerRepo.addTrailer(number, notes)
                            reload()
                        } catch (e: Exception) { error = e.message }
                    }
                },
                onToggleActive = { item ->
                    scope.launch {
                        try {
                            BadgerRepo.setTrailerActive(item.id, !item.isActive)
                            reload()
                        } catch (e: Exception) { error = e.message }
                    }
                },
                onDelete = { item ->
                    scope.launch {
                        try {
                            BadgerRepo.deleteTrailer(item.id)
                            reload()
                        } catch (e: Exception) { error = e.message }
                    }
                }
            )
        }
    }
}

// ── Tractors list ─────────────────────────────────────────────────────────────

@Composable
private fun TractorsList(
    tractors: List<Tractor>,
    trailerList: List<TrailerItem>,
    editingId: Int?,
    onToggleEdit: (Int) -> Unit,
    onSave: (Tractor) -> Unit
) {
    if (tractors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No tractors found", color = MutedText, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tractors, key = { it.id }) { tractor ->
            TractorCard(
                tractor     = tractor,
                trailerList = trailerList,
                expanded    = editingId == tractor.id,
                onToggle    = { onToggleEdit(tractor.id) },
                onSave      = onSave
            )
        }
    }
}

@Composable
private fun TractorCard(
    tractor: Tractor,
    trailerList: List<TrailerItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (Tractor) -> Unit
) {
    var driverName by remember(tractor.id) { mutableStateOf(tractor.driverName ?: "") }
    var driverCell by remember(tractor.id) { mutableStateOf(tractor.driverCell ?: "") }
    var notes      by remember(tractor.id) { mutableStateOf(tractor.notes ?: "") }
    var t1Id       by remember(tractor.id) { mutableStateOf(tractor.trailer1Id) }
    var t2Id       by remember(tractor.id) { mutableStateOf(tractor.trailer2Id) }
    var t3Id       by remember(tractor.id) { mutableStateOf(tractor.trailer3Id) }
    var t4Id       by remember(tractor.id) { mutableStateOf(tractor.trailer4Id) }
    var saving     by remember { mutableStateOf(false) }

    val activeTrailers = trailerList.filter { it.isActive }

    Surface(color = DarkCard, shape = RoundedCornerShape(10.dp)) {
        Column {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = ripple()) { onToggle() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color  = Purple500.copy(alpha = 0.15f),
                    shape  = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Purple500.copy(alpha = 0.4f))
                ) {
                    Text(
                        "#${tractor.truckNumber}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = Purple500, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        tractor.driverName?.takeIf { it.isNotBlank() } ?: "No driver",
                        color = if (tractor.driverName.isNullOrBlank()) MutedText else LightText,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    val assigned = listOfNotNull(tractor.trailer1, tractor.trailer2, tractor.trailer3, tractor.trailer4)
                    if (assigned.isNotEmpty()) {
                        Text(assigned.joinToString(" · ") { it.trailerNumber }, color = Amber500, fontSize = 11.sp)
                    } else {
                        Text("No trailers assigned", color = MutedText, fontSize = 11.sp)
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp)
                )
            }

            // ── Edit form ─────────────────────────────────────────────────
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = DarkBorder)
                    TractorTextField("Driver Name", driverName) { driverName = it }
                    TractorTextField("Driver Cell", driverCell, KeyboardType.Phone) { driverCell = it }

                    Text("Trailers", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailerDropdown("T1", t1Id, activeTrailers, Modifier.weight(1f)) { t1Id = it }
                        TrailerDropdown("T2", t2Id, activeTrailers, Modifier.weight(1f)) { t2Id = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailerDropdown("T3", t3Id, activeTrailers, Modifier.weight(1f)) { t3Id = it }
                        TrailerDropdown("T4", t4Id, activeTrailers, Modifier.weight(1f)) { t4Id = it }
                    }
                    TractorTextField("Notes (optional)", notes) { notes = it }

                    Button(
                        onClick = {
                            saving = true
                            onSave(
                                tractor.copy(
                                    driverName = driverName.trim().takeIf { it.isNotBlank() },
                                    driverCell = driverCell.trim().takeIf { it.isNotBlank() },
                                    trailer1Id = t1Id, trailer2Id = t2Id,
                                    trailer3Id = t3Id, trailer4Id = t4Id,
                                    notes = notes.trim().takeIf { it.isNotBlank() }
                                )
                            )
                        },
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save Changes", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailerDropdown(
    label: String,
    selectedId: Int?,
    trailers: List<TrailerItem>,
    modifier: Modifier = Modifier,
    onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = trailers.find { it.id == selectedId }

    Box(modifier) {
        Surface(
            color  = Color(0xFF111111),
            shape  = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = ripple()) { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(label, color = MutedText, fontSize = 9.sp, letterSpacing = 0.5.sp)
                    Text(
                        selected?.trailerNumber ?: "None",
                        color = if (selected != null) LightText else MutedText,
                        fontSize = 12.sp,
                        fontWeight = if (selected != null) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurface)
        ) {
            DropdownMenuItem(
                text = { Text("None", color = MutedText, fontSize = 13.sp) },
                onClick = { onSelect(null); expanded = false }
            )
            trailers.forEach { t ->
                DropdownMenuItem(
                    text = {
                        Text(
                            t.trailerNumber,
                            color = if (t.id == selectedId) Amber500 else LightText,
                            fontSize = 13.sp,
                            fontWeight = if (t.id == selectedId) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = { onSelect(t.id); expanded = false }
                )
            }
        }
    }
}

// ── Trailers list ─────────────────────────────────────────────────────────────

@Composable
private fun TrailersList(
    trailerList: List<TrailerItem>,
    onAdd: (String, String) -> Unit,
    onToggleActive: (TrailerItem) -> Unit,
    onDelete: (TrailerItem) -> Unit
) {
    var addNumber     by remember { mutableStateOf("") }
    var addNotes      by remember { mutableStateOf("") }
    var showAdd       by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<TrailerItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${trailerList.size} trailers total", color = MutedText, fontSize = 12.sp)
            Button(
                onClick = { showAdd = !showAdd },
                colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    if (showAdd) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = null, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showAdd) "Cancel" else "Add Trailer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(visible = showAdd) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("New Trailer", color = Amber500, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                TractorTextField("Trailer Number *", addNumber) { addNumber = it }
                TractorTextField("Notes (optional)", addNotes) { addNotes = it }
                Button(
                    onClick = {
                        if (addNumber.isNotBlank()) {
                            onAdd(addNumber.trim(), addNotes.trim())
                            addNumber = ""; addNotes = ""; showAdd = false
                        }
                    },
                    enabled = addNumber.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add Trailer", fontWeight = FontWeight.Bold) }
            }
        }

        HorizontalDivider(color = DarkBorder)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(trailerList, key = { it.id }) { item ->
                Surface(color = DarkCard, shape = RoundedCornerShape(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (item.isActive) Color(0xFF22C55E) else Color(0xFF6B7280),
                                    RoundedCornerShape(4.dp)
                                )
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.trailerNumber, color = LightText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (!item.notes.isNullOrBlank()) {
                                Text(item.notes, color = MutedText, fontSize = 11.sp)
                            }
                            Text(
                                if (item.isActive) "Active" else "Inactive",
                                color = if (item.isActive) Color(0xFF22C55E) else MutedText,
                                fontSize = 10.sp
                            )
                        }
                        IconButton(onClick = { onToggleActive(item) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (item.isActive) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = if (item.isActive) Amber500 else MutedText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { deleteConfirm = item }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    deleteConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            containerColor = DarkCard, titleContentColor = LightText, textContentColor = MutedText,
            title = { Text("Delete ${item.trailerNumber}?", fontWeight = FontWeight.ExtraBold) },
            text  = { Text("This permanently removes this trailer. Any tractor assignments will be cleared.") },
            confirmButton = {
                Button(
                    onClick = { onDelete(item); deleteConfirm = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteConfirm = null },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedText),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                ) { Text("Cancel") }
            }
        )
    }
}

// ── Shared text field ─────────────────────────────────────────────────────────

@Composable
private fun TractorTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Amber500,
            unfocusedBorderColor = DarkBorder,
            focusedLabelColor    = Amber500,
            unfocusedLabelColor  = MutedText,
            focusedTextColor     = LightText,
            unfocusedTextColor   = LightText,
            cursorColor          = Amber500
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
