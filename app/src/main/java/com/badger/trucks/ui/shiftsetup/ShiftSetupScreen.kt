package com.badger.trucks.ui.shiftsetup

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.ui.preshift.PreShiftScreen
import com.badger.trucks.ui.printroom.PrintRoomScreen
import com.badger.trucks.ui.theme.*

/**
 * Combined Shift Setup — PrintRoom + PreShift staging with a tab switcher.
 * Mirrors the web app concept where both screens support shift planning.
 */
@Composable
fun ShiftSetupScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🖨️ Print Room", "📋 PreShift")

    Column(Modifier.fillMaxSize().background(DarkBg)) {

        // ── Tab pill switcher ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = selectedTab == index
                val bgColor by animateColorAsState(
                    targetValue = if (selected) Amber500 else DarkCard,
                    label = "tab_bg_$index"
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.Black else MutedText,
                    label = "tab_text_$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { selectedTab = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = textColor,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Divider(color = DarkBorder, thickness = 1.dp)

        // ── Screen content ─────────────────────────────────────────────────
        when (selectedTab) {
            0 -> PrintRoomScreen()
            1 -> PreShiftScreen()
        }
    }
}
