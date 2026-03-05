package com.badger.trucks.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.badger.trucks.MainActivity
import com.badger.trucks.service.BadgerService
import com.badger.trucks.service.NotificationPrefsStore

private val Amber    = Color(0xFFF59E0B)
private val DarkCard = Color(0xFF1A1A1A)
private val DarkBorder = Color(0xFF2A2A2A)

data class NotifItem(val key: String, val icon: ImageVector, val label: String, val description: String)

private val EVENT_ITEMS = listOf(
    NotifItem(NotificationPrefsStore.KEY_TRUCK_STATUS, Icons.Default.LocalShipping, "Truck Status Change",   "When a truck changes status in Live Movement"),
    NotifItem(NotificationPrefsStore.KEY_DOOR_STATUS,  Icons.Default.MeetingRoom,   "Door Status Change",    "When a loading door status is updated"),
    NotifItem(NotificationPrefsStore.KEY_CHAT_MENTION, Icons.Default.Chat,          "Chat Mention",          "When someone mentions you in a chat room"),
    NotifItem(NotificationPrefsStore.KEY_PRESHIFT,     Icons.Default.TableChart,    "PreShift Change",       "When a truck is added or moved in PreShift"),
    NotifItem(NotificationPrefsStore.KEY_SYSTEM,       Icons.Default.Campaign,      "System / Admin Alerts", "Important announcements from admins"),
)

private val CHANNEL_ITEMS = listOf(
    NotifItem(NotificationPrefsStore.KEY_CHANNEL_APP, Icons.Default.Notifications,   "Push Notifications",   "Show heads-up alerts on your device"),
    NotifItem(NotificationPrefsStore.KEY_CHANNEL_TTS, Icons.Default.RecordVoiceOver, "Text-to-Speech",       "Announce changes aloud via TTS"),
    NotifItem(NotificationPrefsStore.KEY_HOTWORD,     Icons.Default.Mic,             "`"Badger`" Wake Word", "Say `"Badger`" to issue a voice command"),
    NotifItem(NotificationPrefsStore.KEY_SHOW_PTT,    Icons.Default.Radio,           "Push-to-Talk Button",  "Show PTT button on Live Movement"),
)

@Composable
fun NotificationSettingsScreen() {
    val context  = LocalContext.current
    val activity = context as? MainActivity

    var prefs        by remember { mutableStateOf(NotificationPrefsStore.getAll(context)) }
    var pttAudioMode by remember { mutableStateOf(NotificationPrefsStore.getPttAudioMode(context)) }
    var ttsAudioMode by remember { mutableStateOf(NotificationPrefsStore.getString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT)) }
    var saved        by remember { mutableStateOf(false) }

    var serviceRunning by remember { mutableStateOf(BadgerService.isRunning) }
    var micGranted     by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )}

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

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = Amber, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Notifications & Audio", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }

        Card(colors = CardDefaults.cardColors(containerColor = if (serviceRunning) Color(0xFF0F2A1A) else Color(0xFF2A0F0F)), shape = MaterialTheme.shapes.medium) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (serviceRunning) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null,
                        tint = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    Column {
                        Text(if (serviceRunning) "Service Running" else "Service Stopped", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (serviceRunning) Color(0xFF22C55E) else Color(0xFFEF4444))
                        Text("TTS - PTT - Realtime", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                Button(
                    onClick = { activity?.restartService(); serviceRunning = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceRunning) Color(0xFF1E3A1E) else Amber,
                        contentColor   = if (serviceRunning) Color(0xFF22C55E) else Color.Black),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(if (serviceRunning) "Restart" else "Start", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        if (!micGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A00)), shape = MaterialTheme.shapes.medium) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.MicOff, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Microphone Permission Required", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Amber)
                            Text("PTT and wake word will not work", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                    Button(onClick = { activity?.requestMicPermission() },
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Grant", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        if (saved) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A3A1A)), shape = MaterialTheme.shapes.medium) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings saved", fontSize = 12.sp, color = Color(0xFF22C55E))
                }
            }
        }

        HorizontalDivider(color = DarkBorder)
        SectionLabel("Event Notifications")
        val allOn = EVENT_ITEMS.all { prefs[it.key] != false }
        OutlinedButton(onClick = {
            EVENT_ITEMS.forEach { item ->
                val u = prefs.toMutableMap(); u[item.key] = !allOn
                prefs = u; NotificationPrefsStore.setAll(context, u); saved = true
            }
        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
            border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.4f))
        ) { Text(if (allOn) "All Off" else "All On", fontSize = 12.sp) }
        EVENT_ITEMS.forEach { item -> NotifToggleRow(item, prefs[item.key] != false) { toggle(item.key) } }

        HorizontalDivider(color = DarkBorder)
        SectionLabel("Delivery Channels")
        CHANNEL_ITEMS.forEach { item -> NotifToggleRow(item, prefs[item.key] != false, Color(0xFF3B82F6)) { toggle(item.key) } }

        HorizontalDivider(color = DarkBorder)
        SectionLabel("PTT Incoming Audio")
        Text("How other apps behave when a PTT message plays.", fontSize = 12.sp, color = Color.Gray)
        listOf(
            Triple(NotificationPrefsStore.PTT_AUDIO_FOCUS, "Audio Focus", "Politely pause other apps while PTT plays"),
            Triple(NotificationPrefsStore.PTT_AUDIO_MUTE,  "Mute Others", "Silence other apps during PTT"),
            Triple(NotificationPrefsStore.PTT_AUDIO_LOWER, "Duck Others", "Lower volume of other apps while PTT plays"),
            Triple(NotificationPrefsStore.PTT_AUDIO_OFF,   "Off",         "No audio management"),
        ).forEach { (v, l, d) ->
            AudioModeRow(v, l, d, pttAudioMode == v, Amber) {
                pttAudioMode = v; NotificationPrefsStore.setPttAudioMode(context, v); saved = true
            }
        }

        HorizontalDivider(color = DarkBorder)
        SectionLabel("TTS Announcement Audio")
        Text("How other apps behave when Badger speaks a status update.", fontSize = 12.sp, color = Color.Gray)
        listOf(
            Triple(NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT, "Transient", "Politely pause music while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE, "Exclusive", "Silence other apps while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_DUCK,      "Duck",      "Lower volume of other apps while TTS speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_OFF,       "Off",       "No audio management"),
        ).forEach { (v, l, d) ->
            AudioModeRow(v, l, d, ttsAudioMode == v, Color(0xFF3B82F6)) {
                ttsAudioMode = v
                NotificationPrefsStore.setString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, v)
                saved = true; notifyService()
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AudioModeRow(value: String, label: String, desc: String, selected: Boolean, accent: Color, onSelect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (selected) DarkCard else Color(0xFF111111)),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { onSelect() }) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected = selected, onClick = { onSelect() },
                colors = RadioButtonDefaults.colors(selectedColor = accent, unselectedColor = Color.Gray))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.White else Color.Gray)
                Text(desc, fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 1.sp)
}

@Composable
fun NotifToggleRow(item: NotifItem, checked: Boolean, accentColor: Color = Amber, onToggle: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (checked) DarkCard else Color(0xFF111111)),
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { onToggle() }) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = checked, onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accentColor,
                    uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color(0xFF333333)))
            Icon(item.icon, contentDescription = null, tint = if (checked) accentColor else Color.Gray, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (checked) Color.White else Color.Gray)
                Text(item.description, fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}