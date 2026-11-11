package com.mulddae.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { TideScreen() } } }
    }
}

@Composable
fun TideScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var station by remember { mutableStateOf(TideRepository.selectedStation(ctx)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var events by remember { mutableStateOf<List<TideEvent>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(station, date, tick) {
        loading = true
        val r = TideRepository.load(ctx, station, date, force = tick > 0)
        events = r.getOrDefault(emptyList())
        error = r.exceptionOrNull()?.message
        loading = false
        if (r.isSuccess) TideWidget().updateAll(ctx)
    }

    val m = mulddaeOf(date)
    val dateFmt = DateTimeFormatter.ofPattern("M월 d일")

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            OutlinedButton(onClick = { menu = true }) { Text("관측소: ${station.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                STATIONS.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = {
                        menu = false
                        station = s
                        TideRepository.selectStation(ctx, s)
                    })
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${date.format(dateFmt)} (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
                    fontSize = 20.sp
                )
                Text("음력 ${m.lunarMonth}.${m.lunarDay}", fontSize = 13.sp, color = Color.Gray)
            }
            TextButton(onClick = { date = date.plusDays(1) }) { Text("▶") }
        }

        Text(m.title(), fontSize = 36.sp, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { date = LocalDate.now() }) { Text("오늘") }
            Button(onClick = { tick++ }, enabled = !loading) { Text("새로고침") }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (e.high) "만조" else "간조", fontSize = 18.sp,
                            color = if (e.high) Color(0xFF1565C0) else Color(0xFFEF6C00))
                        Text("%02d:%02d".format(e.time.hour, e.time.minute), fontSize = 18.sp)
                        Text("${e.heightCm}cm", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
