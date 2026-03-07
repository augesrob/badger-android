package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.BadgerRepo
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
private data class BackupLog(
    val id: Int = 0,
    @SerialName("last_backup_at")       val lastBackupAt: String?      = null,
    @SerialName("last_backup_filename") val lastBackupFilename: String? = null,
    @SerialName("last_backup_tables")   val lastBackupTables: Int?      = null,
    @SerialName("last_backup_rows")     val lastBackupRows: Int?        = null,
    @SerialName("last_backup_size_kb")  val lastBackupSizeKb: Double?   = null,
    @SerialName("last_backup_status")   val lastBackupStatus: String?   = null,
    @SerialName("failed_tables")        val failedTables: List<String>? = null,
)

private val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a").withZone(ZoneId.systemDefault())
private const val VERCEL_URL = "https://badger.augesrob.net"

@Composable
fun BackupScreen() {
    val scope = rememberCoroutineScope()

    var log        by remember { mutableStateOf<BackupLog?>(null) }
    var loading    by remember { mutableStateOf(true) }
    var running    by remember { mutableStateOf(false) }
    var error      by remember { mutableStateOf<String?>(null) }
    var toast      by remember { mutableStateOf<String?>(null) }
    var webhookUrl by remember { mutableStateOf("") }
    var showHook   by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                log = BadgerRepo.supabase.postgrest["backup_log"]
                    .select { filter { eq("id", 1) } }
                    .decodeSingleOrNull<BackupLog>()
            } catch (e: Exception) {
                error = "Failed to load: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun triggerBackup() {
        if (webhookUrl.isBlank()) { toast = "Enter a Discord webhook URL first"; return }
        scope.launch {
            running = true; error = null
            try {
                val session = BadgerRepo.supabase.auth.currentSessionOrNull()
                    ?: throw Exception("Not authenticated")
                val token = session.accessToken
                val body = buildJsonObject { put("webhook_url", webhookUrl) }.toString()

                val response = withContext(Dispatchers.IO) {
                    OkHttpClient().newCall(
                        Request.Builder()
                            .url("$VERCEL_URL/api/admin/backup")
                            .addHeader("Authorization", "Bearer $token")
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                }
                if (response.isSuccessful) {
                    toast = "✅ Backup started — check Discord"
                    kotlinx.coroutines.delay(4000)
                    load()
                } else {
                    error = "Backup failed (${response.code}): ${response.body?.string()}"
                }
                response.close()
            } catch (e: Exception) {
                error = "Error: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(4000); toast = null }
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
                        Spacer(Modifier.weight(1f))
                        if (!loading) {
                            IconButton(onClick = { load() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp),
                            color = Color(0xFF8B5CF6), strokeWidth = 2.dp)
                    } else if (log?.lastBackupAt != null) {
                        val ts = runCatching {
                            fmt.format(Instant.parse(log!!.lastBackupAt!!))
                        }.getOrElse { log!!.lastBackupAt!! }
                        val statusColor = when (log?.lastBackupStatus) {
                            "success" -> Color(0xFF22C55E); "partial" -> Color(0xFFF59E0B)
                            "failed"  -> Color(0xFFEF4444); else -> Color.Gray
                        }
                        val statusEmoji = when (log?.lastBackupStatus) {
                            "success" -> "✅"; "partial" -> "⚠️"; "failed" -> "❌"; else -> "❓"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(statusEmoji, fontSize = 14.sp)
                            Text(log?.lastBackupStatus?.uppercase() ?: "Unknown",
                                color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(ts, color = Color.Gray, fontSize = 12.sp)
                        log?.lastBackupFilename?.let {
                            Text(it, color = Color(0xFF8B5CF6), fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            log?.lastBackupTables?.let { StatChip("📦", "$it tables") }
                            log?.lastBackupRows?.let { StatChip("🗃️", "%,d".format(it) + " rows") }
                            log?.lastBackupSizeKb?.let { StatChip("💾", "%.1f KB".format(it)) }
                        }
                        log?.failedTables?.takeIf { it.isNotEmpty() }?.let { failed ->
                            Text("Failed: ${failed.joinToString(", ")}",
                                color = Color(0xFFEF4444), fontSize = 11.sp)
                        }
                    } else {
                        Text("No backups recorded yet.", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            // Trigger backup card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Backup, null, tint = Color(0xFF3B82F6), modifier = Modifier.size(18.dp))
                        Text("Run Backup", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    // Webhook URL input
                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { webhookUrl = it },
                        label = { Text("Discord Webhook URL", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://discord.com/api/webhooks/...", fontSize = 11.sp, color = Color.DarkGray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF333333),
                            focusedLabelColor = Color(0xFF3B82F6),
                            unfocusedLabelColor = Color.Gray,
                            cursorColor = Color(0xFF3B82F6),
                        ),
                    )

                    Text(
                        "Backup will be sent to this Discord webhook as a JSON file attachment.",
                        color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp
                    )

                    Button(
                        onClick = { triggerBackup() },
                        enabled = !running && webhookUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3B82F6),
                            disabledContainerColor = Color(0xFF1E3A5F),
                        )
                    ) {
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Running backup…", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Run Backup Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0F0F)),
                    shape = RoundedCornerShape(8.dp)) {
                    Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }
        }

        toast?.let { msg ->
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = Color(0xFF0F1A2A)) {
                Text(msg, color = Color(0xFF93C5FD), fontSize = 13.sp)
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
