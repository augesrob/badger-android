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
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
private data class BackupConfig(
    @SerialName("last_backup_at")       val lastBackupAt: String?       = null,
    @SerialName("last_backup_filename") val lastBackupFilename: String?  = null,
    @SerialName("last_backup_tables")   val lastBackupTables: Int?       = null,
    @SerialName("last_backup_rows")     val lastBackupRows: Int?         = null,
    @SerialName("last_backup_size_kb")  val lastBackupSizeKb: Int?       = null,
    @SerialName("last_backup_status")   val lastBackupStatus: String?    = null,
    @SerialName("failed_tables")        val failedTables: List<String>?  = null,
)

private val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a").withZone(ZoneId.systemDefault())

@Composable
fun BackupScreen() {
    val scope = rememberCoroutineScope()

    var config  by remember { mutableStateOf<BackupConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var error   by remember { mutableStateOf<String?>(null) }
    var toast   by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            try {
                config = BadgerRepo.supabase.postgrest["system_config"]
                    .select()
                    .decodeSingleOrNull<BackupConfig>()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun triggerBackup() {
        scope.launch {
            running = true; error = null
            try {
                BadgerRepo.supabase.functions.invoke("backup-database")
                toast = "✅ Backup triggered successfully"
                load()
            } catch (e: Exception) {
                error = "Backup failed: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(3000); toast = null }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Last backup card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudDone, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(20.dp))
                        Text("Last Backup", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp),
                            color = Color(0xFF8B5CF6), strokeWidth = 2.dp)
                    } else if (config?.lastBackupAt != null) {
                        val ts = runCatching {
                            fmt.format(Instant.parse(config!!.lastBackupAt!!))
                        }.getOrElse { config!!.lastBackupAt!! }

                        val statusColor = when (config?.lastBackupStatus) {
                            "success" -> Color(0xFF22C55E)
                            "partial" -> Color(0xFFF59E0B)
                            else      -> Color.Gray
                        }
                        val statusEmoji = when (config?.lastBackupStatus) {
                            "success" -> "✅"; "partial" -> "⚠️"; else -> "❓"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(statusEmoji, fontSize = 14.sp)
                            Text(config?.lastBackupStatus?.uppercase() ?: "Unknown",
                                color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(ts, color = Color.Gray, fontSize = 12.sp)
                        config?.lastBackupFilename?.let {
                            Text(it, color = Color(0xFF8B5CF6), fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            config?.lastBackupTables?.let { StatChip("📦", "$it tables") }
                            config?.lastBackupRows?.let { StatChip("🗃️", "%,d".format(it) + " rows") }
                            config?.lastBackupSizeKb?.let { StatChip("💾", "$it KB") }
                        }
                        config?.failedTables?.takeIf { it.isNotEmpty() }?.let { failed ->
                            Text("Failed: ${failed.joinToString(", ")}",
                                color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    } else {
                        Text("No backups recorded yet.", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            // Trigger button
            Button(
                onClick = { triggerBackup() },
                enabled = !running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B5CF6), contentColor = Color.White,
                    disabledContainerColor = Color(0xFF3B2F6F)
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp),
                        color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running backup…", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Run Backup Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0F0F)),
                    shape = RoundedCornerShape(8.dp)) {
                    Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1A2A)),
                shape = RoundedCornerShape(10.dp)) {
                Row(modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF3B82F6),
                        modifier = Modifier.size(16.dp).padding(top = 1.dp))
                    Text("Backups are sent to the configured Discord webhook as a JSON file. " +
                        "They run automatically each week via Vercel cron.",
                        color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        toast?.let { msg ->
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = Color(0xFF1A3A1A)) {
                Text(msg, color = Color(0xFF22C55E), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatChip(icon: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(icon, fontSize = 12.sp)
        Text(label, color = Color.LightGray, fontSize = 11.sp)
    }
}
