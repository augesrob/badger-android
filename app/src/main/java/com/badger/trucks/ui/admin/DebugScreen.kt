package com.badger.trucks.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.service.BadgerService
import com.badger.trucks.ui.theme.*
import com.badger.trucks.util.RemoteLogger
import kotlinx.coroutines.launch

@Composable
fun DebugScreen() {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp)
    ) {
        Text("🔧 Remote Debug", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))

        Text(
            "Logs are sent to Supabase and viewable at:\nbadger.augesrob.net/admin → 📱 Mobile Debug",
            color = MutedText,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Service:", color = MutedText, fontSize = 13.sp)
            Text(
                if (BadgerService.isRunning) "✅ Running" else "❌ Stopped",
                color = if (BadgerService.isRunning) Color(0xFF22C55E) else Color(0xFFEF4444),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    RemoteLogger.i("DebugScreen", "Manual test log from Debug tab")
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8))
        ) {
            Text("Send Test Log")
        }
    }
}
