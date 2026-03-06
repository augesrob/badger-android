package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.badger.trucks.data.BadgerRepo
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class StatusValue(
    val id: Int = 0,
    @SerialName("status_name")  val statusName: String  = "",
    @SerialName("status_color") val statusColor: String = "#22c55e",
    @SerialName("sort_order")   val sortOrder: Int      = 0,
    @SerialName("is_active")    val isActive: Boolean   = true,
)

private data class StatusTable(val key: String, val label: String, val emoji: String)
private val TABLES = listOf(
    StatusTable("status_values",          "Truck Statuses",     "🚛"),
    StatusTable("door_status_values",     "Door Statuses",      "🚪"),
    StatusTable("dock_lock_status_values","Dock Lock Statuses", "🔒"),
)

private val PRESET_COLORS = listOf(
    "#22c55e", "#ef4444", "#f59e0b", "#3b82f6",
    "#8b5cf6", "#ec4899", "#06b6d4", "#f97316",
    "#84cc16", "#64748b", "#ffffff", "#000000",
)

private fun parseColor(hex: String): Color = try {
    val clean = hex.trimStart('#').padEnd(6, '0')
    Color(android.graphics.Color.parseColor("#$clean"))
} catch (_: Exception) { Color.Gray }

@Composable
fun StatusValuesScreen() {
    val scope = rememberCoroutineScope()
    var selectedTable by remember { mutableStateOf(TABLES[0]) }
    var statuses     by remember { mutableStateOf<List<StatusValue>>(emptyList()) }
    var loading      by remember { mutableStateOf(true) }
    var error        by remember { mutableStateOf<String?>(null) }

    // Dialog state
    var editTarget   by remember { mutableStateOf<StatusValue?>(null) }
    var showAdd      by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<StatusValue?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                statuses = BadgerRepo.supabase.postgrest[selectedTable.key]
                    .select { order("sort_order", Order.ASCENDING) }
                    .decodeList<StatusValue>()
            } catch (e: Exception) { error = e.message }
            finally { loading = false }
        }
    }

    LaunchedEffect(selectedTable) { load() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D0D0D)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Table selector tabs
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TABLES.forEach { t ->
                val selected = t.key == selectedTable.key
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFF3B82F6) else Color(0xFF1A1A1A))
                        .clickable { selectedTable = t }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${t.emoji}\n${t.label.split(" ").first()}",
                        color = if (selected) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${selectedTable.emoji} ${selectedTable.label}",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAdd = true }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF22C55E), modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { load() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }

        error?.let {
            Text(it, color = Color(0xFFEF4444), fontSize = 12.sp)
        }

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF3B82F6), modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(statuses, key = { it.id }) { sv ->
                    StatusRow(
                        sv = sv,
                        onEdit   = { editTarget = sv },
                        onDelete = { deleteTarget = sv },
                    )
                }
            }
        }
    }

    // Edit dialog
    editTarget?.let { sv ->
        StatusEditDialog(
            title    = "Edit Status",
            initial  = sv,
            onDismiss = { editTarget = null },
            onSave   = { updated ->
                scope.launch {
                    try {
                        BadgerRepo.supabase.postgrest[selectedTable.key]
                            .update({
                                set("status_name",  updated.statusName)
                                set("status_color", updated.statusColor)
                            }) { filter { eq("id", sv.id) } }
                        editTarget = null
                        load()
                    } catch (e: Exception) { error = e.message }
                }
            }
        )
    }

    // Add dialog
    if (showAdd) {
        StatusEditDialog(
            title    = "Add Status",
            initial  = StatusValue(sortOrder = (statuses.maxOfOrNull { it.sortOrder } ?: 0) + 1),
            onDismiss = { showAdd = false },
            onSave   = { newSv ->
                scope.launch {
                    try {
                        BadgerRepo.supabase.postgrest[selectedTable.key]
                            .insert(newSv)
                        showAdd = false
                        load()
                    } catch (e: Exception) { error = e.message }
                }
            }
        )
    }

    // Delete confirm
    deleteTarget?.let { sv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("Delete Status?", color = Color.White, fontWeight = FontWeight.Bold) },
            text  = { Text("Delete \"${sv.statusName}\"? This cannot be undone.", color = Color.Gray, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            BadgerRepo.supabase.postgrest[selectedTable.key]
                                .delete { filter { eq("id", sv.id) } }
                            deleteTarget = null
                            load()
                        } catch (e: Exception) { error = e.message; deleteTarget = null }
                    }
                }) { Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }
}

@Composable
private fun StatusRow(sv: StatusValue, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(parseColor(sv.statusColor))
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
        )
        Text(
            sv.statusName,
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(sv.statusColor.uppercase(), color = Color.Gray, fontSize = 10.sp)
        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Edit, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StatusEditDialog(
    title: String,
    initial: StatusValue,
    onDismiss: () -> Unit,
    onSave: (StatusValue) -> Unit,
) {
    var name  by remember { mutableStateOf(initial.statusName) }
    var color by remember { mutableStateOf(initial.statusColor.trimStart('#').padEnd(6, '0')) }
    var hexError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)

            // Name field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Status Name", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedLabelColor = Color(0xFF3B82F6),
                    unfocusedLabelColor = Color.Gray,
                ),
                singleLine = true,
            )

            // Color preview + hex input
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(parseColor("#$color"))
                        .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
                OutlinedTextField(
                    value = "#$color",
                    onValueChange = { raw ->
                        val stripped = raw.trimStart('#').take(6)
                        color = stripped
                        hexError = stripped.length == 6 && !stripped.matches(Regex("[0-9a-fA-F]{6}"))
                    },
                    label = { Text("Hex Color", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    isError = hexError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedLabelColor = Color(0xFF3B82F6),
                        unfocusedLabelColor = Color.Gray,
                    ),
                    singleLine = true,
                )
            }

            // Preset color palette
            Text("Presets", color = Color.Gray, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                PRESET_COLORS.take(6).forEach { preset ->
                    val isSelected = color.equals(preset.trimStart('#'), ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(parseColor(preset))
                            .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) Color.White else Color.White.copy(0.2f), CircleShape)
                            .clickable { color = preset.trimStart('#'); hexError = false }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                PRESET_COLORS.drop(6).forEach { preset ->
                    val isSelected = color.equals(preset.trimStart('#'), ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(parseColor(preset))
                            .border(if (isSelected) 2.dp else 0.5.dp, if (isSelected) Color.White else Color.White.copy(0.2f), CircleShape)
                            .clickable { color = preset.trimStart('#'); hexError = false }
                    )
                }
            }

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333333))
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        if (name.isBlank() || hexError || color.length < 6) return@Button
                        onSave(initial.copy(statusName = name.trim(), statusColor = "#$color"))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    enabled = name.isNotBlank() && !hexError && color.length >= 6,
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
