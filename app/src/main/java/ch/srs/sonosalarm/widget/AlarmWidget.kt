package ch.srs.sonosalarm.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import ch.srs.sonosalarm.MainActivity
import ch.srs.sonosalarm.R
import ch.srs.sonosalarm.data.AlarmStore
import ch.srs.sonosalarm.data.PauseState
import ch.srs.sonosalarm.data.nextTrigger
import ch.srs.sonosalarm.data.shortDate
import ch.srs.sonosalarm.dayLabel
import ch.srs.sonosalarm.hm
import java.time.LocalDateTime

private val VERSION = longPreferencesKey("version")

private data class WidgetRow(val time: String, val day: String, val recurrence: String, val paused: Boolean = false)

/** Home-screen widget listing the active alarms, soonest first. */
class AlarmWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme { Content() }
        }
    }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val version = currentState(VERSION) ?: 0L
        val (speaker, rows) = remember(version) {
            val store = AlarmStore(context)
            val now = LocalDateTime.now()
            val pauses = store.pauses.associateBy { it.alarmId }
            val active = store.alarms
                .mapNotNull { a -> a.nextTrigger(now, pauses[a.id])?.let { a to it } }
                .sortedBy { it.second }
                .map { (a, next) ->
                    val p = pauses[a.id]
                    val sub = if (p != null && p.state == PauseState.PENDING) "Vacances dès le ${shortDate(p.from)}" else a.recurrenceLabel
                    WidgetRow(a.startTime.hm(), dayLabel(next, now), sub)
                }
            val paused = store.alarms
                .filter { a -> pauses[a.id]?.state == PauseState.DISABLED_BY_APP }
                .map { a -> WidgetRow(a.startTime.hm(), pauses.getValue(a.id!!).untilLabel, a.recurrenceLabel, paused = true) }
            (store.speaker?.name ?: "Sonos") to (active + paused)
        }
        val open = actionStartActivity<MainActivity>()

        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(open),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_stat_alarm),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(18.dp),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    "Alarmes",
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(speaker, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(6.dp))
            if (rows.isEmpty()) {
                Text(
                    "Aucune alarme prévue",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    items(rows.withIndex().toList()) { (index, row) ->
                        val first = index == 0 && !row.paused
                        Row(
                            GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.time,
                                style = TextStyle(
                                    color = when {
                                        first -> GlanceTheme.colors.primary
                                        row.paused -> GlanceTheme.colors.outline
                                        else -> GlanceTheme.colors.onSurface
                                    },
                                    fontSize = if (first) 30.sp else 24.sp,
                                    fontWeight = if (first) FontWeight.Bold else FontWeight.Medium,
                                ),
                            )
                            Spacer(GlanceModifier.width(12.dp))
                            Column {
                                Text(
                                    row.day,
                                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                )
                                Text(
                                    row.recurrence,
                                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Redraws every placed widget from the local cache. */
        suspend fun updateAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            val widget = AlarmWidget()
            manager.getGlanceIds(AlarmWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it[VERSION] = System.currentTimeMillis() }
                widget.update(context, id)
            }
        }
    }
}

class AlarmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlarmWidget()
}
