package com.badger.trucks.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.badger.trucks.BuildConfig
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

private val GEMINI_URL get() =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}"

sealed class VoiceResult {
    data class Success(val description: String) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
    object Unknown : VoiceResult()
}

@Serializable
data class VoiceCommand(
    val action: String,
    val truck: String? = null,
    val door: String? = null,
    val status: String? = null,
    val location: String? = null
)

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
You are a smart voice command parser for a truck dispatch system.
Your job is to understand natural speech — including abbreviations, spelling out of letters, and casual phrasing — and map it to a structured command.

AVAILABLE DATA:
Active trucks: $truckList
Loading doors: $doorList
Truck statuses: $statusList
Door statuses: $doorStatList

VOICE INPUT: "$text"

IMPORTANT RULES:
1. Return ONLY raw JSON, no markdown, no explanation.
2. For "truck" field: extract the truck number exactly as it appears in the active trucks list. Match by number even if speech says "truck one four eight" or "148".
3. For "door" field: extract the door name exactly as it appears in loading doors list. "door 13 alpha" = "13A", "thirteen A" = "13A".
4. For "status" field: you MUST return the EXACT status string from the lists above. Use fuzzy/semantic matching:
   - "E O T", "EOT", "end of tote", "end of tot" → "End Of Tote"  
   - "EOT plus 1", "EOT+1", "e o t plus one" → "EOT+1"
   - "loading", "load it" → "Loading"
   - "change truck", "change trailer", "swap" → "Change Truck/Trailer"
   - "waiting", "wait" → "Waiting"
   - "done", "done for the night", "finished" → "Done for Night"
   - "hundred percent", "100", "complete" → "100%"
   - "on route", "en route", "on the road" → match closest truck status
   - "status" alone with no qualifier means you should look for context clues in the sentence
5. For action:
   - "truck_status": changing a truck's status (e.g. "set 148 to on route")
   - "door_status": changing a door's status (e.g. "door 13A EOT")
   - "truck_location": moving a truck to a door/location (e.g. "148 is at door 5")
   - "unknown": cannot determine intent

EXAMPLES:
"148 status" → think: truck 148 needs a status update, but no status given → {"action":"truck_status","truck":"148","door":null,"status":null,"location":null}
"door 13 alpha to E O T" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"thirteen A end of tote" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"truck 231 dash 1 is in door 8" → {"action":"truck_location","truck":"231-1","door":null,"status":null,"location":"door 8"}
"set 223 on route" → {"action":"truck_status","truck":"223","door":null,"status":"On Route","location":null}
"door 5 done for night" → {"action":"door_status","truck":null,"door":"5","status":"Done for Night","location":null}
"148 loading" → {"action":"truck_status","truck":"148","door":null,"status":"Loading","location":null}
"148 status" → {"action":"truck_status","truck":"148","door":null,"status":null,"location":null}
"door 13A EOT" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"13 A E O T" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}

CRITICAL: When the voice says letters spaced out like "E O T" that means the acronym EOT = "End Of Tote".
CRITICAL: Numbers matching a truck number = truck. Numbers matching a door name = door. Use the lists.
CRITICAL: If a number appears in BOTH trucks and doors, prefer truck unless the word "door" was said.

Now parse: "$text"
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

            val clean = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            json.decodeFromString<VoiceCommand>(clean)
        } catch (e: Exception) {
            e.printStackTrace()
            VoiceCommand("unknown")
        }
    }

    // Fuzzy match: find closest string from a list ignoring case, spaces, punctuation
    private fun fuzzyMatch(input: String?, candidates: List<String>): String? {
        if (input == null) return null
        val clean = input.lowercase().replace(Regex("[^a-z0-9]"), "")
        // Exact clean match first
        candidates.firstOrNull {
            it.lowercase().replace(Regex("[^a-z0-9]"), "") == clean
        }?.let { return it }
        // Contains match
        return candidates.firstOrNull {
            val c = it.lowercase().replace(Regex("[^a-z0-9]"), "")
            c.contains(clean) || clean.contains(c)
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
                        ?: trucks.find { it.truckNumber.contains(cmd.truck ?: "") }
                        ?: return@withContext VoiceResult.Error("Truck '${cmd.truck}' not found. Available: ${trucks.map { it.truckNumber }.joinToString()}")

                    if (cmd.status == null)
                        return@withContext VoiceResult.Error("Got truck ${truck.truckNumber} but no status — try: 'truck ${truck.truckNumber} on route'")

                    val status = statuses.find { it.statusName.equals(cmd.status, ignoreCase = true) }
                        ?: statuses.find { fuzzyMatch(cmd.status, listOf(it.statusName)) != null }
                        ?: return@withContext VoiceResult.Error("Status '${cmd.status}' not found. Available: ${statuses.map { it.statusName }.joinToString()}")

                    BadgerRepo.updateMovementStatus(truck.truckNumber, status.id)
                    VoiceResult.Success("Truck ${truck.truckNumber} → ${status.statusName}")
                }

                "door_status" -> {
                    val door = doors.find { it.doorName.equals(cmd.door, ignoreCase = true) }
                        ?: doors.find { fuzzyMatch(cmd.door, listOf(it.doorName)) != null }
                        ?: return@withContext VoiceResult.Error("Door '${cmd.door}' not found. Available: ${doors.map { it.doorName }.joinToString()}")

                    val status = DOOR_STATUSES.find { it.equals(cmd.status, ignoreCase = true) }
                        ?: fuzzyMatch(cmd.status, DOOR_STATUSES)
                        ?: return@withContext VoiceResult.Error("Status '${cmd.status}' not recognized. Available: ${DOOR_STATUSES.joinToString()}")

                    BadgerRepo.updateDoorStatus(door.id, status)
                    VoiceResult.Success("Door ${door.doorName} → $status")
                }

                "truck_location" -> {
                    val truck = trucks.find { it.truckNumber == cmd.truck }
                        ?: trucks.find { it.truckNumber.contains(cmd.truck ?: "") }
                        ?: return@withContext VoiceResult.Error("Truck '${cmd.truck}' not found")

                    val loc = cmd.location ?: cmd.door
                    BadgerRepo.updateMovementLocation(truck.id, loc)
                    VoiceResult.Success("Truck ${truck.truckNumber} location → ${loc ?: "cleared"}")
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
                    SpeechRecognizer.ERROR_NO_MATCH       -> "No match — try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out — try again"
                    SpeechRecognizer.ERROR_AUDIO          -> "Audio error"
                    SpeechRecognizer.ERROR_NETWORK        -> "Network error"
                    else                                  -> "Speech error ($error)"
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
