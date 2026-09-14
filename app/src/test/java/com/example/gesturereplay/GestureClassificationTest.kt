package com.example.gesturereplay

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for gesture classification: taps must not be misclassified as swipes.
 */
class GestureClassificationTest {

    private fun sample(
        x: Float,
        y: Float,
        offsetMs: Long,
        pointerId: Int = 0,
        action: Int = MotionEvent.ACTION_MOVE
    ) = TouchSample(
        pointerId = pointerId,
        action = action,
        x = x,
        y = y,
        xRatio = 0f,
        yRatio = 0f,
        offsetMs = offsetMs
    )

    @Test
    fun `clean tap stays a tap`() {
        val samples = listOf(
            sample(500f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(505f, 1008f, 40, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.TAP, samples.kindFor(durationMs = 40))
        assertNull(samples.toDirection())
    }

    @Test
    fun `sloppy tap with drift stays a tap`() {
        // Finger drifts ~106px while tapping: far below the swipe threshold.
        val samples = listOf(
            sample(500f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(545f, 1060f, 40),
            sample(570f, 1080f, 90, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.TAP, samples.kindFor(durationMs = 90))
        assertNull(samples.toDirection())
    }

    @Test
    fun `long press without movement stays a long press`() {
        val samples = listOf(
            sample(500f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(502f, 1001f, 300),
            sample(501f, 1002f, 600, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.LONG_PRESS, samples.kindFor(durationMs = 600))
    }

    @Test
    fun `long press with wandering finger stays a long press`() {
        // Finger travels far (~1260px) but returns near the origin: the net
        // displacement-to-path ratio is too low for a swipe.
        val samples = listOf(
            sample(500f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(700f, 1200f, 300),
            sample(400f, 800f, 600),
            sample(700f, 1200f, 900, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.LONG_PRESS, samples.kindFor(durationMs = 900))
        assertNull(samples.toDirection())
    }

    @Test
    fun `straight swipe is classified as swipe with direction`() {
        val samples = listOf(
            sample(300f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(500f, 1000f, 50),
            sample(700f, 1000f, 100, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.SWIPE, samples.kindFor(durationMs = 100))
        assertEquals(GestureDirection.RIGHT, samples.toDirection())
    }

    @Test
    fun `vertical swipe direction is detected`() {
        val samples = listOf(
            sample(420f, 1360f, 0, action = MotionEvent.ACTION_DOWN),
            sample(420f, 900f, 60),
            sample(420f, 420f, 120, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureDirection.UP, samples.toDirection())
    }

    @Test
    fun `short deliberate swipe is still a swipe`() {
        // 150px horizontal swipe stays above the 120px threshold.
        val samples = listOf(
            sample(500f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(575f, 1000f, 40),
            sample(650f, 1000f, 80, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.SWIPE, samples.kindFor(durationMs = 80))
        assertEquals(GestureDirection.RIGHT, samples.toDirection())
    }

    @Test
    fun `multi finger tap is not classified by distance between fingers`() {
        // Two fingers land and lift in place. The whole-sample first/last pair
        // spans different fingers; only the primary pointer may be compared.
        val samples = listOf(
            sample(500f, 1000f, 0, pointerId = 0, action = MotionEvent.ACTION_DOWN),
            sample(900f, 600f, 30, pointerId = 1, action = MotionEvent.ACTION_DOWN),
            sample(905f, 605f, 60, pointerId = 1, action = MotionEvent.ACTION_UP),
            sample(505f, 1005f, 90, pointerId = 0, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureKind.TAP, samples.kindFor(durationMs = 90))
        assertNull(samples.toDirection())
    }

    @Test
    fun `semantic synthetic swipe keeps its direction`() {
        val samples = listOf(
            sample(300f, 1000f, 0, action = MotionEvent.ACTION_DOWN),
            sample(500f, 1000f, 280, action = MotionEvent.ACTION_UP)
        )
        assertEquals(GestureDirection.RIGHT, samples.toDirection())
    }
}
