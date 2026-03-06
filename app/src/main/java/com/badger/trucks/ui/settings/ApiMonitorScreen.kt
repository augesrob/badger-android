package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.badger.trucks.service.BadgerService
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

private data class PingResult(
    val table: String,
    val label: String,
    val emoji: String,
    val ms: Long?,
    val rowCount: Int?,
    val error: String?,
)

private val TABLES = listOf(
    Triple("live_movement",  "Live Movement",   "🚛"),
    Triple("loading_doors",  "Loading Doors",   "🚪"),
    Triple("status_values",  "Status Values",   "🎨"),
    Triple("preshift",       "PreShift",        "📋"),
    Triple("profiles",       "Profiles",        "👥"),
    Triple("debug_logs",     "Debug Logs",      "🐛"),
    Triple("backup_log",     "Backup Log",      "💾"),
)

@Composable
fun ApiMonitorScreen() {
    val scope = rememberCoroutineScope()

    var results        by remember { mutableStateOf<List<PingResult>>(emptyList()) }
    var pinging        by remember { mutableStateOf(false) }
    var serviceRunning by remember { mutableStateOf(BadgerService.isRunning) }
    var lastPinged     by remember { mutableStateOf<String?>(null) }

    fun pingAll() {
        scope.launch {
            pinging = true
            serviceRunning = BadgerService.isRunning
            val res = mutableListOf<PingResult>()
            TABLES.forEach { (table, label, emoji) ->
                var rowCount: Int? = null
                var errorMsg: String? = null
                val ms = measureTimeMillis {
                    try {
                        val rows = BadgerRepo.supabase.postgrest[table]
                            .select()
                            .decodeList<kotlinx.serialization.json.JsonObject>()
                        rowCount = rows.size
                    } catch (e: Exception) {
                        errorMsg = e.message?.take(80)
                    }
                }
                res.add(PingResult(table, label, emoji, if (errorMsg == null) ms else null, rowCount, errorMsg))
            }
            results = res
            lastPinged = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a"))
            pinging = false
        }
    }

    LaunchedEffect(Unit) { pingAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header row
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("API Monitor", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                lastPinged?.let { Text("Last checked: $it", color = Color.Gray, fontSize = 11.sp) }
            }
            Button(
                onClick = { pingAll() }, enabled = !pinging,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4), contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (pinging) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (pinging) "Pinging…" else "Ping All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Service status
        Card(colors = CardDefaults.cardColors(
            containerColor = if (serviceRunning) Color(0xFF0F2A1A) else Color(0xFF2A0F0F)),
            shape = RoundedCornerShape(10.dp)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(10.dp).background(
                    if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
                Column {
                    Text("Badger Service", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(if (serviceRunning) "Running — Realtime active" else "Stopped",
                        color = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444), fontSize = 11.sp)
                }
            }
        }

        if (results.isEmpty() && pinging) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF06B6D4))
            }
        } else {
            results.forEach { r -> PingRow(r) }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PingRow(r: PingResult) {
    val ok = r.error == null
    val speedColor = when {
        r.ms == null -> Color(0xFFEF4444)
        r.ms < 300   -> Color(0xFF22C55E)
        r.ms < 800   -> Color(0xFFF59E0B)
        else         -> Color(0xFFEF4444)
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.emoji, fontSize = 18.sp, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(r.table, color = Color.Gray, fontSize = 10.sp)
                r.error?.let { Text(it, color = Color(0xFFEF4444), fontSize = 10.sp) }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (r.ms != null) Text("${r.ms}ms", color = speedColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                else Text("ERR", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                r.rowCount?.let { Text("$it rows", color = Color.Gray, fontSize = 10.sp) }
            }
            Box(modifier = Modifier.size(8.dp).background(
                if (ok) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
        }
    }
}
