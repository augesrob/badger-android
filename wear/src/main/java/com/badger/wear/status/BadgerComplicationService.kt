package com.badger.wear.status

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.badger.wear.WearMainActivity

/**
 * Watch-face complication showing the last Badger status trigger.
 * Push-updated: WearMessageListenerService calls requestUpdateAll() on each trigger
 * (UPDATE_PERIOD_SECONDS = 0 in the manifest, so no polling).
 */
class BadgerComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val last = StatusStore.last(this)
        listener.onComplicationData(build(request.complicationType, last.title, last.body))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, "TR223", "Loading")

    private fun build(type: ComplicationType, title: String, body: String): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val desc = PlainComplicationText.Builder("Badger: $title $body").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(body.ifBlank { "--" }.take(7)).build(), desc,
            )
                .setTitle(PlainComplicationText.Builder(title.take(7)).build())
                .setTapAction(tap)
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(body.ifBlank { "No status yet" }).build(), desc,
            )
                .setTitle(PlainComplicationText.Builder(title).build())
                .setTapAction(tap)
                .build()

            else -> null
        }
    }
}
