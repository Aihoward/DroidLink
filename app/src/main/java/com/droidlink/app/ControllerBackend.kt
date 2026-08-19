package com.droidlink.app

import android.util.Log
import android.view.KeyEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
    fun updateDpad(context: ControllerEventContext, dpadX: Int, dpadY: Int): Boolean = false
    fun resetNeutral(reason: String) = Unit
    fun logHealth(reason: String) = Unit
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
    fun select(profile: ControllerProfile = ControllerProfile.PC_WINLATOR): ControllerBackend {
        Log.d("DroidLink", "UINPUT_AVAILABLE: ${File("/dev/uinput").exists()}")
        Log.d("DroidLink", "CONTROLLER_PROFILE_SELECTED: ${profile.label}")
        return try {
            when (profile) {
                ControllerProfile.GAMECUBE_DOLPHIN -> DolphinVirtualGamepadBackend()
                ControllerProfile.PC_WINLATOR, ControllerProfile.PS2 -> UinputVirtualGamepadBackend()
            }
        } catch (error: Throwable) {
            val rootPresent = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() }
            val status = if (File("/dev/uinput").exists() || rootPresent) ControllerBackendStatus.PERMISSION_REQUIRED else ControllerBackendStatus.UNSUPPORTED
            Log.e("DroidLink", "UINPUT_PERMISSION: denied (${error.message})")
            TransportOnlyBackend(status, if (rootPresent) "Root appears present, but DroidLink has not been granted direct /dev/uinput access" else "Host cannot open/create /dev/uinput; privileged setup is required")
        }
    }
}

class DolphinVirtualGamepadBackend : ControllerBackend {
    companion object {
        private const val TAG = "DroidLink"
        private const val DEVICE_NAME = GameCubeMapping.DOLPHIN_DEVICE_NAME
        private val activeInstances = AtomicInteger(0)

        init { System.loadLibrary("droidlink_native") }

        @JvmStatic private external fun nativeCreate(name: String): Long
        @JvmStatic private external fun nativeKey(handle: Long, code: Int, pressed: Boolean): Boolean
        @JvmStatic private external fun nativeAxes(handle: Long, mainX: Float, mainY: Float, cX: Float, cY: Float, analogL: Float, analogR: Float, digitalL: Boolean, digitalR: Boolean): Boolean
        @JvmStatic private external fun nativeDpad(handle: Long, dpadX: Int, dpadY: Int): Boolean
        @JvmStatic private external fun nativeReset(handle: Long): Boolean
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }

    override val status = ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE
    override val capabilityDescription = DEVICE_NAME
    private val handle = nativeCreate(DEVICE_NAME).also { check(it >= 0) { "native Dolphin uinput create failed errno=${-it}" } }
    private val stateLock = Any()
    private var state = GameCubeControllerState()
    private var stateUpdates = 0L
    private var digitalTransitions = 0L
    private var axisUpdates = 0L
    private var dpadUpdates = 0L
    private var failedUpdates = 0L
    private var neutralResets = 0L
    private var lastSuccessfulUpdateMs = 0L
    private var closed = false

    init {
        val instances = activeInstances.incrementAndGet()
        Log.d(TAG, "GAMECUBE_PROFILE_ACTIVATED: mapping=${GameCubeMapping.TABLE_VERSION}")
        Log.d(TAG, "DOLPHIN_CONTROLLER_CREATED: name=$DEVICE_NAME handle=$handle compatibleWithSavedDolphinSelection=true")
        Log.d(TAG, "DOLPHIN_CONTROLLER_REGISTERED: true")
        Log.d(TAG, "DOLPHIN_DEVICE_IDENTITY: name=$DEVICE_NAME vidPid=045e:028e bus=USB identity=${System.identityHashCode(this)}")
        Log.d(TAG, "DOLPHIN_BUTTON_MAPPING: physical A/B/X/Y->GameCube A/B/X/Y; Start->Start; R1->Z")
        Log.d(TAG, "DOLPHIN_TRIGGER_MAPPING: L2/R2 analog->Analog L/R; threshold=${GameCubeMapping.DIGITAL_TRIGGER_THRESHOLD}; L1/L2 button->Digital L; R2 button->Digital R")
        Log.d(TAG, "DOLPHIN_ANDROID_CONTROLS: Z=Button Mode; Digital L=Button L1; Digital R=Button R1; Analog L/R=Axis LTRIGGER/RTRIGGER")
        Log.d(TAG, "DOLPHIN_AXIS_MAPPING: left stick->Main Stick; right stick->C-Stick; direct Android polarity")
        if (instances > 1) Log.e(TAG, "DOLPHIN_UNEXPECTED_DEVICE_RECREATION: activeInstances=$instances")
    }

    override fun keyDown(context: ControllerEventContext, keyCode: Int) = updateKey(context, keyCode, true)
    override fun keyUp(context: ControllerEventContext, keyCode: Int) = updateKey(context, keyCode, false)

