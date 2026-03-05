package com.badger.trucks.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.service.BadgerService
import com.badger.trucks.service.NotificationPrefsStore

private val Amber = Color(0xFFF59E0B)
private val DarkCard = Color(0xFF1A1A1A)
private val DarkBorder = Color(0xFF2A2A2A)

data class NotifItem(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val description: String
)

private val EVENT_ITEMS = listOf(
    NotifItem(
        NotificationPrefsStore.KEY_TRUCK_STATUS,
        Icons.Default.LocalShipping,
        "Truck Status Change",
        "When a truck changes status in Live Movement"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_DOOR_STATUS,
        Icons.Default.MeetingRoom,
        "Door Status Change",
        "When a loading door status is updated in Print Room"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_CHAT_MENTION,
        Icons.Default.Chat,
        "Chat Mention",
        "When someone mentions you in a chat room"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_PRESHIFT,
        Icons.Default.TableChart,
        "PreShift Change",
        "When a truck is added or moved in PreShift staging"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_SYSTEM,
        Icons.Default.Campaign,
        "System / Admin Alerts",
        "Important announcements from admins"
    ),
)

private val CHANNEL_ITEMS = listOf(
    NotifItem(
        NotificationPrefsStore.KEY_CHANNEL_APP,
        Icons.Default.Notifications,
        "Push Notifications",
        "Show heads-up alerts on your device"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_CHANNEL_TTS,
        Icons.Default.RecordVoiceOver,
        "Text-to-Speech",
        "Announce changes aloud via TTS"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_HOTWORD,
        Icons.Default.Mic,
        "\"Badger\" Wake Word",
        "Say \"Badger\" to issue a voice command hands-free"
    ),
)

private val UI_ITEMS = listOf(
    NotifItem(
        NotificationPrefsStore.KEY_SHOW_PTT,
        Icons.Default.Radio,
        "Push-to-Talk Button",
        "Show the 📻 PTT radio button on Live Movement"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_SHOW_MIC,
        Icons.Default.Mic,
        "Voice Command Button",
        "Show the 🎙 mic button for voice commands"
    ),
    NotifItem(
        NotificationPrefsStore.KEY_SHOW_FIXALL,
        Icons.Default.Build,
        "Fix All Button",
        "Show the 🔧 wrench button to restart the service"
    ),
)

