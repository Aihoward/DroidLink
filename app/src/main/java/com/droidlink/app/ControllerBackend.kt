package com.droidlink.app

import android.util.Log
import android.view.KeyEvent
import java.io.File

data class ControllerEventContext(val sessionId: String, val controllerId: String, val playerSlot: Int, val sequence: Long, val sentAtMs: Long)

enum class ControllerBackendStatus(val label: String) {
    VIRTUAL_GAMEPAD_ACTIVE("Virtual Gamepad Active"), TRANSPORT_ONLY("Transport Only"),
    PERMISSION_REQUIRED("Permission Required"), UNSUPPORTED("Unsupported")
}

interface ControllerBackend {
    val status: ControllerBackendStatus
    val capabilityDescription: String
    fun keyDown(context: ControllerEventContext, keyCode: Int): Boolean
    fun keyUp(context: ControllerEventContext, keyCode: Int): Boolean
    fun updateAxes(context: ControllerEventContext, leftX: Float, leftY: Float, rightX: Float, rightY: Float, leftTrigger: Float, rightTrigger: Float, dpadX: Float, dpadY: Float): Boolean
    fun resetNeutral(reason: String) = Unit
    fun close() = Unit
}

class TransportOnlyBackend(
    override val status: ControllerBackendStatus = ControllerBackendStatus.TRANSPORT_ONLY,
    override val capabilityDescription: String = "Controller packets are received; no privileged virtual input device is available"
) : ControllerBackend {
    private var axisLogCounter = 0
    init { Log.w("DroidLink", "CONTROL_BACKEND_SELECTED: ${status.label} - $capabilityDescription") }
    override fun keyDown(context: ControllerEventContext, keyCode: Int) = false.also { Log.d("DroidLink", "CONTROL TRANSPORT VERIFIED: P${context.playerSlot} KEY DOWN $keyCode") }
    override fun keyUp(context: ControllerEventContext, keyCode: Int) = false.also { Log.d("DroidLink", "CONTROL TRANSPORT VERIFIED: P${context.playerSlot} KEY UP $keyCode") }
    override fun updateAxes(context: ControllerEventContext, leftX: Float, leftY: Float, rightX: Float, rightY: Float, leftTrigger: Float, rightTrigger: Float, dpadX: Float, dpadY: Float): Boolean {
        if (++axisLogCounter % 120 == 1) Log.d("DroidLink", "CONTROL TRANSPORT VERIFIED: P${context.playerSlot} AXES LX=$leftX LY=$leftY RX=$rightX RY=$rightY LT=$leftTrigger RT=$rightTrigger")
        return false
    }
}

object ControllerBackendSelector {
    fun select(): ControllerBackend {
        Log.d("DroidLink", "UINPUT_AVAILABLE: ${File("/dev/uinput").exists()}")
        return try {
            UinputVirtualGamepadBackend()
        } catch (error: Throwable) {
            val rootPresent = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() }
            val status = if (File("/dev/uinput").exists() || rootPresent) ControllerBackendStatus.PERMISSION_REQUIRED else ControllerBackendStatus.UNSUPPORTED
            Log.e("DroidLink", "UINPUT_PERMISSION: denied (${error.message})")
            TransportOnlyBackend(status, if (rootPresent) "Root appears present, but DroidLink has not been granted direct /dev/uinput access" else "Host cannot open/create /dev/uinput; privileged setup is required")
        }
    }
}

