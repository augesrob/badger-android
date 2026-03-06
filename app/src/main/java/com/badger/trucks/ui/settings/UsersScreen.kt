package com.badger.trucks.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch

private val ROLES = listOf(
    Triple("admin",       "👑 Admin",       Color(0xFFF59E0B)),
    Triple("print_room",  "🖨️ Print Room", Color(0xFF3B82F6)),
    Triple("truck_mover", "🚛 Truck Mover", Color(0xFF8B5CF6)),
    Triple("trainee",     "📚 Trainee",     Color(0xFF22C55E)),
    Triple("driver",      "🚚 Driver",      Color(0xFF6B7280)),
)

private fun roleColor(role: String) = ROLES.firstOrNull { it.first == role }?.third ?: Color.Gray
private fun roleLabel(role: String) = ROLES.firstOrNull { it.first == role }?.second ?: role

@Composable
fun UsersScreen(currentProfile: UserProfile) {
    val scope   = rememberCoroutineScope()
    val isAdmin = currentProfile.role == "admin"

    var users    by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var loading  by remember { mutableStateOf(true) }
    var error    by remember { mutableStateOf<String?>(null) }
    var search   by remember { mutableStateOf("") }
    var editUser by remember { mutableStateOf<UserProfile?>(null) }
    var saving   by remember { mutableStateOf(false) }
    var toast    by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true; error = null
            try {
                users = BadgerRepo.supabase.postgrest["profiles"]
                    .select()
                    .decodeList<UserProfile>()
                    .sortedBy { it.username }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }

    val filtered = if (search.isBlank()) users
    else users.filter {
        it.username.contains(search, ignoreCase = true) ||
        (it.displayName ?: "").contains(search, ignoreCase = true) ||
        it.role.contains(search, ignoreCase = true)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Search users...", color = Color.Gray, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Clear, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) } },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF3B82F6), unfocusedBorderColor = Color(0xFF2A2A2A),
                    cursorColor = Color(0xFF3B82F6)
                ),
                modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFF59E0B))
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Failed to load users", color = Color(0xFFEF4444), fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load() }) { Text("Retry", color = Color(0xFFF59E0B)) }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item { Text("${filtered.size} user${if (filtered.size != 1) "s" else ""}", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                    items(filtered, key = { it.id }) { user ->
                        UserRow(user, isAdmin, onClick = { if (isAdmin) editUser = user })
                    }
                }
            }
        }

        toast?.let { msg ->
            Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = Color(0xFF1A3A1A)) {
                Text(msg, color = Color(0xFF22C55E), fontSize = 13.sp)
            }
        }
    }

    editUser?.let { user ->
        var selectedRole by remember(user) { mutableStateOf(user.role) }
        AlertDialog(
            onDismissRequest = { editUser = null },
            containerColor = Color(0xFF1A1A1A),
            title = { Text(user.displayName ?: user.username, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Change role:", color = Color.Gray, fontSize = 12.sp)
                    ROLES.forEach { (value, label, color) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedRole == value, onClick = { selectedRole = value },
                                colors = RadioButtonDefaults.colors(selectedColor = color, unselectedColor = Color.Gray))
                            Text(label, color = if (selectedRole == value) color else Color.Gray,
                                fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                BadgerRepo.supabase.postgrest["profiles"]
                                    .update({ set("role", selectedRole) }) { filter { eq("id", user.id) } }
                                users = users.map { if (it.id == user.id) it.copy(role = selectedRole) else it }
                                toast = "✅ Role updated"; editUser = null
                            } catch (e: Exception) {
                                toast = "❌ ${e.message}"
                            } finally { saving = false }
                        }
                    },
                    enabled = !saving && selectedRole != user.role,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black)
                ) { Text(if (saving) "Saving…" else "Save", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { editUser = null }) { Text("Cancel", color = Color.Gray) } }
        )
    }
}

@Composable
private fun UserRow(user: UserProfile, isAdmin: Boolean, onClick: () -> Unit) {
    val color = roleColor(user.role)
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(38.dp).background(color.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center) {
                Text((user.displayName ?: user.username).take(1).uppercase(),
                    color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(user.displayName ?: user.username, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("@${user.username}", color = Color.Gray, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(roleLabel(user.role), color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                if (isAdmin) Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }
    }
}
