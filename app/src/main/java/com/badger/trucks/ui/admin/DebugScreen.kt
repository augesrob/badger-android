package com.badger.trucks.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.service.BadgerService
import com.badger.trucks.service.NotificationPrefsStore
import com.badger.trucks.ui.theme.*
import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DebugScreen() {
    val context  = LocalContext.current
    val activity = context as? MainActivity
    val scope    = rememberCoroutineScope()

    var serviceRunning  by remember { mutableStateOf(BadgerService.isRunning) }
    var micGranted      by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
    )}
    var logs            by remember { mutableStateOf<List<RemoteLogger.LogEntry>>(emptyList()) }
    var sending         by remember { mutableStateOf(false) }
    val listState       = rememberLazyListState()

    // Load remote logging toggle from prefs
    var remoteLogging by remember {
        mutableStateOf(
            NotificationPrefsStore.get(context, NotificationPrefsStore.KEY_REMOTE_LOGGING, false)
        )
    }

    // Poll service state + logs every 2s
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = BadgerService.isRunning
            micGranted = (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
            logs = RemoteLogger.recentLogs()
            delay(2_000)
        }
    }

    // Auto-scroll to bottom when logs update
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Debug", color = Amber500, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)

        // ── Remote Debug Mode Toggle ──────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (remoteLogging) Color(0xFF1A2A0F) else Color(0xFF1A1A1A)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        tint = if (remoteLogging) Color(0xFF22C55E) else MutedText,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            if (remoteLogging) "Remote Debug: ON" else "Remote Debug: OFF",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = if (remoteLogging) Color(0xFF22C55E) else Color.White
                        )
                        Text(
                            if (remoteLogging)
                                "WARN/ERROR logs uploading to Supabase"
                            else
                                "Logs stored locally only — no egress",
                            fontSize = 11.sp,
                            color = MutedText
                        )
                    }
                }
                Switch(
                    checked = remoteLogging,
                    onCheckedChange = { enabled ->
                        remoteLogging = enabled
                        NotificationPrefsStore.set(context, NotificationPrefsStore.KEY_REMOTE_LOGGING, enabled)
                        RemoteLogger.remoteEnabled = enabled
                        RemoteLogger.i("DebugScreen", "Remote logging ${if (enabled) "ENABLED" else "DISABLED"}")
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF22C55E),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFF333333)
                    )
                )
            }
        }

        // Egress warning when on
        if (remoteLogging) {
            Surface(
                color = Color(0xFF2A1A00),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "⚠️ Remote logging is ON — turn off after debugging to save Supabase egress",
                    modifier = Modifier.padding(10.dp),
                    color = Amber500,
                    fontSize = 11.sp
                )
            }
        }

        // ── Service + Mic Status ──────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (serviceRunning) Color(0xFF0F2A1A) else Color(0xFF2A0F0F),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "●",
                        color = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
                        fontSize = 10.sp
                    )
                    Text(
                        if (serviceRunning) "Service Running" else "Service Stopped",
                        color = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Surface(
                color = if (micGranted) Color(0xFF0F1A2A) else Color(0xFF2A1A00),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (micGranted) "Mic OK" else "No Mic",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (micGranted) Color(0xFF3B82F6) else Amber500,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Action Buttons ────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { activity?.restartService(); serviceRunning = false },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceRunning) DarkCard else Amber500,
                    contentColor   = if (serviceRunning) Color(0xFF22C55E) else Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (serviceRunning) "Restart Service" else "Start Service", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (!micGranted) {
                Button(
                    onClick = { activity?.requestMicPermission() },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Grant Mic", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(
                onClick = {
                    sending = true
                    scope.launch {
                        RemoteLogger.w("DebugScreen", "Manual test log — service=${BadgerService.isRunning} mic=$micGranted remote=$remoteLogging")
                        delay(500)
                        sending = false
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF3B82F6)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                enabled = !sending
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Test Log", fontSize = 12.sp)
            }
        }

        if (remoteLogging) {
            Text(
                "Logs also visible at badger.augesrob.net/admin › Mobile Debug",
                color = MutedText, fontSize = 11.sp
            )
        }

        HorizontalDivider(color = Color(0xFF222222))

        // ── Log List ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Local Logs (last 200)", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${logs.size} entries", color = MutedText, fontSize = 10.sp)
        }

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No logs yet", color = MutedText, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(logs) { entry ->
                    val levelColor = when (entry.level) {
                        "E" -> Color(0xFFEF4444)
                        "W" -> Amber500
                        "I" -> Color(0xFF22C55E)
                        else -> MutedText
                    }
                    Surface(color = DarkCard, shape = RoundedCornerShape(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                entry.level,
                                color = levelColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(12.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.tag,
                                    color = levelColor.copy(alpha = 0.8f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    entry.message,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(entry.time, color = MutedText, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
