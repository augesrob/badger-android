package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.BadgerRepo
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun DataResetScreen() {
    val scope = rememberCoroutineScope()

    var busy       by remember { mutableStateOf<String?>(null) }   // which reset is running
    var toast      by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(3500); toast = null }
    }

    // ── Reset helpers ──────────────────────────────────────────────────────────
    fun resetPrintroom() {
        scope.launch {
            busy = "printroom"
            try {
                // Delete all — DB trigger auto-seeds Batch1×2 + Batch2×6
                BadgerRepo.supabase.postgrest.from("printroom_entries")
                    .delete { filter { neq("id", 0) } }
                // Reset door statuses
                BadgerRepo.supabase.postgrest.from("loading_doors")
                    .update(buildJsonObject {
                        put("is_done_for_night", false)
                        put("door_status", "Loading")
                    }) { filter { neq("id", 0) } }
                toast = "✅ Print Room reset"
            } catch (e: Exception) {
                toast = "❌ ${e.message}"
            } finally { busy = null }
        }
    }

    fun resetPreshift() {
        scope.launch {
            busy = "preshift"
            try {
                BadgerRepo.supabase.postgrest.from("staging_doors")
                    .update(buildJsonObject {
                        put("in_front", null as String?)
                        put("in_back",  null as String?)
                    }) { filter { neq("id", 0) } }
                toast = "✅ PreShift reset"
            } catch (e: Exception) {
                toast = "❌ ${e.message}"
            } finally { busy = null }
        }
    }

    fun resetMovement() {
        scope.launch {
            busy = "movement"
            try {
                BadgerRepo.supabase.postgrest.from("live_movement")
                    .delete { filter { neq("id", 0) } }
                toast = "✅ Live Movement reset"
            } catch (e: Exception) {
                toast = "❌ ${e.message}"
            } finally { busy = null }
        }
    }

    fun resetAll() {
        scope.launch {
            busy = "all"
            try {
                // 1. Printroom (trigger handles re-seeding)
                BadgerRepo.supabase.postgrest.from("printroom_entries")
                    .delete { filter { neq("id", 0) } }
                BadgerRepo.supabase.postgrest.from("loading_doors")
                    .update(buildJsonObject {
                        put("is_done_for_night", false)
                        put("door_status", "Loading")
                    }) { filter { neq("id", 0) } }
                // 2. PreShift
                BadgerRepo.supabase.postgrest.from("staging_doors")
                    .update(buildJsonObject {
                        put("in_front", null as String?)
                        put("in_back",  null as String?)
                    }) { filter { neq("id", 0) } }
                // 3. Movement
                BadgerRepo.supabase.postgrest.from("live_movement")
                    .delete { filter { neq("id", 0) } }

                toast = "✅ Full reset complete"
            } catch (e: Exception) {
                toast = "❌ ${e.message}"
            } finally { busy = null }
        }
    }
    // ──────────────────────────────────────────────────────────────────────────

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Notice
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Shield, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp))
                    Text(
                        "Truck & Tractor databases are always protected.",
                        color = Color(0xFF22C55E), fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }

            // Individual resets
            ResetCard(
                icon = "🖨️",
                title = "Reset Print Room",
                desc = "Clears entries, resets door statuses.\nAuto-seeds Batch 1 (2 rows) + Batch 2 (6 rows).",
                color = Color(0xFFF59E0B),
                loading = busy == "printroom",
                anyBusy = busy != null,
                onReset = { resetPrintroom() }
            )
            ResetCard(
                icon = "📋",
                title = "Reset PreShift",
                desc = "Clears all staging door assignments.",
                color = Color(0xFF3B82F6),
                loading = busy == "preshift",
                anyBusy = busy != null,
                onReset = { resetPreshift() }
            )
            ResetCard(
                icon = "🚚",
                title = "Reset Movement",
                desc = "Clears all live movement data.",
                color = Color(0xFF8B5CF6),
                loading = busy == "movement",
                anyBusy = busy != null,
                onReset = { resetMovement() }
            )

            Spacer(Modifier.height(4.dp))
            Divider(color = Color(0xFF2A2A2A))
            Spacer(Modifier.height(4.dp))

            // Reset All — requires confirmation
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0808)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("💣", fontSize = 20.sp)
                        Column {
                            Text("Reset All", color = Color(0xFFEF4444), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Resets Print Room, PreShift & Movement.\nPrint Room auto-seeds after wipe.",
                                color = Color(0xFF9CA3AF), fontSize = 11.sp, lineHeight = 16.sp
                            )
                        }
                    }

                    if (!confirmAll) {
                        Button(
                            onClick = { confirmAll = true },
                            enabled = busy == null,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7F1D1D),
                                disabledContainerColor = Color(0xFF3A1010)
                            )
                        ) {
                            Text("Reset All…", fontWeight = FontWeight.Bold, color = Color(0xFFFCA5A5))
                        }
                    } else {
                        Text(
                            "Are you sure? This cannot be undone.",
                            color = Color(0xFFFCA5A5), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { confirmAll = false },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                                border = ButtonDefaults.outlinedButtonBorder.copy()
                            ) { Text("Cancel") }

                            Button(
                                onClick = { confirmAll = false; resetAll() },
                                enabled = busy == null,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) {
                                if (busy == "all") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White, strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Yes, Reset All", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Toast
        toast?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = if (msg.startsWith("✅")) Color(0xFF052E16) else Color(0xFF2A0F0F)
            ) {
                Text(msg, color = if (msg.startsWith("✅")) Color(0xFF4ADE80) else Color(0xFFFCA5A5), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ResetCard(
    icon: String,
    title: String,
    desc: String,
    color: Color,
    loading: Boolean,
    anyBusy: Boolean,
    onReset: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(icon, fontSize = 22.sp, modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = Color(0xFF6B7280), fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Button(
                onClick = onReset,
                enabled = !anyBusy,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color.copy(alpha = 0.15f),
                    disabledContainerColor = Color(0xFF1A1A1A),
                    contentColor = color,
                    disabledContentColor = Color(0xFF444444)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
                } else {
                    Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
