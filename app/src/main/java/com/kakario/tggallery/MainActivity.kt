package com.kakario.tggallery

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Td.init(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

// ───────────────────────── 로그인 흐름 ─────────────────────────

@Composable
fun Root() {
    val state by Td.auth.collectAsState()
    val keysSet by Td.keysSet.collectAsState()
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        when (val s = state) {
            null, is TdApi.AuthorizationStateClosed, is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateLoggingOut -> Loading("연결 중...")
            is TdApi.AuthorizationStateWaitTdlibParameters ->
                if (keysSet) Loading("연결 중...") else KeyScreen()
            is TdApi.AuthorizationStateWaitPhoneNumber -> PhoneScreen()
            is TdApi.AuthorizationStateWaitCode -> CodeScreen()
            is TdApi.AuthorizationStateWaitPassword -> PasswordScreen(s.passwordHint)
            is TdApi.AuthorizationStateReady -> MainNav()
            else -> Loading("이 로그인 방식은 아직 지원하지 않아요. 공식 텔레그램 앱에서 계정 상태를 확인해 주세요.")
        }
    }
}

@Composable
fun Loading(msg: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(msg)
    }
}

@Composable
fun FormPage(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    val err by Td.error.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        content()
        err?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun KeyScreen() {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    FormPage("처음 설정", "my.telegram.org에서 받은 api_id와 api_hash를 넣어주세요. 이 폰 안에만 저장돼요.") {
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text("api_id (숫자)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { id.toIntOrNull()?.let { Td.saveKeys(it, hash) } },
            enabled = id.isNotEmpty() && hash.length >= 16, modifier = Modifier.fillMaxWidth()) { Text("다음") }
    }
}

@Composable
fun PhoneScreen() {
    var phone by remember { mutableStateOf("+82") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("텔레그램 로그인", "전화번호를 국가번호와 함께 넣어주세요. 예: +821012345678") {
        OutlinedTextField(phone, { phone = it }, label = { Text("전화번호") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                val p = phone.replace(" ", "").replace("-", "").let { if (it.startsWith("010")) "+82" + it.drop(1) else it }
                try { Td.send(TdApi.SetAuthenticationPhoneNumber(p, null)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && phone.length > 6, modifier = Modifier.fillMaxWidth()) { Text("인증번호 받기") }
    }
}

@Composable
fun CodeScreen() {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("인증번호", "텔레그램 앱(또는 문자)으로 온 숫자를 넣어주세요.") {
        OutlinedTextField(code, { code = it.filter(Char::isDigit) }, label = { Text("인증번호") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                try { Td.send(TdApi.CheckAuthenticationCode(code)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && code.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("확인") }
    }
}

@Composable
fun PasswordScreen(hint: String) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    FormPage("2단계 비밀번호", if (hint.isNotEmpty()) "힌트: $hint" else "텔레그램에 설정한 2단계 인증 비밀번호를 넣어주세요.") {
        OutlinedTextField(pw, { pw = it }, label = { Text("비밀번호") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            busy = true; Td.error.value = null
            scope.launch {
                try { Td.send(TdApi.CheckAuthenticationPassword(pw)) } catch (e: Exception) { Td.error.value = e.message }
                busy = false
            }
        }, enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("확인") }
    }
}

// ───────────────────────── 화면 이동 ─────────────────────────

data class Target(val chatId: Long?, val title: String)

@Composable
fun MainNav() {
    var target by remember { mutableStateOf<Target?>(null) }
    val t = target
    if (t == null) ChatListScreen { target = it }
    else {
        BackHandler { target = null }
        key(t) { MediaScreen(t) { target = null } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(onOpen: (Target) -> Unit) {
    var chats by remember { mutableStateOf<List<TdApi.Chat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val ids = Td.send(TdApi.GetChats(TdApi.ChatListMain(), 500)).chatIds
            chats = ids.map { Td.chat(it) }
        } catch (e: Exception) {
            Td.error.value = e.message
        }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("텔레그램 갤러리") }, actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("로그아웃") }, onClick = { menu = false; Td.sendAsync(TdApi.LogOut()) })
            }
        })
        OutlinedTextField(query, { query = it }, placeholder = { Text("채팅방 이름 검색") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val shown = remember(chats, query) { if (query.isBlank()) chats else chats.filter { it.title.contains(query, true) } }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) item {
                ListItem(
                    headlineContent = { Text("전체 채팅 모아보기", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("모든 방의 사진·영상을 한곳에서") },
                    leadingContent = {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("All", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    modifier = Modifier.clickable { onOpen(Target(null, "전체 채팅")) })
                HorizontalDivider()
            }
            items(shown, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Text(c.title.take(1))
                            TdImage(c.photo?.small, c.photo?.minithumbnail?.data, 4, Modifier.fillMaxSize(), ContentScale.Crop)
                        }
                    },
                    modifier = Modifier.clickable { onOpen(Target(c.id, c.title)) })
            }
        }
    }
}

// ───────────────────────── 사진·영상 목록 ─────────────────────────

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(target: Target, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loader = remember { MediaLoader(scope, target.chatId).also { it.start() } }
    val all by loader.items.collectAsState()
    val loading by loader.loading.collectAsState()
    val total by loader.total.collectAsState()
    val err by loader.error.collectAsState()

    var sort by remember { mutableStateOf(Sort.NEW) }
    var kind by remember { mutableStateOf(KindF.ALL) }
    var dur by remember { mutableStateOf(DurF.ALL) }
    var date by remember { mutableStateOf(DateF.ALL) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }

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
            .filter { query.isBlank() || it.caption.contains(query, true) }
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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = {
                if (searching) {
                    TextField(query, { query = it }, placeholder = { Text("설명(캡션) 글자로 찾기") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                } else Column {
                    Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (loading) "불러오는 중 ${all.size} / $total" else "${list.size}개 표시 · 전체 ${all.size}개",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = { if (searching) { query = ""; searching = false } else searching = true }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, null)
                }
            })
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Pick(kind.label, kind != KindF.ALL, KindF.entries.map { it.label }) { kind = KindF.entries[it] } }
            item { Pick(sort.label, sort != Sort.NEW, Sort.entries.map { it.label }) { sort = Sort.entries[it] } }
            item { Pick(dur.label, dur != DurF.ALL, DurF.entries.map { it.label }) { dur = DurF.entries[it] } }
            item { Pick(date.label, date != DateF.ALL, DateF.entries.map { it.label }) { date = DateF.entries[it] } }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
        if (!loading && list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("조건에 맞는 사진·영상이 없어요") }
        }
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp), state = gridState,
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cells.size, key = { i -> when (val c = cells[i]) { is Header -> "h" + c.text; is Entry -> c.item.key } },
                span = { i -> if (cells[i] is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
                when (val c = cells[i]) {
                    is Header -> Text(c.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp))
                    is Entry -> GridCell(c.item) { viewer = c.index }
                }
            }
        }
    }

    viewer?.let { start ->
        BackHandler { viewer = null }
        Viewer(list, start) { viewer = null }
    }
    }
}

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
fun GridCell(m: MediaItem, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        TdImage(m.thumb, m.mini, 2, Modifier.fillMaxSize(), ContentScale.Crop)
        if (m.kind != Kind.PHOTO) {
            Text(
                if (m.kind == Kind.GIF) "GIF" else fmtDur(m.duration),
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
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
        path?.let { AsyncImage(model = File(it), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale) }
    }
}

// ───────────────────────── 크게 보기 ─────────────────────────

@Composable
fun Viewer(list: List<MediaItem>, start: Int, onClose: () -> Unit) {
    if (list.isEmpty()) return
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.size - 1)) { list.size }
    var showInfo by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { list[it].key }, modifier = Modifier.fillMaxSize()) { page ->
            val m = list[page]
            Box(Modifier.fillMaxSize().clickable { showInfo = !showInfo }, contentAlignment = Alignment.Center) {
                if (m.kind == Kind.PHOTO) {
                    TdImage(m.main, m.mini, 32, Modifier.fillMaxSize(), ContentScale.Fit)
                } else if (pager.currentPage == page) {
                    VideoPlayer(m)
                } else {
                    TdImage(m.thumb, m.mini, 8, Modifier.fillMaxSize(), ContentScale.Fit)
                }
            }
        }
        if (showInfo) {
            val m = list[pager.currentPage]
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 56.dp, end = 12.dp, top = 12.dp)
            ) {
                val parts = buildList {
                    add(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(m.date * 1000L)))
                    if (m.kind != Kind.PHOTO) add(fmtDur(m.duration))
                    add("${m.width}×${m.height}")
                    add(fmtSize(m.size))
                }
                Text(parts.joinToString(" · "), color = Color.White, fontSize = 13.sp)
                Text("${pager.currentPage + 1} / ${list.size}", color = Color(0xBBFFFFFF), fontSize = 12.sp)
                if (m.caption.isNotBlank()) {
                    Text(m.caption, color = Color.White, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(m: MediaItem) {
    val ctx = LocalContext.current
    val player = remember(m.key) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(DataSource.Factory { TdDataSource() }))
            .build().apply {
                setMediaItem(ExoItem.fromUri("td://file/${m.main.id}?size=${if (m.main.size > 0) m.main.size else m.main.expectedSize}"))
                if (m.kind == Kind.GIF) repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = m.kind == Kind.VIDEO } },
        modifier = Modifier.fillMaxSize())
}

fun fmtDur(s: Int): String {
    val h = s / 3600; val mm = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%d:%02d".format(mm, ss)
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1fGB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1fMB".format(b / (1L shl 20).toDouble())
    b > 0 -> "%dKB".format(b / 1024)
    else -> "-"
}
