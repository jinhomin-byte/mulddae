package com.mulddae.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class TideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val station = TideRepository.selectedStation(context)
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val todayEvents = TideRepository.load(context, station, today).getOrDefault(emptyList())
        val tomorrow = TideRepository.load(context, station, today.plusDays(1)).getOrDefault(emptyList())
        val upcoming = (todayEvents + tomorrow).filter { it.time.isAfter(now.minusMinutes(30)) }.take(4)
        val m = mulddaeOf(today)

        provideContent {
            val white = ColorProvider(Color.White)
            Column(
                GlanceModifier.fillMaxSize()
                    .background(ColorProvider(Color(0xFF0B3954)))
                    .cornerRadius(20.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${station.name} ${today.monthValue}/${today.dayOfMonth}",
                        style = TextStyle(color = white, fontSize = 13.sp)
                    )
                }
                Text(
                    m.title(),
                    style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                )
                if (upcoming.isEmpty()) {
                    Text("데이터 없음 (앱에서 새로고침)", style = TextStyle(color = white, fontSize = 12.sp))
                }
                upcoming.forEach { e ->
                    val c = if (e.high) Color(0xFF90CAF9) else Color(0xFFFFCC80)
                    Row(GlanceModifier.fillMaxWidth()) {
                        Text(
                            "${if (e.high) "만조" else "간조"}  %02d:%02d  ${e.heightCm}cm"
                                .format(e.time.hour, e.time.minute),
                            style = TextStyle(color = ColorProvider(c), fontSize = 13.sp)
                        )
                    }
                }
            }
        }
    }
}

class TideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TideWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "tide_widget_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(3, TimeUnit.HOURS).build()
        )
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork("tide_widget_refresh")
    }
}

class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        TideWidget().updateAll(applicationContext)
        return Result.success()
    }
}
