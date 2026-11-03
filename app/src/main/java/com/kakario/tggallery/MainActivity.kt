package com.kakario.tggallery

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.drinkless.tdlib.TdApi

class MainActivity : ComponentActivity() {
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            Prefs.setDownloadUri(uri.toString())
            Downloader.folderPicked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.init(this)
        Downloader.init(this)
        Td.init(this)
        Nav.pickFolder = { runCatching { folderPicker.launch(null) } }
        setContent {
            val ctx = LocalContext.current
            val bgv by Prefs.bg.collectAsState()
            val sysDark = isSystemInDarkTheme()
            val scheme = if (bgv == 0L) {
                if (Build.VERSION.SDK_INT >= 31) { if (sysDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
                else if (sysDark) darkColorScheme() else lightColorScheme()
            } else {
                val bg = Color(bgv)
                val dark = bg.isDark()
                val base = if (Build.VERSION.SDK_INT >= 31) { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
                else if (dark) darkColorScheme() else lightColorScheme()
                val raised = lerp(bg, if (dark) Color.White else Color.Black, 0.06f)
                base.copy(
                    background = bg, surface = bg, surfaceContainer = raised, surfaceContainerLow = raised,
                    surfaceContainerHigh = lerp(bg, if (dark) Color.White else Color.Black, 0.1f),
                    surfaceVariant = lerp(bg, if (dark) Color.White else Color.Black, 0.1f)
                )
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    val state by Td.auth.collectAsState()
    val keysSet by Td.keysSet.collectAsState()
    val stack by Nav.stack.collectAsState()
    val viewer by Nav.viewer.collectAsState()
    val dl by Downloader.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (val s = state) {
                null, is TdApi.AuthorizationStateClosed, is TdApi.AuthorizationStateClosing,
                is TdApi.AuthorizationStateLoggingOut -> Loading("연결 중...")
                is TdApi.AuthorizationStateWaitTdlibParameters ->
                    if (keysSet) Loading("연결 중...") else KeyScreen()
                is TdApi.AuthorizationStateWaitPhoneNumber -> PhoneScreen()
                is TdApi.AuthorizationStateWaitCode -> CodeScreen()
                is TdApi.AuthorizationStateWaitPassword -> PasswordScreen(s.passwordHint)
                is TdApi.AuthorizationStateReady -> {
                    Home()
                    BackHandler(enabled = stack.isNotEmpty()) { Nav.pop() }
                    // 위에 쌓인 화면들 (아래 화면 상태는 그대로 유지)
                    stack.forEachIndexed { i, sc ->
                        key(i, sc) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .clickable(indication = null, interactionSource = null) {}) {
                                when (sc) {
                                    is Nav.Media -> MediaScreen(sc.target)
                                    is Nav.Chat -> ChatScreen(sc.chatId)
                                    Nav.Settings -> SettingsScreen()
                                }
                            }
                        }
                    }
                }
                else -> Loading("이 로그인 방식은 아직 지원하지 않아요. 공식 텔레그램 앱에서 계정 상태를 확인해 주세요.")
            }
        }
        viewer?.let { v -> Viewer(v) { Nav.viewer.value = null } }
        dl?.let { d ->
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp).padding(horizontal = 24.dp)
                    .fillMaxWidth().background(Color(0xE6222222), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val cur = (d.done + 1).coerceAtMost(d.total)
                Text(if (d.done >= d.total) "다운로드 완료 ${d.total - d.failed}/${d.total}" else "다운로드 중 $cur/${d.total} · ${d.percent}%",
                    color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (d.total == 0) 0f else (d.done + d.percent / 100f) / d.total },
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
