package com.kakario.tggallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** 크게 보기 (사진은 옆으로 넘기기, 영상은 플레이어) */
@Composable
fun Viewer(req: Nav.ViewerReq, onClose: () -> Unit) {
    val list = req.list
    if (list.isEmpty()) return
    val act = LocalContext.current.activity()
    val view = LocalView.current
    var rotLocked by remember { mutableStateOf(false) }

    // 전체 화면 + 화면 꺼짐 방지 + 화면 회전 따라가기
    DisposableEffect(Unit) {
        val win = act?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            win?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(rotLocked) {
        act?.requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
    BackHandler(onBack = onClose)

    val pager = rememberPagerState(initialPage = req.start.coerceIn(0, list.size - 1)) { list.size }
    val scope = rememberCoroutineScope()
    var showInfo by remember { mutableStateOf(true) }
    var videoControls by remember { mutableStateOf(true) }
    val current = list[pager.currentPage]
    // 세로 화면(전체화면 아님)에서는 아래 네비게이션 바를 항상 보이고, 가로 전체화면에서는 조작 버튼이 보일 때만
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val uiVisible = if (current.kind == Kind.VIDEO) videoControls else showInfo
    LaunchedEffect(landscape, uiVisible) {
        val ctl = act?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (!landscape || uiVisible) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = null) {}) {
        HorizontalPager(
            pager, key = { list[it].key }, modifier = Modifier.fillMaxSize(),
            // 영상에서는 좌우 끌기를 탐색에 쓰므로 넘기기는 버튼으로
            userScrollEnabled = current.kind != Kind.VIDEO
        ) { page ->
            val m = list[page]
            when {
                m.kind == Kind.VIDEO && pager.currentPage == page -> VideoPage(
                    m, rotLocked, { rotLocked = !rotLocked },
                    hasPrev = page > 0, hasNext = page < list.size - 1,
                    onPrev = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    onNext = { scope.launch { pager.animateScrollToPage(page + 1) } },
                    onClose = onClose,
                    onControls = { videoControls = it }
                )
                m.kind == Kind.GIF && pager.currentPage == page -> Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { showInfo = !showInfo })
                }) { SimplePlayer(m) }
                m.kind == Kind.PHOTO -> Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { showInfo = !showInfo })
                }) { TdImage(m.main, m.mini, 32, Modifier.fillMaxSize(), ContentScale.Fit) }
                else -> TdImage(m.thumb, m.mini, 8, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
        if (showInfo && current.kind != Kind.VIDEO) {
            TopBar(current, "${pager.currentPage + 1} / ${list.size}", rotLocked, { rotLocked = !rotLocked }, onClose)
            if (current.caption.isNotBlank()) {
                Text(
                    current.caption, color = Color.White, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                        .navigationBarsPadding().padding(16.dp)
                )
            }
        }
    }
}

