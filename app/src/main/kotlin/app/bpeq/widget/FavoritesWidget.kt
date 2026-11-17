package app.bpeq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.bpeq.BpeqApp
import app.bpeq.MainActivity
import app.bpeq.R
import app.bpeq.data.Applier
import kotlinx.coroutines.launch

/** Home-screen widget: up to four favorite profiles; tap one to apply it to the dongle. */
class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_APPLY) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val pending = goAsync()
            Applier.scope.launch {
                try { Applier.apply(id) { Applier.toast(it) } } finally { updateAll(ctx); pending.finish() }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        const val ACTION_APPLY = "app.bpeq.widget.APPLY"
        const val EXTRA_ID = "profile_id"
        private val SLOTS = intArrayOf(R.id.fav1, R.id.fav2, R.id.fav3, R.id.fav4)

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, FavoritesWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val lib = BpeqApp.instance.library
            val favs = lib.profiles.value.filter { it.favorite }.take(SLOTS.size)
            val onId = lib.onDevice.value?.first
            val views = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            views.setOnClickPendingIntent(R.id.title, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            views.setViewVisibility(R.id.empty, if (favs.isEmpty()) View.VISIBLE else View.GONE)
            SLOTS.forEachIndexed { i, slot ->
                val p = favs.getOrNull(i)
                if (p == null) { views.setViewVisibility(slot, View.GONE); return@forEachIndexed }
                views.setViewVisibility(slot, View.VISIBLE)
                views.setTextViewText(slot, (if (p.id == onId) "● " else "") + p.name)
                val apply = Intent(ctx, FavoritesWidget::class.java).setAction(ACTION_APPLY).putExtra(EXTRA_ID, p.id)
                views.setOnClickPendingIntent(slot, PendingIntent.getBroadcast(ctx, 100 + i, apply,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}
