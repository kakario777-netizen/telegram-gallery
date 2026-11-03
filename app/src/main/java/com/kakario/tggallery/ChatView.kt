package com.kakario.tggallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 일반 대화 화면 (읽기 + 글 보내기) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long) {
    val chat = Td.chats[chatId]
    val msgs = remember { mutableStateListOf<TdApi.Message>() } // 최신이 앞
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val ct = chat?.type
    val isChannel = ct is TdApi.ChatTypeSupergroup && ct.isChannel
    val isGroup = ct is TdApi.ChatTypeBasicGroup || (ct is TdApi.ChatTypeSupergroup && !ct.isChannel)
    val canSend = !isChannel

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
                msgs.addAll(add)
                Td.sendAsync(TdApi.ViewMessages(chatId, add.map { it.id }.toLongArray(), TdApi.MessageSourceChatHistory(), true))
                if (msgs.size >= 20) break
            }
        } catch (_: Exception) {
        }
        loading = false
    }

    DisposableEffect(chatId) {
        Td.sendAsync(TdApi.OpenChat(chatId))
        onDispose { Td.sendAsync(TdApi.CloseChat(chatId)) }
    }
    LaunchedEffect(chatId) { loadOlder() }
    LaunchedEffect(chatId) {
        Td.events.collect { e ->
            when (e) {
                is TdApi.UpdateNewMessage -> if (e.message.chatId == chatId && msgs.none { it.id == e.message.id }) {
                    msgs.add(0, e.message)
                    Td.sendAsync(TdApi.ViewMessages(chatId, longArrayOf(e.message.id), TdApi.MessageSourceChatHistory(), true))
                    if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
                }
                is TdApi.UpdateMessageSendSucceeded -> if (e.message.chatId == chatId) {
                    val i = msgs.indexOfFirst { it.id == e.oldMessageId }
                    if (i >= 0) msgs[i] = e.message
                }
                is TdApi.UpdateDeleteMessages -> if (e.chatId == chatId && e.isPermanent) {
                    val ids = e.messageIds.toHashSet(); msgs.removeAll { it.id in ids }
                }
                is TdApi.UpdateMessageContent -> if (e.chatId == chatId) {
                    val i = msgs.indexOfFirst { it.id == e.messageId }
                    if (i >= 0) { val old = msgs[i]; old.content = e.newContent; msgs.removeAt(i); msgs.add(i, old) }
                }
                else -> {}
            }
        }
    }
    // 위로 끝까지 올리면 이전 대화 더 불러오기
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= msgs.size - 8 } }
    LaunchedEffect(nearEnd, msgs.size) { if (nearEnd && msgs.isNotEmpty()) loadOlder() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = { Nav.pop() }) { Icon(Icons.Default.ArrowBack, "뒤로") } },
            title = { Text(chat?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = { Nav.push(Nav.Media(Target(chatId, null, chat?.title ?: ""))) }) { Icon(Ic.Grid, "사진·영상 모아보기") }
            })
        if (loading && msgs.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true, contentPadding = PaddingValues(8.dp)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                val older = msgs.getOrNull(i + 1)
                Bubble(m, isGroup && !m.isOutgoing && older?.senderId?.let { sameSender(it, m.senderId) } != true) {
                    val media = msgs.reversed().mapNotNull { it.toMedia() }
                    val idx = media.indexOfFirst { it.msgId == m.id }
                    if (idx >= 0) Nav.open(media, idx)
                }
                if (older == null || !sameDay(older.date, m.date)) DateChip(m.date)
            }
            if (loading && msgs.isNotEmpty()) item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        }
        if (canSend) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, placeholder = { Text("메시지") }, maxLines = 5,
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                IconButton(enabled = input.isNotBlank(), onClick = {
                    val t = input.trim(); input = ""
                    scope.launch {
                        runCatching {
                            Td.send(TdApi.SendMessage(chatId, null, null, null, null,
                                TdApi.InputMessageText(TdApi.FormattedText(t, emptyArray()), null, true)))
                        }
                        listState.animateScrollToItem(0)
                    }
                }) { Icon(Icons.Default.Send, "보내기", tint = MaterialTheme.colorScheme.primary) }
            }
        } else Spacer(Modifier.navigationBarsPadding())
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
private fun Bubble(m: TdApi.Message, showName: Boolean, onMedia: () -> Unit) {
    val out = m.isOutgoing
    val media = remember(m.id, m.content) { m.toMedia() }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        if (showName) Text(Td.senderName(m.senderId), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (media != null) {
                val ratio = if (media.width > 0 && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.5f, 2f) else 1f
                Box(Modifier.width(260.dp).aspectRatio(ratio).clickable(onClick = onMedia)) {
                    TdImage(if (media.kind == Kind.PHOTO) media.thumb else media.thumb, media.mini, 4, Modifier.fillMaxSize(), ContentScale.Crop)
                    if (media.kind != Kind.PHOTO) {
                        Box(Modifier.align(Alignment.Center).size(46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(Ic.Play, null, tint = Color.White)
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
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                if (text.isNotBlank()) Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                Text(SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(m.date * 1000L)), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
