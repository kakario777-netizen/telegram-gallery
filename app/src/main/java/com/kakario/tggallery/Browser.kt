package com.kakario.tggallery

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toIntRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Sort(val label: String) { NEW("최신순"), OLD("오래된순"), LONG("긴 영상순"), SHORT("짧은 영상순"), BIG("큰 파일순"), SMALL("작은 파일순") }
enum class KindF(val label: String) { ALL("전체"), PHOTO("사진"), VIDEO("영상"), GIF("GIF") }
enum class DurF(val label: String, val min: Int, val max: Int) {
    ALL("길이 전체", 0, Int.MAX_VALUE), S1("1분 미만", 0, 59), S5("1~5분", 60, 299),
    S20("5~20분", 300, 1199), S60("20분~1시간", 1200, 3599), L("1시간 이상", 3600, Int.MAX_VALUE)
}
enum class DateF(val label: String, val days: Int) { ALL("기간 전체", 0), W("최근 1주", 7), M("최근 1달", 30), M3("최근 3달", 90), Y("최근 1년", 365), OLD("1년 이전", -365) }

sealed interface Cell
data class Header(val text: String) : Cell
data class Entry(val item: MediaItem, val index: Int) : Cell

/** 채팅방 미디어 화면 (뒤로가기 있는 전체 화면) */
@Composable
fun MediaScreen(target: Target) {
    val scope = rememberCoroutineScope()
    val loader = remember { MediaLoader(scope, target.chatId, target.list).also { it.start() } }
    val all by loader.items.collectAsState()
    val loading by loader.loading.collectAsState()
    val total by loader.total.collectAsState()
    val err by loader.error.collectAsState()
    MediaBrowser(target.title, all, loading, if (loading) "불러오는 중 ${all.size} / $total" else null, err, onBack = { Nav.pop() })
}

