package com.airplay.streamer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.airplay.streamer.engine.SessionState
import com.airplay.streamer.engine.SpeakerStatus
import com.airplay.streamer.service.AudioCaptureService
import com.airplay.streamer.util.Prefs

/** Home-screen widget: one tap plays on your usual speakers, or stops. */
class AirPlayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, AudioCaptureService.state.value)
    }

    companion object {
        fun update(context: Context, state: SessionState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AirPlayWidget::class.java))
            if (ids.isEmpty()) return
            val prefs = Prefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_airplay)
            val playing = state.speakers.filter { it.status == SpeakerStatus.PLAYING }
            val streaming = state.capturing
            views.setTextViewText(
                R.id.widgetSubtitle,
                when {
                    playing.isNotEmpty() -> context.getString(R.string.status_playing_on, playing.joinToString(" + ") { it.name.lowercase() })
                    streaming -> context.getString(R.string.connecting)
                    prefs.lastSpeakers.isNotEmpty() -> context.getString(R.string.widget_tap_to_play)
                    else -> context.getString(R.string.widget_open_app)
                }
            )
            views.setImageViewResource(R.id.widgetAction, if (streaming) R.drawable.ic_widget_stop else R.drawable.ic_widget_play)

            val action = when {
                streaming -> Intent(context, ShortcutActivity::class.java).setAction(ShortcutActivity.ACTION_STOP)
                prefs.lastSpeakers.isNotEmpty() -> Intent(context, ShortcutActivity::class.java).setAction(ShortcutActivity.ACTION_PLAY)
                    .putExtra(ShortcutActivity.EXTRA_IDENTITIES, prefs.lastSpeakers.joinToString(","))
                else -> Intent(context, MainActivity::class.java)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            views.setOnClickPendingIntent(R.id.widgetAction, PendingIntent.getActivity(context, 10, action, flags))
            views.setOnClickPendingIntent(
                R.id.widgetRoot, PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java), flags)
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
