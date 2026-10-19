package io.github.jherobred.lodisave

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** The round home-screen widget. One tap opens the floating card, which saves the copied link. */
class SaveWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(ctx))
    }

    companion object {
        fun views(ctx: Context) = RemoteViews(ctx.packageName, R.layout.widget).apply {
            setTextViewText(R.id.widget_label, Copy.of(ctx).widgetLabel)
            val open = Intent(ctx, ShareActivity::class.java)
                .putExtra(ShareActivity.EXTRA_CLIPBOARD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

        /** Redraws placed widgets, for a language change. */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            for (id in manager.getAppWidgetIds(ComponentName(ctx, SaveWidget::class.java))) manager.updateAppWidget(id, views(ctx))
        }
    }
}
