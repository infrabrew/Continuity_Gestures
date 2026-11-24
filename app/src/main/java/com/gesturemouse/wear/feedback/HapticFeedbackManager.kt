package com.gesturemouse.wear.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.gesturemouse.wear.model.GestureEvent

/**
 * Gen 3 Linear Resonant Actuator (LRA) haptic engine tuned for Pixel Watch & Galaxy Watch.
 *
 * Uses Android [VibrationEffect.Composition] primitives (`PRIMITIVE_CLICK`, `PRIMITIVE_TICK`,
 * `PRIMITIVE_LOW_TICK`, `PRIMITIVE_THUD`, `PRIMITIVE_QUICK_RISE`) when supported by the watch's
 * haptic driver—falling back gracefully to predefined effects and custom waveforms—so users can
 * distinguish left-click, right-click, mode switches, crown detents, side-button clutch, and
 * palm-cover toggles completely eyes-free.
 */
class HapticFeedbackManager(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val supportsClickPrimitives: Boolean = vibrator?.takeIf { it.hasVibrator() }?.let { vib ->
        runCatching {
            vib.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK
            )
        }.getOrDefault(false)
    } ?: false

    private val supportsRichPrimitives: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        vibrator?.takeIf { it.hasVibrator() }?.let { vib ->
            runCatching {
                vib.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
                )
            }.getOrDefault(false)
        } ?: false
    } else {
        false
    }

    fun playForGesture(event: GestureEvent, enabled: Boolean) {
        if (!enabled) return
        when (event) {
            is GestureEvent.SinglePinch -> playLeftClick(true)
            is GestureEvent.DoublePinch -> playRightClick(true)
            is GestureEvent.PinchHoldStart -> playClutchState(engaged = true, enabled = true)
            is GestureEvent.PinchHoldEnd -> playClutchState(engaged = false, enabled = true)
            is GestureEvent.PalmUpPinch -> vibrate {
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 22L, 35L, 22L),
                    intArrayOf(0, 180, 0, 140),
                    -1
                )
            }
            is GestureEvent.FlickUp,
            is GestureEvent.FlickDown,
            is GestureEvent.FlickLeft,
            is GestureEvent.FlickRight -> vibrate {
                VibrationEffect.createOneShot(16L, 145)
            }
            is GestureEvent.WristTwist -> playCrownTick(intensity = 0.45f, enabled = true)
            is GestureEvent.ModeSwitch -> playModeSwitch(true)
        }
    }

    /** Crisp single mechanical click for eyes-free Left Click confirmation. */
    fun playLeftClick(enabled: Boolean) {
        if (!enabled) return
        vibrate {
            if (supportsClickPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
        }
    }

    /** Distinct double-snap signature for eyes-free Right Click confirmation. */
    fun playRightClick(enabled: Boolean) {
        if (!enabled) return
        vibrate {
            if (supportsClickPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 38)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
        }
    }

    /** Ultra-crisp tactile detent tick synchronized to rotating crown scroll/volume/scrub steps. */
    fun playCrownTick(intensity: Float = 0.55f, enabled: Boolean) {
        if (!enabled) return
        val scale = intensity.coerceIn(0.25f, 1.0f)
        vibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsRichPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
                    .compose()
            } else if (supportsClickPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            }
        }
    }

    /** Tactile feedback for engaging (soft thud) vs releasing (crisp tick) Side-Button Clutch / Drag. */
    fun playClutchState(engaged: Boolean, enabled: Boolean) {
        if (!enabled) return
        vibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsRichPrimitives) {
                if (engaged) {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.80f)
                        .compose()
                } else {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.70f)
                        .compose()
                }
            } else {
                VibrationEffect.createPredefined(
                    if (engaged) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK
                )
            }
        }
    }

    /** Soft double pulse confirming eyes-free Palm-Cover pause or drag-lock toggle. */
    fun playPalmCoverToggle(enabled: Boolean) {
        if (!enabled) return
        vibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsRichPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.85f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.60f, 55)
                    .compose()
            } else {
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 20L, 45L, 20L),
                    intArrayOf(0, 170, 0, 125),
                    -1
                )
            }
        }
    }

    /** Rising tactile swell distinguishing Input Mode switches eyes-free. */
    fun playModeSwitch(enabled: Boolean) {
        if (!enabled) return
        vibrate {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsRichPrimitives) {
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.90f, 32)
                    .compose()
            } else {
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 18L, 30L, 28L),
                    intArrayOf(0, 150, 0, 210),
                    -1
                )
            }
        }
    }

    fun playConnected(enabled: Boolean) {
        if (!enabled) return
        vibrate {
            VibrationEffect.createWaveform(
                longArrayOf(0L, 25L, 45L, 40L),
                intArrayOf(0, 140, 0, 220),
                -1
            )
        }
    }

    private inline fun vibrate(effectBuilder: () -> VibrationEffect) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        vib.vibrate(effectBuilder())
    }
}
