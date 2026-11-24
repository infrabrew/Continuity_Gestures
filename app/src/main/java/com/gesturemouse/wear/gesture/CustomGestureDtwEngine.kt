package com.gesturemouse.wear.gesture

import com.gesturemouse.wear.model.CustomGestureAction
import com.gesturemouse.wear.model.ImuSample
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * User-recorded 3D air gesture template stored as a fixed-length normalized 3-axis gyroscope
 * trajectory (`TEMPLATE_POINTS * 3` floats) for ultra-fast on-device Dynamic Time Warping (DTW).
 */
data class CustomGestureTemplate(
    val id: String,
    val name: String,
    val action: CustomGestureAction,
    val points: FloatArray,
    val threshold: Float = DEFAULT_MATCH_THRESHOLD
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomGestureTemplate) return false
        return id == other.id &&
            name == other.name &&
            action == other.action &&
            threshold == other.threshold &&
            points.contentEquals(other.points)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + points.contentHashCode()
        result = 31 * result + threshold.hashCode()
        return result
    }

    companion object {
        const val TEMPLATE_POINTS = 24
        const val DEFAULT_MATCH_THRESHOLD = 0.92f
    }
}

/**
 * Pure-Kotlin lightweight Dynamic Time Warping (DTW) engine for recording 3–5 samples of
 * custom 3D air gestures and matching them in `< 0.5 ms` on Wear OS without external ML models.
 */
object CustomGestureDtwEngine {

    private const val N = CustomGestureTemplate.TEMPLATE_POINTS
    private const val DTW_BAND = 5

    /**
     * Resamples an arbitrary-length 3-axis trajectory into [N] evenly spaced points and
     * normalizes RMS energy to 1.0 so matching is invariant to gesture speed and moderate
     * amplitude differences.
     */
    fun normalizeAndResample(rawFrames: List<FloatArray>): FloatArray {
        val out = FloatArray(N * 3)
        if (rawFrames.isEmpty()) return out
        if (rawFrames.size == 1) {
            val f = rawFrames[0]
            for (i in 0 until N) {
                out[i * 3] = f[0]
                out[i * 3 + 1] = f[1]
                out[i * 3 + 2] = f[2]
            }
            return normalizeRms(out)
        }

        val lastIdx = rawFrames.size - 1
        for (i in 0 until N) {
            val pos = (i.toFloat() / (N - 1)) * lastIdx
            val lo = pos.toInt().coerceIn(0, lastIdx)
            val hi = (lo + 1).coerceAtMost(lastIdx)
            val frac = pos - lo
            val a = rawFrames[lo]
            val b = rawFrames[hi]
            out[i * 3] = a[0] + (b[0] - a[0]) * frac
            out[i * 3 + 1] = a[1] + (b[1] - a[1]) * frac
            out[i * 3 + 2] = a[2] + (b[2] - a[2]) * frac
        }
        return normalizeRms(out)
    }

    /**
     * Computes average per-point Sakoe-Chiba banded Euclidean DTW distance between two
     * normalized 3D templates of length [N].
     */
    fun dtwDistance(a: FloatArray, b: FloatArray, band: Int = DTW_BAND): Float {
        if (a.size != N * 3 || b.size != N * 3) return Float.POSITIVE_INFINITY
        val inf = 1e9f
        var prev = FloatArray(N + 1) { inf }
        var curr = FloatArray(N + 1) { inf }
        prev[0] = 0f

        for (i in 1..N) {
            curr.fill(inf)
            val jStart = max(1, i - band)
            val jEnd = min(N, i + band)
            val ax = a[(i - 1) * 3]
            val ay = a[(i - 1) * 3 + 1]
            val az = a[(i - 1) * 3 + 2]

            for (j in jStart..jEnd) {
                val dx = ax - b[(j - 1) * 3]
                val dy = ay - b[(j - 1) * 3 + 1]
                val dz = az - b[(j - 1) * 3 + 2]
                val cost = sqrt(dx * dx + dy * dy + dz * dz)
                val bestPrev = min(prev[j], min(curr[j - 1], prev[j - 1]))
                curr[j] = cost + bestPrev
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[N] / N.toFloat()
    }

    /**
     * Builds a prototype [CustomGestureTemplate] from 3–5 recorded user samples, computing an
     * averaged normalized centroid and adaptive acceptance threshold from intra-sample spread.
     */
    fun buildTemplate(
        id: String,
        name: String,
        action: CustomGestureAction,
        samples: List<List<FloatArray>>
    ): CustomGestureTemplate? {
        if (samples.isEmpty()) return null
        val normalized = samples.map { normalizeAndResample(it) }
        val centroid = FloatArray(N * 3)
        for (sample in normalized) {
            for (k in centroid.indices) {
                centroid[k] += sample[k]
            }
        }
        val invCount = 1f / normalized.size
        for (k in centroid.indices) centroid[k] *= invCount
        val unitCentroid = normalizeRms(centroid)

        val avgIntraDist = if (normalized.size >= 2) {
            normalized.map { dtwDistance(unitCentroid, it) }.average().toFloat()
        } else {
            0.55f
        }
        val adaptiveThreshold = (avgIntraDist * 1.55f + 0.28f).coerceIn(0.62f, 1.15f)
        return CustomGestureTemplate(
            id = id,
            name = name.trim().ifBlank { action.displayName },
            action = action,
            points = unitCentroid,
            threshold = adaptiveThreshold
        )
    }

    /**
     * Finds the closest matching template whose DTW distance falls beneath its threshold.
     */
    fun match(
        candidateFrames: List<FloatArray>,
        templates: List<CustomGestureTemplate>
    ): CustomGestureTemplate? {
        if (templates.isEmpty() || candidateFrames.size < MIN_CAPTURE_FRAMES) return null
        val normalized = normalizeAndResample(candidateFrames)
        var bestTemplate: CustomGestureTemplate? = null
        var bestDist = Float.POSITIVE_INFINITY
        for (tpl in templates) {
            val dist = dtwDistance(normalized, tpl.points)
            if (dist <= tpl.threshold && dist < bestDist) {
                bestDist = dist
                bestTemplate = tpl
            }
        }
        return bestTemplate
    }

    fun serialize(templates: List<CustomGestureTemplate>): String =
        templates.joinToString(separator = "\n") { tpl ->
            val safeName = tpl.name.replace("|", " ").replace("\n", " ")
            val pts = tpl.points.joinToString(",") { "%.3f".format(it) }
            "${tpl.id}|$safeName|${tpl.action.name}|${"%.3f".format(tpl.threshold)}|$pts"
        }

    fun deserialize(raw: String): List<CustomGestureTemplate> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val action = runCatching { CustomGestureAction.valueOf(parts[2]) }.getOrNull()
                ?: return@mapNotNull null
            val threshold = parts[3].toFloatOrNull() ?: CustomGestureTemplate.DEFAULT_MATCH_THRESHOLD
            val floats = parts[4].split(',').mapNotNull { it.toFloatOrNull() }
            if (floats.size != N * 3) return@mapNotNull null
            CustomGestureTemplate(
                id = parts[0],
                name = parts[1],
                action = action,
                points = floats.toFloatArray(),
                threshold = threshold
            )
        }.toList()
    }

