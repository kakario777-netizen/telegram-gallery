package com.kakario.tggallery

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
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

    private val prefs get() = appCtx.getSharedPreferences("tg", Context.MODE_PRIVATE)

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
                        init(appCtx)
                    }
                    else -> {}
                }
            }
            is TdApi.UpdateNewChat -> chats[o.chat.id] = o.chat
            is TdApi.UpdateChatTitle -> chats[o.chatId]?.title = o.title
            is TdApi.UpdateChatPhoto -> chats[o.chatId]?.photo = o.photo
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
            applicationVersion = "0.1"
        }
        client?.send(p) { r ->
            if (r is TdApi.Error) {
                clearKeys()
                error.value = "API 키를 확인해 주세요 (${r.message})"
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

    /** 썸네일 등 작은 파일을 받아 경로를 돌려줌 (폰 갤러리에 저장하지 않음, 앱 캐시에만) */
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
}
