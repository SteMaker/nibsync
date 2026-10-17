package de.nibsync.shapes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoldDetectorTest {
    private fun drawLine(d: HoldDetector, until: Long) {
        var t = 0L
        while (t <= until) {
            d.onSample(t / 10f, 0f, t) // moves 0.8 mm per 8 ms sample
            t += 8
        }
    }

    @Test
    fun holdIsDetectedAfterRestingLongEnough() {
        val d = HoldDetector(toleranceMm = 1f)
        drawLine(d, 400)
        // Pen rests at x = 40 with tiny jitter.
        for (t in 408L..1000L step 8) d.onSample(40f + (t % 3) * 0.1f, 0.2f, t)
        assertEquals(400L, d.anchorTimeMs)
        assertFalse(d.consumeHold(899))
        assertTrue(d.consumeHold(900))
        assertFalse(d.consumeHold(950), "reported only once")
    }

    @Test
    fun movingPenNeverHolds() {
        val d = HoldDetector(toleranceMm = 1f)
        drawLine(d, 2000)
        assertFalse(d.consumeHold(2000))
    }

    @Test
    fun holdWithoutNewSamplesCountsToo() {
        // Some digitisers stop sending events while the pen is perfectly still.
        val d = HoldDetector(toleranceMm = 1f)
        drawLine(d, 400)
        assertTrue(d.consumeHold(400 + HoldDetector.DEFAULT_HOLD_MS))
    }

    @Test
    fun movingOnAfterAHoldAllowsAnotherHold() {
        val d = HoldDetector(toleranceMm = 1f)
        drawLine(d, 400)
        assertTrue(d.consumeHold(900))
        d.onSample(60f, 0f, 950)
        assertFalse(d.consumeHold(1000))
        assertTrue(d.consumeHold(1450))
    }

    @Test
    fun aDotIsNotAHold() {
        val d = HoldDetector(toleranceMm = 1f)
        d.onSample(0f, 0f, 0)
        d.onSample(0f, 0f, 8)
        assertFalse(d.consumeHold(2000))
    }

    @Test
    fun anchorSampleCountMarksWhereTheRestStarted() {
        val d = HoldDetector(toleranceMm = 1f)
        drawLine(d, 400) // 51 samples
        repeat(20) { d.onSample(40f, 0f, 408L + it * 8) }
        assertEquals(51, d.anchorSampleCount)
        d.reset()
        assertFalse(d.consumeHold(10_000))
    }
}
