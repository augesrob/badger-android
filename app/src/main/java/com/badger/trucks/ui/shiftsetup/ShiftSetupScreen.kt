package com.badger.trucks.ui.shiftsetup

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
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Sync
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.BadgerRepo
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.preshift.PreShiftScreen
import com.badger.trucks.ui.printroom.PrintRoomScreen
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

private enum class ShiftSub { PrintRoom, PreShift, Tractors }

private val SHIFT_ITEMS_BY_ROLE = mapOf(
    "admin"       to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.Tractors),
    "print_room"  to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.Tractors),
    "truck_mover" to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.Tractors),
    "trainee"     to listOf(ShiftSub.PrintRoom),
    "driver"      to emptyList(),
)

@Composable
fun ShiftSetupScreen(profile: UserProfile, resetCounter: Int = 0) {
    var activeSub by remember { mutableStateOf<ShiftSub?>(null) }

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
        label = "shift_sub",
        modifier = Modifier.fillMaxSize().background(DarkBg)
    ) { sub ->
        when (sub) {
            null              -> ShiftMenu(profile, onSelect = { activeSub = it })
            ShiftSub.PrintRoom  -> SubScreenShell("🖨️ Print Room",               Amber500,           { activeSub = null }) { PrintRoomScreen() }
            ShiftSub.PreShift   -> SubScreenShell("📋 PreShift Setup",           Green500,           { activeSub = null }) { PreShiftScreen() }
            ShiftSub.Tractors   -> SubScreenShell("🚛 Tractor Trailer Database", Purple500,          { activeSub = null }) { TractorsSubScreen() }
        }
    }
}

private enum class RouteSyncState { Idle, Requesting, Waiting, Done, Error }

@Composable
private fun ShiftMenu(profile: UserProfile, onSelect: (ShiftSub) -> Unit) {
    val items = SHIFT_ITEMS_BY_ROLE[profile.role] ?: emptyList()
    val canEdit = remember { AuthManager.canFeature("printroom_edit") }
    val scope = rememberCoroutineScope()

    var routeState  by remember { mutableStateOf(RouteSyncState.Idle) }
    var routeMsg    by remember { mutableStateOf("") }
    var waitSeconds by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        SectionLabel("Shift Setup")
        Spacer(Modifier.height(4.dp))

        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🚫", fontSize = 36.sp)
                    Text("No shift setup access for your role", color = MutedText, fontSize = 13.sp)
                }
            }
            return@Column
        }

        items.forEach { sub ->
            val (icon, label, description, color) = shiftItemDef(sub)
            ShiftMenuItem(icon = icon, label = label, description = description, color = color, onClick = { onSelect(sub) })
        }

        // ── Route Sheet sync ─────────────────────────────────────────────────
        if (canEdit) {
            Spacer(Modifier.height(10.dp))
            SectionLabel("Quick Actions")

            val isBusy = routeState == RouteSyncState.Requesting || routeState == RouteSyncState.Waiting
            val cardLabel = when (routeState) {
                RouteSyncState.Idle       -> "Sync Route Sheet"
                RouteSyncState.Requesting -> "Sending request…"
                RouteSyncState.Waiting    -> "Waiting for route data… ${waitSeconds}s"
                RouteSyncState.Done       -> "Sync Route Sheet"
                RouteSyncState.Error      -> "Sync Route Sheet"
            }
            val cardDesc = when (routeState) {
                RouteSyncState.Waiting    -> "Email sent to dispatch — reply usually arrives in 30–60s"
                else                      -> "Request and import fresh route numbers from dispatch"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(DarkCard, RoundedCornerShape(10.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = ripple(), enabled = !isBusy) {
                        routeState = RouteSyncState.Requesting
                        routeMsg   = ""
                        waitSeconds = 0
                        scope.launch {
                            // Step 1: send the ping email
                            val req = BadgerRepo.requestRoutes()
                            if (req.isFailure) {
                                routeMsg   = "❌ ${req.exceptionOrNull()?.message ?: "Failed to send request"}"
                                routeState = RouteSyncState.Error
                                kotlinx.coroutines.delay(5000); routeState = RouteSyncState.Idle; return@launch
                            }
                            // Step 2: poll every 8s, up to 2 minutes
                            routeState = RouteSyncState.Waiting
                            val maxWait = 120
                            while (waitSeconds < maxWait) {
                                kotlinx.coroutines.delay(8000)
                                waitSeconds += 8
                                when (val result = BadgerRepo.importRoutes()) {
                                    is com.badger.trucks.data.RouteImportResult.Done -> {
                                        routeMsg   = "✅ ${result.updated} routes imported"
                                        routeState = RouteSyncState.Done
                                        kotlinx.coroutines.delay(5000); routeState = RouteSyncState.Idle; return@launch
                                    }
                                    is com.badger.trucks.data.RouteImportResult.Waiting -> { /* keep polling */ }
                                    is com.badger.trucks.data.RouteImportResult.Error -> {
                                        routeMsg   = "❌ ${result.message}"
                                        routeState = RouteSyncState.Error
                                        kotlinx.coroutines.delay(5000); routeState = RouteSyncState.Idle; return@launch
                                    }
                                }
                            }
                            routeMsg   = "⏱ No reply in ${maxWait}s — check route email on website"
                            routeState = RouteSyncState.Error
                            kotlinx.coroutines.delay(6000); routeState = RouteSyncState.Idle
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(22.dp).padding(start = 4.dp), color = Amber500, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = Amber500, modifier = Modifier.size(22.dp).padding(start = 4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(cardLabel, color = if (isBusy) MutedText else LightText,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(cardDesc, color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }

            if (routeMsg.isNotBlank()) {
                val success = routeMsg.startsWith("✅")
                Surface(shape = RoundedCornerShape(8.dp),
                    color = if (success) Color(0xFF14532D) else Color(0xFF450A0A)) {
                    Text(routeMsg, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (success) Color(0xFF4ADE80) else Color(0xFFF87171),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class ItemDef(val icon: String, val label: String, val description: String, val color: Color)

private fun shiftItemDef(sub: ShiftSub): ItemDef = when (sub) {
    ShiftSub.PrintRoom  -> ItemDef("🖨️", "Print Room",               "Loading doors, trucks & staging",           Amber500)
    ShiftSub.PreShift   -> ItemDef("📋", "PreShift Setup",           "Staging door truck placement",              Green500)
    ShiftSub.Tractors   -> ItemDef("🚛", "Tractor Trailer Database", "Manage tractors, trailers & assignments",  Purple500)
}

@Composable
private fun ShiftMenuItem(icon: String, label: String, description: String, color: Color, onClick: () -> Unit) {
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
fun SubScreenShell(
    title: String,
    accentColor: Color,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(DarkBg)) {
        // Back header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "‹ Back",
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { onBack() }
            )
            HorizontalDivider(Modifier.width(1.dp).height(14.dp), color = DarkBorder, thickness = 1.dp)
            Text(title, color = LightText, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = DarkBorder)
        Box(Modifier.weight(1f)) { content() }
    }
}
