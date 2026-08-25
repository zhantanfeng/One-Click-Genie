package com.example.gesturereplay

import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

data class TouchSample(
    val pointerId: Int,
    val action: Int,
    val x: Float,
    val y: Float,
    val xRatio: Float,
    val yRatio: Float,
    val offsetMs: Long
)

data class RecordedGesture(
    val startOffsetMs: Long,
    val durationMs: Long,
    val samples: List<TouchSample>
)

data class GestureRecord(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: Int,
    val totalDurationMs: Long,
    val gestures: List<RecordedGesture>
)

data class RecordingDraft(
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: Int,
    val gestures: List<RecordedGesture>
) {
    val totalDurationMs: Long
        get() = gestures.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L
}

enum class EngineState {
    IDLE,
    RECORDING_GRACE_PERIOD,
    RECORDING,
    RECORDED_PREVIEW,
    PLAYBACK_COUNTDOWN,
    PLAYING
}

data class AppState(
    val records: List<GestureRecord> = emptyList(),
    val engineState: EngineState = EngineState.IDLE,
    val draft: RecordingDraft? = null,
    val countdownMs: Long? = null,
    val message: String? = null
)

internal fun List<MotionEvent>.toRecordedGesture(
    recordingStartMs: Long,
    screenWidth: Int,
    screenHeight: Int
): RecordedGesture {
    val gestureStart = first().downTime
    val samples = buildList {
        this@toRecordedGesture.forEach { event ->
            for (historyIndex in 0 until event.historySize) {
                val historicalTime = event.getHistoricalEventTime(historyIndex)
                for (pointerIndex in 0 until event.pointerCount) {
                    val x = event.getHistoricalX(pointerIndex, historyIndex)
                    val y = event.getHistoricalY(pointerIndex, historyIndex)
                    add(
                        TouchSample(
                            pointerId = event.getPointerId(pointerIndex),
                            action = MotionEvent.ACTION_MOVE,
                            x = x,
                            y = y,
                            xRatio = x / screenWidth.coerceAtLeast(1),
                            yRatio = y / screenHeight.coerceAtLeast(1),
                            offsetMs = historicalTime - gestureStart
                        )
                    )
                }
            }
            for (pointerIndex in 0 until event.pointerCount) {
                val pointerAction = when {
                    pointerIndex == event.actionIndex -> event.actionMasked
                    event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_MOVE
                    event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_MOVE
                    else -> event.actionMasked
                }
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                add(
                    TouchSample(
                        pointerId = event.getPointerId(pointerIndex),
                        action = pointerAction,
                        x = x,
                        y = y,
                        xRatio = x / screenWidth.coerceAtLeast(1),
                        yRatio = y / screenHeight.coerceAtLeast(1),
                        offsetMs = event.eventTime - gestureStart
                    )
                )
            }
        }
    }
    return RecordedGesture(
        startOffsetMs = (gestureStart - recordingStartMs).coerceAtLeast(0L),
        durationMs = (last().eventTime - gestureStart).coerceAtLeast(1L),
        samples = samples
    )
}

internal fun GestureRecord.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("createdAt", createdAt)
    put("screenWidth", screenWidth)
    put("screenHeight", screenHeight)
    put("orientation", orientation)
    put("totalDurationMs", totalDurationMs)
    put("gestures", JSONArray().apply { gestures.forEach { put(it.toJson()) } })
}

private fun RecordedGesture.toJson(): JSONObject = JSONObject().apply {
    put("startOffsetMs", startOffsetMs)
    put("durationMs", durationMs)
    put("samples", JSONArray().apply {
        samples.forEach { sample ->
            put(JSONObject().apply {
                put("pointerId", sample.pointerId)
                put("action", sample.action)
                put("x", sample.x.toDouble())
                put("y", sample.y.toDouble())
                put("xRatio", sample.xRatio.toDouble())
                put("yRatio", sample.yRatio.toDouble())
                put("offsetMs", sample.offsetMs)
            })
        }
    })
}

internal fun JSONObject.toGestureRecord(): GestureRecord = GestureRecord(
    id = getLong("id"),
    name = getString("name"),
    createdAt = getLong("createdAt"),
    screenWidth = getInt("screenWidth"),
    screenHeight = getInt("screenHeight"),
    orientation = getInt("orientation"),
    totalDurationMs = getLong("totalDurationMs"),
    gestures = getJSONArray("gestures").mapObjects { gesture ->
        RecordedGesture(
            startOffsetMs = gesture.getLong("startOffsetMs"),
            durationMs = gesture.getLong("durationMs"),
            samples = gesture.getJSONArray("samples").mapObjects { sample ->
                TouchSample(
                    pointerId = sample.getInt("pointerId"),
                    action = sample.getInt("action"),
                    x = sample.getDouble("x").toFloat(),
                    y = sample.getDouble("y").toFloat(),
                    xRatio = sample.getDouble("xRatio").toFloat(),
                    yRatio = sample.getDouble("yRatio").toFloat(),
                    offsetMs = sample.getLong("offsetMs")
                )
            }
        )
    }
)

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { index -> transform(getJSONObject(index)) }
