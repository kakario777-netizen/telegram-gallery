package com.kakario.tggallery

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 상단 다운로드 아이콘: 받는 중이면 화살표가 아래로 슬슬 내려가는 애니메이션, 누르면 실황 */
@Composable
fun DownloadIndicator() {
    val entries by Downloader.entries.collectAsState()
    if (entries.isEmpty()) return
    val running = entries.count { it.st == Downloader.St.WAIT || it.st == Downloader.St.RUN }
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
            if (running > 0) {
                val t = rememberInfiniteTransition(label = "dl")
                val y by t.animateFloat(-9f, 9f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "y")
                val a by t.animateFloat(1f, 0.2f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "a")
                Box(Modifier.size(24.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                    Icon(Ic.ArrowDown, "다운로드 중", tint = MaterialTheme.colorScheme.primary.copy(alpha = a),
                        modifier = Modifier.offset(y = y.dp).size(20.dp))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
            } else Icon(Ic.Download, "다운로드 내역")
        }
    }
    if (open) DownloadSheet { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(onDismiss: () -> Unit) {
    val entries by Downloader.entries.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("다운로드", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { Downloader.clearFinished() }) { Text("완료 지우기") }
            TextButton(onClick = { Downloader.cancelAll() }) { Text("모두 취소", color = MaterialTheme.colorScheme.error) }
        }
        val total = entries.size
        val done = entries.count { it.st == Downloader.St.DONE }
        Text("완료 $done / 전체 $total" + (Prefs.folderName()?.let { " · 저장 위치: $it" } ?: ""),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(12.dp)) {
            items(entries.reversed(), key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        TdImage(e.item.thumb, e.item.mini, 1, Modifier.fillMaxSize(), ContentScale.Crop)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        val m = e.item
                        Text(m.fileName.ifBlank { m.caption.lineSequence().firstOrNull { it.isNotBlank() } ?: if (m.kind == Kind.PHOTO) "사진" else "영상" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        val st = when (e.st) {
                            Downloader.St.WAIT -> "대기 중 · ${fmtSize(m.size)}"
                            Downloader.St.RUN -> "${e.percent}% · ${fmtSize(e.done)} / ${fmtSize(if (e.total > 0) e.total else m.size)}"
                            Downloader.St.DONE -> "완료 · ${fmtSize(m.size)}"
                            Downloader.St.FAIL -> "실패"
                            Downloader.St.CANCEL -> "취소됨"
                        }
                        Text(st, fontSize = 12.sp, color = if (e.st == Downloader.St.FAIL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.st == Downloader.St.RUN) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (e.st == Downloader.St.WAIT || e.st == Downloader.St.RUN) {
                        IconButton(onClick = { Downloader.cancel(e.id) }) { Icon(Icons.Default.Close, "취소") }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** 두 번 물어보는 확인 창 */
@Composable
fun DoubleConfirm(title: String, first: String, second: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (step == 1) title else "정말 진행할까요?") },
        text = { Text(if (step == 1) first else second) },
        confirmButton = {
            TextButton(onClick = { if (step == 1) step = 2 else onConfirm() }) {
                Text(if (step == 1) "삭제" else "네, 삭제", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } })
}
