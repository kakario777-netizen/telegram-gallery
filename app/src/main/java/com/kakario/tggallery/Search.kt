package com.kakario.tggallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 통합 검색: 채팅방 이름 · 모든 대화 내용 · 사진/영상 설명 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var chats by remember { mutableStateOf<List<TdApi.Chat>>(emptyList()) }
    val msgs = remember { mutableStateListOf<TdApi.Message>() }
    var msgOffset by remember { mutableStateOf("") }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(query) {
        if (query.isBlank()) { chats = emptyList(); msgs.clear(); media = emptyList(); return@LaunchedEffect }
        delay(450)
        loading = true
        chats = runCatching { Td.send(TdApi.SearchChats(query, null, 30)).chatIds.map { Td.chat(it) } }.getOrDefault(emptyList())
        runCatching {
            val r = Td.send(TdApi.SearchMessages(null, query, "", 50, TdApi.SearchMessagesFilterEmpty(), null, 0, 0))
            msgs.clear(); msgs.addAll(r.messages.filterNotNull()); msgOffset = r.nextOffset ?: ""
        }
        media = runCatching {
            val hidden = Prefs.hidden.value
            Td.send(TdApi.SearchMessages(null, query, "", 100, TdApi.SearchMessagesFilterPhotoAndVideo(), null, 0, 0))
                .messages.filterNotNull().mapNotNull { it.toMedia() }.filter { it.uid !in hidden }
        }.getOrDefault(emptyList())
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { Nav.pop() }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
            title = {
                TextField(query, { query = it }, placeholder = { Text("채팅방·대화 내용·자료 검색") }, singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "지우기") } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
            })
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("전체", "채팅방 ${chats.size}", "대화 ${msgs.size}", "사진·영상 ${media.size}").forEachIndexed { i, t ->
                Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 13.sp, maxLines = 1) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (query.isBlank()) {
            Text("채팅방 이름, 모든 채팅방의 대화 내용, 사진·영상 설명까지 한 번에 찾아요",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if ((tab == 0 || tab == 1) && chats.isNotEmpty()) {
                if (tab == 0) item { SectionTitle("채팅방") }
                items(if (tab == 0) chats.take(5) else chats, key = { "c" + it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Avatar(c, 44) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { Nav.push(Nav.Chat(c.id)) })
                }
            }
            if ((tab == 0 || tab == 3) && media.isNotEmpty()) {
                item { SectionTitle("사진·영상") }
                if (tab == 0) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(media.take(30).size) { i -> MediaThumb(media[i]) { Nav.open(media, i) } }
                    }
                } else {
                    val rows = media.indices.chunked(3)
                    items(rows.size) { r ->
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rows[r].forEach { i -> Box(Modifier.weight(1f)) { MediaThumb(media[i], fill = true) { Nav.open(media, i) } } }
                            repeat(3 - rows[r].size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if ((tab == 0 || tab == 2) && msgs.isNotEmpty()) {
                item { SectionTitle("대화 내용") }
                items(if (tab == 0) msgs.take(15) else msgs, key = { "m${it.chatId}/${it.id}" }) { m ->
                    MessageHit(m, query) { Nav.push(Nav.Chat(m.chatId, m.id)) }
                }
                if (tab == 2 && msgOffset.isNotEmpty()) item {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                val r = Td.send(TdApi.SearchMessages(null, query, msgOffset, 50, TdApi.SearchMessagesFilterEmpty(), null, 0, 0))
                                val known = msgs.map { "${it.chatId}/${it.id}" }.toHashSet()
                                msgs.addAll(r.messages.filterNotNull().filter { "${it.chatId}/${it.id}" !in known })
                                msgOffset = r.nextOffset ?: ""
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("더 보기") }
                }
                if (tab == 0 && msgs.size > 15) item {
                    TextButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("대화 내용 전체 보기") }
                }
            }
            if (!loading && chats.isEmpty() && msgs.isEmpty() && media.isEmpty()) item {
                Text("검색 결과가 없어요", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
private fun MediaThumb(m: MediaItem, fill: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier.width(110.dp)).aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)
    ) {
        TdImage(m.thumb, m.mini, 4, Modifier.fillMaxSize(), ContentScale.Crop)
        if (m.kind != Kind.PHOTO) Text(if (m.kind == Kind.GIF) "GIF" else fmtDur(m.duration), color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
    }
}

@Composable
private fun MessageHit(m: TdApi.Message, q: String, onClick: () -> Unit) {
    val chat = Td.chats[m.chatId]
    val text = msgPreview(m)
    val idx = text.indexOf(q, ignoreCase = true)
    val shown = if (idx > 30) "…" + text.substring(idx - 20) else text
    ListItem(
        headlineContent = {
            Row {
                Text(chat?.title ?: "", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(SimpleDateFormat("yy.M.d", Locale.KOREA).format(Date(m.date * 1000L)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = {
            val hl = MaterialTheme.colorScheme.primary
            Text(buildAnnotatedString {
                val sender = Td.senderName(m.senderId)
                if (sender.isNotBlank() && sender != chat?.title) append("$sender: ")
                var start = 0
                while (true) {
                    val i = shown.indexOf(q, start, ignoreCase = true)
                    if (i < 0 || q.isEmpty()) { append(shown.substring(start)); break }
                    append(shown.substring(start, i))
                    withStyle(SpanStyle(color = hl, fontWeight = FontWeight.Bold)) { append(shown.substring(i, i + q.length)) }
                    start = i + q.length
                }
            }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { chat?.let { Avatar(it, 40) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick))
}
