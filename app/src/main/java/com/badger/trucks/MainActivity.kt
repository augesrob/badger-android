package com.badger.trucks

import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.chat.ChatScreen
import com.badger.trucks.ui.login.LoginScreen
import com.badger.trucks.ui.movement.MovementScreen
import com.badger.trucks.ui.profile.ProfileScreen
import com.badger.trucks.ui.settings.NotificationSettingsScreen
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
        setContent {
            BadgerTheme {
                BadgerAccessApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
        updateCheckJob?.cancel()
        updateCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                checkForUpdate()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updateCheckJob?.cancel()
    }

    private fun checkForUpdate() {
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        }
        lifecycleScope.launch {
            val update = AppUpdater.checkForUpdate(currentVersion)
            if (update != null) {
                pendingUpdate = update
                AppUpdater.downloadAndInstall(this@MainActivity, update) {}
            }
        }
    }

    companion object {
        var pendingUpdate: com.badger.trucks.updater.UpdateInfo? by androidx.compose.runtime.mutableStateOf(null)
    }
}

// ── Screens available in Badger Access ────────────────────────────────────────

sealed class AccessScreen(val route: String, val label: String, val icon: ImageVector, val requiredPage: String) {
    data object Live      : AccessScreen("live",       "Live",      Icons.Default.LocalShipping, "movement")
    data object ShiftSetup: AccessScreen("shiftsetup", "Shift",     Icons.Default.CalendarToday, "printroom")
    data object Chat      : AccessScreen("chat",       "Chat",      Icons.Default.Chat,          "chat")
    data object Settings  : AccessScreen("settings",   "Settings",  Icons.Default.Settings,      "notifications")
    data object Profile   : AccessScreen("profile",    "Profile",   Icons.Default.Person,        "profile")
}

val ALL_SCREENS = listOf(
    AccessScreen.Live,
    AccessScreen.ShiftSetup,
    AccessScreen.Chat,
    AccessScreen.Settings,
    AccessScreen.Profile,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgerAccessApp() {
    val authState by AuthManager.state.collectAsState()

    // Splash / loading state
    if (authState is AuthManager.AuthState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🦡", style = MaterialTheme.typography.displayMedium)
                CircularProgressIndicator(color = Amber500)
            }
        }
        return
    }

    // Not logged in — show login
    if (authState is AuthManager.AuthState.LoggedOut) {
        LoginScreen()
        return
    }

    // Logged in — show main app
    val profile = (authState as AuthManager.AuthState.LoggedIn).profile
    BadgerAccessMain(profile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgerAccessMain(profile: UserProfile) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val coroutineScope = rememberCoroutineScope()

    // Filter screens by role access
    val visibleScreens = ALL_SCREENS.filter { AuthManager.canAccess(it.requiredPage) }
    val startRoute = visibleScreens.firstOrNull()?.route ?: AccessScreen.Live.route

    var showUpdateBanner by remember { mutableStateOf(MainActivity.pendingUpdate != null) }
    val updateInfo = MainActivity.pendingUpdate

    // Current screen title
    val currentScreen = visibleScreens.find { it.route == currentRoute }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "🦡 Badger Access",
                            color = Amber500,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                    },
                    actions = {
                        // Role badge
                        val roleLabel = when (profile.role) {
                            "admin"       -> "👑"
                            "print_room"  -> "🖨️"
                            "truck_mover" -> "🚛"
                            "trainee"     -> "📚"
                            "driver"      -> "🚚"
                            else          -> "👤"
                        }
                        Surface(
                            color = Amber500.copy(alpha = 0.12f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                "$roleLabel ${profile.displayLabel}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                color = Amber500,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
                )
                // Update banner
                if (showUpdateBanner && updateInfo != null) {
                    Surface(color = Amber500) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Update ${updateInfo.tagName} available", color = Color.Black,
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = {
                                    coroutineScope.launch { AppUpdater.downloadAndInstall(navController.context, updateInfo) {} }
                                    showUpdateBanner = false
                                }) { Text("Install", color = Color.Black, fontWeight = FontWeight.Bold) }
                                TextButton(onClick = { showUpdateBanner = false }) {
                                    Text("Later", color = Color.Black.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = DarkSurface) {
                visibleScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Amber500,
                            selectedTextColor = Amber500,
                            unselectedIconColor = MutedText,
                            unselectedTextColor = MutedText,
                            indicatorColor = Amber500.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(padding)
        ) {
            composable(AccessScreen.Live.route)       { MovementScreen() }
            composable(AccessScreen.ShiftSetup.route) { ShiftSetupScreen() }
            composable(AccessScreen.Chat.route)       { ChatScreen(profile = profile) }
            composable(AccessScreen.Settings.route)   { NotificationSettingsScreen() }
            composable(AccessScreen.Profile.route)    { ProfileScreen(profile = profile) }
        }
    }
}
