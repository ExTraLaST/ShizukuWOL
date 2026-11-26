package com.shizuku.wolwake.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.shizuku.wolwake.MainActivity
import com.shizuku.wolwake.R

class WakeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_wake)

            val wakeIntent = Intent(context, WakeReceiver::class.java).apply {
                action = WakeReceiver.ACTION_WAKE
            }
            val wakePendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_wake_button, wakePendingIntent)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_open_app, openPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
