package com.droidlink.app

import android.view.MotionEvent

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
        "${mapping.logicalName}=${MotionEvent.axisToString(mapping.androidAxis)}"
    }
}

object ControllerAxisCompatibility {
    fun resolve(available: Set<Int>): ControllerAxisLayout {
        return ControllerAxisLayout(
            leftX = MotionEvent.AXIS_X,
            leftY = MotionEvent.AXIS_Y,
            rightX = firstAvailable(available, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX),
            rightY = firstAvailable(available, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY),
            leftTrigger = firstAvailable(available, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
            rightTrigger = firstAvailable(available, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
            hatX = MotionEvent.AXIS_HAT_X,
            hatY = MotionEvent.AXIS_HAT_Y
        )
    }

    private fun firstAvailable(available: Set<Int>, preferred: Int, fallback: Int) =
        if (preferred in available || fallback !in available) preferred else fallback
}
