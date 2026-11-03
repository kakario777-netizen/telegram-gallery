package com.kakario.tggallery

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    /** 무시한 자료: 파일 고유ID → "채팅ID/메시지ID" (같은 파일이 다른 방에 올라와도 같이 숨김) */
    val hidden = MutableStateFlow<Map<String, String>>(emptyMap())
    /** 격자 한 줄 칸 수 */
    val gridCols = MutableStateFlow(3)
    /** 대화 화면에서 보여줄 메시지 종류 */
    val chatFilter = MutableStateFlow(MsgType.entries.toSet())

    fun init(c: Context) {
        ctx = c.applicationContext
        downloadUri.value = sp.getString("dl", null)
        bg.value = sp.getLong("bg", 0L)
        favs.value = sp.getString("favs", "")!!.split(',').filter { it.contains('/') }
        hidden.value = sp.getString("hidden", "")!!.split('\n').mapNotNull { l ->
            val i = l.indexOf('|'); if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
        }.toMap(LinkedHashMap())
        gridCols.value = sp.getInt("cols", 3)
        chatFilter.value = sp.getString("cfilter", null)?.split(',')?.mapNotNull { n -> MsgType.entries.find { it.name == n } }?.toSet()
            ?: MsgType.entries.toSet()
    }

    fun setDownloadUri(u: String) { sp.edit().putString("dl", u).apply(); downloadUri.value = u }
    fun setBg(v: Long) { sp.edit().putLong("bg", v).apply(); bg.value = v }
    fun setCols(v: Int) { sp.edit().putInt("cols", v).apply(); gridCols.value = v }
    fun setChatFilter(v: Set<MsgType>) { sp.edit().putString("cfilter", v.joinToString(",") { it.name }).apply(); chatFilter.value = v }

    fun setFav(keys: Collection<String>, on: Boolean) {
        val cur = favs.value
        val next = if (on) keys.filter { it !in cur } + cur else cur.filter { it !in keys }
        favs.value = next
        sp.edit().putString("favs", next.joinToString(",")).apply()
    }

    fun hide(items: Collection<MediaItem>) {
        val next = LinkedHashMap<String, String>()
        items.forEach { next[it.uid] = it.key }
        next.putAll(hidden.value)
        saveHidden(next)
    }

    fun unhide(uids: Collection<String>) = saveHidden(hidden.value.filterKeys { it !in uids })

    private fun saveHidden(m: Map<String, String>) {
        hidden.value = m
        sp.edit().putString("hidden", m.entries.joinToString("\n") { "${it.key}|${it.value}" }).apply()
    }

    fun folderName(): String? = downloadUri.value?.let {
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(it))?.name }.getOrNull() ?: "선택한 폴더"
    }
}

/** 대화 화면 메시지 종류 */
enum class MsgType(val label: String) {
    TEXT("글"), PHOTO("사진"), PHOTO_T("사진+글"), VIDEO("영상"), VIDEO_T("영상+글"),
    GIF("GIF"), FILE("파일"), VOICE("음성·음악"), STICKER("스티커"), ETC("기타")
}

fun TdApi.Message.type(): MsgType = when (val c = content) {
    is TdApi.MessageText -> MsgType.TEXT
    is TdApi.MessagePhoto -> if (c.caption.str().isBlank()) MsgType.PHOTO else MsgType.PHOTO_T
    is TdApi.MessageVideo -> if (c.caption.str().isBlank()) MsgType.VIDEO else MsgType.VIDEO_T
    is TdApi.MessageAnimation -> MsgType.GIF
    is TdApi.MessageDocument -> MsgType.FILE
    is TdApi.MessageAudio, is TdApi.MessageVoiceNote -> MsgType.VOICE
    is TdApi.MessageSticker -> MsgType.STICKER
    else -> MsgType.ETC
}

/** 화면 이동·전역 상태 */
object Nav {
    sealed interface Screen
    data class Media(val target: Target) : Screen
    data class Chat(val chatId: Long, val focus: Long = 0L) : Screen
    data object Settings : Screen
    data object Search : Screen
    data object Hidden : Screen

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
    private var pending: List<MediaItem>? = null
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var nextId = 1

    enum class St { WAIT, RUN, DONE, FAIL, CANCEL }
    data class Entry(val id: Int, val item: MediaItem, val st: St = St.WAIT, val percent: Int = 0, val done: Long = 0, val total: Long = 0)

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    val active get() = entries.value.any { it.st == St.WAIT || it.st == St.RUN }

    fun init(c: Context) { ctx = c.applicationContext }

