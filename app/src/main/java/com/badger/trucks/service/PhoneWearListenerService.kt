package com.badger.trucks.service

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PhoneWearListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        Log.d("PhoneWearListener", "Message from watch: $path (${data.size} bytes)")

        when (path) {
            WearPaths.MSG_PTT_START -> {
                // Watch pressed PTT — start recording on phone
                Log.i("PhoneWearListener", "Watch PTT start")
                startService(Intent(this, BadgerService::class.java).apply {
                    action = BadgerService.ACTION_PTT_WATCH_START
                })
            }
            WearPaths.MSG_PTT_STOP -> {
                Log.i("PhoneWearListener", "Watch PTT stop")
                startService(Intent(this, BadgerService::class.java).apply {
                    action = BadgerService.ACTION_PTT_WATCH_STOP
                })
            }
            WearPaths.MSG_STATUS_CHANGE -> {
                // Watch changed a truck status
                try {
                    val json = Json.parseToJsonElement(String(data)).jsonObject
                    val truckNumber = json["truckNumber"]?.jsonPrimitive?.content ?: return
                    val statusId = json["statusId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                    Log.i("PhoneWearListener", "Watch status change: $truckNumber -> $statusId")
                    startService(Intent(this, BadgerService::class.java).apply {
                        action = BadgerService.ACTION_WEAR_STATUS_CHANGE
                        putExtra("truckNumber", truckNumber)
                        putExtra("statusId", statusId)
                    })
                } catch (e: Exception) { Log.e("PhoneWearListener", "Status change parse error: ${e.message}") }
            }
            WearPaths.MSG_DOOR_CHANGE -> {
                try {
                    val json = Json.parseToJsonElement(String(data)).jsonObject
                    val doorId = json["doorId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                    val status = json["status"]?.jsonPrimitive?.content ?: return
                    Log.i("PhoneWearListener", "Watch door change: $doorId -> $status")
                    startService(Intent(this, BadgerService::class.java).apply {
                        action = BadgerService.ACTION_WEAR_DOOR_CHANGE
                        putExtra("doorId", doorId)
                        putExtra("status", status)
                    })
                } catch (e: Exception) { Log.e("PhoneWearListener", "Door change parse error: ${e.message}") }
            }
        }
    }
}
