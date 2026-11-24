package com.gesturemouse.wear.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.gesturemouse.wear.model.ImuSample
import com.gesturemouse.wear.model.WatchHardwareProfile
import kotlin.math.sqrt

/**
 * Hardware + 9-DoF Madgwick sensor fusion engine wrapping Android Wear OS [SensorManager].
 *
 * Combines:
 * - **9-DoF Drift-Free Fusion** ([MadgwickAhrsFilter] over [Sensor.TYPE_ACCELEROMETER] +
 *   [Sensor.TYPE_GYROSCOPE] + [Sensor.TYPE_MAGNETIC_FIELD]) when enabled, with automatic
 *   magnetic-interference fallback to 6-DoF.
 * - **6-DoF Hardware Fusion** ([Sensor.TYPE_GAME_ROTATION_VECTOR] / [Sensor.TYPE_ROTATION_VECTOR])
 *   when 9-DoF is disabled or magnetometer hardware is unavailable.
 * - **[Sensor.TYPE_LINEAR_ACCELERATION]** (fallback: gravity-subtracted raw accelerometer)
 * - **[Sensor.TYPE_LIGHT]** ambient light stream for Palm-Cover Pause / Drag-Lock detection.
 *
 * All callbacks execute on a dedicated high-priority background [HandlerThread] so 50–100 Hz
 * sensor fusion never blocks the Compose UI thread during scrolling.
 */
