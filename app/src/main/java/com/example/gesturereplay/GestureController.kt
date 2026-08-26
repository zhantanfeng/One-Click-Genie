package com.example.gesturereplay

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GestureController {
    private lateinit var application: Application
    private lateinit var repository: GestureRepository
    private val mutableState = MutableStateFlow(AppState())
    private var replacementRecordId: Long? = null
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun initialize(app: Application) {
        if (::application.isInitialized) return
        application = app
        repository = GestureRepository(app)
        mutableState.value = AppState(
            records = repository.load(),
            serviceConnected = GestureAccessibilityService.instance != null
        )
    }

    fun onServiceConnected(connected: Boolean) {
        mutableState.value = mutableState.value.copy(serviceConnected = connected)
    }

    fun startRecording(name: String, replaceRecordId: Long? = null): Boolean {
        val service = GestureAccessibilityService.instance ?: run {
            application.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            return false
        }
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.RECORDING_GRACE_PERIOD,
            draft = null,
            countdownMs = null,
            message = null
        )
        replacementRecordId = replaceRecordId
        service.startRecording(name)
        return true
    }

    fun onRecordingActive() {
        mutableState.value = mutableState.value.copy(engineState = EngineState.RECORDING)
    }

    fun onRecordingCountdown(remainingMs: Long?) {
        mutableState.value = mutableState.value.copy(countdownMs = remainingMs)
    }

    fun onRecordingFinished(draft: RecordingDraft) {
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.RECORDED_PREVIEW,
            draft = draft,
            countdownMs = null
        )
        application.startActivity(
            Intent(application, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
    }

    fun saveDraft() {
        val draft = mutableState.value.draft ?: return
        val record = GestureRecord(
            id = System.currentTimeMillis(),
            name = draft.name,
            createdAt = System.currentTimeMillis(),
            screenWidth = draft.screenWidth,
            screenHeight = draft.screenHeight,
            orientation = draft.orientation,
            totalDurationMs = draft.totalDurationMs,
            gestures = draft.gestures
        )
        val records = listOf(record) + mutableState.value.records.filterNot {
            it.id == replacementRecordId
        }
        replacementRecordId = null
        repository.save(records)
        mutableState.value = AppState(
            records = records,
            serviceConnected = GestureAccessibilityService.instance != null,
            message = "已保存"
        )
    }

    fun discardDraft() {
        replacementRecordId = null
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.IDLE,
            draft = null,
            countdownMs = null
        )
    }

    fun deleteRecord(id: Long) {
        val records = mutableState.value.records.filterNot { it.id == id }
        repository.save(records)
        mutableState.value = mutableState.value.copy(records = records)
    }

    fun renameRecord(id: Long, name: String) {
        val records = mutableState.value.records.map { record ->
            if (record.id == id) record.copy(name = name) else record
        }
        repository.save(records)
        mutableState.value = mutableState.value.copy(records = records, message = "已重命名")
    }

    fun play(record: GestureRecord) {
        val service = GestureAccessibilityService.instance ?: run {
            showMessage("操作轨迹服务未运行")
            return
        }
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.PLAYBACK_COUNTDOWN,
            countdownMs = 3000L,
            message = null
        )
        service.playAfterCountdown(record)
    }

    fun onPlaybackCountdown(remainingMs: Long) {
        mutableState.value = mutableState.value.copy(countdownMs = remainingMs)
    }

    fun onPlaybackStarted() {
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.PLAYING,
            countdownMs = null
        )
    }

    fun onPlaybackFinished(success: Boolean) {
        mutableState.value = mutableState.value.copy(
            engineState = EngineState.IDLE,
            countdownMs = null,
            message = if (success) "执行完成" else "执行已中断"
        )
    }

    fun showMessage(message: String) {
        mutableState.value = mutableState.value.copy(message = message)
    }

    fun currentOrientation(): Int = application.resources.configuration.orientation
        .takeIf { it != Configuration.ORIENTATION_UNDEFINED } ?: Configuration.ORIENTATION_PORTRAIT
}