/** 즐겨찾기 탭 */
@Composable
fun FavoritesTab() {
    val scope = rememberCoroutineScope()
    val loader = remember { FavLoader(scope).also { it.start() } }
    val all by loader.items.collectAsState()
    val loading by loader.loading.collectAsState()
    if (!loading && all.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("즐겨찾기한 사진·영상이 없어요\n목록에서 꾹 눌러 고른 뒤 하트를 눌러 추가하세요", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    MediaBrowser("즐겨찾기", all, loading, null, null, onBack = null)
}

/** 정렬·필터·격자·여러 장 선택이 들어간 공용 화면 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowser(title: String, all: List<MediaItem>, loading: Boolean, progress: String?, err: String?, onBack: (() -> Unit)?) {
    var sort by remember { mutableStateOf(Sort.NEW) }
    var kind by remember { mutableStateOf(KindF.ALL) }
    var dur by remember { mutableStateOf(DurF.ALL) }
    var date by remember { mutableStateOf(DateF.ALL) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val favs by Prefs.favs.collectAsState()

    val list = remember(all, sort, kind, dur, date, query) {
        val now = System.currentTimeMillis() / 1000
        all.asSequence()
            .filter {
                when (kind) {
                    KindF.ALL -> true
                    KindF.PHOTO -> it.kind == Kind.PHOTO
                    KindF.VIDEO -> it.kind == Kind.VIDEO
                    KindF.GIF -> it.kind == Kind.GIF
                }
            }
            .filter { dur == DurF.ALL || (it.kind != Kind.PHOTO && it.duration in dur.min..dur.max) }
            .filter {
                when {
                    date.days > 0 -> it.date >= now - date.days * 86400L
                    date.days < 0 -> it.date < now + date.days * 86400L
                    else -> true
                }
            }
            .filter { query.isBlank() || it.caption.contains(query, true) || it.fileName.contains(query, true) }
            .let { s ->
                when (sort) {
                    Sort.NEW -> s.sortedByDescending { it.date }
                    Sort.OLD -> s.sortedBy { it.date }
                    Sort.LONG -> s.sortedByDescending { it.duration }
                    Sort.SHORT -> s.sortedWith(compareBy<MediaItem> { it.kind == Kind.PHOTO }.thenBy { it.duration })
                    Sort.BIG -> s.sortedByDescending { it.size }
                    Sort.SMALL -> s.sortedBy { it.size }
                }
            }
            .toList()
    }
    val cells = remember(list, sort) {
        val out = ArrayList<Cell>(list.size + 64)
        val byMonth = sort == Sort.NEW || sort == Sort.OLD
        val fmt = SimpleDateFormat("yyyy년 M월", Locale.KOREA)
        var last = ""
        list.forEachIndexed { i, m ->
            if (byMonth) {
                val h = fmt.format(Date(m.date * 1000L))
                if (h != last) { out += Header(h); last = h }
            }
            out += Entry(m, i)
        }
        out
    }
    val selMode = selected.isNotEmpty()
    BackHandler(enabled = selMode) { selected = emptySet() }
    BackHandler(enabled = !selMode && searching) { query = ""; searching = false }

    Column(Modifier.fillMaxSize()) {
        if (selMode) {
            val chosen = list.filter { it.key in selected }
            var menu by remember { mutableStateOf(false) }
            TopAppBar(
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "선택 해제") } },
                title = { Text("${selected.size}개 선택") },
                actions = {
                    val allFav = chosen.isNotEmpty() && chosen.all { it.key in favs }
                    IconButton(onClick = {
                        Prefs.setFav(chosen.map { it.key }, !allFav)
                        selected = emptySet()
                    }) { Icon(if (allFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "즐겨찾기") }
                    IconButton(onClick = { Downloader.enqueue(chosen); selected = emptySet() }) { Icon(Ic.Download, "다운로드") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "메뉴") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("전체 선택") }, leadingIcon = { Icon(Ic.SelectAll, null) },
                            onClick = { menu = false; selected = list.map { it.key }.toSet() })
                        DropdownMenuItem(text = { Text("즐겨찾기 추가") }, leadingIcon = { Icon(Icons.Default.Favorite, null) },
                            onClick = { menu = false; Prefs.setFav(chosen.map { it.key }, true); selected = emptySet() })
                        DropdownMenuItem(text = { Text("즐겨찾기 해제") }, leadingIcon = { Icon(Icons.Default.FavoriteBorder, null) },
                            onClick = { menu = false; Prefs.setFav(chosen.map { it.key }, false); selected = emptySet() })
                        DropdownMenuItem(text = { Text("다운로드") }, leadingIcon = { Icon(Ic.Download, null) },
                            onClick = { menu = false; Downloader.enqueue(chosen); selected = emptySet() })
                        DropdownMenuItem(text = { Text("선택 해제") }, leadingIcon = { Icon(Icons.Default.Close, null) },
                            onClick = { menu = false; selected = emptySet() })
                    }
                })
        } else if (onBack != null || searching) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (searching) { query = ""; searching = false } else onBack?.invoke() }) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                title = {
                    if (searching) {
                        TextField(query, { query = it }, placeholder = { Text("설명·파일명 글자로 찾기") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    } else Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(progress ?: "${list.size}개 표시 · 전체 ${all.size}개", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "검색") }
                })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Pick(kind.label, kind != KindF.ALL, KindF.entries.map { it.label }) { kind = KindF.entries[it] } }
                item { Pick(sort.label, sort != Sort.NEW, Sort.entries.map { it.label }) { sort = Sort.entries[it] } }
                item { Pick(dur.label, dur != DurF.ALL, DurF.entries.map { it.label }) { dur = DurF.entries[it] } }
                item { Pick(date.label, date != DateF.ALL, DateF.entries.map { it.label }) { date = DateF.entries[it] } }
            }
            if (onBack == null && !searching && !selMode) {
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "검색") }
            }
        }
        if (onBack == null && !selMode && !searching) {
            Text("${list.size}개 표시 · 전체 ${all.size}개", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        if (!loading && list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("조건에 맞는 사진·영상이 없어요") }
        } else {
            MediaGrid(cells, selected, { selected = it }) { Nav.open(list, it) }
        }
    }
}

/** 격자 + 꾹 눌러 끌어서 여러 장 선택 (구글 포토 방식) */
@Composable
fun MediaGrid(cells: List<Cell>, selected: Set<String>, onSelect: (Set<String>) -> Unit, onOpen: (Int) -> Unit) {
    val state = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    val curSelected by rememberUpdatedState(selected)
    LaunchedEffect(autoScroll) {
        if (autoScroll != 0f) while (isActive) { state.scrollBy(autoScroll); delay(10) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp), state = state,
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize().pointerInput(cells) {
            var anchor = -1
            var base = emptySet<String>()
            var adding = true
            detectDragGesturesAfterLongPress(
                onDragStart = { off ->
                    val i = state.cellAt(off)
                    val c = i?.let { cells.getOrNull(it) }
                    if (c is Entry) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor = i
                        base = curSelected
                        adding = c.item.key !in base
                        onSelect(if (adding) base + c.item.key else base - c.item.key)
                    } else anchor = -1
                },
                onDrag = { change, _ ->
                    if (anchor < 0) return@detectDragGesturesAfterLongPress
                    change.consume()
                    val y = change.position.y
                    val h = size.height
                    autoScroll = when {
                        y > h - edge -> (y - (h - edge)) / 4f
                        y < edge -> -(edge - y) / 4f
                        else -> 0f
                    }
                    val cur = state.cellAt(change.position) ?: return@detectDragGesturesAfterLongPress
                    val range = if (cur >= anchor) anchor..cur else cur..anchor
                    val keys = range.mapNotNull { (cells.getOrNull(it) as? Entry)?.item?.key }
                    onSelect(if (adding) base + keys else base - keys.toSet())
                },
                onDragEnd = { autoScroll = 0f; anchor = -1 },
                onDragCancel = { autoScroll = 0f; anchor = -1 },
            )
        }
    ) {
        items(cells.size, key = { i -> when (val c = cells[i]) { is Header -> "h" + c.text; is Entry -> c.item.key } },
            span = { i -> if (cells[i] is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
            when (val c = cells[i]) {
                is Header -> Text(c.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp))
                is Entry -> {
                    val sel = c.item.key in selected
                    GridCell(c.item, selected.isNotEmpty(), sel) {
                        if (selected.isNotEmpty()) onSelect(if (sel) selected - c.item.key else selected + c.item.key)
                        else onOpen(c.index)
                    }
                }
            }
        }
    }
}

private fun LazyGridState.cellAt(p: Offset): Int? =
    layoutInfo.visibleItemsInfo.find { it.size.toIntRect().contains(IntOffset((p.x - it.offset.x).toInt(), (p.y - it.offset.y).toInt())) }?.index

@Composable
fun Pick(label: String, active: Boolean, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = active, onClick = { open = true }, label = { Text("$label ▾") })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
fun GridCell(m: MediaItem, selMode: Boolean, selected: Boolean, onClick: () -> Unit) {
    val fav = Prefs.favs.collectAsState().value.contains(m.key)
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        TdImage(m.thumb, m.mini, 2, Modifier.fillMaxSize().padding(if (selected) 10.dp else 0.dp).clip(RoundedCornerShape(if (selected) 8.dp else 0.dp)), ContentScale.Crop)
        if (m.kind != Kind.PHOTO) {
            Text(
                if (m.kind == Kind.GIF) "GIF" else fmtDur(m.duration),
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        if (fav) Icon(Icons.Default.Favorite, null, tint = Color(0xFFFF5A79),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp))
        if (selMode) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x55000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 미니 썸네일(흐린 미리보기)을 먼저 보여주고, 받은 이미지로 교체 */
@Composable
fun TdImage(file: TdApi.File?, mini: ByteArray?, priority: Int, modifier: Modifier, scale: ContentScale) {
    val miniBmp = remember(mini) { mini?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    var path by remember(file?.id) {
        mutableStateOf(file?.local?.takeIf { it.isDownloadingCompleted && it.path.isNotEmpty() }?.path)
    }
    LaunchedEffect(file?.id) {
        if (path == null && file != null) path = runCatching { Td.download(file, priority) }.getOrNull()
    }
    Box(modifier) {
        if (miniBmp != null) Image(miniBmp, null, Modifier.fillMaxSize(), contentScale = scale)
        path?.let { coil.compose.AsyncImage(model = File(it), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale) }
    }
}

fun fmtDur(s: Int): String {
    val h = s / 3600; val mm = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%d:%02d".format(mm, ss)
}

fun fmtMs(ms: Long) = fmtDur((ms / 1000).toInt().coerceAtLeast(0))

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1fGB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1fMB".format(b / (1L shl 20).toDouble())
    b > 0 -> "%dKB".format(b / 1024)
    else -> "-"
}
