package com.gesturemouse.wear.sensor

/**
 * Pure-Kotlin ambient light sensor detector for effortless eyes-free "Palm-Cover" actions.
 *
 * Covering the watch face with your other hand causes a sharp drop to near-zero lux.
 * Tracks an adaptive ambient room-brightness baseline so palm cover works reliably both in
 * bright daylight and dimly lit offices, with uncover hysteresis and cooldown to prevent
 * accidental re-triggering.
 */
class PalmCoverDetector {

    private var ambientBaselineLux: Float = 0f
    private var hasBaseline: Boolean = false
    private var isCovered: Boolean = false
    private var darkStartTimestampMs: Long = -1L
    private var lastTriggerTimestampMs: Long = -10_000L

    fun reset() {
        ambientBaselineLux = 0f
        hasBaseline = false
        isCovered = false
        darkStartTimestampMs = -1L
        lastTriggerTimestampMs = -10_000L
    }

    /**
     * Ingests a raw ambient light sample in lux (`Sensor.TYPE_LIGHT`).
     *
     * @return `true` on the rising edge when a deliberate palm cover is confirmed.
     */
    fun onLuxSample(lux: Float, timestampMs: Long): Boolean {
        val safeLux = lux.coerceAtLeast(0f)

        if (!hasBaseline) {
            ambientBaselineLux = safeLux
            hasBaseline = true
            if (safeLux <= COVER_LUX_THRESHOLD) {
                darkStartTimestampMs = timestampMs
            }
            return false
        }

        // Wait for watch face to be uncovered before arming the next trigger
        if (isCovered) {
            if (safeLux >= UNCOVER_LUX_THRESHOLD) {
                isCovered = false
                darkStartTimestampMs = -1L
                ambientBaselineLux = maxOf(safeLux, ambientBaselineLux * 0.5f)
            }
            return false
        }

        val cooldownElapsed = (timestampMs - lastTriggerTimestampMs) >= TRIGGER_COOLDOWN_MS

        if (safeLux <= COVER_LUX_THRESHOLD) {
            if (darkStartTimestampMs < 0L) {
                darkStartTimestampMs = timestampMs
            }

            // Sharp drop from illuminated environment OR sustained near-zero cover
            val isSharpDrop = ambientBaselineLux >= MIN_SHARP_DROP_BASELINE_LUX &&
                safeLux <= ambientBaselineLux * SHARP_DROP_RATIO
            val coveredDurationMs = timestampMs - darkStartTimestampMs
            val isSustainedDark = safeLux <= SUSTAINED_DARK_LUX &&
                coveredDurationMs >= SUSTAINED_COVER_MS

            if (cooldownElapsed && (isSharpDrop || isSustainedDark)) {
                isCovered = true
                lastTriggerTimestampMs = timestampMs
                return true
            }
        } else {
            darkStartTimestampMs = -1L
            // Adapt slowly to gradual room lighting changes while uncovered
            ambientBaselineLux = ambientBaselineLux * BASELINE_EMA_ALPHA +
                safeLux * (1f - BASELINE_EMA_ALPHA)
        }

        return false
    }

    companion object {
        private const val COVER_LUX_THRESHOLD = 3.0f
        private const val SUSTAINED_DARK_LUX = 2.0f
        private const val UNCOVER_LUX_THRESHOLD = 8.0f
        private const val MIN_SHARP_DROP_BASELINE_LUX = 12.0f
        private const val SHARP_DROP_RATIO = 0.15f
        private const val SUSTAINED_COVER_MS = 280L
        private const val TRIGGER_COOLDOWN_MS = 900L
        private const val BASELINE_EMA_ALPHA = 0.85f
    }
}
