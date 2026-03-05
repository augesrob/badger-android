package com.badger.trucks.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(profile: UserProfile) {
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }

    val roleLabel = when (profile.role) {
        "admin"       -> "👑 Admin"
        "print_room"  -> "🖨️ Print Room"
        "truck_mover" -> "🚛 Truck Mover"
        "trainee"     -> "📚 Trainee"
        "driver"      -> "🚚 Driver"
        else          -> profile.role.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    val roleColor = when (profile.role) {
        "admin"       -> Color(0xFFF59E0B)
        "print_room"  -> Color(0xFF3B82F6)
        "truck_mover" -> Color(0xFF22C55E)
        "trainee"     -> Color(0xFF8B5CF6)
        "driver"      -> Color(0xFF6B7280)
        else          -> MutedText
    }
    val avatarColor = try {
        Color(android.graphics.Color.parseColor(profile.avatarColor))
    } catch (_: Exception) { Amber500 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Avatar + name header
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(profile.initials, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(12.dp))
                Text(profile.displayLabel, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = LightText)
                Text("@${profile.username}", fontSize = 13.sp, color = MutedText)
                Spacer(Modifier.height(8.dp))
                Surface(color = roleColor.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
                    Text(roleLabel, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        color = roleColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Access summary
        Text("YOUR ACCESS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedText,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))

        val pages = when (profile.role) {
            "admin"       -> listOf("Print Room", "PreShift", "Live Movement", "Chat", "Settings")
            "print_room"  -> listOf("Print Room", "Live Movement", "Chat", "Settings")
            "truck_mover" -> listOf("Print Room", "Live Movement", "Chat", "Settings")
            "trainee"     -> listOf("Print Room", "Live Movement", "Chat", "Settings")
            "driver"      -> listOf("Live Movement", "Chat", "Settings")
            else          -> listOf("Live Movement", "Chat")
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pages.forEach { page ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(16.dp))
                        Text(page, color = LightText, fontSize = 14.sp)
                    }
                }
            }
        }

        // Info rows
        Text("ACCOUNT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedText,
            letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                InfoRow(Icons.Default.Person, "Username", profile.username)
                Divider(color = DarkBorder, modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow(Icons.Default.Badge, "Display Name", profile.displayLabel)
                Divider(color = DarkBorder, modifier = Modifier.padding(horizontal = 16.dp))
                InfoRow(Icons.Default.Security, "Role", roleLabel)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Sign out
        if (showSignOutConfirm) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1A1A)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign out of Badger Access?", fontWeight = FontWeight.Bold, color = LightText, fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showSignOutConfirm = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedText)
                        ) { Text("Cancel") }
                        Button(
                            onClick = { scope.launch { AuthManager.signOut() } },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Red500, contentColor = Color.White)
                        ) { Text("Sign Out", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Red500),
                border = androidx.compose.foundation.BorderStroke(1.dp, Red500.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign Out", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = MutedText, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = MutedText)
            Text(value, fontSize = 14.sp, color = LightText, fontWeight = FontWeight.Medium)
        }
    }
}
