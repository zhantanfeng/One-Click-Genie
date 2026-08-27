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
    private var recordingInputReadyAtMs = 0L
    private var receivedFirstGesture = false
    private var overlay: TextView? = null
    private var pendingHomeAction: Runnable? = nul
    private val removePlaybackResultRunnable = Runnable { removeOverlay() }

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
    private var lastRawGestureEventTime = Long.MIN_VALUE
    private var lastSemanticScrollEventTime = Long.MIN_VALUE
    private var lastSemanticScrollDirection: GestureDirection? = null
    private var lastSemanticClickEventTime = Long.MIN_VALUE
    private var lastClickPoint: Pair<Float, Float>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        GestureController.onServiceConnected(true)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackageName = event?.packageName?.toString()
        if (!recording || event == null || event.eventTime < recordingStartMs) return
        if (event.packageName?.toString() == packageName) return

        Log.d(
            TAG,
            "event type=${event.eventType} time=${event.eventTime} " +
                "pkg=${eventPackageName ?: "?"} source=${event.source != null}"
        )

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                markInteractionStarted()
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                markInteractionFinished()
            }
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
        recording = false
        removeOverlay()
        returnHomeThen { beginRecording(name) }
    }

    private fun beginRecording(name: String) {
        recordedGestures.clear()
        recordingName = name
        val bounds = currentScreenBounds()
        recordingWidth = bounds.first
        recordingHeight = bounds.second
        recordingOrientation = GestureController.currentOrientation()
        recordingStartMs = SystemClock.uptimeMillis()
        recordingInputReadyAtMs = recordingStartMs
        lastRawGestureEventTime = Long.MIN_VALUE
        lastClickPoint = null
        recording = true
        receivedFirstGesture = false
        showOverlay("正在记录 · 等待首次操作")
        Log.i(TAG, "Recording started: $name, screen=${recordingWidth}x$recordingHeight")
    }

    private fun recordGesture(events: List<MotionEvent>) {
        if (!recording) return
        if (events.first().downTime < recordingInputReadyAtMs) return
        val gesture = events.toRecordedGesture(
            recordingStartMs = recordingStartMs,
            screenWidth = recordingWidth,
            screenHeight = recordingHeight
        )
        markInteractionStarted()
        if (gesture.samples.isNotEmpty()) recordedGestures += gesture
        lastRawGestureEventTime = events.last().eventTime
        Log.d(TAG, "Gesture captured: samples=${gesture.samples.size}, duration=${gesture.durationMs}ms")
        markInteractionFinished()
    }

    private fun recordSemanticClick(event: AccessibilityEvent) {
        lastSemanticClickEventTime = event.eventTime
        if (shouldIgnoreSemanticEvent(event.eventTime)) return
        val bounds = Rect()
        val node = event.source ?: findFallbackClickNode(event)
        val point = if (node != null) {
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) bounds.exactCenterX() to bounds.exactCenterY() else null
        } else {
            null
        }
        val (x, y) = point ?: lastClickPoint ?: (recordingWidth / 2f to recordingHeight / 2f)
        lastClickPoint = x to y
        val duration = if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) 600L else 40L
        recordSyntheticGesture(
            startMs = event.eventTime,
            durationMs = duration,
            points = listOf(x to y)
        )
    }

    private fun findFallbackClickNode(event: AccessibilityEvent): android.view.accessibility.AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val labels = event.text.map { it.toString() }.filter { it.isNotBlank() }
        return findNode(root) { node ->
            labels.any { label ->
                node.text?.toString() == label || node.contentDescription?.toString() == label
            }
        } ?: findNode(root) { it.isFocused || it.isAccessibilityFocused }
            ?: findNode(root) { it.isClickable && it.isVisibleToUser }
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun recordSemanticScroll(event: AccessibilityEvent) {
        if (shouldIgnoreSemanticEvent(event.eventTime)) return
        if (lastSemanticClickEventTime != Long.MIN_VALUE &&
            kotlin.math.abs(event.eventTime - lastSemanticClickEventTime) <= CLICK_SCROLL_GRACE_MS
        ) {
            Log.d(TAG, "Ignoring scroll event adjacent to click")
            return
        }
        if (!event.isScrollable) {
            Log.d(TAG, "Ignoring scroll event from non-scrollable source")
            return
        }
        val bounds = Rect()
        val node = event.source ?: return
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return

        val horizontal = event.scrollDeltaX
        val vertical = event.scrollDeltaY
        if (kotlin.math.abs(horizontal) < MIN_SCROLL_DELTA_PX &&
            kotlin.math.abs(vertical) < MIN_SCROLL_DELTA_PX
        ) {
            Log.d(TAG, "Ignoring zero-delta scroll event")
            return
        }
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
        if (event.eventTime == lastSemanticScrollEventTime &&
            gestureDirection == lastSemanticScrollDirection
        ) {
            Log.d(TAG, "Ignoring duplicate scroll event: $gestureDirection")
            return
        }
        lastSemanticScrollEventTime = event.eventTime
        lastSemanticScrollDirection = gestureDirection
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

    private fun shouldIgnoreSemanticEvent(eventTime: Long): Boolean =
        lastRawGestureEventTime != Long.MIN_VALUE &&
            kotlin.math.abs(eventTime - lastRawGestureEventTime) <= RAW_GESTURE_GRACE_MS

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
        removeOverlay()
        returnHomeThen { startPlaybackCountdown(record) }
    }

    private fun startPlaybackCountdown(record: GestureRecord) {
        showOverlay("1 秒后执行")
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
            finishPlayback(true)
            return
        }
        val source = record.gestures[index]
        val delay = (source.startOffsetMs - previousEndMs).coerceAtLeast(0L)
        handler.postDelayed({
            val gesture = buildGesture(source)
            if (gesture == null) {
                finishPlayback(false)
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
                        finishPlayback(false)
                    }
                },
                handler
            )
            if (!accepted) {
                finishPlayback(false)
            }
        }, delay)
    }

    private fun finishPlayback(success: Boolean) {
        showOverlay(if (success) "执行完成" else "执行已中断")
        GestureController.onPlaybackFinished(success)
        handler.removeCallbacks(removePlaybackResultRunnable)
        handler.postDelayed(removePlaybackResultRunnable, PLAYBACK_RESULT_DISPLAY_MS)
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
        handler.removeCallbacks(removePlaybackResultRunnable)
        pendingHomeAction?.let(handler::removeCallbacks)
        pendingHomeAction = null
    }

    private fun returnHomeThen(action: () -> Unit) {
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
            Log.w(TAG, "Unable to perform HOME before gesture operation")
        }
        val callback = Runnable {
            pendingHomeAction = null
            action()
        }
        pendingHomeAction = callback
        handler.postDelayed(callback, HOME_TRANSITION_DELAY_MS)
    }

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        private const val TAG = "GestureReplayService"
        private const val IDLE_TIMEOUT_MS = 5_000L
        private const val PLAYBACK_COUNTDOWN_MS = 1_000L
        private const val PLAYBACK_RESULT_DISPLAY_MS = 1_500L
        private const val HOME_TRANSITION_DELAY_MS = 300L
        // Only suppress a semantic event that is effectively the same dispatch.
        // A wider window drops legitimate consecutive taps on fast UIs.
        private const val RAW_GESTURE_GRACE_MS = 5L
        private const val CLICK_SCROLL_GRACE_MS = 250L
        private const val MIN_SCROLL_DELTA_PX = 16
    }
}