    private fun normalizeRms(arr: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in arr) sumSq += v * v
        val rms = sqrt(sumSq / (arr.size.coerceAtLeast(1)))
        if (rms > 1e-4f) {
            val inv = 1f / rms
            for (i in arr.indices) arr[i] *= inv
        }
        return arr
    }

    const val MIN_CAPTURE_FRAMES = 10
}

/**
 * Stateful streaming custom-gesture segmenter supporting both live matching and guided
 * 3–5 sample recording on the watch IMU stream.
 */
class CustomGestureRecognizer {
    private var templates: List<CustomGestureTemplate> = emptyList()
    private val activeFrames = mutableListOf<FloatArray>()
    private var isCapturing = false
    private var captureStartMs = 0L
    private var lastActiveMs = 0L
    private var lastMatchMs = -10_000L

    private var recordingCallback: ((List<FloatArray>) -> Unit)? = null

    fun setTemplates(list: List<CustomGestureTemplate>) {
        templates = list
    }

    fun startSingleSampleCapture(onCaptured: (List<FloatArray>) -> Unit) {
        activeFrames.clear()
        isCapturing = false
        recordingCallback = onCaptured
    }

    fun cancelSampleCapture() {
        recordingCallback = null
        activeFrames.clear()
        isCapturing = false
    }

    fun reset() {
        activeFrames.clear()
        isCapturing = false
        captureStartMs = 0L
        lastActiveMs = 0L
    }

    /**
     * Feeds an [ImuSample] into the segmenter; returns a matched [CustomGestureTemplate]
     * when a live custom gesture finishes and matches (returns null while recording samples).
     */
    fun process(sample: ImuSample): CustomGestureTemplate? {
        val rec = recordingCallback
        if (rec == null && templates.isEmpty()) return null

        val mag = sqrt(
            sample.gyroX * sample.gyroX +
                sample.gyroY * sample.gyroY +
                sample.gyroZ * sample.gyroZ
        )
        val now = sample.timestampMs

        if (!isCapturing) {
            if (mag >= START_GYRO_RAD_S && (rec != null || (now - lastMatchMs) >= MATCH_COOLDOWN_MS)) {
                isCapturing = true
                captureStartMs = now
                lastActiveMs = now
                activeFrames.clear()
                activeFrames.add(floatArrayOf(sample.gyroX, sample.gyroY, sample.gyroZ))
            }
            return null
        }

        activeFrames.add(floatArrayOf(sample.gyroX, sample.gyroY, sample.gyroZ))
        if (mag >= CONTINUE_GYRO_RAD_S) {
            lastActiveMs = now
        }

        val elapsedMs = now - captureStartMs
        val quietMs = now - lastActiveMs
        val finished = quietMs >= QUIET_END_MS ||
            elapsedMs >= MAX_GESTURE_DURATION_MS ||
            activeFrames.size >= MAX_CAPTURE_FRAMES

        if (!finished) return null

        isCapturing = false
        val captured = activeFrames.toList()
        activeFrames.clear()

        if (captured.size < CustomGestureDtwEngine.MIN_CAPTURE_FRAMES) return null

        // If recording mode is active, deliver raw frames to the recorder UI
        if (rec != null) {
            recordingCallback = null
            rec(captured)
            return null
        }

        val matched = CustomGestureDtwEngine.match(captured, templates)
        if (matched != null) {
            lastMatchMs = now
        }
        return matched
    }

    companion object {
        private const val START_GYRO_RAD_S = 1.65f
        private const val CONTINUE_GYRO_RAD_S = 0.65f
        private const val QUIET_END_MS = 220L
        private const val MAX_GESTURE_DURATION_MS = 950L
        private const val MAX_CAPTURE_FRAMES = 100
        private const val MATCH_COOLDOWN_MS = 900L
    }
}
