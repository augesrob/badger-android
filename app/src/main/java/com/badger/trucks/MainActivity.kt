package com.badger.trucks

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.chat.ChatScreen
import com.badger.trucks.ui.login.LoginScreen
import com.badger.trucks.ui.movement.MovementScreen
import com.badger.trucks.ui.settings.SettingsScreen
import com.badger.trucks.ui.shiftsetup.ShiftSetupScreen
import com.badger.trucks.ui.theme.*
import com.badger.trucks.updater.AppUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private var updateCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BadgerTheme { BadgerAccessApp() } }
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
        updateCheckJob?.cancel()
        updateCheckJob = lifecycleScope.launch {
            while (isActive) { delay(30 * 60 * 1000L); checkForUpdate() }
        }
    }

    override fun onPause() {
        super.onPause()
        updateCheckJob?.cancel()
    }

    private fun checkForUpdate() {
        val ver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionCode
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        lifecycleScope.launch {
            val update = AppUpdater.checkForUpdate(ver)
            if (update != null) pendingUpdate = update
        }
    }

    companion object {
        var pendingUpdate: com.badger.trucks.updater.UpdateInfo? by mutableStateOf(null)
    }
}

// ── App root ──────────────────────────────────────────────────────────────────

@Composable
fun BadgerAccessApp() {
    val authState by AuthManager.state.collectAsState()
    when (authState) {
        is AuthManager.AuthState.Loading   -> SplashScreen()
        is AuthManager.AuthState.LoggedOut -> LoginScreen()
        is AuthManager.AuthState.LoggedIn  -> BadgerAccessMain((authState as AuthManager.AuthState.LoggedIn).profile)
    }
}

