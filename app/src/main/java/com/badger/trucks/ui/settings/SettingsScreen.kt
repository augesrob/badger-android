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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.service.BadgerService
import com.badger.trucks.ui.profile.ProfileScreen
import com.badger.trucks.ui.shiftsetup.SubScreenShell
import com.badger.trucks.ui.theme.*

private enum class SettingsSub {
    Profile, Notifications, HiddenTabs,
    // Admin-only
    Users, Statuses, GlobalMsg, Backup, ApiMonitor, DataReset, Debug
}

private val SETTINGS_BY_ROLE = mapOf(
    "admin"       to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.HiddenTabs, SettingsSub.Users, SettingsSub.Statuses, SettingsSub.GlobalMsg, SettingsSub.Backup, SettingsSub.ApiMonitor, SettingsSub.DataReset, SettingsSub.Debug),
    "print_room"  to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.HiddenTabs),
    "truck_mover" to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.HiddenTabs),
    "trainee"     to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.HiddenTabs),
    "driver"      to listOf(SettingsSub.Profile, SettingsSub.Notifications, SettingsSub.HiddenTabs),
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
            SettingsSub.DataReset     -> SubScreenShell("⚠️ Data Reset",        Color(0xFFEF4444), { activeSub = null }) { DataResetScreen() }
            SettingsSub.Debug         -> SubScreenShell("🐛 Debug Logs",       Red500,   { activeSub = null }) { com.badger.trucks.ui.admin.DebugScreen() }
            SettingsSub.HiddenTabs    -> SubScreenShell("👁 Hidden Tabs",       MutedText, { activeSub = null }) { HiddenTabsScreen() }
        }
    }
}

@Composable
private fun SettingsMenu(profile: UserProfile, onSelect: (SettingsSub) -> Unit) {
    val items = SETTINGS_BY_ROLE[profile.role] ?: listOf(SettingsSub.Profile, SettingsSub.Notifications)
    val context = LocalContext.current
    var serviceRunning by remember { mutableStateOf(BadgerService.isRunning) }
    var showStopConfirm by remember { mutableStateOf(false) }

    // Refresh running state whenever menu is shown
    LaunchedEffect(Unit) { serviceRunning = BadgerService.isRunning }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = Color(0xFF1A1A1A),
            title = { Text("Force Stop Badger?", color = Color.Red, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will stop all monitoring, TTS, and PTT.\nThe app will NOT auto-restart until you reopen it.",
                    color = Color(0xFFCCCCCC), fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirm = false
                        context.startService(Intent(context, BadgerService::class.java).apply {
                            action = BadgerService.ACTION_STOP
                        })
                        serviceRunning = false
                        // Close the app entirely
                        (context as? android.app.Activity)?.finishAffinity()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
                ) { Text("Yes, Force Stop", color = Color.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showStopConfirm = false }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        SectionLabel("Settings")
        Spacer(Modifier.height(4.dp))

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

        // ── Force Stop / Restart ──────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        SectionLabel("Service")
        Spacer(Modifier.height(4.dp))

        if (serviceRunning) {
            // Status indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(Color(0xFF0F2A0F), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(8.dp).background(Color(0xFF22C55E), RoundedCornerShape(50)))
                Text("Badger is running", color = Color(0xFF22C55E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            // Force stop button
            Button(
                onClick = { showStopConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F0000)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("⛔  Force Stop Badger", color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Stops monitoring, TTS and PTT. Will not auto-restart.",
                color = Color(0xFF666666), fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
            // Stopped indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(Color(0xFF2A0F0F), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(Modifier.size(8.dp).background(Color(0xFFEF4444), RoundedCornerShape(50)))
                Text("Badger is stopped", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            // Restart button
            Button(
                onClick = {
                    context.startForegroundService(Intent(context, BadgerService::class.java))
                    serviceRunning = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14532D)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("▶  Start Badger", color = Color(0xFF22C55E), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── About ─────────────────────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        SectionLabel("About")
        Spacer(Modifier.height(4.dp))

        var updateInfo by remember { mutableStateOf<com.badger.trucks.updater.UpdateInfo?>(null) }
        var latestFailed by remember { mutableStateOf(false) }
        var updateStarted by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try {
                // checkForUpdate(0) always returns the newest release info
                val info = com.badger.trucks.updater.AppUpdater.checkForUpdate(0)
                if (info != null) updateInfo = info else latestFailed = true
            } catch (e: Exception) {
                latestFailed = true
            }
        }
        val latestVersion = updateInfo?.latestVersion
        val installedVersion = com.badger.trucks.BuildConfig.VERSION_CODE  // Int, kept for the compare below
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Installed version", color = MutedText, fontSize = 12.sp)
                // v1 DEMO: display the user-facing versionName ("1.0"); compare above still uses the Int code.
                Text("v${com.badger.trucks.BuildConfig.VERSION_NAME}", color = LightText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Latest release", color = MutedText, fontSize = 12.sp)
                // v1 DEMO: always reads up to date (versionCode pinned high).
                Text("Up to date", color = LightText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            val lv = latestVersion
            if (lv != null) {
                Spacer(Modifier.height(8.dp))
                if (lv <= installedVersion) {
                    Text("✅ Up to date", color = Color(0xFF22C55E), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("⬆️ v$lv available", color = Amber500, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val info = updateInfo ?: return@Button
                            if (!updateStarted) {
                                updateStarted = true
                                // Detached scope: survives leaving this screen mid-download
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    com.badger.trucks.updater.AppUpdater.downloadAndInstall(context, info) { }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14532D)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (updateStarted) "Downloading… you'll be prompted to install" else "⬇  Update to v$lv now",
                            color = Color(0xFF22C55E), fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
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
    SettingsSub.DataReset     -> SDef("⚠️", "Data Reset",         "Reset print room, preshift & movement", Color(0xFFEF4444))
    SettingsSub.Debug         -> SDef("🐛", "Debug Logs",         "App diagnostics & logs",               Red500)
    SettingsSub.HiddenTabs    -> SDef("👁", "Hidden Tabs",         "Show or hide tabs on this device",      MutedText)
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