/** 위쪽 막대: 뒤로, 정보, 즐겨찾기, 다운로드, 회전 잠금 */
@Composable
fun TopBar(m: MediaItem, sub: String, rotLocked: Boolean, onRot: () -> Unit, onClose: () -> Unit) {
    val fav = Prefs.favs.collectAsState().value.contains(m.key)
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "뒤로", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            val parts = buildList {
                add(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(m.date * 1000L)))
                if (m.kind != Kind.PHOTO) add(fmtDur(m.duration))
                add(fmtSize(m.size))
            }
            Text(parts.joinToString(" · "), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$sub · ${m.width}×${m.height}", color = Color(0xBBFFFFFF), fontSize = 11.sp, maxLines = 1)
        }
        IconButton(onClick = { Prefs.setFav(listOf(m.key), !fav) }) {
            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "즐겨찾기", tint = if (fav) Color(0xFFFF5A79) else Color.White)
        }
        IconButton(onClick = {
            Downloader.enqueue(listOf(m))
            if (Prefs.downloadUri.value != null) android.widget.Toast.makeText(ctx, "다운로드를 시작했어요", android.widget.Toast.LENGTH_SHORT).show()
        }) { Icon(Ic.Download, "다운로드", tint = Color.White) }
        IconButton(onClick = {
            Prefs.hide(listOf(m))
            android.widget.Toast.makeText(ctx, "무시했어요. 설정 > 무시한 자료에서 되돌릴 수 있어요", android.widget.Toast.LENGTH_SHORT).show()
        }) { Icon(Ic.EyeOff, "무시하기", tint = Color.White) }
        IconButton(onClick = onRot) {
            Icon(if (rotLocked) Ic.RotateLock else Ic.Rotate, "회전 잠금", tint = if (rotLocked) Color(0xFF64B5F6) else Color.White)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun buildPlayer(ctx: Context, m: MediaItem): ExoPlayer =
    ExoPlayer.Builder(ctx)
        .setMediaSourceFactory(ProgressiveMediaSource.Factory(DataSource.Factory { TdDataSource() }))
        .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
        .build().apply {
            setMediaItem(ExoItem.fromUri("td://file/${m.main.id}?size=${if (m.main.size > 0) m.main.size else m.main.expectedSize}"))
            if (m.kind == Kind.GIF) repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }

/** GIF용 단순 반복 재생 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SimplePlayer(m: MediaItem) {
    val ctx = LocalContext.current
    val player = remember(m.key) { buildPlayer(ctx, m) }
    DisposableEffect(player) { onDispose { player.release(); Streams.stop(m) } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize())
}

private enum class DragMode { NONE, SEEK, BRIGHT, VOLUME }

/** 일반 동영상 플레이어 수준의 재생 화면 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    m: MediaItem, rotLocked: Boolean, onRot: () -> Unit,
    hasPrev: Boolean, hasNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
    onControls: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val act = ctx.activity()
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val player = remember(m.key) { buildPlayer(ctx, m) }
    DisposableEffect(player) { onDispose { player.release(); Streams.stop(m) } }

    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(m.duration * 1000L) }
    var controls by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var sliderDrag by remember { mutableStateOf<Float?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    var fit by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying || player.playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (ended) controls = true
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            if (player.duration > 0) dur = player.duration
            delay(250)
        }
    }
    // 재생 중이면 조작 버튼 자동 숨김
    LaunchedEffect(controls, playing, touchTick, sliderDrag) {
        if (controls && playing && sliderDrag == null && !ended) { delay(3500); controls = false }
    }
    LaunchedEffect(hintTick) { if (hint != null) { delay(700); hint = null } }
    LaunchedEffect(controls) { onControls(controls) }

    fun showHint(s: String) { hint = s; hintTick++ }
    fun togglePlay() {
        if (ended) { player.seekTo(0); ended = false }
        player.playWhenReady = !player.playWhenReady
        touchTick++
    }
    fun seekBy(ms: Long) {
        val t = (player.currentPosition + ms).coerceIn(0, dur.coerceAtLeast(0))
        player.seekTo(t)
        pos = t
        showHint((if (ms > 0) "+" else "-") + "${abs(ms) / 1000}초  ${fmtMs(t)}")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            update = { it.resizeMode = if (fit) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_ZOOM },
            modifier = Modifier.fillMaxSize()
        )

        // 손가락 조작: 탭 = 버튼 보이기, 두 번 탭 = 좌/우 10초, 끌기 = 탐색·밝기·음량
        Box(
            Modifier.fillMaxSize()
                .pointerInput(m.key) {
                    detectTapGestures(
                        onTap = { controls = !controls; touchTick++ },
                        onDoubleTap = { o ->
                            when {
                                o.x < size.width * 0.4f -> seekBy(-10_000)
                                o.x > size.width * 0.6f -> seekBy(10_000)
                                else -> togglePlay()
                            }
                        }
                    )
                }
                .pointerInput(m.key) {
                    var mode = DragMode.NONE
                    var start = Offset.Zero
                    var total = Offset.Zero
                    var startPos = 0L
                    var target = 0L
                    var startB = 0.5f
                    var startV = 0
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = DragMode.NONE; start = o; total = Offset.Zero
                            startPos = player.currentPosition
                            val attr = act?.window?.attributes?.screenBrightness ?: -1f
                            startB = if (attr >= 0f) attr else runCatching {
                                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                            }.getOrDefault(0.5f)
                            startV = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            if (mode == DragMode.NONE && total.getDistance() > 24f) {
                                mode = if (abs(total.x) > abs(total.y)) DragMode.SEEK
                                else if (start.x < size.width / 2f) DragMode.BRIGHT else DragMode.VOLUME
                            }
                            when (mode) {
                                DragMode.SEEK -> {
                                    val span = dur.coerceIn(60_000L, 300_000L)
                                    target = (startPos + (total.x / size.width * span).toLong()).coerceIn(0, dur.coerceAtLeast(0))
                                    val d = target - startPos
                                    hint = "${fmtMs(target)} / ${fmtMs(dur)}\n" + (if (d >= 0) "+" else "-") + fmtMs(abs(d))
                                }
                                DragMode.BRIGHT -> {
                                    val b = (startB - total.y / (size.height * 0.7f)).coerceIn(0.01f, 1f)
                                    act?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = b } }
                                    hint = "밝기 ${(b * 100).toInt()}%"
                                }
                                DragMode.VOLUME -> {
                                    val v = (startV - total.y / (size.height * 0.7f) * maxVol).toInt().coerceIn(0, maxVol)
                                    if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    hint = "음량 ${v * 100 / maxVol}%"
                                }
                                DragMode.NONE -> {}
                            }
                        },
                        onDragEnd = {
                            if (mode == DragMode.SEEK) { player.seekTo(target); pos = target }
                            mode = DragMode.NONE
                            hintTick++
                        },
                        onDragCancel = { mode = DragMode.NONE; hintTick++ }
                    )
                }
        )

        if (buffering && !ended) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        hint?.let {
            Text(
                it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (controls) {
            Box(Modifier.fillMaxSize().background(Color(0x44000000)))
            Column(Modifier.align(Alignment.TopCenter)) {
                TopBar(m, if (m.caption.isNotBlank()) m.caption.replace('\n', ' ') else (m.fileName.ifBlank { "영상" }), rotLocked, onRot, onClose)
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                RoundBtn(enabled = hasPrev, onClick = onPrev) { Icon(Icons.Default.KeyboardArrowLeft, "이전", tint = Color.White, modifier = Modifier.size(34.dp)) }
                RoundBtn(big = true, onClick = { togglePlay() }) {
                    Icon(if (playing && !ended) Ic.Pause else Ic.Play, "재생", tint = Color.White, modifier = Modifier.size(44.dp))
                }
                RoundBtn(enabled = hasNext, onClick = onNext) { Icon(Icons.Default.KeyboardArrowRight, "다음", tint = Color.White, modifier = Modifier.size(34.dp)) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val shown = sliderDrag?.let { (it * dur).toLong() } ?: pos
                Slider(
                    value = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onValueChange = { sliderDrag = it; touchTick++ },
                    onValueChangeFinished = {
                        sliderDrag?.let { val t = (it * dur).toLong(); player.seekTo(t); pos = t }
                        sliderDrag = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF))
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fmtMs(shown)} / ${fmtMs(dur)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { fit = !fit; touchTick++ }) { Text(if (fit) "화면 맞춤" else "꽉 채우기", color = Color.White, fontSize = 13.sp) }
                    TextButton(onClick = {
                        val sp = when (player.playbackParameters.speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.5f; else -> 1f }
                        player.setPlaybackSpeed(sp); touchTick++
                        scope.launch { showHint("${sp}배속") }
                    }) { Text("${player.playbackParameters.speed}x", color = Color.White, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(if (big) 72.dp else 52.dp).clip(CircleShape).background(Color(0x66000000))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) { Box(Modifier.then(if (enabled) Modifier else Modifier.background(Color.Transparent))) { if (enabled) content() } }
}
