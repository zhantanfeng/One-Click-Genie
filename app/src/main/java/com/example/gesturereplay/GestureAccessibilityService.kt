package com.example.gesturereplay

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.max

class GestureAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val recordedGestures = mutableListOf<RecordedGesture>()
    private var recordingName = ""
    private var recordingStartMs = 0L
    private var recordingWidth = 0
    private var recordingHeight = 0
    private var recordingOrientation = 0
    private var recording = false
    private var receivedFirstGesture = false
    private var overlay: TextView? = null

    private val finishRecordingRunnable = Runnable { finishRecording() }
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!recording || !receivedFirstGesture) return
            val finishAt = lastGestureFinishedAt + IDLE_TIMEOUT_MS
            val remaining = (finishAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
            GestureController.onRecordingCountdown(remaining)
            updateOverlay("正在记录 · %.1f 秒后结束".format(remaining / 1000f))
            if (remaining > 0L) handler.postDelayed(this, 100L)
        }
    }
    private var lastGestureFinishedAt = 0L
    private var lastRawGestureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GestureController.onServiceConnected(true)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording || event == null || event.eventTime < recordingStartMs) return
        if (event.packageName?.toString() == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> markInteractionStarted()
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> markInteractionFinished()
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recordSemanticClick(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> recordSemanticScroll(event)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopTimers()
        removeOverlay()
        if (instance === this) instance = null
        GestureController.onServiceConnected(false)
        super.onDestroy()
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        if (!recording || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val events = gestureEvent.motionEvents
        if (events.isEmpty()) return false
        recordGesture(events)
        return false
    }

    fun startRecording(name: String) {
        stopTimers()
        recordedGestures.clear()
        recordingName = name
        val bounds = currentScreenBounds()
        recordingWidth = bounds.first
        recordingHeight = bounds.second
        recordingOrientation = GestureController.currentOrientation()
        recordingStartMs = SystemClock.uptimeMillis()
        recording = true
        receivedFirstGesture = false
        showOverlay("正在记录 · 等待首次操作")
        Log.i(TAG, "Recording started: $name, screen=${recordingWidth}x$recordingHeight")
    }

    private fun recordGesture(events: List<MotionEvent>) {
        if (!recording) return
        if (events.first().downTime < recordingStartMs) return
        markInteractionStarted()
        val gesture = events.toRecordedGesture(
            recordingStartMs = recordingStartMs,
            screenWidth = recordingWidth,
            screenHeight = recordingHeight
        )
        if (gesture.samples.isNotEmpty()) recordedGestures += gesture
        lastRawGestureAt = SystemClock.uptimeMillis()
        Log.d(TAG, "Gesture captured: samples=${gesture.samples.size}, duration=${gesture.durationMs}ms")
        markInteractionFinished()
    }

    private fun recordSemanticClick(event: AccessibilityEvent) {
        if (shouldIgnoreSemanticEvent()) return
        val bounds = Rect()
        val node = event.source ?: return
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val duration = if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) 600L else 40L
        recordSyntheticGesture(
            startMs = event.eventTime,
            durationMs = duration,
            points = listOf(x to y)
        )
    }

    private fun recordSemanticScroll(event: AccessibilityEvent) {
        if (shouldIgnoreSemanticEvent()) return
        val bounds = Rect()
        val node = event.source ?: return
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return

        val horizontal = event.scrollDeltaX
        val vertical = event.scrollDeltaY
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val horizontalScroll = kotlin.math.abs(horizontal) > kotlin.math.abs(vertical)
        val distance = if (horizontalScroll) bounds.width() * 0.35f else bounds.height() * 0.35f
        val direction = if ((if (horizontalScroll) horizontal else vertical) >= 0) -1f else 1f
        val gestureDirection = if (horizontalScroll) {
            if (direction < 0) GestureDirection.LEFT else GestureDirection.RIGHT
        } else {
            if (direction < 0) GestureDirection.UP else GestureDirection.DOWN
        }
        val start = if (horizontalScroll) {
            (centerX - direction * distance) to centerY
        } else {
            centerX to (centerY - direction * distance)
        }
        val end = if (horizontalScroll) {
            (centerX + direction * distance) to centerY
        } else {
            centerX to (centerY + direction * distance)
        }
        recordSyntheticGesture(
            startMs = event.eventTime,
            durationMs = 280L,
            points = listOf(start, end),
            direction = gestureDirection
        )
    }

    private fun shouldIgnoreSemanticEvent(): Boolean =
        SystemClock.uptimeMillis() - lastRawGestureAt < RAW_GESTURE_GRACE_MS

    private fun recordSyntheticGesture(
        startMs: Long,
        durationMs: Long,
        points: List<Pair<Float, Float>>,
        direction: GestureDirection? = null
    ) {
        if (points.isEmpty()) return
        markInteractionStarted()
        val samples = buildList {
            points.forEachIndexed { index, (x, y) ->
                val offset = if (points.size == 1) 0L else durationMs * index / (points.size - 1)
                add(
                    TouchSample(
                        pointerId = 0,
                        action = when {
                            index == 0 -> MotionEvent.ACTION_DOWN
                            index == points.lastIndex -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_MOVE
                        },
                        x = x,
                        y = y,
                        xRatio = x / recordingWidth.coerceAtLeast(1),
                        yRatio = y / recordingHeight.coerceAtLeast(1),
                        offsetMs = offset
                    )
                )
            }
            if (points.size == 1) {
                val (x, y) = points.first()
                add(
                    TouchSample(
                        pointerId = 0,
                        action = MotionEvent.ACTION_UP,
                        x = x,
                        y = y,
                        xRatio = x / recordingWidth.coerceAtLeast(1),
                        yRatio = y / recordingHeight.coerceAtLeast(1),
                        offsetMs = durationMs
                    )
                )
            }
        }
        recordedGestures += RecordedGesture(
            startOffsetMs = (startMs - recordingStartMs).coerceAtLeast(0L),
            durationMs = durationMs,
            samples = samples,
            direction = direction ?: samples.toDirection()
        )
        markInteractionFinished()
        Log.d(TAG, "Semantic gesture captured: points=${points.size}")
    }

    private fun markInteractionStarted() {
        if (!receivedFirstGesture) {
            receivedFirstGesture = true
            GestureController.onRecordingActive()
        }
        handler.removeCallbacks(finishRecordingRunnable)
        handler.removeCallbacks(countdownRunnable)
        updateOverlay("正在记录")
    }

    private fun markInteractionFinished() {
        if (!recording || !receivedFirstGesture) return
        lastGestureFinishedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(finishRecordingRunnable)
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
        handler.postDelayed(finishRecordingRunnable, IDLE_TIMEOUT_MS)
    }

    private fun finishRecording() {
        if (!recording) return
        recording = false
        stopTimers()
        removeOverlay()
        GestureController.onRecordingFinished(
            RecordingDraft(
                name = recordingName,
                screenWidth = recordingWidth,
                screenHeight = recordingHeight,
                orientation = recordingOrientation,
                gestures = recordedGestures.toList()
            )
        )
        Log.i(TAG, "Recording finished: gestures=${recordedGestures.size}")
    }

    fun playAfterCountdown(record: GestureRecord) {
        stopTimers()
        showOverlay("3 秒后执行")
        val startedAt = SystemClock.uptimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startedAt
                val remaining = (PLAYBACK_COUNTDOWN_MS - elapsed).coerceAtLeast(0L)
                GestureController.onPlaybackCountdown(remaining)
                updateOverlay("%.1f 秒后执行".format(remaining / 1000f))
                if (remaining > 0L) {
                    handler.postDelayed(this, 100L)
                } else {
                    GestureController.onPlaybackStarted()
                    updateOverlay("正在执行")
                    playGestureAt(record, 0, 0L)
                }
            }
        }
        handler.post(ticker)
    }

    private fun playGestureAt(record: GestureRecord, index: Int, previousEndMs: Long) {
        if (index >= record.gestures.size) {
            removeOverlay()
            GestureController.onPlaybackFinished(true)
            return
        }
        val source = record.gestures[index]
        val delay = (source.startOffsetMs - previousEndMs).coerceAtLeast(0L)
        handler.postDelayed({
            val gesture = buildGesture(source)
            if (gesture == null) {
                removeOverlay()
                GestureController.onPlaybackFinished(false)
                return@postDelayed
            }
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        playGestureAt(
                            record = record,
                            index = index + 1,
                            previousEndMs = source.startOffsetMs + source.durationMs
                        )
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        removeOverlay()
                        GestureController.onPlaybackFinished(false)
                    }
                },
                handler
            )
            if (!accepted) {
                removeOverlay()
                GestureController.onPlaybackFinished(false)
            }
        }, delay)
    }

    private fun buildGesture(source: RecordedGesture): GestureDescription? {
        val bounds = currentScreenBounds()
        val width = bounds.first.toFloat()
        val height = bounds.second.toFloat()
        val grouped = source.samples.groupBy { it.pointerId }
        if (grouped.isEmpty()) return null
        val builder = GestureDescription.Builder()
        grouped.values.take(GestureDescription.getMaxStrokeCount()).forEach { pointerSamples ->
            val ordered = pointerSamples.sortedBy { it.offsetMs }
            val first = ordered.first()
            val startMs = first.offsetMs.coerceAtLeast(0L)
            val endMs = ordered.last().offsetMs
            val duration = max(1L, endMs - startMs).coerceAtMost(GestureDescription.getMaxGestureDuration())
            val path = Path().apply {
                moveTo(first.xRatio * width, first.yRatio * height)
                ordered.drop(1).forEach { point ->
                    lineTo(point.xRatio * width, point.yRatio * height)
                }
                if (ordered.size == 1) {
                    lineTo(first.xRatio * width + 0.1f, first.yRatio * height + 0.1f)
                }
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, startMs, duration))
        }
        return runCatching { builder.build() }
            .onFailure { Log.e(TAG, "Unable to build gesture", it) }
            .getOrNull()
    }

    private fun currentScreenBounds(): Pair<Int, Int> {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    private fun showOverlay(text: String) {
        removeOverlay()
        val view = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                setColor(0xE6262C36.toInt())
                cornerRadius = 18f
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }
        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
        overlay = view
    }

    private fun updateOverlay(text: String) {
        overlay?.text = text
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
        overlay = null
    }

    private fun stopTimers() {
        handler.removeCallbacks(finishRecordingRunnable)
        handler.removeCallbacks(countdownRunnable)
    }

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        private const val TAG = "GestureReplayService"
        private const val IDLE_TIMEOUT_MS = 5_000L
        private const val PLAYBACK_COUNTDOWN_MS = 3_000L
        private const val RAW_GESTURE_GRACE_MS = 500L
    }
}
