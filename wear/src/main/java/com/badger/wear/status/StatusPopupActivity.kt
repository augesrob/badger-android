package com.badger.wear.status

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen status card shown over the watch face when a trigger fires.
 * Launched via full-screen intent from WearMessageListenerService.
 * Auto-dismisses after AUTO_DISMISS_MS; tap anywhere to dismiss sooner.
 */
class StatusPopupActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY  = "body"
        private const val AUTO_DISMISS_MS = 6_000L
    }

    private val titleState = mutableStateOf("Badger")
    private val bodyState  = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readIntent(intent)
        setContent {
            val title by titleState
            val body by bodyState
            // Restart the dismiss timer whenever a newer status replaces the content
            LaunchedEffect(title, body) {
                delay(AUTO_DISMISS_MS)
                finish()
            }
            PopupScreen(title, body) { finish() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        titleState.value = intent?.getStringExtra(EXTRA_TITLE) ?: "Badger"
        bodyState.value  = intent?.getStringExtra(EXTRA_BODY) ?: ""
    }
}

@Composable
private fun PopupScreen(title: String, body: String, onDismiss: () -> Unit) {
    val accent = Color(StatusStore.colorFor("$title $body"))
    val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    color = accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = time,
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