    private fun updateKey(context: ControllerEventContext, keyCode: Int, down: Boolean): Boolean = synchronized(stateLock) {
        val next = GameCubeMapping.updateKey(state, keyCode, down) ?: return@synchronized false
        state = next
        digitalTransitions++
        val logical = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_Z -> "Z"
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> "L_DIGITAL"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R_DIGITAL"
            KeyEvent.KEYCODE_MENU -> "Start"
            else -> ControllerMapping.logicalForAndroidKey(keyCode)?.displayName ?: keyCode.toString()
        }
        Log.d(TAG, "DOLPHIN_BUTTON_TRANSITION: control=$logical state=${if (down) "DOWN" else "UP"} transitions=$digitalTransitions")
        val linuxCode = GameCubeMapping.linuxCodeForAndroidKey(keyCode) ?: return@synchronized false
        val outputPressed = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> state.digitalL
            KeyEvent.KEYCODE_BUTTON_R2 -> state.digitalR
            else -> down
        }
        val generated = writeDigitalLocked("digital:$logical", linuxCode, outputPressed)
        Log.d(TAG, "DOLPHIN_COMPAT_EVENT_GENERATED: player=${context.playerSlot} controller=${context.controllerId} type=BUTTON control=$logical action=${if (down) "DOWN" else "UP"} success=$generated")
        generated
    }

    override fun updateAxes(
        context: ControllerEventContext,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
        dpadX: Float,
        dpadY: Float
    ): Boolean = synchronized(stateLock) {
        state = GameCubeMapping.updateAxes(state, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger)
        axisUpdates++
        val result = try {
            check(!closed) { "Dolphin backend is closed" }
            check(nativeAxes(handle, state.mainX, state.mainY, state.cX, state.cY, state.analogL, state.analogR, state.digitalL, state.digitalR))
            recordSuccessfulUpdate()
            true
        } catch (error: Throwable) {
            recordFailedUpdate("analog", error)
            false
        }
        if (axisUpdates % 120L == 1L) {
            Log.d(TAG, "DOLPHIN_ANALOG_SUMMARY: updates=$axisUpdates main=${state.mainX},${state.mainY} c=${state.cX},${state.cY} analogL=${state.analogL} analogR=${state.analogR} digitalL=${state.digitalL} digitalR=${state.digitalR}")
            Log.d(TAG, "DOLPHIN_COMPAT_EVENT_GENERATED: player=${context.playerSlot} controller=${context.controllerId} type=AXIS success=$result")
        }
        result
    }

    override fun updateDpad(context: ControllerEventContext, dpadX: Int, dpadY: Int): Boolean = synchronized(stateLock) {
        state = GameCubeMapping.updateDpad(state, dpadX, dpadY)
        dpadUpdates++
        Log.d(TAG, "DOLPHIN_DPAD_TRANSITION: state=${DpadState(state.dpadX, state.dpadY).label} updates=$dpadUpdates")
        val generated = try {
            check(!closed) { "Dolphin backend is closed" }
            check(nativeDpad(handle, state.dpadX, state.dpadY))
            recordSuccessfulUpdate()
            true
        } catch (error: Throwable) {
            recordFailedUpdate("dpad", error)
            false
        }
        Log.d(TAG, "DOLPHIN_COMPAT_EVENT_GENERATED: player=${context.playerSlot} controller=${context.controllerId} type=DPAD state=${DpadState(state.dpadX, state.dpadY).label} success=$generated")
        generated
    }

    private fun writeDigitalLocked(reason: String, linuxCode: Int, pressed: Boolean): Boolean = try {
        check(!closed) { "Dolphin backend is closed" }
        check(nativeKey(handle, linuxCode, pressed))
        recordSuccessfulUpdate()
        true
    } catch (error: Throwable) {
        recordFailedUpdate(reason, error)
        false
    }

    private fun recordSuccessfulUpdate() {
        stateUpdates++
        lastSuccessfulUpdateMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun recordFailedUpdate(reason: String, error: Throwable) {
        failedUpdates++
        Log.e(TAG, "DOLPHIN_INPUT_UPDATE_FAILED: reason=$reason failures=$failedUpdates ${error.message}", error)
    }

    override fun resetNeutral(reason: String) = synchronized(stateLock) {
        if (closed) return@synchronized
        state = GameCubeControllerState()
        neutralResets++
        try {
            check(nativeReset(handle))
            recordSuccessfulUpdate()
            Log.d(TAG, "DOLPHIN_NEUTRAL_RESET: reason=$reason resets=$neutralResets virtualDeviceRecreated=false")
        } catch (error: Throwable) {
            recordFailedUpdate("neutral:$reason", error)
        }
    }

    override fun logHealth(reason: String) {
        synchronized(stateLock) {
            Log.d(TAG, "DOLPHIN_CONTROLLER_HEALTH: reason=$reason registered=${!closed} handle=$handle identity=${System.identityHashCode(this)} statesSent=$stateUpdates digitalTransitions=$digitalTransitions axisUpdates=$axisUpdates dpadUpdates=$dpadUpdates failedUpdates=$failedUpdates neutralResets=$neutralResets lastSuccessfulUpdateMs=$lastSuccessfulUpdateMs activeInstances=${activeInstances.get()} state=$state")
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) {
                Log.w(TAG, "DOLPHIN_CONTROLLER_CLEANUP_SKIPPED: already destroyed handle=$handle")
                return@synchronized
            }
            resetNeutral("backend close")
            closed = true
            nativeDestroy(handle)
            val instances = activeInstances.decrementAndGet()
            Log.d(TAG, "DOLPHIN_CONTROLLER_CLEANUP: handle=$handle statesSent=$stateUpdates activeInstances=$instances")
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
        @JvmStatic private external fun nativeDpad(handle: Long, dx: Int, dy: Int): Boolean
        @JvmStatic private external fun nativeReset(handle: Long): Boolean
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
    override val status = ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE
    override val capabilityDescription = DEVICE_NAME
    private val handle: Long = nativeCreate(DEVICE_NAME).also { check(it >= 0) { "native uinput create failed errno=${-it}" } }
    private var digitalWriteCounter = 0
    private var failedDigitalWrites = 0L
    private var failedAxisWrites = 0L
    private var failedDpadWrites = 0L
    private var neutralResetCounter = 0L
    private var closed = false
    private var lastWriteMs = 0L

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
        lastWriteMs = android.os.SystemClock.elapsedRealtime()
        val writeMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        if (++digitalWriteCounter % 64 == 1) Log.d(TAG, "UINPUT_DIGITAL_SUMMARY: writes=$digitalWriteCounter logical=${logical.displayName} value=$value writeMs=${writeMicros / 1000.0}")
        true
    } catch (error: Throwable) {
        failedDigitalWrites++
        Log.e(TAG, "CONTROL_INJECTION_FAILED: type=digital failures=$failedDigitalWrites ${error.message}", error)
        false
    }

    private var axisWriteCounter = 0
    override fun updateAxes(context: ControllerEventContext, leftX: Float, leftY: Float, rightX: Float, rightY: Float, leftTrigger: Float, rightTrigger: Float, dpadX: Float, dpadY: Float): Boolean = try {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        check(nativeAxes(handle, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpadX, dpadY))
        lastWriteMs = android.os.SystemClock.elapsedRealtime()
        if (++axisWriteCounter % 120 == 1) {
            val writeMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
            Log.d(TAG, "UINPUT_EVENT_WRITTEN: logical=AXES lx=$leftX ly=$leftY rx=$rightX ry=$rightY lt=$leftTrigger rt=$rightTrigger hat=$dpadX,$dpadY")
            Log.d(TAG, "UINPUT_WRITE_MS: ${writeMicros / 1000.0} type=analog")
        }
        true
    } catch (error: Throwable) {
        failedAxisWrites++
        Log.e(TAG, "CONTROL_INJECTION_FAILED: type=analog failures=$failedAxisWrites ${error.message}", error)
        false
    }
    override fun updateDpad(context: ControllerEventContext, dpadX: Int, dpadY: Int): Boolean = try {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        check(nativeDpad(handle, dpadX, dpadY))
        val writeMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        Log.d(TAG, "UINPUT_EVENT_WRITTEN: logical=DPAD state=${DpadState(dpadX, dpadY).label} hat=$dpadX,$dpadY")
        Log.d(TAG, "UINPUT_WRITE_MS: ${writeMicros / 1000.0} type=dpad")
        true
    } catch (error: Throwable) {
        failedDpadWrites++
        Log.e(TAG, "CONTROL_INJECTION_FAILED: type=dpad failures=$failedDpadWrites ${error.message}", error)
        false
    }
    override fun resetNeutral(reason: String) {
        neutralResetCounter++
        if (nativeReset(handle)) Log.d(TAG, "CONTROL_NEUTRAL_RESET: $reason") else Log.e(TAG, "CONTROL_INJECTION_FAILED: neutral reset failed reason=$reason")
    }
    override fun logHealth(reason: String) {
        Log.d(TAG, "VIRTUAL_GAMEPAD_HEALTH: reason=$reason registered=${!closed} handle=$handle identity=${System.identityHashCode(this)} digitalWrites=$digitalWriteCounter axisWrites=$axisWriteCounter failedDigital=$failedDigitalWrites failedAxis=$failedAxisWrites failedDpad=$failedDpadWrites neutralResets=$neutralResetCounter lastWriteMs=$lastWriteMs")
    }
    override fun close() {
        if (closed) {
            Log.w(TAG, "VIRTUAL_GAMEPAD_DESTROY_SKIPPED: already destroyed handle=$handle")
            return
        }
        resetNeutral("backend close")
        closed = true
        nativeDestroy(handle)
        Log.d(TAG, "VIRTUAL_GAMEPAD_DESTROYED: handle=$handle digitalWrites=$digitalWriteCounter axisWrites=$axisWriteCounter lastWriteMs=$lastWriteMs reason=backend close")
    }
}
