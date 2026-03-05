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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.preshift.PreShiftScreen
import com.badger.trucks.ui.printroom.PrintRoomScreen
import com.badger.trucks.ui.theme.*

private enum class ShiftSub { PrintRoom, PreShift, RouteSheet, CheatSheet, Tractors }

private val SHIFT_ITEMS_BY_ROLE = mapOf(
    "admin"       to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.RouteSheet, ShiftSub.CheatSheet, ShiftSub.Tractors),
    "print_room"  to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.RouteSheet, ShiftSub.CheatSheet, ShiftSub.Tractors),
    "truck_mover" to listOf(ShiftSub.PrintRoom, ShiftSub.PreShift, ShiftSub.RouteSheet, ShiftSub.CheatSheet, ShiftSub.Tractors),
    "trainee"     to listOf(ShiftSub.PrintRoom),
    "driver"      to emptyList(),
)

@Composable
fun ShiftSetupScreen(profile: UserProfile) {
    var activeSub by remember { mutableStateOf<ShiftSub?>(null) }

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
            ShiftSub.RouteSheet -> SubScreenShell("📄 Route Sheet",              Blue500,            { activeSub = null }) { RouteSheetSubScreen() }
            ShiftSub.CheatSheet -> SubScreenShell("📑 Cheat Sheet",              Color(0xFF06B6D4),  { activeSub = null }) { CheatSheetSubScreen() }
            ShiftSub.Tractors   -> SubScreenShell("🚛 Tractor Trailer Database", Purple500,          { activeSub = null }) { TractorsSubScreen() }
        }
    }
}

@Composable
private fun ShiftMenu(profile: UserProfile, onSelect: (ShiftSub) -> Unit) {
    val items = SHIFT_ITEMS_BY_ROLE[profile.role] ?: emptyList()

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
    }
}

private data class ItemDef(val icon: String, val label: String, val description: String, val color: Color)

private fun shiftItemDef(sub: ShiftSub): ItemDef = when (sub) {
    ShiftSub.PrintRoom  -> ItemDef("🖨️", "Print Room",               "Loading doors, trucks & staging",           Amber500)
    ShiftSub.PreShift   -> ItemDef("📋", "PreShift Setup",           "Staging door truck placement",              Green500)
    ShiftSub.RouteSheet -> ItemDef("📄", "Route Sheet",              "View & download tonight's routes",          Blue500)
    ShiftSub.CheatSheet -> ItemDef("📑", "Cheat Sheet",              "Quick reference & door assignments",        Color(0xFF06B6D4))
    ShiftSub.Tractors   -> ItemDef("🚛", "Tractor Trailer Database", "Manage tractors, trailers & assignments",  Purple500)
}

@Composable
private fun ShiftMenuItem(icon: String, label: String, description: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(DarkCard, RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = androidx.compose.material.ripple.rememberRipple()) { onClick() }
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

// ── Stub content composables (called inside SubScreenShell) ──────────────────

@Composable
fun RouteSheetSubScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Route Sheet coming soon", color = MutedText, fontSize = 14.sp)
    }
}

@Composable
fun CheatSheetSubScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Cheat Sheet coming soon", color = MutedText, fontSize = 14.sp)
    }
}

@Composable
fun TractorsSubScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Tractor Trailer Database coming soon", color = MutedText, fontSize = 14.sp)
    }
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
