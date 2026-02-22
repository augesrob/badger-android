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
You are a voice command parser for a truck dispatch system at a warehouse.

AVAILABLE DATA (these are the ONLY valid values — never invent new ones):
Active trucks: $truckList
Loading doors (physical dock doors): $doorList
Truck statuses (where a truck IS or what it's doing): $statusList
Door statuses (state of a loading dock): $doorStatList

VOICE INPUT: "$text"

CONTEXT RULES — read carefully:
- Truck statuses like "8", "9", "12A", "13A", "In Door", "On Route", "Ready", "Yard" etc. describe WHERE a truck is or what it's doing.
- Loading doors like "13A", "8" etc. are PHYSICAL dock doors at the warehouse.
- The word "door" before a number/letter means it's a LOADING DOOR (dock). No "door" prefix = likely a truck STATUS.
- "EOT", "E O T", "end of tote" = door status "End Of Tote" — this applies to a LOADING DOOR.
- If someone says "149 to status 8" or "149 status 8" → they mean set truck 149's status to "8" (truck_status action).
- If someone says "door 13A EOT" or "door 13A end of tote" → they mean set LOADING DOOR 13A's status to "End Of Tote" (door_status action).
- If someone says "13A EOT" with NO "door" prefix → this is ambiguous. If 13A exists in loading doors list, treat as door_status. If not, treat as truck_status.

IMPORTANT RULES:
1. Return ONLY raw JSON, no markdown, no explanation.
2. For "truck" field: ONLY use values that appear EXACTLY in the active trucks list. Never combine numbers. "148" and "8" are separate — never merge to "1488".
3. For "door" field: ONLY use values from the loading doors list. Only use this when the command is about a PHYSICAL DOCK DOOR.
4. For "status" field: return the EXACT string from truck statuses or door statuses list. Use fuzzy/semantic matching:
   - "E O T", "EOT", "end of tote" → "End Of Tote"
   - "EOT+1", "e o t plus one" → "EOT+1"
   - "loading" → "Loading"
   - "change truck", "swap" → "Change Truck/Trailer"
   - "waiting", "wait" → "Waiting"
   - "done", "done for night" → "Done for Night"
   - "hundred percent", "100 percent" → "100%"
   - "in door" → "In Door"
   - "put away" → "Put Away"
   - "on route", "en route" → "On Route"
   - "in front" → "In Front"
   - "ready" → "Ready"
   - "in back" → "In Back"
   - "the rock" → "The Rock"
   - "trailer area" → "Trailer Area"
   - "yard" → "Yard"
   - "missing" → "Missing"
   - "gap" → "Gap"
   - "transfer" → "Transfer"
   - "end" → "END"
   - "ignore" → "Ignore"
   - A bare number like "8", "9", "12A", "13A" with no "door" prefix = truck STATUS
5. action values:
   - "truck_status": changing a TRUCK's status (e.g. "149 to status 8", "set 148 on route")
   - "door_status": changing a LOADING DOOR's status (e.g. "door 13A EOT")
   - "truck_location": moving a truck to a dock location
   - "unknown": cannot determine

EXAMPLES:
"door 13A EOT" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"door 13A end of tote" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"13A E O T" → {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"149 to status 8" → {"action":"truck_status","truck":"149","door":null,"status":"8","location":null}
"149 status 8" → {"action":"truck_status","truck":"149","door":null,"status":"8","location":null}
"set 148 on route" → {"action":"truck_status","truck":"148","door":null,"status":"On Route","location":null}
"148 in door" → {"action":"truck_status","truck":"148","door":null,"status":"In Door","location":null}
"148 ready" → {"action":"truck_status","truck":"148","door":null,"status":"Ready","location":null}
"231-1 yard" → {"action":"truck_status","truck":"231-1","door":null,"status":"Yard","location":null}
"door 8 loading" → {"action":"door_status","truck":null,"door":"8","status":"Loading","location":null}
"148 status" → {"action":"truck_status","truck":"148","door":null,"status":null,"location":null}

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
