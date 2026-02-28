@file:OptIn(ExperimentalLayoutApi::class)

package com.badger.trucks.ui.admin

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.badger.trucks.data.*
import com.badger.trucks.ui.printroom.fieldColors
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AdminScreen() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Trucks", "Tractors", "Statuses", "Fleet", "Debug")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = DarkSurface,
            contentColor = Amber500,
            edgePadding = 8.dp,
            divider = { Divider(color = DarkBorder) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == index) Amber500 else MutedText
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> TruckSection()
            1 -> TractorSection()
            2 -> StatusSection()
            3 -> FleetSection()
            4 -> DebugScreen()
        }
    }
}

// ─── TRUCKS ────────────────────────────────────────────────────────────────

@Composable
fun TruckSection() {
    val scope = rememberCoroutineScope()
    var trucks by remember { mutableStateOf<List<Truck>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var editTruck by remember { mutableStateOf<Truck?>(null) }

    fun load() { scope.launch { try { trucks = BadgerRepo.getTrucks() } catch (e: Exception) { e.printStackTrace() }; loading = false } }
    LaunchedEffect(Unit) { load() }

    if (showAdd) {
        TruckDialog(
            truck = null,
            onDismiss = { showAdd = false },
            onSave = { truck ->
                scope.launch { try { BadgerRepo.addTruck(truck); load() } catch (e: Exception) { e.printStackTrace() } }
                showAdd = false
            }
        )
    }

    editTruck?.let { t ->
        TruckDialog(
            truck = t,
            onDismiss = { editTruck = null },
            onSave = { updated ->
                scope.launch { try { BadgerRepo.updateTruck(t.id, updated); load() } catch (e: Exception) { e.printStackTrace() } }
                editTruck = null
            },
            onDelete = {
                scope.launch { try { BadgerRepo.deleteTruck(t.id); load() } catch (e: Exception) { e.printStackTrace() } }
                editTruck = null
            }
        )
    }

    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }; return }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("🚚 Trucks", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
                    Text("${trucks.size} trucks • tap to edit", color = MutedText, fontSize = 12.sp)
                }
                IconButton(onClick = { showAdd = true }, modifier = Modifier.background(Amber500, RoundedCornerShape(10.dp)).size(40.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(trucks) { truck ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { editTruck = truck },
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(truck.truckNumber.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Amber500, modifier = Modifier.width(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(truck.truckType.replace("_", " ").replaceFirstChar { it.uppercase() }, color = LightText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(truck.transmission.replaceFirstChar { it.uppercase() }, color = MutedText, fontSize = 11.sp)
                    }
                    if (!truck.isActive) Surface(shape = RoundedCornerShape(4.dp), color = Red500.copy(alpha = 0.3f)) {
                        Text("Inactive", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Red500, fontSize = 9.sp)
                    }
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MutedText, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun TruckDialog(truck: Truck?, onDismiss: () -> Unit, onSave: (Truck) -> Unit, onDelete: (() -> Unit)? = null) {
    var number by remember { mutableStateOf(truck?.truckNumber?.toString() ?: "") }
    var type by remember { mutableStateOf(truck?.truckType ?: "box_truck") }
    var transmission by remember { mutableStateOf(truck?.transmission ?: "automatic") }
    var notes by remember { mutableStateOf(truck?.notes ?: "") }
    var isActive by remember { mutableStateOf(truck?.isActive ?: true) }
    var showDelete by remember { mutableStateOf(false) }

    val typeOptions = listOf("box_truck", "straight_truck", "semi", "van")
    val transOptions = listOf("automatic", "manual")

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (truck == null) "Add Truck" else "Edit Truck ${truck.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    Row {
                        if (onDelete != null) IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red500) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                    }
                }

                if (showDelete) {
                    Card(colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.15f)), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Delete truck ${truck?.truckNumber}?", color = Red500, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDelete = false }, Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = { onDelete?.invoke() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red500)) { Text("Delete") }
                            }
                        }
                    }
                }

                OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Truck #", color = MutedText) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, colors = fieldColors())

                // Type selector
                Text("Type", color = MutedText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    typeOptions.forEach { opt ->
                        FilterChip(selected = type == opt, onClick = { type = opt }, label = { Text(opt.replace("_", " ").replaceFirstChar { it.uppercase() }, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber500, selectedLabelColor = Color.Black))
                    }
                }

                // Transmission selector
                Text("Transmission", color = MutedText, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    transOptions.forEach { opt ->
                        FilterChip(selected = transmission == opt, onClick = { transmission = opt }, label = { Text(opt.replaceFirstChar { it.uppercase() }, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber500, selectedLabelColor = Color.Black))
                    }
                }

                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes", color = MutedText) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors())

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Active", color = LightText, fontSize = 14.sp)
                    Switch(checked = isActive, onCheckedChange = { isActive = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Amber500))
                }

                Button(
                    onClick = {
                        val num = number.toIntOrNull() ?: return@Button
                        onSave(Truck(id = truck?.id ?: 0, truckNumber = num, truckType = type, transmission = transmission, isActive = isActive, notes = notes.ifBlank { null }))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) { Text(if (truck == null) "Add Truck" else "Save Changes", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ─── TRACTORS ──────────────────────────────────────────────────────────────

@Composable
fun TractorSection() {
    val scope = rememberCoroutineScope()
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var trailerList by remember { mutableStateOf<List<TrailerItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editTractor by remember { mutableStateOf<Tractor?>(null) }

    fun load() {
        scope.launch {
            try {
                tractors = BadgerRepo.getTractors()
                trailerList = BadgerRepo.getTrailerList()
            } catch (e: Exception) { e.printStackTrace() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    editTractor?.let { t ->
        TractorDialog(
            tractor = t,
            trailerList = trailerList,
            onDismiss = { editTractor = null },
            onSave = { updated ->
                scope.launch { try { BadgerRepo.updateTractor(t.id, updated); load() } catch (e: Exception) { e.printStackTrace() } }
                editTractor = null
            }
        )
    }

    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }; return }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("🚛 Tractors", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Text("${tractors.size} tractors • tap to edit trailers/driver", color = MutedText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        items(tractors) { t ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { editTractor = t },
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t.truckNumber.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Amber500)
                            Column {
                                Text(t.driverName ?: "No driver", fontWeight = FontWeight.Bold, color = LightText, fontSize = 14.sp)
                                if (t.driverCell != null) Text(t.driverCell, color = MutedText, fontSize = 11.sp)
                            }
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MutedText, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailerChip("${t.truckNumber}-1", t.trailer1?.trailerNumber, Green400)
                        TrailerChip("${t.truckNumber}-2", t.trailer2?.trailerNumber, Blue400)
                        TrailerChip("${t.truckNumber}-3", t.trailer3?.trailerNumber, Purple400)
                        TrailerChip("${t.truckNumber}-4", t.trailer4?.trailerNumber, Pink400)
                    }
                }
            }
        }
    }
}

@Composable
fun TractorDialog(tractor: Tractor, trailerList: List<TrailerItem>, onDismiss: () -> Unit, onSave: (Tractor) -> Unit) {
    var driverName by remember { mutableStateOf(tractor.driverName ?: "") }
    var driverCell by remember { mutableStateOf(tractor.driverCell ?: "") }
    var t1Id by remember { mutableStateOf(tractor.trailer1Id) }
    var t2Id by remember { mutableStateOf(tractor.trailer2Id) }
    var t3Id by remember { mutableStateOf(tractor.trailer3Id) }
    var t4Id by remember { mutableStateOf(tractor.trailer4Id) }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tractor ${tractor.truckNumber}", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                }

                OutlinedTextField(value = driverName, onValueChange = { driverName = it }, label = { Text("Driver Name", color = MutedText) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors())
                OutlinedTextField(value = driverCell, onValueChange = { driverCell = it }, label = { Text("Driver Cell", color = MutedText) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, colors = fieldColors())

                Text("Trailer Slots", color = MutedText, fontSize = 12.sp)
                TrailerSlotPicker("${tractor.truckNumber}-1", t1Id, trailerList, Green400) { t1Id = it }
                TrailerSlotPicker("${tractor.truckNumber}-2", t2Id, trailerList, Blue400) { t2Id = it }
                TrailerSlotPicker("${tractor.truckNumber}-3", t3Id, trailerList, Purple400) { t3Id = it }
                TrailerSlotPicker("${tractor.truckNumber}-4", t4Id, trailerList, Pink400) { t4Id = it }

                Button(
                    onClick = {
                        onSave(tractor.copy(
                            driverName = driverName.ifBlank { null },
                            driverCell = driverCell.ifBlank { null },
                            trailer1Id = t1Id,
                            trailer2Id = t2Id,
                            trailer3Id = t3Id,
                            trailer4Id = t4Id
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) { Text("Save Changes", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailerSlotPicker(
    label: String,
    currentId: Int?,
    trailerList: List<TrailerItem>,
    accentColor: Color,
    onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val current = trailerList.find { it.id == currentId }
    val filtered = trailerList.filter {
        search.isBlank() || it.trailerNumber.contains(search, ignoreCase = true)
    }

    Column {
        // Display row — tap to open
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DarkCard)
                .clickable { expanded = true; search = "" }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(accentColor, RoundedCornerShape(2.dp)))
                Text(label, color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                current?.trailerNumber ?: "— None —",
                color = if (current != null) accentColor else MutedText,
                fontSize = 13.sp,
                fontWeight = if (current != null) FontWeight.Bold else FontWeight.Normal
            )
        }

        // Dropdown
        if (expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search trailer #", color = MutedText, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = fieldColors()
                    )
                    Spacer(Modifier.height(6.dp))
                    // Clear option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(null); expanded = false }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("— None —", color = MutedText, fontSize = 13.sp)
                    }
                    Divider(color = DarkBorder)
                    // Trailer list (max visible height via scroll)
                    filtered.take(8).forEach { trailer ->
                        val isSelected = trailer.id == currentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelect(trailer.id); expanded = false }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(trailer.trailerNumber, color = if (isSelected) accentColor else LightText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                            if (isSelected) Text("✓", color = accentColor, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    if (filtered.size > 8) {
                        Text("  +${filtered.size - 8} more — type to filter", color = MutedText, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TrailerChip(label: String, trailerNum: String?, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = DarkCard) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("$label:", color = MutedText, fontSize = 10.sp)
            Spacer(Modifier.width(3.dp))
            Text(trailerNum ?: "—", color = if (trailerNum != null) color else MutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

// ─── STATUSES ──────────────────────────────────────────────────────────────

@Composable
fun StatusSection() {
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<StatusValue>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf<StatusValue?>(null) }

    fun load() { scope.launch { try { statuses = BadgerRepo.getStatuses() } catch (e: Exception) { e.printStackTrace() }; loading = false } }
    LaunchedEffect(Unit) { load() }

    if (showAdd) {
        StatusDialog(
            status = null,
            onDismiss = { showAdd = false },
            onSave = { name, color ->
                scope.launch { try { BadgerRepo.addStatus(name, color); load() } catch (e: Exception) { e.printStackTrace() } }
                showAdd = false
            }
        )
    }

    editStatus?.let { s ->
        StatusDialog(
            status = s,
            onDismiss = { editStatus = null },
            onSave = { name, color ->
                scope.launch { try { BadgerRepo.updateStatus(s.id, name, color); load() } catch (e: Exception) { e.printStackTrace() } }
                editStatus = null
            },
            onDelete = {
                scope.launch { try { BadgerRepo.deleteStatus(s.id); load() } catch (e: Exception) { e.printStackTrace() } }
                editStatus = null
            }
        )
    }

    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }; return }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("🏷️ Statuses", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
                    Text("${statuses.size} statuses • tap to edit", color = MutedText, fontSize = 12.sp)
                }
                IconButton(onClick = { showAdd = true }, modifier = Modifier.background(Amber500, RoundedCornerShape(10.dp)).size(40.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(statuses) { s ->
            val statusColor = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { MutedText }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { editStatus = s },
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).background(statusColor, RoundedCornerShape(6.dp)))
                    Text(s.statusName, color = LightText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(s.statusColor, color = MutedText, fontSize = 10.sp)
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MutedText, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun StatusDialog(status: StatusValue?, onDismiss: () -> Unit, onSave: (String, String) -> Unit, onDelete: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(status?.statusName ?: "") }
    var colorHex by remember { mutableStateOf(status?.statusColor ?: "#3B82F6") }
    var showDelete by remember { mutableStateOf(false) }

    val presetColors = listOf(
        "#3B82F6", "#22C55E", "#F59E0B", "#EF4444",
        "#8B5CF6", "#EC4899", "#06B6D4", "#6B7280",
        "#F97316", "#84CC16", "#14B8A6", "#A855F7"
    )

    val parsedColor = try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { Color.Gray }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (status == null) "Add Status" else "Edit Status", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Amber500)
                    Row {
                        if (onDelete != null) IconButton(onClick = { showDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Red500) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = MutedText) }
                    }
                }

                if (showDelete) {
                    Card(colors = CardDefaults.cardColors(containerColor = Red500.copy(alpha = 0.15f)), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Delete \"${status?.statusName}\"?", color = Red500, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showDelete = false }, Modifier.weight(1f)) { Text("Cancel") }
                                Button(onClick = { onDelete?.invoke() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Red500)) { Text("Delete") }
                            }
                        }
                    }
                }

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Status Name", color = MutedText) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors())

                // Color preview + hex input
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).background(parsedColor, RoundedCornerShape(8.dp)))
                    OutlinedTextField(
                        value = colorHex,
                        onValueChange = { colorHex = it },
                        label = { Text("Hex Color", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = fieldColors(),
                        placeholder = { Text("#3B82F6", color = MutedText) }
                    )
                }

                // Color presets
                Text("Presets:", color = MutedText, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presetColors.forEach { hex ->
                        val c = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Gray }
                        Box(
                            Modifier
                                .size(30.dp)
                                .background(c, RoundedCornerShape(6.dp))
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { colorHex = hex }
                        ) {
                            if (colorHex == hex) {
                                Text("✓", color = Color.White, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center), fontSize = 14.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = { if (name.isNotBlank()) onSave(name.trim(), colorHex.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) { Text(if (status == null) "Add Status" else "Save Changes", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ─── FLEET ─────────────────────────────────────────────────────────────────

@Composable
fun FleetSection() {
    val scope = rememberCoroutineScope()
    var trucks by remember { mutableStateOf<List<Truck>>(emptyList()) }
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var movement by remember { mutableStateOf<List<LiveMovement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            trucks = BadgerRepo.getTrucks()
            tractors = BadgerRepo.getTractors()
            movement = BadgerRepo.getLiveMovement()
        } catch (e: Exception) { e.printStackTrace() }
        loading = false
    }

    val activeTrucks = movement.map { it.truckNumber }.toSet()

    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }; return }

    val inUse = trucks.count { activeTrucks.contains(it.truckNumber.toString()) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("🚛 Fleet Inventory", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("Total", trucks.size.toString())
                StatBox("In Use", inUse.toString(), Green400)
                StatBox("Available", (trucks.size - inUse).toString(), MutedText)
            }
            Spacer(Modifier.height(8.dp))
        }
        items(trucks) { truck ->
            val isInUse = activeTrucks.contains(truck.truckNumber.toString())
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (isInUse) Green500.copy(alpha = 0.08f) else DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(truck.truckNumber.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
                    Spacer(Modifier.width(12.dp))
                    Text(truck.truckType.replace("_", " ").replaceFirstChar { it.uppercase() }, color = MutedText, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (isInUse) Surface(shape = RoundedCornerShape(4.dp), color = Green500) {
                        Text("IN USE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String, color: Color = LightText) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = color)
        Text(label, color = MutedText, fontSize = 10.sp)
    }
}
