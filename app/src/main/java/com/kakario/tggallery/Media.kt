package com.kakario.tggallery

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

enum class Kind { PHOTO, VIDEO, GIF }

class MediaItem(
    val chatId: Long,
    val msgId: Long,
    val date: Int,
    val kind: Kind,
    val duration: Int,
    val width: Int,
    val height: Int,
    val size: Long,
    val mini: ByteArray?,
    val thumb: TdApi.File?,
    val main: TdApi.File,
    val caption: String,
    val fileName: String = "",
    val mime: String = "",
) {
    val key get() = "$chatId/$msgId"
    /** 같은 파일이면 어느 방에 올라와도 같은 값 */
    val uid: String get() = main.remote?.uniqueId?.takeIf { it.isNotEmpty() } ?: key
}

private fun TdApi.File.fsize() = if (size > 0) size else expectedSize

private fun TdApi.Thumbnail?.usable(): TdApi.File? =
    this?.takeIf { it.format !is TdApi.ThumbnailFormatMpeg4 && it.format !is TdApi.ThumbnailFormatWebm }?.file

fun TdApi.Message.toMedia(): MediaItem? = when (val c = content) {
    is TdApi.MessagePhoto -> {
        val sizes = c.photo.sizes
        if (sizes.isEmpty()) null else {
            val big = sizes.maxBy { it.width.toLong() * it.height }
            val th = sizes.filter { it.width >= 240 || it.height >= 240 }
                .minByOrNull { it.width.toLong() * it.height } ?: big
            MediaItem(
                chatId, id, date, Kind.PHOTO, 0, big.width, big.height, big.photo.fsize(),
                c.photo.minithumbnail?.data, th.photo, big.photo, c.caption?.text ?: ""
            )
        }
    }
    is TdApi.MessageVideo -> {
        val v = c.video
        MediaItem(
            chatId, id, date, Kind.VIDEO, v.duration, v.width, v.height, v.video.fsize(),
            v.minithumbnail?.data, v.thumbnail.usable(), v.video, c.caption?.text ?: "", v.fileName ?: "", v.mimeType ?: ""
        )
    }
    is TdApi.MessageAnimation -> {
        val a = c.animation
        MediaItem(
            chatId, id, date, Kind.GIF, a.duration, a.width, a.height, a.animation.fsize(),
            a.minithumbnail?.data, a.thumbnail.usable(), a.animation, c.caption?.text ?: "", a.fileName ?: "", a.mimeType ?: ""
        )
    }
    else -> null
}

/** 한 채팅방(또는 전체 채팅)의 사진·영상 목록을 끝까지 불러옴 */
class MediaLoader(private val scope: CoroutineScope, private val chatId: Long?, private val chatList: TdApi.ChatList?) {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val loading = MutableStateFlow(true)
    val total = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    private val seen = HashSet<String>()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            try {
                for (filter in listOf(TdApi.SearchMessagesFilterPhotoAndVideo(), TdApi.SearchMessagesFilterAnimation())) {
                    if (chatId != null) loadChat(chatId, filter) else loadAll(filter)
                }
            } catch (e: TdError) {
                error.value = e.message
            } finally {
                loading.value = false
            }
        }
    }

    private fun add(msgs: Array<TdApi.Message>) {
        val add = msgs.mapNotNull { it.toMedia() }.filter { seen.add(it.key) }
        if (add.isNotEmpty()) items.value = items.value + add
    }

    private suspend fun loadChat(id: Long, filter: TdApi.SearchMessagesFilter) {
        var from = 0L
        var first = true
        while (coroutineContext.isActive) {
            val r = Td.send(TdApi.SearchChatMessages(id, null, "", null, from, 0, 100, filter))
            if (first) { total.value += r.totalCount; first = false }
            add(r.messages)
            if (r.nextFromMessageId == 0L || r.messages.isEmpty()) break
            from = r.nextFromMessageId
        }
    }

    private suspend fun loadAll(filter: TdApi.SearchMessagesFilter) {
        var offset = ""
        var first = true
        while (coroutineContext.isActive && items.value.size < 20000) {
            val r = Td.send(TdApi.SearchMessages(chatList, "", offset, 100, filter, null, 0, 0))
            if (first) { total.value += r.totalCount; first = false }
            add(r.messages)
            if (r.nextOffset.isNullOrEmpty() || r.messages.isEmpty()) break
            offset = r.nextOffset
        }
    }
}

/** 저장해 둔 "채팅ID/메시지ID" 목록(즐겨찾기·무시 목록)을 메시지에서 다시 불러옴 */
class KeyLoader(private val scope: CoroutineScope, private val keysFlow: kotlinx.coroutines.flow.Flow<List<String>>) {
    val items = MutableStateFlow<List<MediaItem>>(emptyList())
    val loading = MutableStateFlow(true)
    private val cache = HashMap<String, MediaItem>()
    private val missing = HashSet<String>()

    fun start() {
        scope.launch {
            keysFlow.collect { keys ->
                loading.value = true
                for (k in keys) {
                    if (k in cache || k in missing) continue
                    val (c, m) = k.split('/').map { it.toLong() }
                    val item = try { Td.send(TdApi.GetMessage(c, m)).toMedia() } catch (e: TdError) { null }
                    if (item != null) cache[k] = item else missing += k
                    items.value = keys.mapNotNull { cache[it] }
                }
                items.value = keys.mapNotNull { cache[it] }
                loading.value = false
            }
        }
    }
}

/**
 * 영상을 내려받으면서 바로 재생(스트리밍)하기 위한 연결 고리.
 * 주소 형태: td://file/<파일번호>?size=<크기>
 */
@OptIn(UnstableApi::class)
class TdDataSource : BaseDataSource(true) {
    private var uri: Uri? = null
    private var fileId = 0
    private var pos = 0L
    private var remaining = 0L
    private var raf: RandomAccessFile? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("bad uri")
        val size = dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: 0L
        pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - pos
        Td.sendAsync(TdApi.DownloadFile(fileId, 32, pos, 0, false))
        transferStarted(dataSpec)
        return remaining
    }

    private fun <T : TdApi.Object> call(f: TdApi.Function<T>): T =
        try {
            runBlocking { Td.send(f) }
        } catch (e: TdError) {
            throw IOException(e.message)
        }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        var waited = 0L
        while (true) {
            val avail = call(TdApi.GetFileDownloadedPrefixSize(fileId, pos)).size
            if (avail > 0) {
                val r = raf ?: RandomAccessFile(call(TdApi.GetFile(fileId)).local.path, "r").also { raf = it }
                val n = minOf(length.toLong(), avail, remaining).toInt()
                r.seek(pos)
                val got = r.read(buffer, offset, n)
                if (got > 0) {
                    pos += got
                    remaining -= got
                    bytesTransferred(got)
                    return got
                }
            }
            try {
                Thread.sleep(40)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
            waited += 40
            // 다른 위치로 받던 중이면 이 위치부터 다시 받도록 요청
            if (waited % 2000 == 0L) Td.sendAsync(TdApi.DownloadFile(fileId, 32, pos, 0, false))
            if (waited > 90_000) throw IOException("영상 불러오기 시간 초과")
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } catch (_: IOException) {
        }
        raf = null
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}
