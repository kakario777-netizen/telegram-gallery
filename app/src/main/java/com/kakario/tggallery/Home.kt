package com.kakario.tggallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 첫 화면: 위 탭(채팅 | 갤러리) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    var top by rememberSaveable { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("텔레그램 갤러리") }, actions = {
            IconButton(onClick = { Nav.push(Nav.Settings) }) { Icon(Icons.Default.Settings, "설정") }
        })
        PrimaryTabRow(selectedTabIndex = top) {
            Tab(top == 0, { top = 0 }, text = { Text("채팅") })
            Tab(top == 1, { top = 1 }, text = { Text("갤러리") })
        }
        Box(Modifier.weight(1f)) {
            if (top == 0) ChatsTab() else GalleryTab()
        }
    }
}

@Composable
fun ChatsTab() {
    ChatLists(galleryMode = false)
}

/** 갤러리 탭: 아래쪽에 채팅 목록 | 즐겨찾기 */
@Composable
fun GalleryTab() {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (sub == 0) ChatLists(galleryMode = true) else FavoritesTab()
        }
        NavigationBar(Modifier.height(64.dp), windowInsets = WindowInsets(0)) {
            NavigationBarItem(sub == 0, { sub = 0 }, icon = { Icon(Icons.Default.List, null) }, label = { Text("채팅 목록") })
            NavigationBarItem(sub == 1, { sub = 1 }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text("즐겨찾기") })
        }
    }
}

/** 전체 · 보관함 · 사용자 폴더 탭이 달린 채팅 목록 */
@Composable
fun ChatLists(galleryMode: Boolean) {
    val folders by Td.folders.collectAsState()
    val tabs = remember(folders) {
        listOf<Pair<String, TdApi.ChatList>>("전체" to TdApi.ChatListMain(), "보관함" to TdApi.ChatListArchive()) +
                folders.map { (it.name?.text?.text ?: "폴더") to TdApi.ChatListFolder(it.id) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    if (tab >= tabs.size) tab = 0
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, divider = {}) {
            tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t.first, maxLines = 1) }) }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("채팅방 이름 검색") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        key(tab, tabs.size) {
            ChatListBody(tabs[tab].second, tabs[tab].first, galleryMode, query)
        }
    }
}

