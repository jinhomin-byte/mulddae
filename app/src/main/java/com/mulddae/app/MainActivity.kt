package com.mulddae.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.style.TextOverflow
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
        setContent {
            var theme by remember { mutableIntStateOf(loadTheme(this@MainActivity)) }
            AppTheme(theme) {
                TideScreen(theme) { theme = it; saveTheme(this@MainActivity, it) }
            }
        }
    }
}

private val TAB_LABELS = mapOf("tide" to "조위", "current" to "조류", "obs" to "수온·기온", "wind" to "바람", "sal" to "염분")
private val STAR_COLOR = Color(0xFFF9A825)

/** 즐겨찾기 안내 문구. undo가 있으면 '되돌리기' 버튼이 붙는다(삭제 위치, 관측소). */
private class FavNote(val text: String, val undo: Pair<Int, Station>?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TideScreen(themeIndex: Int = 0, onThemeSelect: (Int) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var station by remember { mutableStateOf(TideRepository.selectedStation(ctx)) }
    // 가운데 점선이 가리키는 시각. 그래프를 끌면 바뀐다.
    var viewTime by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    var yearData by remember(station.code) { mutableStateOf<Map<Int, List<TideEvent>>>(emptyMap()) }
    val noData = remember(station.code) { HashSet<Int>() } // 데이터 없음이 확인된 미래 연도(이번 실행 동안만)
    var forceNext by remember { mutableStateOf(false) }
    var tabKey by remember { mutableStateOf("tide") }
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
    val obsDate = viewTime.toLocalDate()
    var obsInfoDate by remember(station.code) { mutableStateOf(obsDate) }
    LaunchedEffect(station.code, tick, obsDate) {
        val force = forceObs
        forceObs = false
        // 날짜가 바뀌면 이전 데이터를 비우고 로딩 표시부터 보여준다(새로고침·관측소 변경은 기존 값 유지).
        if (obsInfo != null && obsInfoDate != obsDate) obsInfo = null
        obsInfoDate = obsDate
        if (obsDate.isAfter(LocalDate.now())) return@LaunchedEffect // 미래는 안내 문구만 보여준다
        obsError = null
        obsLoading = true
        try {
            val r = ObsRepository.load(ctx, station, force, obsDate)
            r.onSuccess { obsInfo = it; obsError = null }
                .onFailure { obsError = it.message ?: "오류" }
        } finally {
            obsLoading = false
        }
    }

    // 조류: 짝 지점이 있는 관측소만 탭을 보인다(curHasPair == true). 오늘-1일~+6일 밖의 날짜는 다시 받지 않는다.
    var curDay by remember(station.code) { mutableStateOf<CurrentRepository.Day?>(null) }
    var curDayDate by remember(station.code) { mutableStateOf<LocalDate?>(null) }
    var curLoading by remember(station.code) { mutableStateOf(false) }
    // 짝 지점 유무: 캐시된 map.json으로 먼저 바로 판단(null=아직 모름). 짝이 있으면 로딩·실패 중에도 탭을 유지한다.
    var curHasPair by remember(station.code) { mutableStateOf(CurrentRepository.cachedHasPair(ctx, station.code)) }
    val curReq = remember { IntArray(1) } // 가장 최근에 시작한 불러오기 번호(이전 효과가 로딩 표시를 끄지 못하게)
    var forceCur by remember { mutableStateOf(false) }
    val curInRange = !obsDate.isBefore(LocalDate.now().minusDays(1)) && !obsDate.isAfter(LocalDate.now().plusDays(6))
    LaunchedEffect(station.code, tick, obsDate) {
        val force = forceCur
        forceCur = false
        if (!curInRange && curDay != null) return@LaunchedEffect
        val q = if (curInRange) obsDate else LocalDate.now()
        val myReq = ++curReq[0]
        curLoading = true
        try {
            val pair = CurrentRepository.hasPair(ctx, station.code)
            if (pair != null) curHasPair = pair
            if (pair == false) {
                curDay = null
                curDayDate = null
                return@LaunchedEffect
            }
            val r = CurrentRepository.dayFor(ctx, station.code, q, force)
            val newDay = r ?: curDay?.let { CurrentRepository.Day(it.pointName, it.distanceKm, it.maxCrsp, null, null) }
            curDay = newDay
            curDayDate = q
        } finally {
            if (curReq[0] == myReq) curLoading = false
        }
    }
    // 새 관측소에 조류가 없으면 조류 탭 선택을 조위로 정리한다(돌아왔을 때 선택 안 한 탭으로 점프 방지).
    LaunchedEffect(curHasPair) {
        if (curHasPair == false && tabKey == "current") tabKey = "tide"
    }
    val tabKeys = if (curHasPair == true) listOf("tide", "current", "obs", "wind", "sal") else listOf("tide", "obs", "wind", "sal")
    val tab = if (tabKey in tabKeys) tabKey else "tide"

    var stationsFailed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 수온 마킹(온도계): obs index의 wtemLast가 24시간 이내인 관측소. 실패하면 마킹 없음.
    var wtemCodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(tick) {
        ObsRepository.loadWtemCodes(ctx).onSuccess { wtemCodes = it }
    }

    // 즐겨찾기(최대 5개). favNote: 안내 문구(+되돌리기용 삭제 항목), 몇 초 뒤 사라진다.
    var favs by remember { mutableStateOf(Favorites.load(ctx)) }
    var editCode by remember { mutableStateOf<String?>(null) }
    var favNote by remember { mutableStateOf<FavNote?>(null) }
    LaunchedEffect(favNote) {
        if (favNote != null) {
            kotlinx.coroutines.delay(4000)
            favNote = null
        }
    }
    var stationsReady by remember { mutableStateOf(false) } // 서버 관측소 목록을 받은 뒤 true
    fun setFavs(list: List<Station>) {
        favs = list
        Favorites.save(ctx, list)
        favNote = null // 즐겨찾기가 바뀌면 되돌리기 안내를 닫는다
    }
    // 칩이 사라지면 ×도 해제
    LaunchedEffect(favs, editCode) {
        if (editCode != null && favs.none { it.code == editCode }) editCode = null
    }
    fun toggleFav(s: Station) {
        if (favs.any { it.code == s.code }) {
            setFavs(favs.filter { it.code != s.code })
            favNote = null
        } else if (favs.size >= MAX_FAVORITES) {
            favNote = FavNote("최대 5개까지 가능합니다", null)
        } else {
            setFavs(favs + s)
        }
    }
    fun selectStation(s: Station) {
        editCode = null
        station = s
        TideRepository.selectStation(ctx, s)
    }

    // 관측소 목록 로드. 새로고침(tick)을 누르면 실패했을 때 다시 시도한다.
    LaunchedEffect(tick) {
        TideRepository.loadStations(ctx).onSuccess { list ->
            stationsFailed = false
            if (list.isNotEmpty()) {
                stations = list
                stationsReady = true
                // 즐겨찾기: 목록에 없는 코드는 정리하고 이름은 목록의 최신 이름으로 맞춘다.
                val cleaned = favs.mapNotNull { f -> list.firstOrNull { it.code == f.code } }
                if (cleaned != favs) setFavs(cleaned)
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
        editCode = null
        error = null
        notice = null
        // 이미 받아 둔 데이터여도 위젯은 새 관측소로 바로 다시 그린다(캐시가 없으면 로드 성공 뒤 한 번 더 갱신).
        refreshAllWidgets(ctx)
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
            if (currentYearOk || (force && anyOk)) refreshAllWidgets(ctx)
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
                OutlinedButton(
                    onClick = { menu = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ptextColor()),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "관측소 선택, ${station.name}" }
                ) {
                    ThermoMark(station.code in wtemCodes)
                    var nameSize by remember(station.name) { mutableStateOf(14f) }
                    Text(
                        station.name, maxLines = 1, softWrap = false,
                        fontSize = nameSize.sp,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        onTextLayout = { r ->
                            if (r.hasVisualOverflow && nameSize > 9f) {
                                nameSize = (nameSize - 0.5f).coerceAtLeast(9f)
                            }
                        },
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(" ▾", maxLines = 1, softWrap = false)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    stations.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ThermoMark(s.code in wtemCodes)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.name)
                                }
                            },
                            trailingIcon = {
                                val fav = favs.any { it.code == s.code }
                                Text(
                                    if (fav) "★" else "☆", fontSize = 22.sp,
                                    color = if (fav) STAR_COLOR else subColor(),
                                    modifier = Modifier
                                        .semantics { contentDescription = if (fav) "즐겨찾기 해제" else "즐겨찾기 추가" }
                                        .clickable { toggleFav(s) }.padding(horizontal = 6.dp)
                                )
                            },
                            onClick = {
                                menu = false
                                selectStation(s)
                            })
                    }
                }
            }
            val curFav = favs.any { it.code == station.code }
            Spacer(Modifier.width(5.dp))
            CircleOutlineButton(
                onClick = { toggleFav(station) },
                description = if (curFav) "즐겨찾기 해제" else "즐겨찾기 추가"
            ) {
                Text(if (curFav) "★" else "☆", fontSize = 22.sp, color = if (curFav) STAR_COLOR else subColor())
            }
            Spacer(Modifier.width(5.dp))
            CircleOutlineButton(
                onClick = { viewTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) },
                description = "현재로 이동"
            ) {
                var todaySize by remember { mutableStateOf(12f) }
                Text(
                    "오늘", fontSize = todaySize.sp, fontWeight = FontWeight.SemiBold,
                    color = ptextColor(), maxLines = 1, softWrap = false,
                    onTextLayout = { r ->
                        if (r.hasVisualOverflow && todaySize > 9f) {
                            todaySize = (todaySize - 0.5f).coerceAtLeast(9f)
                        }
                    }
                )
            }
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (loading) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !loading) {
                        forceNext = true
                        forceObs = true
                        forceCur = true
                        notice = null
                        error = null
                        tick++
                    }
                    .semantics { contentDescription = "새로고침" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(5.dp))
            ThemeButton(themeIndex, onThemeSelect)
        }

        if (favs.isNotEmpty()) {
            FavoriteChips(
                favs = favs, selectedCode = station.code, wtemCodes = wtemCodes, editCode = editCode,
                onSelect = { f ->
                    // 목록을 받은 뒤에는 반드시 목록 안의 Station을 쓴다.
                    val target = if (stationsReady) stations.firstOrNull { it.code == f.code } else f
                    if (target != null) selectStation(target)
                },
                onLongPress = { editCode = it.code },
                onDelete = { s ->
                    val idx = favs.indexOfFirst { it.code == s.code }
                    if (idx >= 0) {
                        setFavs(favs.filter { it.code != s.code })
                        favNote = FavNote("'${s.name}' 삭제됨", idx to s)
                    }
                    editCode = null
                }
            )
        }
        favNote?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.text, fontSize = 13.sp, color = subColor())
                n.undo?.let { (idx, s) ->
                    TextButton(
                        onClick = {
                            if (favs.none { it.code == s.code }) {
                                if (favs.size < MAX_FAVORITES) {
                                    setFavs(favs.toMutableList().also { it.add(idx.coerceIn(0, it.size), s) })
                                } else {
                                    favNote = FavNote("최대 5개까지 가능합니다", null)
                                }
                            } else {
                                favNote = null
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("되돌리기", fontSize = 13.sp) }
                }
            }
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
                Text("음력 ${m.lunarMonth}.${m.lunarDay}", fontSize = 13.sp, color = subColor())
            }
            TextButton(onClick = { viewTime = viewTime.plusDays(1) }) { Text("▶") }
        }

        TabRow(selectedTabIndex = tabKeys.indexOf(tab)) {
            tabKeys.forEach { key ->
                // 수온이 없는 실측소는 '수온·기온' 탭 이름을 '기온'으로 바꾼다.
                val label = if (key == "obs" && obsInfo?.let { it.inIndex && !it.hasWtem } == true) "기온" else TAB_LABELS.getValue(key)
                Tab(selected = tab == key, onClick = { tabKey = key }, modifier = Modifier.height(40.dp), selectedContentColor = ptextColor(), text = {
                    Text(
                        label, maxLines = 1, softWrap = false, fontSize = 11.sp, lineHeight = 16.sp,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
                        modifier = Modifier.wrapContentWidth(unbounded = true)
                    )
                })
            }
        }

        if (stationsFailed) {
            Text("관측소 목록을 불러오지 못했습니다. 새로고침을 눌러 다시 시도하세요.",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        notice?.let { Text(it, color = subColor(), fontSize = 13.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        // 탭 내용은 이 영역 안에서 세로로 스크롤된다(출처 줄은 맨 아래 고정).
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        if (tab == "current") {
            CurrentTabContent(
                day = curDay, dayDate = curDayDate, viewDate = viewDate, loading = curLoading,
                themeIndex = themeIndex, events = allEvents, yMin = yMin, yMax = yMax
            )
        } else if (tab != "tide" && viewDate.isAfter(LocalDate.now())) {
            ObsMessage("미래의 정보는 제공하지 않습니다.")
        } else if (tab == "obs" || tab == "sal") {
            ObsTabContent(obsInfo, obsLoading, obsError, salinity = tab == "sal", isToday = viewDate == LocalDate.now())
        } else if (tab == "wind") {
            WindTabContent(obsInfo, obsLoading, obsError, isToday = viewDate == LocalDate.now())
        } else {
            val now = tideHeightAt(allEvents, viewTime)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(m.title(), fontSize = 28.sp, lineHeight = 32.sp, color = ptextColor())
                val sub = subColor()
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = sub)) {
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
                    if (viewDate.isBefore(LocalDate.now()))
                        "이 날짜의 조석 데이터가 없습니다.\n과거 자료는 오늘부터 30일 전까지만 제공됩니다."
                    else
                        "이 날짜의 조석 데이터가 없습니다.\n(오늘부터 약 1년 뒤까지만 제공됩니다)",
                    color = subColor(), fontSize = 13.sp
                )
            }
            dayEvents.forEach { e ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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

        if (tab == "current") {
            CurrentFooter(curDay)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val sourceText = if (tab == "tide") TideRepository.SOURCE_TEXT else ObsRepository.SOURCE_TEXT
            var sourceSize by remember(sourceText) { mutableStateOf(9f) }
            Text(
                sourceText,
                fontSize = sourceSize.sp, color = subColor(), maxLines = 1, softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { r ->
                    if (r.hasVisualOverflow && sourceSize > 6.5f) {
                        sourceSize = (sourceSize - 0.5f).coerceAtLeast(6.5f)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