@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(DarkBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("🦡", fontSize = 60.sp)
            Text("Badger Access", color = Amber500, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            CircularProgressIndicator(color = Amber500, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Tab definition ────────────────────────────────────────────────────────────

enum class Tab(val label: String, val emoji: String, val requiredPage: String, val isLive: Boolean = false) {
    Shift   ("Shift Setup", "🖨️", "printroom"),
    Live    ("Live",        "🚚", "movement",       isLive = true),
    Chat    ("Chat",        "💬", "chat"),
    Settings("Settings",   "⚙️", "notifications"),
}

// ── Main shell ────────────────────────────────────────────────────────────────

@Composable
fun BadgerAccessMain(profile: UserProfile) {
    val visibleTabs = remember(profile.role) { Tab.entries.filter { AuthManager.canAccess(it.requiredPage) } }
    val startTab    = if (Tab.Live in visibleTabs) Tab.Live else visibleTabs.first()

    var currentTab        by remember { mutableStateOf(startTab) }
    var showUpdateBanner  by remember { mutableStateOf(MainActivity.pendingUpdate != null) }

    // If role changes and current tab is no longer accessible, reset to first available
    LaunchedEffect(profile.role) {
        if (currentTab !in visibleTabs) {
            currentTab = visibleTabs.firstOrNull() ?: Tab.Live
        }
    }

    val updateInfo         = MainActivity.pendingUpdate
    val scope             = rememberCoroutineScope()
    val context           = LocalContext.current

    // ── Role / permissions change dialog ──────────────────────────────────
    var roleChangeDialog  by remember { mutableStateOf<AuthManager.ProfileEvent?>(null) }

    LaunchedEffect(Unit) {
        AuthManager.profileEvents.collect { event ->
            roleChangeDialog = event
        }
    }

    // Show dialog when role/permissions changed
    roleChangeDialog?.let { event ->
        val (title, message) = when (event) {
            is AuthManager.ProfileEvent.RoleChanged ->
                "🔄 Role Updated" to "Your role changed from ${roleLabel(event.oldRole)} to ${roleLabel(event.newRole)}.\n\nYour app access has been updated to reflect your new permissions."
            is AuthManager.ProfileEvent.PermissionsChanged ->
                "🔐 Permissions Updated" to event.message
        }
        AlertDialog(
            onDismissRequest = { roleChangeDialog = null },
            containerColor = DarkCard,
            titleContentColor = LightText,
            textContentColor = MutedText,
            title = { Text(title, fontWeight = FontWeight.ExtraBold) },
            text  = { Text(message, fontSize = 13.sp, lineHeight = 20.sp) },
            confirmButton = {
                Button(
                    onClick = { roleChangeDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) { Text("Got It", fontWeight = FontWeight.Bold) }
            }
        )
    }

    val roleColor = roleColor(profile.role)
    val roleIcon  = roleIcon(profile.role)
    val roleLabel = roleLabel(profile.role)

    Column(Modifier.fillMaxSize().background(DarkBg)) {

        // Status bar padding
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

        // ── Top bar ───────────────────────────────────────────────────────
        Box {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: logo + role badge
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("🦡", fontSize = 18.sp)
                        Text("Badger", color = LightText, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Surface(
                            color = roleColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, roleColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                "$roleIcon $roleLabel",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = roleColor, fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Right: LIVE pill + bell + avatar
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (currentTab == Tab.Live) {
                            Surface(color = Green500, shape = RoundedCornerShape(999.dp)) {
                                Text("●LIVE", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Text("🔔", fontSize = 16.sp)
                        val avatarBg = try { Color(android.graphics.Color.parseColor(profile.avatarColor)) } catch (_: Exception) { Amber500 }
                        Box(Modifier.size(26.dp).clip(CircleShape).background(avatarBg), contentAlignment = Alignment.Center) {
                            Text(profile.initials, color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                // Amber accent line under top bar
                Box(Modifier.fillMaxWidth().height(2.dp).background(Amber500))

                // Update banner
                if (showUpdateBanner && updateInfo != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Amber500)
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Update ${updateInfo.tagName} available", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row {
                            TextButton(onClick = { scope.launch { AppUpdater.downloadAndInstall(context, updateInfo) {} }; showUpdateBanner = false }) {
                                Text("Install", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            TextButton(onClick = { showUpdateBanner = false }) {
                                Text("Later", color = Color.Black.copy(alpha = 0.55f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Content area ──────────────────────────────────────────────────
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab_anim",
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { tab ->
            when (tab) {
                Tab.Shift    -> ShiftSetupScreen(profile)
                Tab.Live     -> MovementScreen()
                Tab.Chat     -> ChatScreen(profile = profile)
                Tab.Settings -> SettingsScreen(profile = profile)
            }
        }

        // ── Bottom nav ────────────────────────────────────────────────────
        Column {
            HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .navigationBarsPadding()
            ) {
                visibleTabs.forEach { tab ->
                    val active     = currentTab == tab
                    val tintColor  = if (tab.isLive) Green500 else Amber500

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { currentTab = tab },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Active indicator — thin line at top edge
                        Box(
                            Modifier.fillMaxWidth().height(2.dp)
                                .background(if (active) tintColor else Color.Transparent)
                        )
                        Spacer(Modifier.height(6.dp))
                        // Emoji with subtle bg pill when active
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (active) tintColor.copy(alpha = 0.12f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(tab.emoji, fontSize = if (tab.isLive) 20.sp else 17.sp)
                        }
                        Text(
                            tab.label,
                            fontSize = 9.sp,
                            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (active) tintColor else MutedText,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Role helpers ──────────────────────────────────────────────────────────────
fun roleColor(role: String): Color = when (role) {
    "admin"       -> Amber500
    "print_room"  -> Blue500
    "truck_mover" -> Green500
    "trainee"     -> Purple500
    else          -> MutedText
}
fun roleIcon(role: String): String = when (role) {
    "admin"       -> "👑"
    "print_room"  -> "🖨️"
    "truck_mover" -> "🚛"
    "trainee"     -> "📚"
    "driver"      -> "🚚"
    else          -> "👤"
}
fun roleLabel(role: String): String = when (role) {
    "admin"       -> "Admin"
    "print_room"  -> "Print Room"
    "truck_mover" -> "Truck Mover"
    "trainee"     -> "Trainee"
    "driver"      -> "Driver"
    else          -> role.replace("_", " ")
}
