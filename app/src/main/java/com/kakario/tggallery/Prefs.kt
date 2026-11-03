package com.kakario.tggallery

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 설정 (폰 안에만 저장) */
object Prefs {
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)

    val downloadUri = MutableStateFlow<String?>(null)
    /** 배경색 (0 = 기본 테마) */
    val bg = MutableStateFlow(0L)
    /** 즐겨찾기 "채팅ID/메시지ID", 최근 추가가 앞 */
    val favs = MutableStateFlow<List<String>>(emptyList())

    fun init(c: Context) {
        ctx = c.applicationContext
        downloadUri.value = sp.getString("dl", null)
        bg.value = sp.getLong("bg", 0L)
        favs.value = sp.getString("favs", "")!!.split(',').filter { it.contains('/') }
    }

    fun setDownloadUri(u: String) { sp.edit().putString("dl", u).apply(); downloadUri.value = u }
    fun setBg(v: Long) { sp.edit().putLong("bg", v).apply(); bg.value = v }

    fun isFav(key: String) = favs.value.contains(key)

    fun setFav(keys: Collection<String>, on: Boolean) {
        val cur = favs.value
        val next = if (on) keys.filter { it !in cur } + cur else cur.filter { it !in keys }
        favs.value = next
        sp.edit().putString("favs", next.joinToString(",")).apply()
    }

    fun folderName(): String? = downloadUri.value?.let {
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name }.getOrNull() ?: "선택한 폴더"
    }
}

/** 화면 이동·전역 상태 */
object Nav {
    sealed interface Screen
    data class Media(val target: Target) : Screen
    data class Chat(val chatId: Long) : Screen
    data object Settings : Screen

    val stack = MutableStateFlow<List<Screen>>(emptyList())
    fun push(s: Screen) { stack.value = stack.value + s }
    fun pop() { stack.value = stack.value.dropLast(1) }

    class ViewerReq(val list: List<MediaItem>, val start: Int)
    val viewer = MutableStateFlow<ViewerReq?>(null)
    fun open(list: List<MediaItem>, start: Int) { viewer.value = ViewerReq(list, start) }

    /** 다운로드 폴더 고르기 창 띄우기 (MainActivity가 채워 넣음) */
    var pickFolder: () -> Unit = {}
}

data class Target(val chatId: Long?, val list: TdApi.ChatList?, val title: String)

/** 원본 파일을 받아 설정한 폴더에 저장 */
object Downloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var ctx: Context
    private val queue = ArrayDeque<MediaItem>()
    private var pending: List<MediaItem>? = null
    private var running = false

    data class State(val done: Int, val total: Int, val percent: Int, val failed: Int)
    val state = MutableStateFlow<State?>(null)

    fun init(c: Context) { ctx = c.applicationContext }

    fun enqueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (Prefs.downloadUri.value == null) {
            pending = items
            toast("다운로드할 폴더를 먼저 골라주세요")
            Nav.pickFolder()
            return
        }
        synchronized(queue) {
            queue.addAll(items)
            val s = state.value
            state.value = State(s?.done ?: 0, (s?.total ?: 0) + items.size, 0, s?.failed ?: 0)
            if (running) return
            running = true
        }
        scope.launch { run() }
    }

    fun folderPicked() {
        pending?.let { pending = null; enqueue(it) }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private suspend fun run() {
        while (true) {
            val m = synchronized(queue) {
                queue.removeFirstOrNull() ?: run { running = false; null }
            } ?: break
            val ok = runCatching { save(m) }.getOrDefault(false)
            val s = state.value ?: State(0, 1, 0, 0)
            state.value = s.copy(done = s.done + 1, percent = 0, failed = s.failed + if (ok) 0 else 1)
        }
        val s = state.value
        if (s != null) toast(if (s.failed == 0) "다운로드 완료 (${s.done}개)" else "다운로드 완료 ${s.done - s.failed}개 · 실패 ${s.failed}개")
        delay(1500)
        synchronized(queue) { if (!running) state.value = null }
    }

    private suspend fun save(m: MediaItem): Boolean {
        val id = m.main.id
        val wasCached = m.main.local.isDownloadingCompleted
        val poll = scope.launch {
            while (isActive) {
                runCatching {
                    val f = Td.send(TdApi.GetFile(id))
                    val total = if (f.size > 0) f.size else f.expectedSize
                    if (total > 0) state.value = state.value?.copy(percent = (f.local.downloadedSize * 100 / total).toInt())
                }
                delay(500)
            }
        }
        val f = try {
            Td.send(TdApi.DownloadFile(id, 1, 0, 0, true))
        } finally {
            poll.cancel()
        }
        if (!f.local.isDownloadingCompleted) return false
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(Prefs.downloadUri.value ?: return false)) ?: return false
        val (mime, name) = fileNameOf(m)
        val doc = tree.createFile(mime, name) ?: return false
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                File(f.local.path).inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: error("저장 실패")
        }
        // 큰 영상은 앱 임시 공간에서 지워 용량 확보
        if (!wasCached && m.kind != Kind.PHOTO) Td.sendAsync(TdApi.DeleteFile(id))
        return true
    }

    private fun fileNameOf(m: MediaItem): Pair<String, String> {
        val mime = m.mime.ifBlank { if (m.kind == Kind.PHOTO) "image/jpeg" else "video/mp4" }
        if (m.fileName.isNotBlank()) return mime to m.fileName.substringBeforeLast('.')
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date(m.date * 1000L))
        return mime to "telegram_${d}_${m.msgId shr 20}"
    }
}
