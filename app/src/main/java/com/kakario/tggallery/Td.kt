package com.kakario.tggallery

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdError(val code: Int, msg: String) : Exception(msg)

/** 텔레그램(TDLib) 연결을 한 곳에서 관리 */
object Td {
    private lateinit var appCtx: Context
    @Volatile private var client: Client? = null

    val auth = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val keysSet = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    val users = ConcurrentHashMap<Long, TdApi.User>()
    val folders = MutableStateFlow<List<TdApi.ChatFolderInfo>>(emptyList())
    /** 채팅 목록 쪽 변화가 있을 때마다 1씩 올라감 (화면 새로고침용) */
    val chatTick = MutableStateFlow(0)
    /** 새 메시지·삭제 등 대화 화면에서 쓰는 업데이트 */
    val events = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = 512)

    /** api_id·api_hash는 안드로이드 보안 저장소(Keystore) 기반 암호화 저장 */
    private val prefs: android.content.SharedPreferences by lazy { securePrefs(appCtx) }

    private fun securePrefs(ctx: Context): android.content.SharedPreferences {
        fun open(): android.content.SharedPreferences {
            val key = androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            return androidx.security.crypto.EncryptedSharedPreferences.create(
                ctx, "tg_secure", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
        val sp = try { open() } catch (e: Exception) {
            // 보안 저장소가 깨졌으면 지우고 새로 만듦 (키는 다시 입력)
            ctx.deleteSharedPreferences("tg_secure"); open()
        }
        // 예전 버전의 일반 저장값을 암호화 저장소로 옮기고 원본은 지움
        val old = ctx.getSharedPreferences("tg", Context.MODE_PRIVATE)
        if (old.contains("api_id") || old.contains("api_hash")) {
            sp.edit().putInt("api_id", old.getInt("api_id", 0)).putString("api_hash", old.getString("api_hash", "")).apply()
            old.edit().clear().apply()
            ctx.deleteSharedPreferences("tg")
        }
        return sp
    }

    private fun readKeysSet() =
        prefs.getInt("api_id", 0) != 0 && !prefs.getString("api_hash", "").isNullOrBlank()

    fun saveKeys(id: Int, hash: String) {
        prefs.edit().putInt("api_id", id).putString("api_hash", hash.trim()).apply()
        keysSet.value = true
        error.value = null
        if (auth.value is TdApi.AuthorizationStateWaitTdlibParameters) sendParams()
    }

    private fun clearKeys() {
        prefs.edit().remove("api_id").remove("api_hash").apply()
        keysSet.value = false
    }

    @Synchronized
    fun init(ctx: Context) {
        if (client != null) return
        appCtx = ctx.applicationContext
        keysSet.value = readKeysSet()
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (_: Throwable) {
        }
        client = Client.create({ onUpdate(it) }, null, null)
    }

    private fun tick() {
        chatTick.value = chatTick.value + 1
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> {
                val s = o.authorizationState
                auth.value = s
                when (s) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> if (readKeysSet()) sendParams()
                    is TdApi.AuthorizationStateClosed -> {
                        synchronized(this) { client = null }
                        chats.clear()
                        users.clear()
                        folders.value = emptyList()
                        init(appCtx)
                    }
                    else -> {}
                }
            }
            is TdApi.UpdateNewChat -> { chats[o.chat.id] = o.chat; tick() }
            is TdApi.UpdateChatTitle -> { chats[o.chatId]?.title = o.title; tick() }
            is TdApi.UpdateChatPhoto -> { chats[o.chatId]?.photo = o.photo; tick() }
            is TdApi.UpdateChatLastMessage -> {
                chats[o.chatId]?.let { it.lastMessage = o.lastMessage; it.positions = o.positions }
                tick()
            }
            is TdApi.UpdateChatPosition -> tick()
            is TdApi.UpdateChatAddedToList -> tick()
            is TdApi.UpdateChatRemovedFromList -> tick()
            is TdApi.UpdateChatReadInbox -> { chats[o.chatId]?.unreadCount = o.unreadCount; tick() }
            is TdApi.UpdateChatReadOutbox -> { chats[o.chatId]?.lastReadOutboxMessageId = o.lastReadOutboxMessageId; events.tryEmit(o) }
            is TdApi.UpdateChatFolders -> folders.value = o.chatFolders.toList()
            is TdApi.UpdateUser -> users[o.user.id] = o.user
            is TdApi.UpdateNewMessage, is TdApi.UpdateMessageSendSucceeded, is TdApi.UpdateDeleteMessages,
            is TdApi.UpdateMessageContent -> events.tryEmit(o)
            else -> {}
        }
    }

    private fun sendParams() {
        val p = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(appCtx.filesDir, "td").absolutePath
            filesDirectory = File(appCtx.filesDir, "td_files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = prefs.getInt("api_id", 0)
            apiHash = prefs.getString("api_hash", "") ?: ""
            systemLanguageCode = "ko"
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: ""
            applicationVersion = "0.3"
        }
        client?.send(p) { r ->
            if (r is TdApi.Error) {
                clearKeys()
                error.value = "API 키를 확인해 주세요"
            }
        }
    }

    fun sendAsync(f: TdApi.Function<*>) {
        client?.send(f) { }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(f: TdApi.Function<T>): T =
        suspendCancellableCoroutine { c ->
            val cl = client
            if (cl == null) {
                c.resumeWithException(TdError(-1, "연결 준비 중"))
                return@suspendCancellableCoroutine
            }
            cl.send(f) { r ->
                if (!c.isActive) return@send
                if (r is TdApi.Error) c.resumeWithException(TdError(r.code, r.message))
                else c.resume(r as T)
            }
        }

    /** 썸네일 등 작은 파일을 앱 캐시에 받아 경로를 돌려줌 (폰 갤러리에는 저장하지 않음) */
    suspend fun download(file: TdApi.File, priority: Int): String? {
        if (file.local.isDownloadingCompleted && file.local.path.isNotEmpty() && File(file.local.path).exists()) {
            return file.local.path
        }
        try {
            val f = send(TdApi.DownloadFile(file.id, priority, 0, 0, true))
            return f.local.path.takeIf { f.local.isDownloadingCompleted && it.isNotEmpty() }
        } catch (e: CancellationException) {
            sendAsync(TdApi.CancelDownloadFile(file.id, true))
            throw e
        }
    }

    suspend fun chat(id: Long): TdApi.Chat = chats[id] ?: send(TdApi.GetChat(id)).also { chats[id] = it }

    fun senderName(s: TdApi.MessageSender?): String = when (s) {
        is TdApi.MessageSenderUser -> users[s.userId]?.let { "${it.firstName} ${it.lastName}".trim() } ?: ""
        is TdApi.MessageSenderChat -> chats[s.chatId]?.title ?: ""
        else -> ""
    }
}

fun TdApi.FormattedText?.str(): String = this?.text ?: ""

/** 채팅 목록에 보여줄 마지막 메시지 요약 */
fun msgPreview(m: TdApi.Message?): String {
    val c = m?.content ?: return ""
    fun cap(label: String, t: TdApi.FormattedText?) = if (t.str().isBlank()) label else "$label ${t.str()}"
    return when (c) {
        is TdApi.MessageText -> c.text.str()
        is TdApi.MessagePhoto -> cap("[사진]", c.caption)
        is TdApi.MessageVideo -> cap("[영상]", c.caption)
        is TdApi.MessageAnimation -> cap("[GIF]", c.caption)
        is TdApi.MessageDocument -> cap("[파일] ${c.document.fileName}", c.caption)
        is TdApi.MessageAudio -> "[음악] ${c.audio.title.ifBlank { c.audio.fileName }}"
        is TdApi.MessageVoiceNote -> "[음성 메시지]"
        is TdApi.MessageVideoNote -> "[동그라미 영상]"
        is TdApi.MessageSticker -> "${c.sticker.emoji} 스티커"
        else -> "[메시지]"
    }.replace('\n', ' ')
}
