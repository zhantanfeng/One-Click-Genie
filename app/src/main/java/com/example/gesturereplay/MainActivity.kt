package com.example.gesturereplay

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GestureReplayApp() }
    }
}

private val Background = Color(0xFFF7F9FC)
private val Ink = Color(0xFF182230)
private val Muted = Color(0xFF667085)
private val Blue = Color(0xFF175CD3)
private val Border = Color(0xFFE4E7EC)
private val Danger = Color(0xFFB42318)

@Composable
private fun GestureReplayApp() {
    val state by GestureController.state.collectAsStateWithLifecycle()
    var selectedRecordId by remember { mutableStateOf<Long?>(null) }
    val selectedRecord = state.records.firstOrNull { it.id == selectedRecordId }
    val draft = state.draft
    val activity = LocalContext.current as? Activity

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Blue,
            background = Background,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        when {
            state.engineState == EngineState.RECORDED_PREVIEW && draft != null -> {
                RecordingPreview(
                    draft = draft,
                    onCancel = GestureController::discardDraft,
                    onSave = GestureController::saveDraft
                )
            }
            selectedRecord != null -> {
                RecordDetail(
                    record = selectedRecord,
                    onBack = { selectedRecordId = null },
                    onPlay = {
                        GestureController.play(selectedRecord)
                        activity?.moveTaskToBack(true)
                    },
                    onRerecord = {
                        if (GestureController.startRecording(selectedRecord.name, selectedRecord.id)) {
                            selectedRecordId = null
                            activity?.moveTaskToBack(true)
                        }
                    },
                    onRename = { GestureController.renameRecord(selectedRecord.id, it) },
                    onDelete = {
                        GestureController.deleteRecord(selectedRecord.id)
                        selectedRecordId = null
                    }
                )
            }
            else -> {
                RecordList(
                    state = state,
                    onOpen = { selectedRecordId = it.id }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordList(state: AppState, onOpen: (GestureRecord) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("操作轨迹", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
                actions = {
                    TextButton(onClick = { naming = true }) {
                        Text("＋", fontSize = 22.sp)
                        Text("添加手势")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            state.message?.let { message ->
                Text(
                    text = message,
                    color = Blue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFEFF4FF))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            if (state.records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无操作轨迹", color = Muted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                ) {
                    items(state.records, key = { it.id }) { record ->
                        RecordRow(
                            record = record,
                            onOpen = { onOpen(record) },
                            onPlay = {
                                GestureController.play(record)
                                activity?.moveTaskToBack(true)
                            }
                        )
                        if (record != state.records.last()) HorizontalDivider(color = Border)
                    }
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = "添加手势",
            confirmLabel = "开始记录",
            initialName = "",
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                if (GestureController.startRecording(name.ifBlank { "未命名" })) {
                    activity?.moveTaskToBack(true)
                }
            }
        )
    }
}

@Composable
private fun RecordRow(record: GestureRecord, onOpen: () -> Unit, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.name,
                color = Ink,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${record.gestures.size} 个操作 · ${formatDuration(record.totalDurationMs)}",
                color = Muted,
                fontSize = 13.sp
            )
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
            Text("▶", color = Blue, fontSize = 22.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingPreview(
    draft: RecordingDraft,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    BackHandler(onBack = onCancel)
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("录制预览", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(draft.name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "已记录 ${draft.gestures.size} 个操作，总时长 ${formatDuration(draft.totalDurationMs)}",
                    color = Muted
                )
                Spacer(Modifier.height(12.dp))
            }
            items(draft.gestures.size) { index ->
                GestureSummary(index, draft.gestures[index])
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordDetail(
    record: GestureRecord,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onRerecord: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("‹", fontSize = 32.sp, color = Ink) }
                },
                title = { Text(record.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "${record.gestures.size} 个操作 · ${formatDuration(record.totalDurationMs)}",
                    color = Muted
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("▶  执行") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { renaming = true }, modifier = Modifier.weight(1f)) {
                        Text("重命名")
                    }
                    OutlinedButton(onClick = onRerecord, modifier = Modifier.weight(1f)) {
                        Text("重新录制")
                    }
                }
                TextButton(onClick = { confirmingDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("删除", color = Danger)
                }
                HorizontalDivider(color = Border)
                Spacer(Modifier.height(6.dp))
            }
            items(record.gestures.size) { index ->
                GestureSummary(index, record.gestures[index])
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "重命名",
            confirmLabel = "保存",
            initialName = record.name,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                onRename(it.ifBlank { record.name })
            }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text(record.name) },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("删除", color = Danger) }
            }
        )
    }
}

@Composable
private fun GestureSummary(index: Int, gesture: RecordedGesture) {
    val samples = gesture.samples
    val first = samples.firstOrNull()
    val last = samples.lastOrNull()
    val distance = if (first != null && last != null) hypot(
        (last.x - first.x).toDouble(),
        (last.y - first.y).toDouble()
    ) else 0.0
    val type = when {
        samples.map { it.pointerId }.distinct().size > 1 -> "多指"
        distance > 24 -> "滑动"
        gesture.durationMs >= 500 -> "长按"
        else -> "点击"
    }
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", color = Blue, fontWeight = FontWeight.SemiBold)
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(type, color = Ink, fontWeight = FontWeight.Medium)
                Text(
                    text = if (first == null) {
                        formatDuration(gesture.durationMs)
                    } else {
                        "(${first.x.toInt()}, ${first.y.toInt()}) · ${formatDuration(gesture.durationMs)}"
                    },
                    color = Muted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) } }
    )
}

private fun formatDuration(durationMs: Long): String = when {
    durationMs < 1_000 -> "${durationMs} ms"
    durationMs < 60_000 -> "%.1f 秒".format(durationMs / 1_000f)
    else -> "${durationMs / 60_000} 分 ${durationMs % 60_000 / 1_000} 秒"
}
