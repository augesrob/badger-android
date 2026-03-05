package com.badger.trucks.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.badger.trucks.BadgerApp
import com.badger.trucks.data.AuthManager
import com.badger.trucks.data.UserProfile
import com.badger.trucks.ui.theme.*
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val PRESET_COLORS = listOf(
    "#f59e0b","#f97316","#ef4444","#ec4899","#8b5cf6",
    "#3b82f6","#06b6d4","#14b8a6","#22c55e","#84cc16",
    "#ffffff","#94a3b8","#64748b","#1e293b"
)

private val CARRIERS = listOf(
    "Verizon"    to "vtext.com",
    "AT&T"       to "txt.att.net",
    "T-Mobile"   to "tmomail.net",
    "Sprint"     to "messaging.sprintpcs.com",
    "Cricket"    to "sms.cricketwireless.net",
    "Boost"      to "sms.myboostmobile.com",
    "Metro PCS"  to "mymetropcs.com",
    "US Cellular" to "email.uscc.net",
)

@Composable
fun ProfileScreen(profile: UserProfile) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current
    val client  = BadgerApp.supabase

    // ── State ────────────────────────────────────────────────────────────────
    var displayName   by remember { mutableStateOf(profile.displayName ?: profile.username) }
    var avatarColor   by remember { mutableStateOf(profile.avatarColor) }
    var avatarUrl     by remember { mutableStateOf(profile.avatarUrl) }
    var previewUri    by remember { mutableStateOf<Uri?>(null) }
    var uploading     by remember { mutableStateOf(false) }
    var saving        by remember { mutableStateOf(false) }
    var toast         by remember { mutableStateOf<String?>(null) }

    // SMS / email notif
    var phone         by remember { mutableStateOf("") }
    var carrier       by remember { mutableStateOf("") }
    var smsEnabled    by remember { mutableStateOf(false) }
    var emailEnabled  by remember { mutableStateOf(false) }
    var notifyEmail   by remember { mutableStateOf("") }

    // Change credentials
    var newEmail      by remember { mutableStateOf("") }
    var newPassword   by remember { mutableStateOf("") }
    var showPass      by remember { mutableStateOf(false) }
    var credSaving    by remember { mutableStateOf(false) }

    // Truck subscriptions
    var subscriptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var newTruck      by remember { mutableStateOf("") }
    var subLoading    by remember { mutableStateOf(false) }

    // Sign-out confirm
    var confirmSignOut by remember { mutableStateOf(false) }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            previewUri = uri
            uploading = true
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                        ?: throw Exception("Could not read file")
                    if (bytes.size > 5 * 1024 * 1024) throw Exception("Image must be under 5MB")
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = when (mimeType) {
                        "image/png"  -> "png"
                        "image/webp" -> "webp"
                        "image/gif"  -> "gif"
                        else         -> "jpg"
                    }
                    val path = "${profile.id}/avatar.$ext"
                    client.storage.from("profiles").upload(path, bytes) { upsert = true }
                    val publicUrl = client.storage.from("profiles").publicUrl(path)
                    val bustedUrl = "$publicUrl?t=${System.currentTimeMillis()}"
                    client.postgrest["profiles"].update({ set("avatar_url", bustedUrl) }) {
                        filter { eq("id", profile.id) }
                    }
                    avatarUrl = bustedUrl
                    previewUri = null
                    toast = "Profile image updated ✓"
                    RemoteLogger.i("Profile", "Avatar uploaded")
                } catch (e: Exception) {
                    previewUri = null
                    toast = "Upload failed: ${e.message}"
                    RemoteLogger.e("Profile", "Avatar upload failed: ${e.message}")
                }
                uploading = false
            }
        }
    }

    // Load profile extras on first composition
    LaunchedEffect(profile.id) {
        try {
            val row = client.postgrest["profiles"]
                .select { filter { eq("id", profile.id) } }
                .decodeSingleOrNull<kotlinx.serialization.json.JsonObject>()
            row?.let { r ->
                phone        = (r["phone"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                carrier      = (r["carrier"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                smsEnabled   = (r["sms_enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
                emailEnabled = (r["notify_email"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBoolean() ?: false
                notifyEmail  = (r["notify_email_address"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            }
            // subscriptions
            val subs = client.postgrest["truck_subscriptions"]
                .select { filter { eq("user_id", profile.id) } }
                .decodeList<kotlinx.serialization.json.JsonObject>()
            subscriptions = subs.mapNotNull {
                (it["truck_number"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        } catch (e: Exception) {
            RemoteLogger.e("Profile", "load extras: ${e.message}")
        }
    }

    // Toast auto-dismiss
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(3000)
            toast = null
        }
    }

    val roleLabel = when (profile.role) {
        "admin"       -> "👑 Admin"
        "print_room"  -> "🖨️ Print Room"
        "truck_mover" -> "🚛 Truck Mover"
        "trainee"     -> "📚 Trainee"
        "driver"      -> "🚚 Driver"
        else          -> profile.role.replace("_", " ")
    }
    val roleColor = when (profile.role) {
        "admin"       -> Amber500
        "print_room"  -> Blue500
        "truck_mover" -> Green500
        "trainee"     -> Purple500
        else          -> MutedText
    }

    fun showToast(msg: String) { toast = msg }

    fun saveProfile() {
        saving = true
        scope.launch {
            try {
                client.postgrest["profiles"].update({
                    set("display_name",        displayName.trim().ifBlank { profile.username })
                    set("avatar_color",        avatarColor)
                    set("phone",               phone.trim().ifBlank { null })
                    set("carrier",             carrier.ifBlank { null })
                    set("sms_enabled",         smsEnabled)
                    set("notify_email",        emailEnabled)
                    set("notify_email_address", notifyEmail.trim().ifBlank { null })
                }) { filter { eq("id", profile.id) } }
                showToast("Profile saved ✓")
                RemoteLogger.i("Profile", "Profile saved")
            } catch (e: Exception) {
                showToast("Save failed: ${e.message}")
            }
            saving = false
        }
    }

    fun updateCredentials() {
        credSaving = true
        scope.launch {
            try {
                if (newEmail.isNotBlank()) {
                    client.auth.updateUser { email = newEmail.trim() }
                    showToast("Email update sent — check your inbox ✓")
                    newEmail = ""
                }
                if (newPassword.isNotBlank()) {
                    if (newPassword.length < 6) { showToast("Password must be 6+ characters"); credSaving = false; return@launch }
                    client.auth.updateUser { password = newPassword }
                    showToast("Password updated ✓")
                    newPassword = ""
                }
            } catch (e: Exception) {
                showToast("Update failed: ${e.message}")
            }
            credSaving = false
        }
    }

    fun addSubscription() {
        val truck = newTruck.trim().uppercase().removePrefix("TR")
        if (truck.isBlank()) return
        if (subscriptions.contains(truck)) { showToast("Already subscribed to TR$truck"); return }
        subLoading = true
        scope.launch {
            try {
                client.postgrest["truck_subscriptions"].insert(buildJsonObject {
                    put("user_id",       profile.id)
                    put("truck_number",  truck)
                    put("notify_sms",    smsEnabled)
                    put("notify_email",  emailEnabled)
                    put("notify_app",    true)
                })
                subscriptions = subscriptions + truck
                newTruck = ""
                showToast("Subscribed to TR$truck ✓")
            } catch (e: Exception) { showToast("Error: ${e.message}") }
            subLoading = false
        }
    }

    fun removeSubscription(truck: String) {
        scope.launch {
            try {
                client.postgrest["truck_subscriptions"].delete {
                    filter { eq("user_id", profile.id); eq("truck_number", truck) }
                }
                subscriptions = subscriptions.filter { it != truck }
                showToast("Unsubscribed from TR$truck")
            } catch (e: Exception) { showToast("Error: ${e.message}") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text("👤 Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LightText)

            // ── Avatar + identity card ────────────────────────────────────
            ProfileCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        val imgUrl = avatarUrl
                        if (previewUri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(previewUri).crossfade(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                            )
                        } else if (!imgUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(imgUrl).crossfade(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                            )
                        } else {
                            val avatarBg = try { Color(android.graphics.Color.parseColor(avatarColor)) } catch (_: Exception) { Amber500 }
                            Box(Modifier.size(64.dp).clip(CircleShape).background(avatarBg), contentAlignment = Alignment.Center) {
                                Text(profile.initials, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (uploading) {
                            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(profile.displayLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = LightText)
                        Text("@${profile.username}", fontSize = 12.sp, color = MutedText)
                        Spacer(Modifier.height(4.dp))
                        Surface(color = roleColor.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
                            Text(roleLabel, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                color = roleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Edit Profile ──────────────────────────────────────────────
            ProfileCard {
                SectionLabel("Edit Profile")
                Spacer(Modifier.height(12.dp))

                // Display name
                FieldLabel("Display Name")
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = fieldColors()
                )
                Spacer(Modifier.height(12.dp))

                // Image upload
                FieldLabel("Profile Image")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        enabled = !uploading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LightText),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (uploading) "Uploading…" else "Upload Image", fontSize = 13.sp)
                    }
                    if (!avatarUrl.isNullOrBlank()) {
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    client.postgrest["profiles"].update({ set("avatar_url", null as String?) }) {
                                        filter { eq("id", profile.id) }
                                    }
                                    avatarUrl = null
                                    showToast("Image removed")
                                } catch (e: Exception) { showToast("Failed: ${e.message}") }
                            }
                        }) { Text("✕ Remove", color = Red500, fontSize = 12.sp) }
                    }
                }
                Text("JPG, PNG, WebP, or GIF · Max 5MB · Syncs with website", fontSize = 10.sp, color = MutedText, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))

                // Avatar color (shown when no image)
                FieldLabel("Avatar Color ${if (!avatarUrl.isNullOrBlank()) "(fallback)" else ""}")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PRESET_COLORS.forEach { hex ->
                        val c = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { MutedText }
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .then(if (avatarColor == hex) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .clickable { avatarColor = hex }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { saveProfile() }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                ) {
                    if (saving) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Save Profile", fontWeight = FontWeight.Bold)
                }
            }

            // ── Change Email / Password ───────────────────────────────────
            ProfileCard {
                SectionLabel("🔐 Account Security")
                Spacer(Modifier.height(12.dp))

                FieldLabel("New Email Address")
                OutlinedTextField(
                    value = newEmail, onValueChange = { newEmail = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("new@example.com", color = MutedText) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(12.dp))

                FieldLabel("New Password")
                OutlinedTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("6+ characters", color = MutedText) },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = MutedText)
                        }
                    },
                    colors = fieldColors()
                )
                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { updateCredentials() },
                    enabled = !credSaving && (newEmail.isNotBlank() || newPassword.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue500, contentColor = Color.White,
                        disabledContainerColor = Blue500.copy(alpha = 0.3f))
                ) {
                    if (credSaving) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Update Credentials", fontWeight = FontWeight.Bold)
                }
            }

            // ── SMS Notifications ─────────────────────────────────────────
            ProfileCard {
                SectionLabel("📱 SMS Notifications")
                Text("Get texts when subscribed trucks change status. Uses free carrier email-to-SMS.",
                    fontSize = 11.sp, color = MutedText, modifier = Modifier.padding(bottom = 12.dp))

                FieldLabel("Phone Number (10 digits)")
                OutlinedTextField(
                    value = phone,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.let {
                            if (it.length == 11 && it.startsWith("1")) it.drop(1) else it
                        }.take(10)
                        phone = digits
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("4145551234", color = MutedText) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(12.dp))

                FieldLabel("Carrier")
                // carrier stored as gateway domain prefix e.g. "vtext"
                var carrierExpanded by remember { mutableStateOf(false) }
                val carrierLabel = CARRIERS.find { (_, gw) -> gw.startsWith(carrier) }?.first ?: ""
                ExposedDropdownMenuBox(expanded = carrierExpanded, onExpandedChange = { carrierExpanded = it }) {
                    OutlinedTextField(
                        value = carrierLabel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        placeholder = { Text("Select carrier…", color = MutedText) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = carrierExpanded) },
                        colors = fieldColors()
                    )
                    ExposedDropdownMenu(expanded = carrierExpanded, onDismissRequest = { carrierExpanded = false }) {
                        CARRIERS.forEach { (label, gateway) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    carrier = gateway.substringBefore(".")
                                    carrierExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth().clickable { smsEnabled = !smsEnabled }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(checked = smsEnabled, onCheckedChange = { smsEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Amber500))
                    Spacer(Modifier.width(12.dp))
                    Text("Enable SMS notifications", fontSize = 14.sp, color = LightText)
                }

                if (phone.length == 10 && carrier.isNotBlank()) {
                    val gateway = CARRIERS.find { it.second.startsWith(carrier) }?.second ?: "$carrier.com"
                    Text("✓ Texts will go to: $phone@$gateway", fontSize = 11.sp, color = Green500, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(14.dp))

                FieldLabel("📧 Email Notifications (backup / Verizon workaround)")
                Text("Works reliably where SMS gateways fail. Can be any email address.",
                    fontSize = 10.sp, color = MutedText, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = notifyEmail, onValueChange = { notifyEmail = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("any@email.com", color = MutedText) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { emailEnabled = !emailEnabled }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(checked = emailEnabled, onCheckedChange = { emailEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Amber500))
                    Spacer(Modifier.width(12.dp))
                    Text("Enable email notifications", fontSize = 14.sp, color = LightText)
                }
                if (emailEnabled && notifyEmail.isNotBlank())
                    Text("✓ Alerts will go to: $notifyEmail", fontSize = 11.sp, color = Green500, modifier = Modifier.padding(top = 4.dp))
                if (emailEnabled && notifyEmail.isBlank())
                    Text("⚠ Enter an email address above", fontSize = 11.sp, color = Red500, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { saveProfile() }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue500, contentColor = Color.White)
                ) { Text("Save Notification Settings", fontWeight = FontWeight.Bold) }
            }

            // ── Truck Subscriptions ───────────────────────────────────────
            ProfileCard {
                SectionLabel("🚚 Truck Subscriptions")
                Text("Get notified when a truck's status changes in Live Movement.",
                    fontSize = 11.sp, color = MutedText)
                Spacer(Modifier.height(8.dp))

                // Help card
                Surface(color = DarkBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row { Text("170", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace); Text(" — all trailers on truck 170", color = MutedText, fontSize = 12.sp) }
                        Row { Text("170-1", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace); Text(" — only trailer 1 on truck 170", color = MutedText, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTruck, onValueChange = { newTruck = it.removePrefix("TR").removePrefix("tr") },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("170  or  170-1", color = MutedText) },
                        prefix = { Text("TR", color = Amber500, fontWeight = FontWeight.Bold) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        colors = fieldColors()
                    )
                    Button(
                        onClick = { addSubscription() },
                        enabled = newTruck.isNotBlank() && !subLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500, contentColor = Color.Black)
                    ) { Text("+ Add", fontWeight = FontWeight.Bold) }
                }

                if (subscriptions.isEmpty()) {
                    Text("No subscriptions yet.", fontSize = 12.sp, color = MutedText, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        subscriptions.forEach { truck ->
                            Surface(
                                color = DarkCard, shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("TR$truck", color = Amber500, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                    Text("✕", color = MutedText, fontSize = 11.sp, modifier = Modifier.clickable { removeSubscription(truck) })
                                }
                            }
                        }
                    }
                }
            }

            // ── Sign Out ──────────────────────────────────────────────────
            if (confirmSignOut) {
                ProfileCard(color = Color(0xFF2A1010)) {
                    Text("Sign out of Badger Access?", fontWeight = FontWeight.Bold, color = LightText, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { confirmSignOut = false }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MutedText)) { Text("Cancel") }
                        Button(onClick = { scope.launch { AuthManager.signOut() } }, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Red500, contentColor = Color.White)) {
                            Text("Sign Out", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red500),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Red500.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        // Toast overlay
        if (toast != null) {
            Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    color = DarkCard, shape = RoundedCornerShape(24.dp),
                    shadowElevation = 8.dp, modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(toast ?: "", modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        color = LightText, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Reusable helpers ──────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(
    color: Color = DarkSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MutedText, letterSpacing = 1.sp)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 11.sp, color = MutedText, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber500, unfocusedBorderColor = DarkBorder,
    cursorColor = Amber500, focusedTextColor = LightText,
    unfocusedTextColor = LightText, focusedLabelColor = Amber500, unfocusedLabelColor = MutedText
)