@Composable
fun NotificationSettingsScreen() {
    val context = LocalContext.current
    val activity = context as? MainActivity

    var prefs        by remember { mutableStateOf(NotificationPrefsStore.getAll(context)) }
    var saved        by remember { mutableStateOf(false) }
    var pttAudioMode by remember { mutableStateOf(NotificationPrefsStore.getPttAudioMode(context)) }
    var ttsAudioMode by remember { mutableStateOf(NotificationPrefsStore.getString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT)) }

    // Live service + permission state
    var serviceRunning by remember { mutableStateOf(BadgerService.isRunning) }
    var micGranted     by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    ) }

    // Refresh on recompose (user may have just granted permission)
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = BadgerService.isRunning
            micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            kotlinx.coroutines.delay(2_000)
        }
    }

    fun notifyService() {
        if (BadgerService.isRunning) {
            context.startService(Intent(context, BadgerService::class.java).apply {
                action = BadgerService.ACTION_APPLY_SETTINGS
            })
        }
    }

    fun toggle(key: String) {
        val updated = prefs.toMutableMap()
        updated[key] = !(updated[key] ?: true)
        prefs = updated
        NotificationPrefsStore.setAll(context, updated)
        saved = true
        notifyService()
    }

    fun allEventsOn() { EVENT_ITEMS.forEach { toggle(it.key) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = Amber, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Notification Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text("Customize which events trigger alerts on this device.", fontSize = 12.sp, color = Color.Gray)

        // ── Service status card ───────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (serviceRunning) Color(0xFF0F2A1A) else Color(0xFF2A0F0F)
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (serviceRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            if (serviceRunning) "Service Running" else "Service Stopped",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444)
                        )
                        Text("TTS · PTT · Realtime sync", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Button(
                    onClick = {
                        activity?.restartService()
                        serviceRunning = false  // will flip back in 2s poll
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceRunning) Color(0xFF1E3A1E) else Amber,
                        contentColor   = if (serviceRunning) Color(0xFF22C55E) else Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(if (serviceRunning) "Restart" else "Start", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Mic permission card ───────────────────────────────────────────
        if (!micGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A00)),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.MicOff, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Microphone Permission Required", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Amber)
                            Text("PTT and wake word won't work without it", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    Button(
                        onClick = { activity?.requestMicPermission() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        if (saved) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A1A)),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Settings saved automatically", fontSize = 12.sp, color = Color(0xFF22C55E))
                }
            }
        }

        // All On / All Off
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val allOn = prefs.filterKeys { k -> EVENT_ITEMS.any { it.key == k } }.all { it.value }
                    val updated = prefs.toMutableMap()
                    EVENT_ITEMS.forEach { updated[it.key] = !allOn }
                    prefs = updated
                    NotificationPrefsStore.setAll(context, updated)
                    saved = true
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.4f))
            ) {
                val allOn = EVENT_ITEMS.all { prefs[it.key] != false }
                Text(if (allOn) "All Off" else "All On", fontSize = 12.sp)
            }
        }

        // Event toggles section
        SectionLabel("Event Notifications")
        EVENT_ITEMS.forEach { item ->
            NotifToggleRow(
                item = item,
                checked = prefs[item.key] != false,
                onToggle = { toggle(item.key) }
            )
        }

        Divider(color = DarkBorder, thickness = 1.dp)

        // Channel section
        SectionLabel("Delivery Channels")
        CHANNEL_ITEMS.forEach { item ->
            NotifToggleRow(
                item = item,
                checked = prefs[item.key] != false,
                onToggle = { toggle(item.key) },
                accentColor = Color(0xFF3B82F6)
            )
        }

        Divider(color = DarkBorder, thickness = 1.dp)

        // UI Buttons section
        SectionLabel("Live Movement Buttons")
        Text(
            "Choose which action buttons appear on the Live Movement screen.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        UI_ITEMS.forEach { item ->
            NotifToggleRow(
                item = item,
                checked = prefs[item.key] != false,
                onToggle = { toggle(item.key) },
                accentColor = Color(0xFF8B5CF6)
            )
        }

        Divider(color = DarkBorder, thickness = 1.dp)

        // PTT Audio Focus section
        SectionLabel("PTT Incoming Audio")
        Text(
            "Controls how other apps behave when a PTT message plays.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(4.dp))

        val pttOptions = listOf(
            Triple(NotificationPrefsStore.PTT_AUDIO_FOCUS,    "Audio Focus",            "Politely pause other apps while PTT plays"),
            Triple(NotificationPrefsStore.PTT_AUDIO_MUTE,     "Mute Other Apps",        "Silence other apps completely during PTT"),
            Triple(NotificationPrefsStore.PTT_AUDIO_PRIORITY, "Priority",               "Exclusive focus — strongest interrupt"),
            Triple(NotificationPrefsStore.PTT_AUDIO_LOWER,    "Lower Volume of Others", "Duck other audio down while PTT plays"),
            Triple(NotificationPrefsStore.PTT_AUDIO_OFF,      "Off",                    "No audio management — play over everything"),
        )
        pttOptions.forEach { (value, label, desc) ->
            val selected = pttAudioMode == value
            Card(
                colors = CardDefaults.cardColors(containerColor = if (selected) DarkCard else Color(0xFF111111)),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        pttAudioMode = value
                        NotificationPrefsStore.setPttAudioMode(context, value)
                        saved = true
                    }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = {
                            pttAudioMode = value
                            NotificationPrefsStore.setPttAudioMode(context, value)
                            saved = true
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Amber,
                            unselectedColor = Color.Gray
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (selected) Color.White else Color.Gray)
                        Text(desc, fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Divider(color = DarkBorder, thickness = 1.dp)

        // TTS Audio Focus section
        SectionLabel("TTS Announcement Audio")
        Text("Controls how other apps behave when Badger speaks a status update.", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        val ttsOptions = listOf(
            Triple(NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT,  "Transient Focus",  "Politely pause music while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE,  "Exclusive",        "Silence other apps completely while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_DUCK,       "Duck",             "Lower volume of other apps while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_OFF,        "Off",              "No audio management — speak over everything"),
        )
        ttsOptions.forEach { (value, label, desc) ->
            val selected = ttsAudioMode == value
            Card(
                colors = CardDefaults.cardColors(containerColor = if (selected) DarkCard else Color(0xFF111111)),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().clickable {
                    ttsAudioMode = value
                    NotificationPrefsStore.setString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, value)
                    saved = true
                    notifyService()
                }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(
                        selected = selected, onClick = {
                            ttsAudioMode = value
                            NotificationPrefsStore.setString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, value)
                            saved = true
                            notifyService()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF3B82F6), unselectedColor = Color.Gray)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.White else Color.Gray)
                        Text(desc, fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Gray,
        letterSpacing = 1.sp
    )
}

@Composable
private fun NotifToggleRow(
    item: NotifItem,
    checked: Boolean,
    onToggle: () -> Unit,
    accentColor: Color = Amber
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) DarkCard else Color(0xFF111111)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Switch(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
            Icon(
                item.icon,
                contentDescription = null,
                tint = if (checked) accentColor else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (checked) Color.White else Color.Gray
                )
                Text(
                    item.description,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