    private fun update(id: Int, f: (Entry) -> Entry) {
        synchronized(this) { entries.value = entries.value.map { if (it.id == id) f(it) else it } }
    }

    fun enqueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (Prefs.downloadUri.value == null) {
            pending = items
            toast("다운로드할 폴더를 먼저 골라주세요")
            Nav.pickFolder()
            return
        }
        synchronized(this) {
            entries.value = entries.value + items.map { Entry(nextId++, it) }
            if (worker?.isActive != true) worker = scope.launch { run() }
        }
        toast("다운로드 ${items.size}개 추가")
    }

    fun folderPicked() {
        pending?.let { pending = null; enqueue(it) }
    }

    fun cancel(id: Int) {
        val e = entries.value.find { it.id == id } ?: return
        if (e.st == St.RUN) {
            Td.sendAsync(TdApi.CancelDownloadFile(e.item.main.id, false))
            currentJob?.cancel()
        }
        if (e.st == St.WAIT || e.st == St.RUN) update(id) { it.copy(st = St.CANCEL) }
    }

    fun cancelAll() = entries.value.filter { it.st == St.WAIT || it.st == St.RUN }.forEach { cancel(it.id) }

    fun clearFinished() {
        synchronized(this) { entries.value = entries.value.filter { it.st == St.WAIT || it.st == St.RUN } }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private suspend fun run() {
        var ok = 0
        var fail = 0
        while (true) {
            val e = synchronized(this) { entries.value.firstOrNull { it.st == St.WAIT } } ?: break
            update(e.id) { it.copy(st = St.RUN) }
            val job = scope.launch {
                val r = runCatching { save(e) }.getOrDefault(false)
                if (entries.value.find { it.id == e.id }?.st == St.RUN) {
                    update(e.id) { it.copy(st = if (r) St.DONE else St.FAIL, percent = if (r) 100 else it.percent) }
                    if (r) ok++ else fail++
                }
            }
            currentJob = job
            job.join()
        }
        if (ok + fail > 0) toast(if (fail == 0) "다운로드 완료 (${ok}개)" else "다운로드 완료 ${ok}개 · 실패 ${fail}개")
    }

    private suspend fun save(e: Entry): Boolean {
        val m = e.item
        val id = m.main.id
        val wasCached = m.main.local.isDownloadingCompleted
        val poll = scope.launch {
            while (isActive) {
                runCatching {
                    val f = Td.send(TdApi.GetFile(id))
                    val total = if (f.size > 0) f.size else f.expectedSize
                    if (total > 0) update(e.id) { it.copy(percent = (f.local.downloadedSize * 100 / total).toInt(), done = f.local.downloadedSize, total = total) }
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

/** 캐시(임시 저장) 확인·삭제 */
object Cache {
    val videoTypes: Array<TdApi.FileType> get() = arrayOf(TdApi.FileTypeVideo(), TdApi.FileTypeAnimation(), TdApi.FileTypeVideoNote())
    val thumbTypes: Array<TdApi.FileType> get() = arrayOf(TdApi.FileTypeThumbnail(), TdApi.FileTypePhoto(), TdApi.FileTypeProfilePhoto())

    data class Sizes(val video: Long, val thumb: Long, val other: Long)

    private fun isVideo(t: TdApi.FileType) = t is TdApi.FileTypeVideo || t is TdApi.FileTypeAnimation || t is TdApi.FileTypeVideoNote
    private fun isThumb(t: TdApi.FileType) = t is TdApi.FileTypeThumbnail || t is TdApi.FileTypePhoto || t is TdApi.FileTypeProfilePhoto

    private fun sum(list: List<TdApi.StorageStatisticsByFileType>) = Sizes(
        list.filter { isVideo(it.fileType) }.sumOf { it.size },
        list.filter { isThumb(it.fileType) }.sumOf { it.size },
        list.filter { !isVideo(it.fileType) && !isThumb(it.fileType) }.sumOf { it.size },
    )

    /** chatId가 null이면 전체 */
    suspend fun sizes(chatId: Long?): Sizes {
        val s = Td.send(TdApi.GetStorageStatistics(if (chatId == null) 0 else 100000))
        val rows = if (chatId == null) s.byChat.flatMap { it.byFileType.toList() }
        else s.byChat.filter { it.chatId == chatId }.flatMap { it.byFileType.toList() }
        return sum(rows)
    }

    suspend fun clear(chatId: Long?, video: Boolean) {
        Td.send(TdApi.OptimizeStorage(0, 0, 0, 0, if (video) videoTypes else thumbTypes,
            if (chatId == null) LongArray(0) else longArrayOf(chatId), LongArray(0), false, 0))
    }
}