class UinputVirtualGamepadBackend : ControllerBackend {
    companion object {
        private const val TAG = "DroidLink"; private const val DEVICE_NAME = "DroidLink Player 2"
        init { System.loadLibrary("droidlink_native") }
        @JvmStatic private external fun nativeCreate(name: String): Long
        @JvmStatic private external fun nativeKey(handle: Long, code: Int, pressed: Boolean): Boolean
        @JvmStatic private external fun nativeAxes(handle: Long, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, dx: Float, dy: Float): Boolean
        @JvmStatic private external fun nativeReset(handle: Long): Boolean
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
    override val status = ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE
    override val capabilityDescription = DEVICE_NAME
    private val handle: Long = nativeCreate(DEVICE_NAME).also { check(it >= 0) { "native uinput create failed errno=${-it}" } }

    init {
        Log.d(TAG, "UINPUT_PERMISSION: granted")
        Log.d(TAG, "VIRTUAL_GAMEPAD_CREATED")
        Log.d(TAG, "VIRTUAL_GAMEPAD_DEVICE_NAME: $DEVICE_NAME")
        Log.d(TAG, "PLAYER_SLOT_ASSIGNED: 2")
        Log.d(TAG, "CONTROL_BACKEND_SELECTED: ${status.label}")
        Log.d(TAG, "GAMEPAD_DEVICE_REGISTERED: true")
        Log.d(TAG, "GAMEPAD_DESCRIPTOR_READY: true")
        Log.d(TAG, "GAMEPAD_HOTPLUG_STATE: created; start Winlator container after this message")
        Log.d(TAG, "GAMEPAD_CAPABILITIES: Xbox 360-class evdev gamepad")
        Log.d(TAG, "GAMEPAD_BUTTON_CAPABILITIES: A B X Y L1 R1 L2 R2 L3 R3 Start Select Guide D-pad")
        Log.d(TAG, "GAMEPAD_AXIS_CAPABILITIES: X Y RX RY Z(0..255) RZ(0..255)")
        Log.d(TAG, "GAMEPAD_HAT_CAPABILITIES: HAT0X(-1..1) HAT0Y(-1..1)")
        Log.d(TAG, "GAMEPAD_VID_PID: 045e:028e")
        Log.d(TAG, "GAMEPAD_BUS_TYPE: USB")
        Log.d(TAG, "WINLATOR_GAMEPAD_READY: restart/start the Winlator container after virtual device creation")
        Log.d(TAG, "CONTROL_MAPPING_TABLE_VERSION: ${ControllerMapping.TABLE_VERSION}")
        Log.d(TAG, "CONTROL_MAPPING_TABLE: ${ControllerMapping.tableDescription()}")
    }

    override fun keyDown(context: ControllerEventContext, keyCode: Int) = injectKey(keyCode, 1)
    override fun keyUp(context: ControllerEventContext, keyCode: Int) = injectKey(keyCode, 0)
    private fun injectKey(androidCode: Int, value: Int): Boolean = try {
        val logical = ControllerMapping.logicalForAndroidKey(androidCode) ?: return false
        val linux = logical.linuxCode ?: return false
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        check(nativeKey(handle, linux, value != 0))
        val writeMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        Log.d(TAG, "UINPUT_EVENT_WRITTEN: logical=${logical.displayName} evKey=$linux value=$value")
        Log.d(TAG, "UINPUT_WRITE_MS: ${writeMicros / 1000.0} type=digital")
        true
    } catch (error: Throwable) { Log.e(TAG, "CONTROL_INJECTION_FAILED: ${error.message}", error); false }

    private var axisWriteCounter = 0
    override fun updateAxes(context: ControllerEventContext, leftX: Float, leftY: Float, rightX: Float, rightY: Float, leftTrigger: Float, rightTrigger: Float, dpadX: Float, dpadY: Float): Boolean = try {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        check(nativeAxes(handle, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpadX, dpadY))
        if (++axisWriteCounter % 120 == 1) {
            val writeMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
            Log.d(TAG, "UINPUT_EVENT_WRITTEN: logical=AXES lx=$leftX ly=$leftY rx=$rightX ry=$rightY lt=$leftTrigger rt=$rightTrigger hat=$dpadX,$dpadY")
            Log.d(TAG, "UINPUT_WRITE_MS: ${writeMicros / 1000.0} type=analog")
        }
        true
    } catch (error: Throwable) { Log.e(TAG, "CONTROL_INJECTION_FAILED: ${error.message}", error); false }
    override fun resetNeutral(reason: String) { if (nativeReset(handle)) Log.d(TAG, "CONTROL_NEUTRAL_RESET: $reason") else Log.e(TAG, "CONTROL_INJECTION_FAILED: neutral reset failed reason=$reason") }
    override fun close() { resetNeutral("backend close"); nativeDestroy(handle); Log.d(TAG, "Virtual gamepad released") }
}
