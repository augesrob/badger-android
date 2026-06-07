package com.badger.trucks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.service.WearBridgePhone

@Composable
fun WatchSettingsSection() {
    val context = LocalContext.current
    val isConnected = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isConnected.value = WearBridgePhone.isWatchConnected(context)
    }

    val surface  = Color(0xFF1A1A1A)
    val amber    = Color(0xFFF59E0B)
    val green    = Color(0xFF22C55E)
    val red      = Color(0xFFEF4444)
    val gray     = Color(0xFF888888)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Status card
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Galaxy Watch 8", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (isConnected.value) "Connected via Bluetooth" else "Not connected",
                    color = if (isConnected.value) green else gray,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier.size(12.dp)
                    .background(if (isConnected.value) green else gray, RoundedCornerShape(50))
            )
        }

        // Mode indicator
        if (isConnected.value) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFF0F2A1A), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("📱", fontSize = 20.sp)
                Column {
                    Text("Phone Relay Active", color = green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Watch using phone connection — minimal battery drain",
                        color = gray, fontSize = 11.sp)
                }
            }
        }

        // Info cards
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(surface, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Watch Features", color = amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            listOf(
                "🚚" to "Live truck & door status",
                "🔄" to "Tap to change status",
                "📢" to "TTS on both phone + watch",
                "🎙️" to "Push to talk from wrist",
                "📳" to "Vibrate on changes",
                "📡" to "Standalone LTE when phone offline"
            ).forEach { (emoji, label) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(emoji, fontSize = 13.sp)
                    Text(label, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                }
            }
        }

        // Stop watch remotely
        if (isConnected.value) {
            Button(
                onClick = { WearBridgePhone.pushStop() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F0000)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("⛔ Stop Watch App Remotely", color = red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Install instructions
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("📌 Install on Watch", color = gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("1. Enable Developer Options on watch", color = gray, fontSize = 10.sp)
            Text("2. Turn on ADB Debugging + Wireless Debugging", color = gray, fontSize = 10.sp)
            Text("3. Connect PC: adb connect <watch-ip>", color = Color(0xFFF59E0B), fontSize = 10.sp)
            Text("4. adb install badger-wear-vXXX.apk", color = Color(0xFFF59E0B), fontSize = 10.sp)
        }
    }
}
