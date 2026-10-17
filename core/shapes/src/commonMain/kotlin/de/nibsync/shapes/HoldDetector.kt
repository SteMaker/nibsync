package de.nibsync.shapes

/**
 * Detects that the pen is held still at the end of a stroke, which is the gesture that asks
 * for shape recognition. Feed every input sample with [onSample]; [isHolding] tells whether the
 * pen has stayed within [toleranceMm] of one position for at least [holdMs].
 *
 * Positions are in document millimetres, times in milliseconds of any monotonic clock.
 */
class HoldDetector(
    private val toleranceMm: Float,
    val holdMs: Long = DEFAULT_HOLD_MS,
) {
    private var anchorX = 0f
    private var anchorY = 0f
    private var hasAnchor = false
    private var fired = false

    /** Time the pen arrived at the current resting position. */
    var anchorTimeMs: Long = 0
        private set

    /** Number of samples up to (including) the one that started the current rest. */
    var anchorSampleCount: Int = 0
        private set

    private var samples = 0

    fun onSample(x: Float, y: Float, timeMs: Long) {
        samples++
        val dx = x - anchorX
        val dy = y - anchorY
        if (!hasAnchor || dx * dx + dy * dy > toleranceMm * toleranceMm) {
            hasAnchor = true
            anchorX = x
            anchorY = y
            anchorTimeMs = timeMs
            anchorSampleCount = samples
            fired = false
        }
    }

    /** True once the pen has rested for [holdMs]; reported only once per resting position. */
    fun consumeHold(nowMs: Long): Boolean {
        if (!hasAnchor || fired || samples < MIN_SAMPLES || nowMs - anchorTimeMs < holdMs) return false
        fired = true
        return true
    }

    /** Time at which the current rest becomes a hold. */
    val holdDeadlineMs: Long get() = anchorTimeMs + holdMs

    fun reset() {
        hasAnchor = false
        fired = false
        samples = 0
        anchorSampleCount = 0
    }

    companion object {
        const val DEFAULT_HOLD_MS = 500L

        /** A tap or dot is not a shape. */
        private const val MIN_SAMPLES = 5
    }
}
