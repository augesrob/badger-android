package com.badger.trucks.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.profile.ProfileScreen
import com.badger.trucks.ui.shiftsetup.SubScreenShell
import com.badger.trucks.ui.theme.*

private enum class SettingsSub {
    Profile, Notifications,
    // Admin-only
    Users, Statuses, GlobalMsg, Backup, ApiMonitor, Debug
}

private val SETTINGS_BY_ROLE = mapOf(
    "admin"       to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.Users, SettingsSub.Statuses, SettingsSub.GlobalMsg, SettingsSub.Backup, SettingsSub.ApiMonitor, SettingsSub.Debug),
    "print_room"  to listOf(SettingsSub.Profile, SettingsSub.Notifications),
    "truck_mover" to listOf(SettingsSub.Profile, SettingsSub.Notifications),
    "trainee"     to listOf(SettingsSub.Profile, SettingsSub.Notifications),
    "driver"      to listOf(SettingsSub.Profile, SettingsSub.Notifications),
)

@Composable
fun SettingsScreen(profile: UserProfile, resetCounter: Int = 0) {
    var activeSub by remember { mutableStateOf<SettingsSub?>(null) }

    // Pop back to root menu when nav bar tab is re-tapped
    LaunchedEffect(resetCounter) { if (resetCounter > 0) activeSub = null }

    BackHandler(enabled = activeSub != null) { activeSub = null }

    AnimatedContent(
        targetState = activeSub,
        transitionSpec = {
            if (targetState != null)
                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            else
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
        },
        label = "settings_sub",
        modifier = Modifier.fillMaxSize().background(DarkBg)
    ) { sub ->
        when (sub) {
            null                     -> SettingsMenu(profile, onSelect = { activeSub = it })
            SettingsSub.Profile       -> SubScreenShell("👤 My Profile",       Amber500, { activeSub = null }) { ProfileScreen(profile) }
            SettingsSub.Notifications -> SubScreenShell("🔔 Notifications",    Purple500, { activeSub = null }) { NotificationSettingsScreen() }
            SettingsSub.Users         -> SubScreenShell("👥 Users",            Amber500, { activeSub = null }) { UsersScreen(profile) }
            SettingsSub.Statuses      -> SubScreenShell("🎨 Status Values",    Blue500,  { activeSub = null }) { StatusValuesScreen() }
            SettingsSub.GlobalMsg     -> SubScreenShell("🌐 Global Message",   Green500, { activeSub = null }) { GlobalMessagesScreen(profile) }
            SettingsSub.Backup        -> SubScreenShell("💾 Backup",           Purple500, { activeSub = null }) { BackupScreen() }
            SettingsSub.ApiMonitor    -> SubScreenShell("🔌 API Monitor",      Color(0xFF06B6D4), { activeSub = null }) { ApiMonitorScreen() }
            SettingsSub.Debug         -> SubScreenShell("🐛 Debug Logs",       Red500,   { activeSub = null }) { com.badger.trucks.ui.admin.DebugScreen() }
        }
    }
}

@Composable
private fun SettingsMenu(profile: UserProfile, onSelect: (SettingsSub) -> Unit) {
    val items = SETTINGS_BY_ROLE[profile.role] ?: listOf(SettingsSub.Profile, SettingsSub.Notifications)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        SectionLabel("Settings")
        Spacer(Modifier.height(4.dp))

        // User & account items first
        val userItems  = items.filter { it in listOf(SettingsSub.Profile, SettingsSub.Notifications) }
        val adminItems = items.filter { it !in listOf(SettingsSub.Profile, SettingsSub.Notifications) }

        userItems.forEach { sub ->
            val d = settingsDef(sub)
            SettingsMenuItem(icon = d.icon, label = d.label, description = d.sub, color = d.color, onClick = { onSelect(sub) })
        }

        if (adminItems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("Admin")
            Spacer(Modifier.height(4.dp))
            adminItems.forEach { sub ->
                val d = settingsDef(sub)
                SettingsMenuItem(icon = d.icon, label = d.label, description = d.sub, color = d.color, onClick = { onSelect(sub) })
            }
        }
    }
}

private data class SDef(val icon: String, val label: String, val sub: String, val color: Color)

private fun settingsDef(s: SettingsSub): SDef = when (s) {
    SettingsSub.Profile      -> SDef("👤", "My Profile",          "Avatar, display name & account",        Amber500)
    SettingsSub.Notifications -> SDef("🔔", "Notifications",      "Alerts, PTT & audio settings",          Purple500)
    SettingsSub.Users         -> SDef("👥", "Users",              "Manage roles & accounts",               Amber500)
    SettingsSub.Statuses      -> SDef("🎨", "Status Values",      "Door, dock & truck statuses",           Blue500)
    SettingsSub.GlobalMsg     -> SDef("🌐", "Global Message",     "Broadcast to all users",                Green500)
    SettingsSub.Backup        -> SDef("💾", "Backup",             "Database backup & restore",             Purple500)
    SettingsSub.ApiMonitor    -> SDef("🔌", "API Monitor",        "Live debug & event log",                Color(0xFF06B6D4))
    SettingsSub.Debug         -> SDef("🐛", "Debug Logs",         "App diagnostics & logs",               Red500)
}

@Composable
private fun SettingsMenuItem(icon: String, label: String, description: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(DarkCard, RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = ripple()) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = LightText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = MutedText, letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun StubContent(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = MutedText, fontSize = 13.sp)
    }
}
