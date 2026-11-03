package com.kakario.tggallery

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 일반 대화 화면 (읽기 + 글·자료 보내기) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long, focus: Long) {
    val ctx = LocalContext.current
    val chat = Td.chats[chatId]
    var msgs by remember { mutableStateOf<List<TdApi.Message>>(emptyList()) } // 최신이 앞
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var atLatest by remember { mutableStateOf(focus == 0L) }
    var highlight by remember { mutableLongStateOf(focus) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(0) }
    val filter by Prefs.chatFilter.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val ct = chat?.type
    val isChannel = ct is TdApi.ChatTypeSupergroup && ct.isChannel
    val isGroup = ct is TdApi.ChatTypeBasicGroup || (ct is TdApi.ChatTypeSupergroup && !ct.isChannel)
    var readTick by remember { mutableIntStateOf(0) }
    var members by remember { mutableIntStateOf(0) }

    fun merge(add: List<TdApi.Message>) {
        if (add.isEmpty()) return
        val map = LinkedHashMap<Long, TdApi.Message>()
        msgs.forEach { map[it.id] = it }
        add.forEach { map[it.id] = it }
        msgs = map.values.sortedByDescending { it.id }
        Td.sendAsync(TdApi.ViewMessages(chatId, add.map { it.id }.toLongArray(), TdApi.MessageSourceChatHistory(), true))
    }

    suspend fun loadOlder() {
        if (loading || reachedEnd) return
        loading = true
        try {
            var tries = 0
            while (tries++ < 3) {
                val from = msgs.lastOrNull()?.id ?: 0L
                val r = Td.send(TdApi.GetChatHistory(chatId, from, 0, 50, false))
                val known = msgs.map { it.id }.toHashSet()
                val add = r.messages.filterNotNull().filter { it.id !in known }
                if (add.isEmpty()) {
                    if (from == 0L && tries == 1) continue
                    reachedEnd = true
                    break
                }
                merge(add)
                if (msgs.size >= 20) break
            }
        } catch (_: Exception) {
        }
        loading = false
    }

    suspend fun loadNewer() {
        if (loading || atLatest || msgs.isEmpty()) return
        loading = true
        try {
            val from = msgs.first().id
            val r = Td.send(TdApi.GetChatHistory(chatId, from, -49, 50, false))
            val add = r.messages.filterNotNull().filter { it.id > from }
            if (add.isEmpty()) atLatest = true else merge(add)
        } catch (_: Exception) {
        }
        loading = false
    }

    fun jumpLatest() {
        scope.launch {
            if (!atLatest) { msgs = emptyList(); reachedEnd = false; atLatest = true; loadOlder() }
            listState.scrollToItem(0)
        }
    }

    DisposableEffect(chatId) {
        Td.sendAsync(TdApi.OpenChat(chatId))
        onDispose { Td.sendAsync(TdApi.CloseChat(chatId)) }
    }
    LaunchedEffect(chatId) {
        if (focus != 0L) {
            loading = true
            runCatching {
                val r = Td.send(TdApi.GetChatHistory(chatId, focus, -20, 45, false))
                merge(r.messages.filterNotNull())
                if (msgs.firstOrNull()?.id == chat?.lastMessage?.id) atLatest = true
            }
            loading = false
            val i = msgs.indexOfFirst { it.id == focus }
            if (i >= 0) listState.scrollToItem(i)
            delay(2500); highlight = 0
        } else loadOlder()
        // 그룹 인원 (읽음 숫자 계산용)
        members = runCatching {
            when (ct) {
                is TdApi.ChatTypeSupergroup -> Td.send(TdApi.GetSupergroup(ct.supergroupId)).memberCount
                is TdApi.ChatTypeBasicGroup -> Td.send(TdApi.GetBasicGroup(ct.basicGroupId)).memberCount
                else -> 2
            }
        }.getOrDefault(0)
    }
    LaunchedEffect(chatId) {
        Td.events.collect { e ->
            when (e) {
                is TdApi.UpdateNewMessage -> if (e.message.chatId == chatId && atLatest) {
                    merge(listOf(e.message))
                    if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
                }
                is TdApi.UpdateMessageSendSucceeded -> if (e.message.chatId == chatId) {
                    msgs = msgs.map { if (it.id == e.oldMessageId) e.message else it }.sortedByDescending { it.id }
                }
                is TdApi.UpdateDeleteMessages -> if (e.chatId == chatId && e.isPermanent) {
                    val ids = e.messageIds.toHashSet(); msgs = msgs.filter { it.id !in ids }
                }
                is TdApi.UpdateMessageContent -> if (e.chatId == chatId) {
                    msgs.find { it.id == e.messageId }?.let { it.content = e.newContent; msgs = msgs.toList() }
                }
                is TdApi.UpdateChatReadOutbox -> if (e.chatId == chatId) readTick++
                else -> {}
            }
        }
    }

    val shown = remember(msgs, filter) { if (filter.size == MsgType.entries.size) msgs else msgs.filter { it.type() in filter } }
    // 위로 끝까지 올리면 이전 대화, 아래 끝이면 이후 대화 더 불러오기
    val nearOld by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 6 } }
    val nearNew by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    LaunchedEffect(nearOld, msgs.size, shown.size) { if (nearOld && msgs.isNotEmpty()) loadOlder() }
    LaunchedEffect(nearNew, msgs.size) { if (nearNew && !atLatest) loadNewer() }

    // 첨부 보내기
    fun sendFiles(uris: List<Uri>, asFile: Boolean) {
        if (uris.isEmpty()) return
        val caption = input.trim(); input = ""
        sending += uris.size
        scope.launch {
            try {
                val contents = withContext(Dispatchers.IO) { uris.mapIndexedNotNull { i, u -> buildContent(ctx, u, asFile, if (i == 0) caption else "") } }
                val media = contents.filter { it !is TdApi.InputMessageDocument }
                if (!asFile && media.size >= 2) {
                    media.chunked(10).forEach { part ->
                        if (part.size >= 2) Td.send(TdApi.SendMessageAlbum(chatId, null, null, null, part.toTypedArray()))
                        else Td.send(TdApi.SendMessage(chatId, null, null, null, null, part[0]))
                    }
                    contents.filter { it is TdApi.InputMessageDocument }.forEach { Td.send(TdApi.SendMessage(chatId, null, null, null, null, it)) }
                } else contents.forEach { Td.send(TdApi.SendMessage(chatId, null, null, null, null, it)) }
                jumpLatest()
            } catch (e: Exception) {
                Toast.makeText(ctx, "보내기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            sending -= uris.size
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { sendFiles(it, false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendFiles(it, true) }

    Column(Modifier.fillMaxSize().imePadding()) {
        var filterOpen by remember { mutableStateOf(false) }
        TopAppBar(
            navigationIcon = { IconButton(onClick = { Nav.pop() }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
            title = {
                Column {
                    Text(chat?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (filter.size != MsgType.entries.size) Text("보기: " + filter.sortedBy { it.ordinal }.joinToString(", ") { it.label },
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                }
            },
            actions = {
                Box {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(Ic.Filter, "보고 싶은 글", tint = if (filter.size != MsgType.entries.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(filterOpen, { filterOpen = false }) {
                        Text("보고 싶은 글만 체크", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        MsgType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                leadingIcon = { Checkbox(t in filter, null) },
                                onClick = {
                                    val n = if (t in filter) filter - t else filter + t
                                    Prefs.setChatFilter(if (n.isEmpty()) setOf(t) else n)
                                })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("모두 보기") }, onClick = { Prefs.setChatFilter(MsgType.entries.toSet()); filterOpen = false })
                    }
                }
                IconButton(onClick = { Nav.push(Nav.Media(Target(chatId, null, chat?.title ?: ""))) }) { Icon(Ic.Grid, "사진·영상 모아보기") }
            })
        if (loading && msgs.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(8.dp)) {
                items(shown.size, key = { shown[it].id }) { i ->
                    val m = shown[i]
                    val older = shown.getOrNull(i + 1)
                    val readLabel = readLabel(m, chat, isGroup, isChannel, members, readTick)
                    Bubble(m, isGroup && !m.isOutgoing && older?.senderId?.let { sameSender(it, m.senderId) } != true, m.id == highlight, readLabel) {
                        val media = shown.reversed().mapNotNull { it.toMedia() }
                        val idx = media.indexOfFirst { it.msgId == m.id }
                        if (idx >= 0) Nav.open(media, idx)
                    }
                    if (older == null || !sameDay(older.date, m.date)) DateChip(m.date)
                }
                if (loading && msgs.isNotEmpty()) item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                if (reachedEnd && shown.isEmpty()) item {
                    Text("보여줄 메시지가 없어요", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
            }
            if (!atLatest || listState.firstVisibleItemIndex > 8) {
                SmallFloatingActionButton(onClick = { jumpLatest() }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "최신으로")
                }
            }
        }
        if (sending > 0) Text("자료 ${sending}개 보내는 중...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
        if (!isChannel || chat?.permissions?.canSendBasicMessages == true) {
            var attachOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachOpen = true }) { Icon(Ic.Attach, "첨부") }
                    DropdownMenu(attachOpen, { attachOpen = false }) {
                        DropdownMenuItem(text = { Text("사진·영상") }, leadingIcon = { Icon(Ic.Grid, null) }, onClick = {
                            attachOpen = false
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                        DropdownMenuItem(text = { Text("파일 (원본 그대로)") }, leadingIcon = { Icon(Ic.Attach, null) }, onClick = {
                            attachOpen = false
                            pickFiles.launch(arrayOf("*/*"))
                        })
                    }
                }
                OutlinedTextField(input, { input = it }, placeholder = { Text("메시지") }, maxLines = 5,
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                IconButton(enabled = input.isNotBlank(), onClick = {
                    val t = input.trim(); input = ""
                    scope.launch {
                        runCatching {
                            Td.send(TdApi.SendMessage(chatId, null, null, null, null,
                                TdApi.InputMessageText(TdApi.FormattedText(t, emptyArray()), null, true)))
                        }
                        jumpLatest()
                    }
                }) { Icon(Icons.Default.Send, "보내기", tint = MaterialTheme.colorScheme.primary) }
            }
        } else Spacer(Modifier.navigationBarsPadding())
    }
}

/** 카톡처럼 안 읽은 사람 수 (1:1은 1, 그룹은 남은 인원 수, 채널은 조회수) */
@Composable
private fun readLabel(m: TdApi.Message, chat: TdApi.Chat?, isGroup: Boolean, isChannel: Boolean, members: Int, readTick: Int): String? {
    if (isChannel) {
        val v = m.interactionInfo?.viewCount ?: 0
        return if (v > 0) "조회 " + if (v >= 10000) "%.1f만".format(v / 10000.0) else if (v >= 1000) "%.1f천".format(v / 1000.0) else "$v" else null
    }
    if (!m.isOutgoing || m.sendingState != null) return null
    if (!isGroup) return if (m.id > (chat?.lastReadOutboxMessageId ?: 0)) "1" else null
    if (members <= 1) return null
    var unread by remember(m.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(m.id, readTick, members) {
        if (System.currentTimeMillis() / 1000 - m.date > 7 * 86400) return@LaunchedEffect
        unread = runCatching { (members - 1 - Td.send(TdApi.GetMessageViewers(m.chatId, m.id)).viewers.size).coerceAtLeast(0) }
            .getOrElse { if (m.id > (chat?.lastReadOutboxMessageId ?: 0)) members - 1 else 0 }
    }
    return unread?.takeIf { it > 0 }?.toString()
}

private fun queryName(ctx: Context, u: Uri): String =
    runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "file_${System.currentTimeMillis()}"

/** 고른 자료를 앱 임시 폴더로 복사해 보낼 내용으로 만들기 */
private fun buildContent(ctx: Context, u: Uri, asFile: Boolean, caption: String): TdApi.InputMessageContent? {
    val name = queryName(ctx, u)
    val dir = File(ctx.cacheDir, "send").apply { mkdirs() }
    val f = File(dir, "${System.nanoTime()}_$name")
    ctx.contentResolver.openInputStream(u)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return null
    val cap = TdApi.FormattedText(caption, emptyArray())
    val file = TdApi.InputFileLocal(f.absolutePath)
    val mime = ctx.contentResolver.getType(u) ?: ""
    return when {
        asFile -> TdApi.InputMessageDocument(TdApi.InputDocument(file, null, false), cap)
        mime.startsWith("image/") && !mime.contains("gif") -> TdApi.InputMessagePhoto(TdApi.InputPhoto(file, null, null, IntArray(0), 0, 0), cap, false, null, false)
        mime.startsWith("video/") -> {
            var w = 0; var h = 0; var d = 0
            runCatching {
                MediaMetadataRetriever().apply {
                    setDataSource(f.absolutePath)
                    d = ((extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
                    w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    release()
                }
            }
            TdApi.InputMessageVideo(TdApi.InputVideo(file, null, null, 0, IntArray(0), d, w, h, true), cap, false, null, false)
        }
        else -> TdApi.InputMessageDocument(TdApi.InputDocument(file, null, false), cap)
    }
}

private fun sameSender(a: TdApi.MessageSender, b: TdApi.MessageSender?): Boolean = when {
    a is TdApi.MessageSenderUser && b is TdApi.MessageSenderUser -> a.userId == b.userId
    a is TdApi.MessageSenderChat && b is TdApi.MessageSenderChat -> a.chatId == b.chatId
    else -> false
}

private fun sameDay(a: Int, b: Int): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a * 1000L }
    val cb = Calendar.getInstance().apply { timeInMillis = b * 1000L }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DateChip(date: Int) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(SimpleDateFormat("yyyy년 M월 d일 E요일", Locale.KOREA).format(Date(date * 1000L)), fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun Bubble(m: TdApi.Message, showName: Boolean, highlight: Boolean, readLabel: String?, onMedia: () -> Unit) {
    val out = m.isOutgoing
    val media = remember(m.id, m.content) { m.toMedia() }
    val hl by animateFloatAsState(if (highlight) 1f else 0f, label = "hl")
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * hl)).padding(vertical = 2.dp),
        horizontalAlignment = if (out) Alignment.End else Alignment.Start
    ) {
        if (showName) Text(Td.senderName(m.senderId), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val side: @Composable () -> Unit = {
                Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start, modifier = Modifier.padding(horizontal = 4.dp)) {
                    if (readLabel != null) Text(readLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE6B800))
                    Text(SimpleDateFormat("a h:mm", Locale.KOREA).format(Date(m.date * 1000L)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (out) side()
            Column(
                Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (media != null) {
                    val ratio = if (media.width > 0 && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.5f, 2f) else 1f
                    Box(Modifier.width(250.dp).aspectRatio(ratio).clickable(onClick = onMedia)) {
                        TdImage(media.thumb, media.mini, 4, Modifier.fillMaxSize(), ContentScale.Crop)
                        if (media.kind != Kind.PHOTO) {
                            Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                                Icon(Ic.Play, null, tint = Color.White)
                            }
                            Text(if (media.kind == Kind.GIF) "GIF" else "${fmtDur(media.duration)} · ${fmtSize(media.size)}", color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                        }
                    }
                }
                val text = when (val c = m.content) {
                    is TdApi.MessageText -> c.text.str()
                    is TdApi.MessagePhoto -> c.caption.str()
                    is TdApi.MessageVideo -> c.caption.str()
                    is TdApi.MessageAnimation -> c.caption.str()
                    else -> msgPreview(m)
                }
                if (text.isNotBlank()) Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (!out) side()
        }
    }
}
