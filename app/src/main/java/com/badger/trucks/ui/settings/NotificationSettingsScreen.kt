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
    var prefs by remember { mutableStateOf(NotificationPrefsStore.getAll(context)) }
    var saved by remember { mutableStateOf(false) }

    fun notifyService() {
        if (BadgerService.isRunning) {
            val intent = Intent(context, BadgerService::class.java).apply {
                action = BadgerService.ACTION_APPLY_SETTINGS
            }
            context.startService(intent)
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


    fun allEventsOn()  { EVENT_ITEMS.forEach { toggle(it.key) } }

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
        Text(
            "Customize which events trigger alerts on this device.",
            fontSize = 12.sp,
            color = Color.Gray
        )

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

        // Audio Focus section
        SectionLabel("Audio Focus")
        Text(
            "Control how Badger interacts with other audio apps (music, podcasts) when speaking or listening.",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(8.dp))

        val audioFocusMode = remember {
            mutableStateOf(
                NotificationPrefsStore.getString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS,
                    NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT)
            )
        }

        val audioFocusOptions = listOf(
            Triple(NotificationPrefsStore.AUDIO_FOCUS_EXCLUSIVE, "Mute Other Apps",         "Other apps go completely silent while Badger speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_TRANSIENT, "Priority (Recommended)",  "Other apps pause and resume after Badger is done"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_DUCK,      "Lower Volume of Others",  "Other apps drop to ~20% volume while Badger speaks"),
            Triple(NotificationPrefsStore.AUDIO_FOCUS_OFF,       "Off",                     "No audio focus — Badger competes with other audio"),
        )

        audioFocusOptions.forEach { (value, label, desc) ->
            val selected = audioFocusMode.value == value
            Card(
                colors = CardDefaults.cardColors(containerColor = if (selected) DarkCard else Color(0xFF111111)),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        audioFocusMode.value = value
                        NotificationPrefsStore.setString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, value)
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
                            audioFocusMode.value = value
                            NotificationPrefsStore.setString(context, NotificationPrefsStore.KEY_AUDIO_FOCUS, value)
                            saved = true
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF8B5CF6))
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
