package com.droidlink.app

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

data class ControllerAxisLayout(
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
    val hatX: Int,
    val hatY: Int
) {
    fun orderedMappings() = listOf(
        AxisMapping(leftX, "Left X", false),
        AxisMapping(leftY, "Left Y", false),
        AxisMapping(rightX, "Right X", false),
        AxisMapping(rightY, "Right Y", false),
        AxisMapping(leftTrigger, "L2", true),
        AxisMapping(rightTrigger, "R2", true),
        AxisMapping(hatX, "D-pad X", false),
        AxisMapping(hatY, "D-pad Y", false)
    )

    fun description() = orderedMappings().joinToString { mapping ->
        val axis = if (mapping.androidAxis == ControllerAxisCompatibility.UNAVAILABLE_AXIS) {
            "UNAVAILABLE"
        } else {
            MotionEvent.axisToString(mapping.androidAxis)
        }
        "${mapping.logicalName}=$axis"
    }
}

object ControllerAxisCompatibility {
    const val UNAVAILABLE_AXIS = -1

    val candidateAxes = setOf(
        MotionEvent.AXIS_X,
        MotionEvent.AXIS_Y,
        MotionEvent.AXIS_Z,
        MotionEvent.AXIS_RZ,
        MotionEvent.AXIS_RX,
        MotionEvent.AXIS_RY,
        MotionEvent.AXIS_LTRIGGER,
        MotionEvent.AXIS_RTRIGGER,
        MotionEvent.AXIS_BRAKE,
        MotionEvent.AXIS_GAS,
        MotionEvent.AXIS_THROTTLE,
        MotionEvent.AXIS_HAT_X,
        MotionEvent.AXIS_HAT_Y
    )

    fun resolve(available: Set<Int>): ControllerAxisLayout {
        val hasZPair = available.hasPair(MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        val hasRxPair = available.hasPair(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)

        val dedicatedTriggers = when {
            available.hasPair(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER) ->
                MotionEvent.AXIS_LTRIGGER to MotionEvent.AXIS_RTRIGGER
            available.hasPair(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS) ->
                MotionEvent.AXIS_BRAKE to MotionEvent.AXIS_GAS
            available.hasPair(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_THROTTLE) ->
                MotionEvent.AXIS_BRAKE to MotionEvent.AXIS_THROTTLE
            else -> null
        }

        // A common Android Xbox-style layout uses RX/RY for the right stick and Z/RZ for triggers.
        val inferredZTriggers = dedicatedTriggers == null && hasRxPair && hasZPair
        val rightStick = when {
            inferredZTriggers -> MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
            hasZPair -> MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
            hasRxPair -> MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
            else -> UNAVAILABLE_AXIS to UNAVAILABLE_AXIS
        }
        val triggers = dedicatedTriggers ?: if (inferredZTriggers) {
            MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        } else {
            UNAVAILABLE_AXIS to UNAVAILABLE_AXIS
        }
        val leftStick = if (available.hasPair(MotionEvent.AXIS_X, MotionEvent.AXIS_Y)) {
            MotionEvent.AXIS_X to MotionEvent.AXIS_Y
        } else {
            UNAVAILABLE_AXIS to UNAVAILABLE_AXIS
        }
        val hat = if (available.hasPair(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)) {
            MotionEvent.AXIS_HAT_X to MotionEvent.AXIS_HAT_Y
        } else {
            UNAVAILABLE_AXIS to UNAVAILABLE_AXIS
        }

        return ControllerAxisLayout(
            leftX = leftStick.first,
            leftY = leftStick.second,
            rightX = rightStick.first,
            rightY = rightStick.second,
            leftTrigger = triggers.first,
            rightTrigger = triggers.second,
            hatX = hat.first,
            hatY = hat.second
        )
    }

    private fun Set<Int>.hasPair(first: Int, second: Int) = first in this && second in this
}

data class ControllerAxisRange(val min: Float, val max: Float, val flat: Float) {
    val span: Float get() = max - min
    val isCentered: Boolean get() = min < 0f && max > 0f
}

object ControllerAxisNormalizer {
    private const val DEFAULT_STICK_DEADZONE = 0.12f
    private const val DEFAULT_TRIGGER_DEADZONE = 0.02f
    private const val AXIS_EPSILON = 0.0001f

    fun normalizeStick(raw: Float, range: ControllerAxisRange?): Float {
        if (!raw.isFinite()) return 0f
        if (range == null || range.span <= AXIS_EPSILON) {
            return applyCenteredDeadzone(raw.coerceIn(-1f, 1f), DEFAULT_STICK_DEADZONE)
        }
        val center = if (range.isCentered) 0f else (range.min + range.max) / 2f
        val extent = max(center - range.min, range.max - center)
        if (extent <= AXIS_EPSILON) return 0f
        val normalized = ((raw - center) / extent).coerceIn(-1f, 1f)
        val deadzone = max(abs(range.flat) / extent, DEFAULT_STICK_DEADZONE).coerceAtMost(0.95f)
        return applyCenteredDeadzone(normalized, deadzone)
    }

    fun normalizeTrigger(raw: Float, range: ControllerAxisRange?, centeredAxisActivated: Boolean): Float {
        if (!raw.isFinite()) return 0f
        if (range == null || range.span <= AXIS_EPSILON) return raw.coerceIn(0f, 1f)
        if (range.isCentered && !centeredAxisActivated && abs(raw) <= AXIS_EPSILON) return 0f
        val normalized = ((raw - range.min) / range.span).coerceIn(0f, 1f)
        val deadzone = max(abs(range.flat) / range.span, DEFAULT_TRIGGER_DEADZONE).coerceAtMost(0.95f)
        return if (normalized <= deadzone) 0f else normalized
    }

    fun centeredTriggerActivated(raw: Float, range: ControllerAxisRange?) =
        range?.isCentered == true && abs(raw) > AXIS_EPSILON

    fun dpadDirection(raw: Float): Int = when {
        raw <= -0.5f -> -1
        raw >= 0.5f -> 1
        else -> 0
    }

    private fun applyCenteredDeadzone(value: Float, deadzone: Float): Float {
        if (abs(value) <= deadzone) return 0f
        return value
    }
}

data class ControllerDeviceAxisState(
    val layout: ControllerAxisLayout,
    var leftCenteredTriggerActivated: Boolean = false,
    var rightCenteredTriggerActivated: Boolean = false
)
