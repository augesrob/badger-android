package com.badger.trucks.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

    // ΓöÇΓöÇ Step 1: normalize speech before anything else ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
    private fun normalizeText(raw: String, trucks: List<LiveMovement>): String {
        var t = raw.trim()

        // "170 dash 1" / "170 slash 1" / "170 hyphen 1" / "170 minus 1" / "170 silent 1" ΓåÆ "170-1"
        t = t.replace(Regex("(\\d+)\\s+(?:dash|slash|hyphen|minus|silent|stroke)\\s+(\\d+)", RegexOption.IGNORE_CASE)) { m ->
            "${m.groupValues[1]}-${m.groupValues[2]}"
        }

        // "170 1" ΓåÆ "170-1" ONLY if that combo exists in trucks list
        t = t.replace(Regex("(\\d{2,3})\\s+(\\d{1,2})(?=\\s|$)")) { m ->
            val candidate = "${m.groupValues[1]}-${m.groupValues[2]}"
            if (trucks.any { it.truckNumber == candidate }) candidate else m.value
        }

        return t
    }

    // ΓöÇΓöÇ Step 2: try to parse WITHOUT Gemini for common patterns ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
    private fun directParse(
        text: String,
        trucks: List<LiveMovement>,
        statuses: List<StatusValue>
    ): VoiceCommand? {

        val t = text.trim()

        // Pattern: "[truck] status [statusValue]"
        // Handles: "148 status 10", "148 status 12A", "148 status on route", etc.
        val statusWordMatch = Regex("^(\\S+)\\s+status\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)
        if (statusWordMatch != null) {
            val truckRaw  = statusWordMatch.groupValues[1]
            val statusRaw = statusWordMatch.groupValues[2].trim()
            val truck = trucks.find { it.truckNumber.equals(truckRaw, ignoreCase = true) }
            if (truck != null) {
                val status = fuzzyMatchStatus(statusRaw, statuses)
                Log.d("VoiceCmd", "DirectParse: truck=${truck.truckNumber} status=${status?.statusName} (raw=$statusRaw)")
                return VoiceCommand("truck_status", truck = truck.truckNumber, status = status?.statusName)
            }
        }

        // Pattern: "[truck] to [statusValue]" ΓÇö "148 to 10", "148 to on route"
        val toMatch = Regex("^(\\S+)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)
        if (toMatch != null) {
            val truckRaw  = toMatch.groupValues[1]
            val statusRaw = toMatch.groupValues[2].trim()
            val truck = trucks.find { it.truckNumber.equals(truckRaw, ignoreCase = true) }
            if (truck != null) {
                val status = fuzzyMatchStatus(statusRaw, statuses)
                Log.d("VoiceCmd", "DirectParse to: truck=${truck.truckNumber} status=${status?.statusName}")
                return VoiceCommand("truck_status", truck = truck.truckNumber, status = status?.statusName)
            }
        }

        // Pattern: "[truck] [statusValue]" ΓÇö "148 yard", "148 indoor", "148 ready"
        // Only if status matches exactly or closely
        val parts = t.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2) {
            val truckRaw  = parts[0]
            val statusRaw = parts[1].trim()
            val truck = trucks.find { it.truckNumber.equals(truckRaw, ignoreCase = true) }
            if (truck != null) {
                val status = fuzzyMatchStatus(statusRaw, statuses)
                if (status != null) {
                    Log.d("VoiceCmd", "DirectParse short: truck=${truck.truckNumber} status=${status.statusName}")
                    return VoiceCommand("truck_status", truck = truck.truckNumber, status = status.statusName)
                }
            }
        }

        return null // fall through to Gemini
    }

    // Fuzzy status matching ΓÇö strips non-alphanumeric and compares
    private fun fuzzyMatchStatus(raw: String, statuses: List<StatusValue>): StatusValue? {
        // Exact match first
        statuses.find { it.statusName.equals(raw, ignoreCase = true) }?.let { return it }

        val cleanRaw = raw.lowercase().replace(Regex("[^a-z0-9]"), "")

        // Known aliases
        val alias = mapOf(
            "indoor" to "In Door", "indoors" to "In Door", "inthedoor" to "In Door", "indock" to "In Door",
            "putaway" to "Put Away", "putitaway" to "Put Away",
            "onroute" to "On Route", "enroute" to "On Route", "ontheroad" to "On Route",
            "infront" to "In Front", "upfront" to "In Front",
            "inback" to "In Back", "outback" to "In Back",
            "therock" to "The Rock",
            "trailerarea" to "Trailer Area",
            "eot" to "End Of Tote", "endoftote" to "End Of Tote", "endoftot" to "End Of Tote",
            "eotplus1" to "EOT+1", "eot1" to "EOT+1",
            "donefornight" to "Done for Night", "done" to "Done for Night", "finished" to "Done for Night",
            "hundredpercent" to "100%", "100percent" to "100%",
            "changetruck" to "Change Truck/Trailer", "changetrailer" to "Change Truck/Trailer"
        )
        alias[cleanRaw]?.let { aliasName ->
            statuses.find { it.statusName == aliasName }?.let { return it }
        }

        // Alphanumeric strip match
        statuses.find {
            it.statusName.lowercase().replace(Regex("[^a-z0-9]"), "") == cleanRaw
        }?.let { return it }

        // Contains match
        return statuses.find {
            val c = it.statusName.lowercase().replace(Regex("[^a-z0-9]"), "")
            c.contains(cleanRaw) || cleanRaw.contains(c)
        }
    }

    // ΓöÇΓöÇ Step 3: fuzzy match for doors ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
    fun fuzzyMatch(input: String?, candidates: List<String>): String? {
        if (input == null) return null
        val clean = input.lowercase().replace(Regex("[^a-z0-9]"), "")
        candidates.firstOrNull { it.lowercase().replace(Regex("[^a-z0-9]"), "") == clean }?.let { return it }
        return candidates.firstOrNull {
            val c = it.lowercase().replace(Regex("[^a-z0-9]"), "")
            c.contains(clean) || clean.contains(c)
        }
    }

    // ΓöÇΓöÇ Main entry point ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
    suspend fun parseCommand(
        text: String,
        trucks: List<LiveMovement>,
        doors: List<LoadingDoor>,
        statuses: List<StatusValue>
    ): VoiceCommand = withContext(Dispatchers.IO) {

        val normalized = normalizeText(text, trucks)
        Log.d("VoiceCmd", "Normalized: '$normalized'")

        // Try direct Kotlin parse first ΓÇö fast and reliable
        directParse(normalized, trucks, statuses)?.let { return@withContext it }

        // Fall back to Gemini for complex commands (door statuses, locations, etc.)
        val truckList    = trucks.map { it.truckNumber }.joinToString(", ")
        val doorList     = doors.map { it.doorName }.joinToString(", ")
        val statusList   = statuses.map { it.statusName }.joinToString(", ")
        val doorStatList = DOOR_STATUSES.joinToString(", ")

        val prompt = """
You are a voice command parser for a truck dispatch system at a warehouse.

AVAILABLE DATA (only use these exact values):
Active trucks: $truckList
Loading doors (physical dock doors): $doorList
Truck statuses: $statusList
Door statuses: $doorStatList

VOICE INPUT: "$normalized"

RULES:
1. Return ONLY raw JSON, no markdown.
2. "truck" field: ONLY exact values from trucks list. Never combine numbers. "170 1" = "170-1" if in list.
3. "door" field: ONLY exact values from loading doors list. Only for PHYSICAL dock doors.
4. "status" field: EXACT string from status lists. Use semantic matching for speech variations.
5. The word "status" between truck and value is a connector ΓÇö "[truck] status [value]" means set truck's status to that value.
6. "door [name] [doorStatus]" = door_status action. "[truck] [truckStatus]" = truck_status action.

EXAMPLES:
"door 13A EOT" ΓåÆ {"action":"door_status","truck":null,"door":"13A","status":"End Of Tote","location":null}
"148 status 10" ΓåÆ {"action":"truck_status","truck":"148","door":null,"status":"10","location":null}
"148 indoor" ΓåÆ {"action":"truck_status","truck":"148","door":null,"status":"In Door","location":null}
"170-1 yard" ΓåÆ {"action":"truck_status","truck":"170-1","door":null,"status":"Yard","location":null}
"door 8 loading" ΓåÆ {"action":"door_status","truck":null,"door":"8","status":"Loading","location":null}

Now parse: "$normalized"
        """.trimIndent()

        var clean = ""
        try {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                    }
                }
            }
            val response = http.post(GEMINI_URL) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val content = root["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: return@withContext VoiceCommand("unknown")

            clean = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val cmd = json.decodeFromString<VoiceCommand>(clean)
            Log.d("VoiceCmd", "Gemini: '$normalized' ΓåÆ action=${cmd.action} truck=${cmd.truck} status=${cmd.status} door=${cmd.door}")
            cmd
        } catch (e: Exception) {
            Log.e("VoiceCmd", "Gemini failed for '$normalized' raw=$clean", e)
            VoiceCommand("unknown")
        }
    }

    // ΓöÇΓöÇ Execute parsed command ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
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

                    if (cmd.status == null)
                        return@withContext VoiceResult.Error("Got truck ${truck.truckNumber} but no status ΓÇö say the status too, e.g. '${truck.truckNumber} yard'")

                    val status = statuses.find { it.statusName.equals(cmd.status, ignoreCase = true) }
                        ?: fuzzyMatchStatus(cmd.status, statuses)
                        ?: return@withContext VoiceResult.Error("Status '${cmd.status}' not found. Options: ${statuses.map { it.statusName }.joinToString()}")

                    BadgerRepo.updateMovementStatus(truck.truckNumber, status.id)
                    VoiceResult.Success("Truck ${truck.truckNumber} ΓåÆ ${status.statusName}")
                }

                "door_status" -> {
                    val door = doors.find { it.doorName.equals(cmd.door, ignoreCase = true) }
                        ?: doors.find { fuzzyMatch(cmd.door, listOf(it.doorName)) != null }
                        ?: return@withContext VoiceResult.Error("Door '${cmd.door}' not found. Options: ${doors.map { it.doorName }.joinToString()}")

                    val status = DOOR_STATUSES.find { it.equals(cmd.status, ignoreCase = true) }
                        ?: fuzzyMatch(cmd.status, DOOR_STATUSES)
                        ?: return@withContext VoiceResult.Error("Door status '${cmd.status}' not recognized. Options: ${DOOR_STATUSES.joinToString()}")

                    BadgerRepo.updateDoorStatus(door.id, status)
                    VoiceResult.Success("Door ${door.doorName} ΓåÆ $status")
                }

                "truck_location" -> {
                    val truck = trucks.find { it.truckNumber == cmd.truck }
                        ?: return@withContext VoiceResult.Error("Truck '${cmd.truck}' not found")
                    val loc = cmd.location ?: cmd.door
                    BadgerRepo.updateMovementLocation(truck.id, loc)
                    VoiceResult.Success("Truck ${truck.truckNumber} location ΓåÆ ${loc ?: "cleared"}")
                }

                else -> VoiceResult.Unknown
            }
        } catch (e: Exception) {
            VoiceResult.Error("Error: ${e.message}")
        }
    }
}

// ΓöÇΓöÇΓöÇ Android SpeechRecognizer wrapper ΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇΓöÇ
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
                    SpeechRecognizer.ERROR_NO_MATCH       -> "No match ΓÇö try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timed out ΓÇö try again"
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
