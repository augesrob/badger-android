package com.badger.trucks.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import com.badger.trucks.util.RemoteLogger
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(profile: UserProfile) {
    val scope    = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focus    = LocalFocusManager.current

    var rooms        by remember { mutableStateOf<List<ChatRoom>>(emptyList()) }
    var activeRoomId by remember { mutableStateOf<Int?>(null) }
    var messages     by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input        by remember { mutableStateOf("") }
    var loading      by remember { mutableStateOf(true) }
    var sending      by remember { mutableStateOf(false) }

    val activeRoom = rooms.find { it.id == activeRoomId }

    fun canWrite(room: ChatRoom?): Boolean {
        if (room == null) return false
        if (profile.role == "admin") return true
        return room.readOnlyRoles?.contains(profile.role) != true
    }

    fun canSeeRoom(room: ChatRoom): Boolean {
        if (profile.role == "admin") return true
        if (room.allowedRoles == null) return true
        if (room.allowedRoles.isEmpty()) return false
        return room.allowedRoles.contains(profile.role)
    }

    suspend fun loadMessages(roomId: Int) {
        try {
            messages = BadgerRepo.getChatMessages(roomId)
            kotlinx.coroutines.delay(80)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        } catch (e: Exception) { RemoteLogger.e("Chat", "loadMessages: ${e.message}") }
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isBlank() || activeRoomId == null || sending) return
        val roomId = activeRoomId!!
        input = ""
        focus.clearFocus()
        sending = true
        scope.launch {
            try {
                BadgerRepo.sendChatMessage(roomId, text)
                loadMessages(roomId)
                RemoteLogger.i("Chat", "Sent in room $roomId")
            } catch (e: Exception) { RemoteLogger.e("Chat", "sendMessage: ${e.message}") }
            sending = false
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        try {
            val allRooms = BadgerRepo.getChatRooms()
            rooms = allRooms.filter { canSeeRoom(it) }
            loading = false
            if (rooms.isNotEmpty()) {
                activeRoomId = rooms.first().id
                loadMessages(rooms.first().id)
            }
        } catch (e: Exception) {
            RemoteLogger.e("Chat", "load rooms: ${e.message}")
            loading = false
        }
    }

    // Realtime subscription for active room
    LaunchedEffect(activeRoomId) {
        val roomId = activeRoomId ?: return@LaunchedEffect
        try {
            val ch = BadgerRepo.realtimeChannel("chat-access-$roomId")
            ch.postgresChangeFlow<PostgresAction>("public") { table = "messages" }
                .collect { action ->
                    if (action is PostgresAction.Insert) loadMessages(roomId)
                    else if (action is PostgresAction.Delete) loadMessages(roomId)
                }
            ch.subscribe()
        } catch (e: Exception) { RemoteLogger.e("Chat", "realtime: ${e.message}") }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(DarkBg)) {

        // Room tabs
        if (rooms.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().background(DarkSurface).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rooms) { room ->
                    val selected = room.id == activeRoomId
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (selected) Amber500.copy(alpha = 0.15f) else DarkCard,
                        modifier = Modifier.clickable {
                            activeRoomId = room.id
                            scope.launch { loadMessages(room.id) }
                        }
                    ) {
                        Text(
                            room.name,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = if (selected) Amber500 else MutedText,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Divider(color = DarkBorder)
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("No messages yet. Say something! 👋", color = MutedText, fontSize = 13.sp)
                    }
                }
            }
            items(messages, key = { it.id }) { msg ->
                val isMe = msg.senderId == profile.id
                val prevMsg = messages.getOrNull(messages.indexOf(msg) - 1)
                val grouped = prevMsg?.senderId == msg.senderId &&
                    (parseTs(msg.createdAt) - parseTs(prevMsg.createdAt)) < 60_000

                MessageBubble(msg = msg, isMe = isMe, grouped = grouped, myId = profile.id)
            }
        }

        Divider(color = DarkBorder)

        // Input bar
        Row(
            modifier = Modifier.fillMaxWidth().background(DarkSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canWrite(activeRoom)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message ${activeRoom?.name ?: ""}…", color = MutedText, fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber500, unfocusedBorderColor = DarkBorder,
                        cursorColor = Amber500, focusedTextColor = LightText, unfocusedTextColor = LightText
                    )
                )
                IconButton(
                    onClick = { sendMessage() },
                    enabled = input.isNotBlank() && !sending,
                    modifier = Modifier.size(44.dp).background(
                        if (input.isNotBlank()) Amber500 else DarkCard, CircleShape
                    )
                ) {
                    Icon(Icons.Default.Send, null, tint = if (input.isNotBlank()) Color.Black else MutedText, modifier = Modifier.size(20.dp))
                }
            } else {
                Surface(modifier = Modifier.fillMaxWidth(), color = DarkCard, shape = RoundedCornerShape(12.dp)) {
                    Text("👁 Read-only room", modifier = Modifier.padding(14.dp), color = MutedText, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage, isMe: Boolean, grouped: Boolean, myId: String) {
    val senderColor = try { Color(android.graphics.Color.parseColor(msg.profiles?.avatarColor ?: "#6b7280")) } catch (_: Exception) { MutedText }
    val senderName  = msg.profiles?.let { it.displayName ?: it.username } ?: "?"
    val timeStr     = formatTime(msg.createdAt)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (grouped) 1.dp else 8.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Avatar (non-me, non-grouped)
        if (!isMe) {
            if (!grouped) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(senderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(senderName.take(2).uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(Modifier.width(32.dp))
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            if (!grouped) {
                Row(
                    horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 2.dp)
                ) {
                    if (!isMe) {
                        Text(senderName, color = senderColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(timeStr, color = MutedText, fontSize = 10.sp)
                }
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                color = if (isMe) Amber500 else DarkCard
            ) {
                Text(
                    msg.content,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (isMe) Color.Black else LightText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }

        if (isMe) Spacer(Modifier.width(8.dp))
    }
}

private fun parseTs(ts: String): Long {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.parse(ts.take(19))?.time ?: 0L
    } catch (_: Exception) { 0L }
}

private fun formatTime(ts: String): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val d = fmt.parse(ts.take(19)) ?: return ""
        val now = Date()
        val today = SimpleDateFormat("yyyyMMdd", Locale.US)
        if (today.format(d) == today.format(now)) SimpleDateFormat("h:mm a", Locale.US).format(d)
        else SimpleDateFormat("MMM d, h:mm a", Locale.US).format(d)
    } catch (_: Exception) { "" }
}
