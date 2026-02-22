package com.badger.trucks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.badger.trucks.service.BadgerService
import com.badger.trucks.ui.theme.*
import com.badger.trucks.ui.printroom.PrintRoomScreen
import com.badger.trucks.ui.preshift.PreShiftScreen
import com.badger.trucks.ui.movement.MovementScreen
import com.badger.trucks.ui.admin.AdminScreen
import com.badger.trucks.updater.AppUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled */ }

    private var updateCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestAppPermissions()
        startBadgerService()
        setContent {
            BadgerTheme {
                BadgerMainApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check for update every time app comes to foreground
        checkForUpdate()
        // Also start a periodic check every 30 minutes while app is open
        updateCheckJob?.cancel()
        updateCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L) // 30 minutes
                checkForUpdate()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        updateCheckJob?.cancel()
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        // Microphone for voice commands
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        // Notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startBadgerService() {
        if (!BadgerService.isRunning) {
            ContextCompat.startForegroundService(this, Intent(this, BadgerService::class.java))
        }
    }

    private fun checkForUpdate() {
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode
        }
        lifecycleScope.launch {
            val update = AppUpdater.checkForUpdate(currentVersion)
            if (update != null) {
                pendingUpdate = update
                android.widget.Toast.makeText(this@MainActivity, "🔄 Update ${update.tagName} found, downloading...", android.widget.Toast.LENGTH_LONG).show()
                AppUpdater.downloadAndInstall(this@MainActivity, update) { /* silent */ }
            }
        }
    }

    companion object {
        // Observed by BadgerMainApp via mutableStateOf so the banner re-composes
        var pendingUpdate: com.badger.trucks.updater.UpdateInfo? by androidx.compose.runtime.mutableStateOf(null)
    }
}

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object PrintRoom : Screen("printroom", "Print Room", Icons.Default.Print)
    data object PreShift : Screen("preshift", "PreShift", Icons.Default.List)
    data object Movement : Screen("movement", "Live", Icons.Default.LocalShipping)
    data object Admin : Screen("admin", "Admin", Icons.Default.Settings)
}

val screens = listOf(Screen.PrintRoom, Screen.PreShift, Screen.Movement, Screen.Admin)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgerMainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Update banner state
    var showUpdateBanner by remember { mutableStateOf(MainActivity.pendingUpdate != null) }
    val updateInfo = MainActivity.pendingUpdate

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("🦡 Badger", color = Amber500, style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
                )
                // ── Update banner ──────────────────────────────────────────
                if (showUpdateBanner && updateInfo != null) {
                    Surface(color = Amber500) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Update available: ${updateInfo.tagName}",
                                color = androidx.compose.ui.graphics.Color.Black,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row {
                                TextButton(onClick = {
                                    coroutineScope.launch {
                                        AppUpdater.downloadAndInstall(context, updateInfo) { /* status */ }
                                    }
                                    showUpdateBanner = false
                                }) {
                                    Text("Install", color = androidx.compose.ui.graphics.Color.Black,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                                }
                                TextButton(onClick = { showUpdateBanner = false }) {
                                    Text("Later", color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = DarkSurface, contentColor = MutedText) {
                screens.forEach { screen ->
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
                            indicatorColor = Amber500.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.PrintRoom.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.PrintRoom.route) { PrintRoomScreen() }
            composable(Screen.PreShift.route) { PreShiftScreen() }
            composable(Screen.Movement.route) { MovementScreen() }
            composable(Screen.Admin.route) { AdminScreen() }
        }
    }
}
