package com.badger.trucks.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.badger.trucks.data.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─── Get a free key at https://aistudio.google.com/ ────────────────────────
private const val GEMINI_API_KEY = "AIzaSyByB30poej45HX7ujWNxITYvkRGpbpm7s4"
private const val GEMINI_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$GEMINI_API_KEY"

// ─── Result types ────────────────────────────────────────────────────────────
sealed class VoiceResult {
    data class Success(val description: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
    object Unknown : VoiceResult()
}

@Serializable
data class VoiceCommand(
    val action: String,         // "truck_status" | "door_status" | "truck_location" | "unknown"
    val truck: String? = null,  // e.g. "223" or "231-1"
    val door: String? = null,   // e.g. "13A"
    val status: String? = null, // matched status name
    val location: String? = null
)

// ─── Gemini-powered command parser + executor ─────────────────────────────────
object VoiceCommandProcessor {

    private val http = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun parseCommand(
        text: String,
        trucks: List<LiveMovement>,
        doors: List<LoadingDoor>,
        statuses: List<StatusValue>
    ): VoiceCommand = withContext(Dispatchers.IO) {
        val truckList    = trucks.map { it.truckNumber }.joinToString(", ")
        val doorList     = doors.map { it.doorName }.joinToString(", ")
        val statusList   = statuses.map { it.statusName }.joinToString(", ")
        val doorStatList = DOOR_STATUSES.joinToString(", ")

        val prompt = """
You are a dispatcher command parser for a truck management system.
Parse the following voice command and return ONLY a raw JSON object (no markdown fences).

Active trucks: $truckList
Loading doors: $doorList
Truck statuses: $statusList
Door statuses: $doorStatList

Voice command: "$text"

Return JSON with:
- action: one of "truck_status", "door_status", "truck_location", "unknown"
- truck: truck number string (e.g. "223", "231-1") or null
- door: door name string (e.g. "13A") or null
- status: exact status name from the lists above, or null
- location: location string or null

Examples:
"change 13A to EOT" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"231-1 is in door 8" → {"action":"truck_location","truck":"231-1","door":null,"status":null,"location":"door 8"}
"set truck 223 to on route" → {"action":"truck_status","truck":"223","door":null,"status":"On Route","location":null}
"door 5 is done for night" → {"action":"door_status","truck":null,"door":"5","status":"Done for Night","location":null}
        """.trimIndent()

        try {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject { put("text", prompt) }
                        }
                    }
                }
            }

            val response = http.post(GEMINI_URL) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val content = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: return@withContext VoiceCommand("unknown")

            val clean = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            json.decodeFromString<VoiceCommand>(clean)
        } catch (e: Exception) {
            e.printStackTrace()
            VoiceCommand("unknown")
        }
    }

    suspend fun executeCommand(
        cmd: VoiceCommand,
        trucks: List<LiveMovement>,
        doors: List<LoadingDoor>,
        statuses: List<StatusValue>
    ): VoiceResult = withContext(Dispatchers.IO) {
        try {
            when (cmd.action) {
                "truck_status" -> {
                    val truck = trucks.find { it.truckNumber == cmd.truck }
                        ?: return@withContext VoiceResult.Error("Truck '${cmd.truck}' not found")
                    val status = statuses.find { it.statusName.equals(cmd.status, ignoreCase = true) }
                        ?: return@withContext VoiceResult.Error("Status '${cmd.status}' not found")
                    BadgerRepo.updateMovementStatus(truck.truckNumber, status.id)
                    VoiceResult.Success("✅ Truck ${truck.truckNumber} → ${status.statusName}")
                }

                "door_status" -> {
                    val door = doors.find { it.doorName.equals(cmd.door, ignoreCase = true) }
                        ?: return@withContext VoiceResult.Error("Door '${cmd.door}' not found")
                    val status = DOOR_STATUSES.find { it.equals(cmd.status, ignoreCase = true) }
                        ?: return@withContext VoiceResult.Error("Status '${cmd.status}' not recognized")
                    BadgerRepo.updateDoorStatus(door.id, status)
                    VoiceResult.Success("✅ Door ${door.doorName} → $status")
                }

                "truck_location" -> {
                    val truck = trucks.find { it.truckNumber == cmd.truck }
                        ?: return@withContext VoiceResult.Error("Truck '${cmd.truck}' not found")
                    val loc = cmd.location ?: cmd.door
                    BadgerRepo.updateMovementLocation(truck.id, loc)
                    VoiceResult.Success("✅ Truck ${truck.truckNumber} location → ${loc ?: "cleared"}")
                }

                else -> VoiceResult.Unknown
            }
        } catch (e: Exception) {
            VoiceResult.Error("Error: ${e.message}")
        }
    }
}

// ─── Android SpeechRecognizer wrapper ────────────────────────────────────────
class BadgerSpeechRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text != null) onResult(text) else onError("No speech detected")
            }
            override fun onError(error: Int) {
                onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH      -> "No match — try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out — try again"
                    SpeechRecognizer.ERROR_AUDIO         -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK       -> "Network error"
                    else                                 -> "Speech error ($error)"
                })
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