@Composable
private fun ChatListBody(list: TdApi.ChatList, label: String, galleryMode: Boolean, query: String) {
    var ids by remember { mutableStateOf<List<Long>?>(null) }
    val tick by Td.chatTick.collectAsState()
    LaunchedEffect(list) {
        var first = true
        Td.chatTick.collectLatest {
            if (!first) delay(400)
            first = false
            ids = try {
                val r = Td.send(TdApi.GetChats(list, 3000)).chatIds.toList()
                r.forEach { id -> if (Td.chats[id] == null) runCatching { Td.chat(id) } }
                r
            } catch (e: Exception) { ids ?: emptyList() }
        }
    }
    val shown = remember(ids, query, tick) {
        val l = ids ?: emptyList()
        if (query.isBlank()) l else l.filter { Td.chats[it]?.title?.contains(query, true) == true }
    }
    Column(Modifier.fillMaxSize()) {
        if (ids == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            val allList: TdApi.ChatList? = when (list) {
                is TdApi.ChatListMain -> null
                is TdApi.ChatListArchive -> list
                else -> list
            }
            if (galleryMode && query.isBlank() && list !is TdApi.ChatListFolder) item {
                ListItem(
                    headlineContent = { Text(if (allList == null) "전체 채팅 모아보기" else "$label 모아보기", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(if (allList == null) "보관함 포함 모든 방의 사진·영상" else "$label 안 모든 방의 사진·영상") },
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Ic.Grid, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    modifier = Modifier.clickable { Nav.push(Nav.Media(Target(null, allList, if (allList == null) "전체 채팅" else label))) })
                HorizontalDivider()
            }
            if (ids != null && shown.isEmpty()) item {
                Text("채팅방이 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(shown, key = { it }) { id ->
                val c = Td.chats[id] ?: return@items
                ChatRow(c, tick, galleryMode) {
                    if (galleryMode) Nav.push(Nav.Media(Target(c.id, null, c.title))) else Nav.push(Nav.Chat(c.id))
                }
            }
        }
    }
}

@Composable
private fun ChatRow(c: TdApi.Chat, @Suppress("UNUSED_PARAMETER") tick: Int, galleryMode: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (c.unreadCount > 0 && !galleryMode) FontWeight.Bold else null) },
        supportingContent = { Text(msgPreview(c.lastMessage), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Avatar(c, 48) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                c.lastMessage?.let { Text(shortTime(it.date), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (c.unreadCount > 0 && !galleryMode) {
                    Spacer(Modifier.height(4.dp))
                    Badge { Text(if (c.unreadCount > 999) "999+" else c.unreadCount.toString()) }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun Avatar(c: TdApi.Chat, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(c.title.take(1), fontWeight = FontWeight.Bold)
        if (c.photo != null) TdImage(c.photo?.small, c.photo?.minithumbnail?.data, 4, Modifier.fillMaxSize(), ContentScale.Crop)
    }
}

private fun shortTime(d: Int): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = d * 1000L }
    val pattern = when {
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> "M/d"
        else -> "yy.M.d"
    }
    return SimpleDateFormat(pattern, Locale.KOREA).format(Date(d * 1000L))
}

// ───────────────────────── 설정 ─────────────────────────

val bgChoices = listOf(
    0L to "기본",
    0xFF000000 to "검정", 0xFF1C1C1E to "진회색", 0xFF16213E to "남색", 0xFF1B2E25 to "짙은 녹색",
    0xFFFFFFFF to "흰색", 0xFFF2F2F2 to "밝은 회색", 0xFFFAF6EC to "아이보리", 0xFFE8F3FB to "하늘",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val dl by Prefs.downloadUri.collectAsState()
    val bg by Prefs.bg.collectAsState()
    var me by remember { mutableStateOf<TdApi.User?>(null) }
    var logoutStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { me = runCatching { Td.send(TdApi.GetMe()) }.getOrNull() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { Nav.pop() }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
            title = { Text("설정") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            me?.let {
                ListItem(
                    headlineContent = { Text("${it.firstName} ${it.lastName}".trim()) },
                    supportingContent = { Text("+" + it.phoneNumber) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider()
            }
            Section("다운로드")
            ListItem(
                headlineContent = { Text("다운로드 위치") },
                supportingContent = { Text(if (dl == null) "아직 정하지 않았어요 (눌러서 고르기)" else Prefs.folderName() ?: "") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { Nav.pickFolder() })
            HorizontalDivider()
            Section("화면")
            Text("배경색", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bgChoices) { (v, name) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { Prefs.setBg(v) }) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape)
                                .background(if (v == 0L) MaterialTheme.colorScheme.primaryContainer else Color(v))
                                .border(if (bg == v) 3.dp else 1.dp, if (bg == v) MaterialTheme.colorScheme.primary else Color(0x33888888), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bg == v) Icon(Icons.Default.Check, null, tint = if (v == 0L || Color(v).isDark()) Color.White else Color.Black)
                        }
                        Text(name, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(40.dp))
            TextButton(onClick = { logoutStep = 1 }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("로그아웃", color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (logoutStep == 1) AlertDialog(
        onDismissRequest = { logoutStep = 0 },
        title = { Text("로그아웃") },
        text = { Text("텔레그램 계정에서 로그아웃할까요?") },
        confirmButton = { TextButton(onClick = { logoutStep = 2 }) { Text("로그아웃") } },
        dismissButton = { TextButton(onClick = { logoutStep = 0 }) { Text("취소") } })
    if (logoutStep == 2) AlertDialog(
        onDismissRequest = { logoutStep = 0 },
        title = { Text("정말 로그아웃할까요?") },
        text = { Text("다시 들어오려면 전화번호와 인증번호를 새로 입력해야 해요. 즐겨찾기와 설정은 이 폰에 그대로 남아요.") },
        confirmButton = {
            TextButton(onClick = { logoutStep = 0; Nav.stack.value = emptyList(); Td.sendAsync(TdApi.LogOut()) }) {
                Text("네, 로그아웃", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { logoutStep = 0 }) { Text("취소") } })
}

@Composable
private fun Section(t: String) {
    Text(t, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

fun Color.isDark() = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