class SensorFusionEngine(
    context: Context,
    private val onSample: (ImuSample) -> Unit,
    private val onLightSample: ((lux: Float, timestampMs: Long) -> Unit)? = null,
    private val onOffBodyChanged: ((isOnBody: Boolean) -> Unit)? = null
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometerSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val lightSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val offBodySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)

    val hasMagnetometer: Boolean
        get() = magnetometerSensor != null

    val hasLightSensor: Boolean
        get() = lightSensor != null

    private var isRunning = false
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    private var useNineDofFusion: Boolean = true

    @Volatile
    private var monitorLightSensor: Boolean = true

    private val madgwickFilter = MadgwickAhrsFilter()
    private var lastGyroTimestampMs: Long = 0L

    // Latest fused states updated sequentially on the dedicated IMU HandlerThread
    private var rawAccX = 0f
    private var rawAccY = 0f
    private var rawAccZ = GRAVITY_EARTH

    private var linAccX = 0f
    private var linAccY = 0f
    private var linAccZ = 0f

    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f
    private var hasMagSample = false

    // Gravity vector in watch coordinates (default: screen up)
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = GRAVITY_EARTH

    // Orientation quaternion (x, y, z, w)
    private var quatX = 0f
    private var quatY = 0f
    private var quatZ = 0f
    private var quatW = 1f

    private val rotationMatrix = FloatArray(9)
    private val quaternionBuffer = FloatArray(4)

    /**
     * Auto-detects Galaxy Watch vs Pixel Watch hardware to provide sensible default sensor tuning.
     */
    fun detectHardwareProfile(): WatchHardwareProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        return when {
            manufacturer.contains("samsung") || model.contains("sm-r") || model.contains("sm-l") ->
                WatchHardwareProfile.GALAXY_WATCH
            manufacturer.contains("google") || model.contains("pixel watch") ->
                WatchHardwareProfile.PIXEL_WATCH
            else -> WatchHardwareProfile.AUTO
        }
    }

    /**
     * Starts streaming fused IMU & optional ambient light frames on a dedicated background
     * [HandlerThread].
     */
    @Synchronized
    fun start(
        highFrequency: Boolean = true,
        useNineDof: Boolean = true,
        enableLightSensor: Boolean = true,
        lightOnly: Boolean = false
    ) {
        if (isRunning) return
        isRunning = true
        useNineDofFusion = !lightOnly && useNineDof && magnetometerSensor != null && rawAccSensor != null
        monitorLightSensor = enableLightSensor
        madgwickFilter.reset()
        lastGyroTimestampMs = 0L
        hasMagSample = false

        val thread = HandlerThread("GestureMouse-IMU", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
            start()
        }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler

        if (lightOnly) {
            if (monitorLightSensor && onLightSample != null) {
                lightSensor?.let {
                    sensorManager.registerListener(this, it, SAMPLING_PERIOD_LIGHT_US, 0, handler)
                }
            }
            return
        }

        val samplingPeriodUs = if (highFrequency) {
            SAMPLING_PERIOD_HIGH_US
        } else {
            SAMPLING_PERIOD_ECO_US
        }
        val maxReportLatencyUs = 0

        if (useNineDofFusion) {
            // 9-DoF Madgwick path: raw accel + gyro + magnetometer
            rawAccSensor?.let {
                sensorManager.registerListener(this, it, samplingPeriodUs, maxReportLatencyUs, handler)
            }
            magnetometerSensor?.let {
                sensorManager.registerListener(this, it, SAMPLING_PERIOD_MAG_US, maxReportLatencyUs, handler)
            }
        } else {
            // 6-DoF hardware rotation vector path
            rotationSensor?.let {
                sensorManager.registerListener(this, it, samplingPeriodUs, maxReportLatencyUs, handler)
            }
            if (rotationSensor == null && linearAccSensor != null) {
                rawAccSensor?.let {
                    sensorManager.registerListener(this, it, SAMPLING_PERIOD_ECO_US, maxReportLatencyUs, handler)
                }
            }
        }

        if (linearAccSensor != null) {
            sensorManager.registerListener(this, linearAccSensor, samplingPeriodUs, maxReportLatencyUs, handler)
        } else if (!useNineDofFusion) {
            rawAccSensor?.let {
                sensorManager.registerListener(this, it, samplingPeriodUs, maxReportLatencyUs, handler)
            }
        }

        gyroSensor?.let {
            sensorManager.registerListener(this, it, samplingPeriodUs, maxReportLatencyUs, handler)
        }

        if (monitorLightSensor && onLightSample != null) {
            lightSensor?.let {
                sensorManager.registerListener(this, it, SAMPLING_PERIOD_LIGHT_US, maxReportLatencyUs, handler)
            }
        }

        if (onOffBodyChanged != null) {
            offBodySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                val isOnBody = event.values.firstOrNull()?.let { it >= 0.5f } ?: true
                onOffBodyChanged?.invoke(isOnBody)
            }

            Sensor.TYPE_LIGHT -> {
                if (monitorLightSensor) {
                    onLightSample?.invoke(event.values[0], SystemClock.elapsedRealtime())
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                magX = event.values[0]
                magY = event.values[1]
                magZ = event.values[2]
                hasMagSample = true
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (!useNineDofFusion) {
                    SensorManager.getQuaternionFromVector(quaternionBuffer, event.values)
                    quatW = quaternionBuffer[0]
                    quatX = quaternionBuffer[1]
                    quatY = quaternionBuffer[2]
                    quatZ = quaternionBuffer[3]

                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    gravityX = rotationMatrix[6] * GRAVITY_EARTH
                    gravityY = rotationMatrix[7] * GRAVITY_EARTH
                    gravityZ = rotationMatrix[8] * GRAVITY_EARTH
                }
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linAccX = event.values[0]
                linAccY = event.values[1]
                linAccZ = event.values[2]
            }

            Sensor.TYPE_ACCELEROMETER -> {
                rawAccX = event.values[0]
                rawAccY = event.values[1]
                rawAccZ = event.values[2]

                if (!useNineDofFusion && rotationSensor == null) {
                    val alpha = COMPLEMENTARY_GRAVITY_ALPHA
                    gravityX = alpha * gravityX + (1f - alpha) * rawAccX
                    gravityY = alpha * gravityY + (1f - alpha) * rawAccY
                    gravityZ = alpha * gravityZ + (1f - alpha) * rawAccZ
                }
                if (linearAccSensor == null) {
                    linAccX = rawAccX - gravityX
                    linAccY = rawAccY - gravityY
                    linAccZ = rawAccZ - gravityZ
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]

                val timestampMs = SystemClock.elapsedRealtime()

                if (useNineDofFusion) {
                    val dtSec = if (lastGyroTimestampMs > 0L) {
                        ((timestampMs - lastGyroTimestampMs).coerceIn(1L, 80L)) / 1000f
                    } else {
                        0.01f
                    }
                    lastGyroTimestampMs = timestampMs
                    madgwickFilter.update(
                        gx = gyroX,
                        gy = gyroY,
                        gz = gyroZ,
                        ax = rawAccX,
                        ay = rawAccY,
                        az = rawAccZ,
                        mx = if (hasMagSample) magX else 0f,
                        my = if (hasMagSample) magY else 0f,
                        mz = if (hasMagSample) magZ else 0f,
                        dtSec = dtSec
                    )
                    quatW = madgwickFilter.qw
                    quatX = madgwickFilter.qx
                    quatY = madgwickFilter.qy
                    quatZ = madgwickFilter.qz
                    gravityX = madgwickFilter.gravityX * GRAVITY_EARTH
                    gravityY = madgwickFilter.gravityY * GRAVITY_EARTH
                    gravityZ = madgwickFilter.gravityZ * GRAVITY_EARTH
                }

                val gNorm = sqrt(
                    (gravityX * gravityX) + (gravityY * gravityY) + (gravityZ * gravityZ)
                ).coerceAtLeast(0.001f)

                onSample(
                    ImuSample(
                        timestampMs = timestampMs,
                        linearAccX = linAccX,
                        linearAccY = linAccY,
                        linearAccZ = linAccZ,
                        gyroX = gyroX,
                        gyroY = gyroY,
                        gyroZ = gyroZ,
                        gravityX = gravityX / gNorm,
                        gravityY = gravityY / gNorm,
                        gravityZ = gravityZ / gNorm,
                        quatX = quatX,
                        quatY = quatY,
                        quatZ = quatZ,
                        quatW = quatW
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op: hardware calibration and Madgwick magnetic-anomaly guard self-adjust continuously.
    }

    companion object {
        private const val SAMPLING_PERIOD_HIGH_US = 10_000  // ~100 Hz
        private const val SAMPLING_PERIOD_ECO_US = 20_000   // ~50 Hz
        private const val SAMPLING_PERIOD_MAG_US = 20_000   // ~50 Hz magnetometer
        private const val SAMPLING_PERIOD_LIGHT_US = 100_000 // ~10 Hz ambient light
        private const val COMPLEMENTARY_GRAVITY_ALPHA = 0.88f
        private const val GRAVITY_EARTH = 9.80665f
    }
}
