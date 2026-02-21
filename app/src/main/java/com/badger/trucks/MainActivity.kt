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
import androidx.core.content.ContextCompat
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

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result — service already started */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startBadgerService()
        setContent {
            BadgerTheme {
                BadgerMainApp()
            }
        }
    }

    private fun startBadgerService() {
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (!BadgerService.isRunning) {
            val intent = Intent(this, BadgerService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "🦡 Badger",
                        color = Amber500,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = MutedText
            ) {
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
