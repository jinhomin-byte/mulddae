package com.mulddae.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { TideScreen() } } }
    }
}

private val TAB_NAMES = listOf("조위", "수온·기온", "바람", "염분")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TideScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var station by remember { mutableStateOf(TideRepository.selectedStation(ctx)) }
    // 가운데 점선이 가리키는 시각. 그래프를 끌면 바뀐다.
    var viewTime by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    var yearData by remember(station.code) { mutableStateOf<Map<Int, List<TideEvent>>>(emptyMap()) }
    val noData = remember(station.code) { HashSet<Int>() } // 데이터 없음이 확인된 미래 연도(이번 실행 동안만)
    var forceNext by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var stations by remember { mutableStateOf(listOf(station)) }

    // 실측(수온·기온·염분): 관측소가 바뀌거나 새로고침(tick)하면 오늘 자료를 다시 읽는다.
    var obsInfo by remember(station.code) { mutableStateOf<ObsInfo?>(null) }
    var obsError by remember(station.code) { mutableStateOf<String?>(null) }
    var obsLoading by remember(station.code) { mutableStateOf(false) }
    var forceObs by remember { mutableStateOf(false) }
    LaunchedEffect(station.code, tick) {
        val force = forceObs
        forceObs = false
        obsLoading = true
        try {
            val r = ObsRepository.load(ctx, station, force)
            r.onSuccess { obsInfo = it; obsError = null }
                .onFailure { obsError = it.message ?: "오류" }
        } finally {
            obsLoading = false
        }
    }
    // 실측 탭은 오늘만 보여준다: 날짜를 오늘이 아닌 날로 넘기면 조위 탭으로 돌아간다.
    LaunchedEffect(viewTime.toLocalDate()) {
        if (tab != 0 && viewTime.toLocalDate() != LocalDate.now()) tab = 0
    }

    var stationsFailed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 관측소 목록 로드. 새로고침(tick)을 누르면 실패했을 때 다시 시도한다.
    LaunchedEffect(tick) {
        TideRepository.loadStations(ctx).onSuccess { list ->
            stationsFailed = false
            if (list.isNotEmpty()) {
                stations = list
                val found = list.firstOrNull { it.code == station.code }
                if (found == null) {
                    // 저장된 관측소가 목록에 없으면 기본(인천)으로 되돌린다.
                    val def = list.firstOrNull { it.code == DEFAULT_STATION.code } ?: DEFAULT_STATION
                    station = def
                    TideRepository.selectStation(ctx, def)
                } else if (found.name != station.name) {
                    station = found
                    TideRepository.selectStation(ctx, found)
                }
            }
        }.onFailure { stationsFailed = true }
    }

    // 점선 앞뒤 12시간이 걸치는 연도 파일을 받아 둔다(이미 받은 해는 건너뜀).
    val yearFrom = viewTime.minusDays(1).year
    val yearTo = viewTime.plusDays(1).year
    // 관측소가 바뀌면 이전 안내·오류 문구를 지운다(아래 로드 효과보다 먼저 실행됨).
    LaunchedEffect(station.code) {
        error = null
        notice = null
    }
    LaunchedEffect(station, yearFrom, yearTo, tick) {
        val force = forceNext // 한 번 읽고 바로 리셋
        forceNext = false
        val years = listOf(yearFrom, yearTo).distinct()
        if (force) noData.clear()
        val needed = if (force) years else years.filter { !yearData.containsKey(it) && it !in noData }
        if (needed.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            var anyOk = false
            var currentYearOk = false
            for (y in needed) {
                val r = TideRepository.loadYear(ctx, station, y, force)
                if (r.isSuccess) {
                    anyOk = true
                    if (y == LocalDate.now().year) currentYearOk = true
                    yearData = yearData + (y to r.getOrThrow()) // 실패한 해는 넣지 않아 다시 시도된다
                    error = null
                    notice = if (force && TideRepository.lastDownloadFailed) "새로고침 실패, 저장된 데이터 표시" else null
                } else if (y > LocalDate.now().year) {
                    // 미래 연도의 데이터 없음은 오류로 보이지 않게 하고, 이번 실행 동안 다시 묻지 않는다.
                    noData.add(y)
                } else {
                    error = r.exceptionOrNull()?.message
                }
            }
            if (currentYearOk || (force && anyOk)) TideWidget().updateAll(ctx)
        } finally {
            loading = false
        }
    }

    val allEvents = remember(yearData) { yearData.values.flatten().sortedBy { it.time } }
    val viewDate = viewTime.toLocalDate()
    val dayEvents = remember(allEvents, viewDate) { allEvents.filter { it.time.toLocalDate() == viewDate } }
    val m = remember(viewDate) { mulddaeOf(viewDate) }
    val dateFmt = DateTimeFormatter.ofPattern("M월 d일")

    // 세로 범위: 로드된 모든 연도 데이터의 최소·최대 (100cm 단위로 바깥으로). 데이터 없으면 -100~1000
    val yMin = if (allEvents.isEmpty()) -100
        else Math.floorDiv(allEvents.minOf { it.heightCm } - 10, 100) * 100
    val yMax = if (allEvents.isEmpty()) 1000
        else -Math.floorDiv(-(allEvents.maxOf { it.heightCm } + 10), 100) * 100

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = viewDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        viewTime = LocalDateTime.of(d, viewTime.toLocalTime())
                    }
                    showPicker = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("취소") } }
        ) { DatePicker(state = pickerState) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                OutlinedButton(onClick = { menu = true }) {
                    Text("관측소: ${station.name}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    stations.forEach { s ->
                        DropdownMenuItem(text = { Text(s.name) }, onClick = {
                            menu = false
                            station = s
                            TideRepository.selectStation(ctx, s)
                        })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) }) {
                Text("현재", maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                forceNext = true
                forceObs = true
                notice = null
                error = null
                tick++
            }, enabled = !loading) { Text("새로고침", maxLines = 1, softWrap = false) }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewTime = viewTime.minusDays(1) }) { Text("◀") }
            Column(
                Modifier.weight(1f).clickable { showPicker = true },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "${viewDate.format(dateFmt)} (${viewDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
                    fontSize = 20.sp
                )
                Text("음력 ${m.lunarMonth}.${m.lunarDay}", fontSize = 13.sp, color = Color.Gray)
            }
            TextButton(onClick = { viewTime = viewTime.plusDays(1) }) { Text("▶") }
        }

        TabRow(selectedTabIndex = tab) {
            TAB_NAMES.forEachIndexed { i, name ->
                // 수온이 없는 실측소는 '수온·기온' 탭 이름을 '기온'으로 바꾼다.
                val label = if (i == 1 && obsInfo?.let { it.inIndex && !it.hasWtem } == true) "기온" else name
                Tab(selected = tab == i, onClick = {
                    // 실측 탭은 오늘만 보여주므로 날짜를 오늘로 되돌린다.
                    if (i != 0 && viewTime.toLocalDate() != LocalDate.now()) {
                        viewTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                    }
                    tab = i
                }, text = { Text(label, maxLines = 2, fontSize = 12.sp) })
            }
        }

        if (stationsFailed) {
            Text("관측소 목록을 불러오지 못했습니다. 새로고침을 눌러 다시 시도하세요.",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        notice?.let { Text(it, color = Color.Gray, fontSize = 13.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        // 탭 내용은 이 영역 안에서 세로로 스크롤된다(출처 줄은 맨 아래 고정).
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        if (tab == 1 || tab == 3) {
            ObsTabContent(obsInfo, obsLoading, obsError, salinity = tab == 3)
        } else if (tab == 2) {
            WindTabContent(obsInfo, obsLoading, obsError)
        } else {
            val now = tideHeightAt(allEvents, viewTime)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(m.title(), fontSize = 28.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.Gray)) {
                            append(viewTime.format(DateTimeFormatter.ofPattern("M월 d일")))
                        }
                        withStyle(SpanStyle(color = CURRENT_LINE_COLOR, fontWeight = FontWeight.Bold)) {
                            append(" " + viewTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                            if (now != null) append(" · 약 ${Math.round(now)} cm")
                        }
                    },
                    fontSize = 14.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                TideChart(
                    events = allEvents,
                    center = viewTime,
                    onCenterChange = { viewTime = it },
                    yMin = yMin,
                    yMax = yMax,
                    modifier = Modifier.fillMaxWidth().height(270.dp)
                )
            }

            if (dayEvents.isEmpty() && !loading) {
                Text(
                    "이 날짜의 조석 데이터가 없습니다.\n(오늘부터 약 1년 뒤까지만 제공됩니다)",
                    color = Color.Gray, fontSize = 13.sp
                )
            }
            dayEvents.forEach { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
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

        Text(
            if (tab == 0) TideRepository.SOURCE_TEXT else ObsRepository.SOURCE_TEXT,
            fontSize = 9.sp, color = Color.Gray, maxLines = 1
        )
    }
}
