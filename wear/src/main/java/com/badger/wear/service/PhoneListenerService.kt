package com.badger.wear.service

import android.util.Log
import com.badger.wear.WearDoor
import com.badger.wear.WearPaths
import com.badger.wear.WearStatus
import com.badger.wear.WearTruck
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        Log.d("PhoneListener", "Message: $path (${data.size} bytes)")

        when (path) {
            WearPaths.TRUCKS -> {
                val trucks = Json.decodeFromString<List<WearTruck>>(String(data))
                WearService.onPhoneTrucks(trucks)
            }
            WearPaths.DOORS -> {
                val doors = Json.decodeFromString<List<WearDoor>>(String(data))
                WearService.onPhoneDoors(doors)
            }
            WearPaths.STATUSES -> {
                val statuses = Json.decodeFromString<List<WearStatus>>(String(data))
                WearService.onPhoneStatuses(statuses)
            }
            WearPaths.MSG_PHONE_ALIVE -> {
                WearService.onPhoneAlive()
            }
            WearPaths.MSG_TTS -> {
                val text = String(data)
                Log.i("PhoneListener", "TTS from phone: $text")
                WearService.onPhoneTts(text)
            }
            WearPaths.MSG_STOP -> {
                Log.i("PhoneListener", "Stop signal from phone")
                WearService.onPhoneStop()
            }
            WearPaths.MSG_PTT_AUDIO -> {
                Log.i("PhoneListener", "Incoming PTT audio (${data.size} bytes)")
            }
        }
    }
}
