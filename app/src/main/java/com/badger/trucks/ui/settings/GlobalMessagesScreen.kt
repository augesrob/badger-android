package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.BadgerRepo
import com.badger.trucks.data.UserProfile
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class GlobalMessage(
    val id: Int = 0,
    val message: String = "",
    @SerialName("message_type") val messageType: String = "info",
    @SerialName("is_active")    val isActive: Boolean = true,
    @SerialName("expires_at")   val expiresAt: String? = null,
    @SerialName("created_at")   val createdAt: String? = null,
)

private fun typeColor(t: String) = when (t) {
    "warning" -> Color(0xFFF59E0B)
    "error"   -> Color(0xFFEF4444)
    "success" -> Color(0xFF22C55E)
    else      -> Color(0xFF3B82F6)
}
private fun typeEmoji(t: String) = when (t) {
    "warning" -> "⚠️"; "error" -> "🚨"; "success" -> "✅"; else -> "ℹ️"
}

@Composable
fun GlobalMessagesScreen(currentProfile: UserProfile) {
    val scope   = rememberCoroutineScope()
    val isAdmin = currentProfile.role == "admin"

    var messages by remember { mutableStateOf<List<GlobalMessage>>(emptyList()) }
    var loading  by remember { mutableStateOf(true) }
    var error    by remember { mutableStateOf<String?>(null) }
    var newText  by remember { mutableStateOf("") }
    var newType  by remember { mutableStateOf("info") }
    var posting  by remember { mutableStateOf(false) }
    var toast    by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                messages = BadgerRepo.supabase.postgrest["global_messages"]
                    .select { order("created_at", Order.DESCENDING) }
                    .decodeList<GlobalMessage>()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun post() {
        val text = newText.trim(); if (text.isBlank()) return
        scope.launch {
            posting = true
            try {
                BadgerRepo.supabase.postgrest["global_messages"].insert(
                    buildMap {
                        put("message", text); put("message_type", newType); put("is_active", true)
                    }
                )
                newText = ""; toast = "✅ Message posted"; load()
            } catch (e: Exception) { toast = "❌ ${e.message}" }
            finally { posting = false }
        }
    }

    fun deactivate(id: Int) {
        scope.launch {
            try {
                BadgerRepo.supabase.postgrest["global_messages"]
                    .update({ set("is_active", false) }) { filter { eq("id", id) } }
                load()
            } catch (e: Exception) { toast = "❌ ${e.message}" }
        }
    }

    fun delete(id: Int) {
        scope.launch {
            try {
                BadgerRepo.supabase.postgrest["global_messages"]
                    .delete { filter { eq("id", id) } }
                load()
            } catch (e: Exception) { toast = "❌ ${e.message}" }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isAdmin) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Broadcast Message", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = newText, onValueChange = { newText = it },
                                placeholder = { Text("Enter message to broadcast…", color = Color.Gray, fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF2A2A2A),
                                    cursorColor = Color(0xFF3B82F6)
                                ),
                                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("info", "warning", "error", "success").forEach { type ->
                                    val selected = newType == type
                                    val color = typeColor(type)
                                    FilterChip(
                                        selected = selected, onClick = { newType = type },
                                        label = { Text("${typeEmoji(type)} ${type.replaceFirstChar { it.uppercase() }}", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = color.copy(alpha = 0.3f),
                                            selectedLabelColor = color,
                                            containerColor = Color(0xFF111111), labelColor = Color.Gray
                                        )
                                    )
                                }
                            }
                            Button(
                                onClick = { post() },
                                enabled = !posting && newText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
                            ) {
                                if (posting) { CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                                Text(if (posting) "Posting…" else "Post Broadcast", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            when {
                loading -> item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF3B82F6))
                    }
                }
                messages.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No messages", color = Color.Gray, fontSize = 13.sp)
                    }
                }
                else -> {
                    item { Text("${messages.size} message${if (messages.size != 1) "s" else ""}", color = Color.Gray, fontSize = 11.sp) }
                    items(messages, key = { it.id }) { msg ->
                        val color = typeColor(msg.messageType)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.isActive) color.copy(alpha = 0.08f) else Color(0xFF111111)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top) {
                                Text(typeEmoji(msg.messageType), fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(msg.message, color = if (msg.isActive) Color.White else Color.Gray, fontSize = 13.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(msg.messageType.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        if (!msg.isActive) Text("INACTIVE", color = Color.Gray, fontSize = 10.sp)
                                    }
                                }
                                if (isAdmin) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (msg.isActive) {
                                            IconButton(onClick = { deactivate(msg.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Default.VisibilityOff, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        IconButton(onClick = { delete(msg.id) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            error?.let { item { Text(it, color = Color(0xFFEF4444), fontSize = 12.sp, modifier = Modifier.padding(4.dp)) } }
        }

        toast?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = if (msg.startsWith("✅")) Color(0xFF1A3A1A) else Color(0xFF2A0F0F)
            ) {
                Text(msg, color = if (msg.startsWith("✅")) Color(0xFF22C55E) else Color(0xFFEF4444), fontSize = 13.sp)
            }
        }
    }
}
