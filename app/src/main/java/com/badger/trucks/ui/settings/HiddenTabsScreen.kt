package com.badger.trucks.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.Tab
import com.badger.trucks.data.AuthManager
import com.badger.trucks.ui.theme.*

// Tabs that can NEVER be hidden (would lock the app)
private val ALWAYS_VISIBLE = setOf(Tab.Settings)

object TabVisibilityPrefs {
    private const val PREFS = "badger_tab_visibility"

    fun getHidden(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet("hidden_tabs", emptySet()) ?: emptySet()
    }

    fun setHidden(context: Context, hidden: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet("hidden_tabs", hidden).apply()
    }

    fun isVisible(context: Context, tab: Tab): Boolean {
        if (tab in ALWAYS_VISIBLE) return true
        return tab.name !in getHidden(context)
    }
}

@Composable
fun HiddenTabsScreen() {
    val context = LocalContext.current
    val profile = AuthManager.profile

    // All tabs this user has access to
    val accessibleTabs = remember(profile?.role) {
        Tab.entries.filter { AuthManager.canAccess(it.requiredPage) }
    }

    var hiddenTabs by remember {
        mutableStateOf(TabVisibilityPrefs.getHidden(context))
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text(
            "Choose which tabs appear in your bottom navigation bar.",
            color = MutedText, fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        accessibleTabs.forEach { tab ->
            val alwaysOn = tab in ALWAYS_VISIBLE
            val isVisible = tab.name !in hiddenTabs

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(DarkCard, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tab.emoji, fontSize = 20.sp)
                    Column {
                        Text(tab.label, color = LightText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (alwaysOn) {
                            Text("Always visible — required to unhide tabs",
                                color = MutedText, fontSize = 10.sp)
                        }
                    }
                }
                Switch(
                    checked = isVisible,
                    onCheckedChange = { show ->
                        if (alwaysOn) return@Switch
                        val updated = hiddenTabs.toMutableSet()
                        if (show) updated.remove(tab.name) else updated.add(tab.name)
                        hiddenTabs = updated
                        TabVisibilityPrefs.setHidden(context, updated)
                    },
                    enabled = !alwaysOn,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Amber500,
                        uncheckedThumbColor = MutedText,
                        uncheckedTrackColor = Color(0xFF333333),
                        disabledCheckedTrackColor = Amber500.copy(alpha = 0.4f),
                        disabledCheckedThumbColor = Color.Black.copy(alpha = 0.4f),
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Changes apply immediately. Re-enable any tab here at any time.",
            color = Color(0xFF555555), fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
