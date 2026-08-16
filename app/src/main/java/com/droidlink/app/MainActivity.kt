package com.droidlink.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.droidlink.app.ui.theme.DroidLinkTheme
import org.webrtc.*

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "DroidLink" }
    private val firebase = FirebaseRoomManager()
    private val hostRtc by lazy { WebRtcManager(this) }
    private val clientRtc by lazy { WebRtcManager(this) }
    private var controllerBackend: ControllerBackend = TransportOnlyBackend()

    private var mode by mutableStateOf("menu")
    private var hostRoomCode by mutableStateOf("")
    private var hostStatus by mutableStateOf("")
    private var captureStatus by mutableStateOf("DroidLink is ready")
    private var clientStatus by mutableStateOf("Not connected")
    private var clientConnected by mutableStateOf(false)
    private var audioStatus by mutableStateOf("Audio not evaluated")
    private var betaDiagnostics by mutableStateOf(BetaDiagnostics())
    private var sessionActive by mutableStateOf(false)
    private var sessionMenuOpen by mutableStateOf(false)
    private var sessionStatsOpen by mutableStateOf(false)
    private var controllerInputTestOpen by mutableStateOf(false)
    private var controllerTestDisplayState by mutableStateOf(ControllerInputState())
    private var logicalControllerState = ControllerInputState()
    private var lastControllerTestUiMs = 0L
    private var diagnosticsPerfActive = false
    private var previousFrameNanos = 0L
    private var frameWindowStartedMs = 0L
    private var frameTimeTotalMs = 0.0
    private var frameTimeCount = 0
    private var lastProcessCpuMs = 0L
    private val diagnosticsFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!diagnosticsPerfActive) return
            if (previousFrameNanos != 0L) {
                frameTimeTotalMs += (frameTimeNanos - previousFrameNanos) / 1_000_000.0
                frameTimeCount++
            }
            previousFrameNanos = frameTimeNanos
            val now = android.os.SystemClock.elapsedRealtime()
            if (frameWindowStartedMs == 0L) { frameWindowStartedMs = now; lastProcessCpuMs = android.os.Process.getElapsedCpuTime() }
            if (now - frameWindowStartedMs >= 1_000L && frameTimeCount > 0) {
                val wallMs = now - frameWindowStartedMs
                val cpuNow = android.os.Process.getElapsedCpuTime()
                val cpuLoad = (cpuNow - lastProcessCpuMs) * 100.0 / wallMs.coerceAtLeast(1L)
                Log.d(TAG, "MAIN_THREAD_FRAME_MS: ${frameTimeTotalMs / frameTimeCount}")
                Log.d(TAG, "CPU_LOAD: processCpuPercent=${String.format(java.util.Locale.US, "%.1f", cpuLoad)}")
                Log.d(TAG, "GC_COUNT: ${Debug.getRuntimeStats()["art.gc.gc-count"] ?: "unavailable"}")
                frameWindowStartedMs = now; lastProcessCpuMs = cpuNow; frameTimeTotalMs = 0.0; frameTimeCount = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var remoteTrack by mutableStateOf<VideoTrack?>(null)
    private var renderer: SurfaceViewRenderer? = null
    private var rendererTrack: VideoTrack? = null
    private var pendingCaptureIntent: Intent? = null
    private var pendingOffer: (() -> Unit)? = null
    private var receiverRegistered = false
    private var sessionStarting = false
    private var clientControlActive = false
    private var lastAxisSendTime = 0L
    private var lastAxisHeartbeatTime = 0L
    private var lastAxes = FloatArray(8) { Float.NaN }
    private var digitalSequence = 0L
    private var analogSequence = 0L
    private var lastControllerDeviceId = -1
    private val dpadSources = mutableMapOf<Int, DpadSource>()
    private val joinerDpadKeys = mutableSetOf<LogicalControl>()
    private val joinerDpadState = DpadStateMachine()
    private val hostDpadState = DpadStateMachine()
    private var dpadDuplicateDrops = 0L
    private var activeSessionId = "none"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backendUnavailableLogged = false
    private var controllerWindowStart = android.os.SystemClock.elapsedRealtime()
    private var controllerWindowPackets = 0
    private var axisAckCounter = 0
    private var controlThreadLogCounter = 0
    private var lastControlRoundTripMs: Long? = null
    private var controllerLatencyTotalMs = 0L
    private var controllerLatencySamples = 0L
    private var controllerLatencyMaxMs = 0L
    private var latestControllerLatencyMs: Long? = null
    private val recentControllerLatencies = ArrayDeque<Long>()
    private var duplicateControlPacketsDropped = 0L
    private var outOfOrderControlPacketsDropped = 0L
    private var staleAnalogPacketsDropped = 0L
    private var player2Classification = "Unknown"
    private var clientPeerState = PeerConnection.PeerConnectionState.NEW
    private val heldRemoteButtons = mutableSetOf<Int>()
    private val lastRemoteSequences = mutableMapOf<String, Long>()
    private lateinit var sessionBackCallback: OnBackPressedCallback
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { inspectPlayer2Device(deviceId) }
        override fun onInputDeviceChanged(deviceId: Int) { inspectPlayer2Device(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) { if (deviceId == lastControllerDeviceId) { Log.w(TAG, "Controller disconnected: $deviceId"); sendNeutralReset("controller disconnected"); resetLocalDpadState("controller disconnected") } }
    }
    private val disconnectGraceRunnable = Runnable {
        if (clientPeerState == PeerConnection.PeerConnectionState.DISCONNECTED) {
            clientConnected = false; clientControlActive = false
            clientStatus = "Disconnected - reconnect the session"
            Log.e(TAG, "RECONNECT GRACE EXPIRED: PeerConnection remained DISCONNECTED for 15 seconds")
        }
    }

    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_READY) return
            val permission = pendingCaptureIntent ?: return
            try {
                Log.d(TAG, "MediaProjection service ready; attaching host video track")
                val capture = captureSettings()
                hostRtc.startScreenShare(permission, capture.first, capture.second, capture.third)
                captureStatus = "WebRTC screen share started"
                pendingOffer?.invoke()
            } catch (error: Exception) {
                Log.e(TAG, "Screen share start failed", error)
                hostStatus = "Screen share error: ${error.message}"
                sessionStarting = false
            } finally {
                pendingCaptureIntent = null
                pendingOffer = null
            }
        }
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingCaptureIntent = result.data
            captureStatus = "Starting screen-share service..."
            ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureService::class.java))
        } else {
            hostStatus = "Screen capture permission denied"
            pendingCaptureIntent = null; pendingOffer = null; sessionStarting = false
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        sessionStarting = false
        if (granted) {
            Log.d(TAG, "AUDIO_CAPTURE_PERMISSION_READY")
            startHost()
        } else {
            audioStatus = "Playback audio permission denied"
            hostStatus = "Microphone permission is required by Android for playback capture"
            Log.e(TAG, "AUDIO_UNAVAILABLE_REASON: RECORD_AUDIO permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, projectionReadyReceiver, IntentFilter(ScreenCaptureService.ACTION_READY), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, mainHandler)
        sessionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when { controllerInputTestOpen -> controllerInputTestOpen = false
                    sessionStatsOpen -> { sessionStatsOpen = false; sessionMenuOpen = true }
                    sessionMenuOpen -> sessionMenuOpen = false
                    sessionActive -> sessionMenuOpen = true }
            }
        }
        onBackPressedDispatcher.addCallback(this, sessionBackCallback)
        setContent {
            DroidLinkTheme {
                var joinCode by remember { mutableStateOf("") }
                Box(Modifier.fillMaxSize()) {
                    when (mode) {
                        "host" -> HostScreen(onStart = ::startHost, onBack = ::returnToMenu)
                        "client" -> if (clientConnected) VideoScreen() else JoinScreen(
                            code = joinCode,
                            onCode = { joinCode = it.filter(Char::isDigit).take(6) },
                            onConnect = { startJoin(joinCode) },
                            onBack = ::returnToMenu
                        )
                        else -> MenuScreen()
                    }
                    if (sessionMenuOpen) SessionMenu()
                    if (sessionStatsOpen) SessionStats()
                }
                LaunchedEffect(sessionStatsOpen) { setPerformanceDiagnosticsActive(sessionStatsOpen) }
            }
        }
    }

    @Composable private fun MenuScreen() = Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("DroidLink")
        Button(onClick = { mode = "host" }) { Text("Host") }
        Button(onClick = { mode = "client" }) { Text("Join") }
    }

    @Composable private fun HostScreen(onStart: () -> Unit, onBack: () -> Unit) = Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("DroidLink Host")
        Button(onClick = onStart, enabled = !sessionStarting) { Text("Start Host") }
        if (hostRoomCode.isNotEmpty()) Text("Connection Code: $hostRoomCode")
        if (hostStatus.isNotEmpty()) Text(hostStatus)
        Text(captureStatus)
        Text(audioStatus)
        Text("Controller Backend: ${controllerBackend.status.label}")
        Button(onClick = onBack) { Text("Back") }
    }

    @Composable private fun JoinScreen(code: String, onCode: (String) -> Unit, onConnect: () -> Unit, onBack: () -> Unit) = Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("DroidLink Client")
        OutlinedTextField(value = code, onValueChange = onCode, label = { Text("6-Digit Room Code") }, singleLine = true)
        Button(onClick = onConnect, enabled = !sessionStarting) { Text("Connect") }
        Text(clientStatus)
        Text(audioStatus)
        Button(onClick = onBack) { Text("Back") }
    }

    @Composable private fun VideoScreen() {
        DisposableEffect(Unit) { onDispose { detachRenderer() } }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        setZOrderMediaOverlay(false); setZOrderOnTop(false)
                        init(clientRtc.eglContext(), object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { Log.d(TAG, "FIRST REMOTE FRAME RENDERED"); runOnUiThread { clientStatus = "Connected - video playing" } }
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) { Log.d(TAG, "Remote video frame size: ${width}x$height rotation=$rotation") }
                        })
                        setEnableHardwareScaler(true); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT); setMirror(false)
                        renderer = this
                        remoteTrack?.let { attachTrack(this, it) }
                    }
                },
                update = { view -> remoteTrack?.let { if (rendererTrack !== it) attachTrack(view, it) } }
            )
            if (remoteTrack == null) Text(clientStatus, modifier = Modifier.align(Alignment.Center))
        }
    }

    @Composable private fun SessionMenu() = Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.background(Color(0xEE202020)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DroidLink", color = Color.White)
            Button(onClick = { sessionMenuOpen = false }) { Text("Resume") }
            Button(onClick = { sessionMenuOpen = false; sessionStatsOpen = true; controllerInputTestOpen = false }) { Text("Stats") }
            Button(onClick = { disconnectSession() }) { Text("Disconnect") }
        }
    }

    @Composable private fun SessionStats() = Box(Modifier.fillMaxSize().background(Color(0xBB000000)), contentAlignment = Alignment.Center) {
        if (controllerInputTestOpen) ControllerInputTestPanel() else DiagnosticsPanel()
    }

    @Composable private fun DiagnosticsPanel() {
        val d = betaDiagnostics
        Column(Modifier.background(Color(0xCC101010)).padding(10.dp)) {
            Text("DroidLink Beta Diagnostics", color = Color.White)
            diagnosticLine("Connection", "${d.connectionState} / ICE ${d.iceState}")
            diagnosticLine("Route", d.route)
            diagnosticLine("Candidate pair", d.candidatePair)
            diagnosticLine("RTT", d.rttMs?.let { "%.1f ms".format(it) } ?: "—")
            diagnosticLine("Resolution", d.resolution)
            diagnosticLine("FPS", d.fps?.let { "%.1f".format(it) } ?: "—")
            diagnosticLine("Video bitrate", "${d.videoBitrateBps / 1_000} kbps")
            diagnosticLine("Packet loss", d.packetLoss.toString())
            diagnosticLine("Jitter", d.jitterMs?.let { "%.1f ms".format(it) } ?: "—")
            diagnosticLine("Frames", "enc ${d.framesEncoded} / dec ${d.framesDecoded}")
            diagnosticLine("Dropped frames", d.framesDropped.toString())
            diagnosticLine("Controller packets/sec", "%.1f".format(d.controllerPacketsPerSecond))
            diagnosticLine("Player 2", d.player2Status)
            diagnosticLine("Android classification", d.player2Classification)
            diagnosticLine("Control RTT", lastControlRoundTripMs?.let { "$it ms" } ?: "—")
            diagnosticLine("Control latency", d.lastControllerLatencyMs?.let { "$it ms" } ?: "—")
            diagnosticLine("Average / recent max", "${d.averageControllerLatencyMs ?: "—"} / ${d.maxControllerLatencyMs ?: "—"} ms")
            diagnosticLine("Control p95", d.controllerP95LatencyMs?.let { "$it ms" } ?: "—")
            diagnosticLine("Control p50", d.controllerP50LatencyMs?.let { "$it ms" } ?: "—")
            diagnosticLine("Packet age", d.controllerPacketAgeMs?.let { "$it ms" } ?: "—")
            diagnosticLine("Digital / analog queue", "${d.digitalQueueDepth} / ${d.analogQueueDepth}")
            diagnosticLine("DataChannel buffered", "${d.controlBufferedBytes} bytes")
            diagnosticLine("Dropped stale analog", d.droppedStaleAnalogPackets.toString())
            diagnosticLine("Duplicate / out-of-order", "${d.duplicateControlPacketsDropped} / ${d.outOfOrderControlPacketsDropped}")
            diagnosticLine("Audio", audioStatus)
            diagnosticLine("Controller backend", controllerBackend.status.label)
            Button(onClick = { controllerInputTestOpen = true; controllerTestDisplayState = logicalControllerState }) { Text("PLAYER 2 INPUT TEST") }
        }
    }

    @Composable private fun ControllerInputTestPanel() {
        val state = controllerTestDisplayState
        Column(Modifier.background(Color(0xEE101010)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PLAYER 2 INPUT TEST", color = Color.White)
            Text("Mapping: ${ControllerMapping.TABLE_VERSION}", color = Color.White)
            diagnosticLine("A / B / X / Y", "${upDown(state, LogicalControl.A)} / ${upDown(state, LogicalControl.B)} / ${upDown(state, LogicalControl.X)} / ${upDown(state, LogicalControl.Y)}")
            diagnosticLine("L1 / R1", "${upDown(state, LogicalControl.L1)} / ${upDown(state, LogicalControl.R1)}")
            diagnosticLine("L2 / R2", "${axisText(state.leftTrigger)} / ${axisText(state.rightTrigger)}")
            diagnosticLine("L3 / R3", "${upDown(state, LogicalControl.L3)} / ${upDown(state, LogicalControl.R3)}")
            diagnosticLine("Start / Select", "${upDown(state, LogicalControl.START)} / ${upDown(state, LogicalControl.SELECT)}")
            diagnosticLine("D-pad", state.dpadText())
            DpadVisualization(state)
            diagnosticLine("Left X / Y", "${axisText(state.leftX)} / ${axisText(state.leftY)}")
            diagnosticLine("Right X / Y", "${axisText(state.rightX)} / ${axisText(state.rightY)}")
            Button(onClick = { controllerInputTestOpen = false }) { Text("Back to Stats") }
        }
    }

    private fun upDown(state: ControllerInputState, control: LogicalControl) = if (state.isDown(control)) "DOWN" else "UP"
    private fun axisText(value: Float) = String.format(java.util.Locale.US, "%.2f", value)

    @Composable private fun DpadVisualization(state: ControllerInputState) {
        val active = state.dpadText()
        dpadCell("UP", active.contains("UP"))
        Row { dpadCell("LEFT", active.contains("LEFT")); dpadCell("CENTER", active == "NEUTRAL"); dpadCell("RIGHT", active.contains("RIGHT")) }
        dpadCell("DOWN", active.contains("DOWN"))
    }

    @Composable private fun dpadCell(label: String, active: Boolean) {
        Text(label, color = if (active) Color.Green else Color.Gray, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }

    private fun setPerformanceDiagnosticsActive(active: Boolean) {
        if (diagnosticsPerfActive == active) return
        diagnosticsPerfActive = active
        if (active) {
            previousFrameNanos = 0L; frameWindowStartedMs = 0L; frameTimeTotalMs = 0.0; frameTimeCount = 0
            Choreographer.getInstance().postFrameCallback(diagnosticsFrameCallback)
        } else {
            Choreographer.getInstance().removeFrameCallback(diagnosticsFrameCallback)
        }
    }

    @Composable private fun diagnosticLine(label: String, value: String) { Text("$label: $value", color = Color.White) }

    private fun attachTrack(view: SurfaceViewRenderer, track: VideoTrack) {
        rendererTrack?.let { old -> try { old.removeSink(view) } catch (_: Exception) {} }
        track.setEnabled(true)
        track.addSink(view); rendererTrack = track
        Log.d(TAG, "REMOTE VIDEO SINK ATTACHED: trackId=${track.id()} enabled=${track.enabled()} renderer=${System.identityHashCode(view)}")
    }

    private fun startHost() {
        if (sessionStarting) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sessionStarting = true
            hostStatus = "Audio permission required..."
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        cleanupSession(deleteHostRoom = true)
        sessionStarting = true; hostStatus = "Creating room..."
        audioStatus = "Preparing playback audio capture..."
        firebase.createRoom({ code ->
            activeSessionId = code
            hostRoomCode = code; hostStatus = "Loading TURN credentials..."
            controllerBackend.close()
            controllerBackend = ControllerBackendSelector.select()
            updateControllerDiagnostics { copy(player2Status = controllerBackend.status.label) }
            schedulePlayer2Inspection()
            hostRtc.initialize()
            hostRtc.onControlMessageReceived = ::handleControlMessage
            hostRtc.onAudioStatus = { status -> runOnUiThread { audioStatus = status } }
            hostRtc.onDiagnostics = { update -> runOnUiThread { betaDiagnostics = mergeControllerDiagnostics(update) } }
            hostRtc.onDataChannelStateChanged = { _, state -> if (state == DataChannel.State.CLOSING || state == DataChannel.State.CLOSED) runOnUiThread { resetRemoteInput("DataChannel $state") } }
            hostRtc.onConnectionStateChanged = { state -> runOnUiThread {
                hostStatus = connectionText(state)
                if (state == PeerConnection.PeerConnectionState.CONNECTED) { sessionStarting = false; updateSessionActive(true) }
                if (state == PeerConnection.PeerConnectionState.DISCONNECTED || state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) resetRemoteInput("PeerConnection $state")
            } }
            hostRtc.onIceCandidateReady = { candidate ->
                firebase.saveIceCandidate(code, "host", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) { hostStatus = "ICE save error: $it" }
            }
            hostRtc.createPeerConnection(true, onSuccess = {
                runOnUiThread {
                    hostStatus = "PeerConnection ready; waiting for screen permission..."
                    firebase.listenForIceCandidates(code, "client", { c, mid, line -> hostRtc.addIceCandidate(c, mid, line) }, { hostStatus = "ICE listen error: $it" })
                    pendingOffer = { createAndPublishOffer(code) }
                    requestScreenCapture()
                }
            }, onError = { runOnUiThread { hostStatus = "PeerConnection error: $it"; sessionStarting = false } })
        }, { hostStatus = "Room error: $it"; sessionStarting = false })
    }

    private fun createAndPublishOffer(code: String) {
        hostStatus = "Creating WebRTC offer..."
        hostRtc.createOffer({ offer ->
            firebase.saveOffer(code, offer, {
                hostStatus = "Waiting for player"
                firebase.listenForAnswer(code, { answer ->
                    hostRtc.setRemoteAnswer(answer, { hostStatus = "Answer received - connecting..." }, { hostStatus = "Answer error: $it" })
                }, { hostStatus = "Answer listen error: $it" })
            }, { hostStatus = "Offer save failed: $it" })
        }, { hostStatus = "Offer error: $it"; sessionStarting = false })
    }

    private fun startJoin(code: String) {
        if (code.length != 6) { clientStatus = "Enter a 6-digit room code"; return }
        if (sessionStarting) return
        cleanupSession(deleteHostRoom = true)
        activeSessionId = code
        sessionStarting = true; clientStatus = "Looking for room..."
        firebase.joinRoom(code, {
            clientStatus = "Loading WebRTC offer..."
            firebase.getOffer(code, { offer -> prepareJoinPeer(code, offer) }, { clientStatus = "Offer load failed: $it"; sessionStarting = false })
        }, { clientStatus = "Join failed: $it"; sessionStarting = false })
    }

    private fun prepareJoinPeer(code: String, offer: String) {
        audioStatus = "Waiting for remote game audio..."
        clientRtc.initialize()
        clientRtc.onAudioStatus = { status -> runOnUiThread { audioStatus = status } }
        clientRtc.onControlMessageReceived = ::handleControlMessage
        clientRtc.onDataChannelStateChanged = { label, state -> if (state == DataChannel.State.CLOSING || state == DataChannel.State.CLOSED) Log.w(TAG, "Client DataChannel closed: $label") }
        clientRtc.onDiagnostics = { update -> runOnUiThread { betaDiagnostics = mergeControllerDiagnostics(update) } }
        clientRtc.onRemoteVideoTrack = { track -> Log.d(TAG, "Remote video track stored for renderer"); runOnUiThread { remoteTrack = track; clientStatus = "Connected - video track received" } }
        clientRtc.onConnectionStateChanged = { state -> runOnUiThread {
            clientPeerState = state
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    clientControlActive = true; clientConnected = true; sessionStarting = false
                    updateSessionActive(true)
                    clientStatus = if (remoteTrack == null) "Connected - waiting for video..." else "Connected - video playing"
                    mainHandler.postDelayed({
                        if (clientConnected && remoteTrack == null) {
                            clientStatus = "Connected, but no video track received"
                            Log.e(TAG, "VIDEO TRACK UNAVAILABLE: connected for 10 seconds without a remote video track")
                        }
                    }, 10_000L)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    sendNeutralReset("connection interrupted")
                    clientControlActive = false
                    clientStatus = "Connection interrupted - recovering..."
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    mainHandler.postDelayed(disconnectGraceRunnable, 15_000L)
                    Log.w(TAG, "CONNECTION INTERRUPTED: preserving renderer for 15-second automatic WebRTC recovery window")
                }
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    sendNeutralReset("PeerConnection $state")
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    clientControlActive = false; clientConnected = false
                    clientStatus = connectionText(state)
                }
                else -> clientStatus = connectionText(state)
            }
        } }
        clientRtc.onIceCandidateReady = { candidate -> firebase.saveIceCandidate(code, "client", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) { clientStatus = "ICE save error: $it" } }
        clientStatus = "Loading TURN credentials..."
        clientRtc.createPeerConnection(false, onSuccess = {
            firebase.listenForIceCandidates(code, "host", { c, mid, line -> clientRtc.addIceCandidate(c, mid, line) }, { clientStatus = "ICE listen error: $it" })
            clientRtc.setRemoteOffer(offer, {
                clientStatus = "Offer found; creating answer..."
                clientRtc.createAnswer({ answer ->
                    Log.d(TAG, "Answer created")
                    firebase.saveAnswer(code, answer, { clientStatus = "WebRTC answer sent - connecting..." }, { clientStatus = "Answer save failed: $it" })
                }, { clientStatus = "Answer error: $it"; sessionStarting = false })
            }, { clientStatus = "Offer error: $it"; sessionStarting = false })
        }, onError = { clientStatus = "PeerConnection error: $it"; sessionStarting = false })
    }

    private fun connectionText(state: PeerConnection.PeerConnectionState) = when (state) {
        PeerConnection.PeerConnectionState.CONNECTED -> "Connected!"
        PeerConnection.PeerConnectionState.CONNECTING -> "Connecting..."
        PeerConnection.PeerConnectionState.FAILED -> "Connection failed"
        PeerConnection.PeerConnectionState.DISCONNECTED -> "Disconnected"
        PeerConnection.PeerConnectionState.CLOSED -> "Closed"
        else -> "WebRTC: $state"
    }

    private fun handleControlMessage(message: String) {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        handleControlMessageInternal(message)
        val elapsedMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        if (!message.startsWith("AXIS|") || ++controlThreadLogCounter % 120 == 1) Log.d(TAG, "CONTROL_THREAD_MS: ${elapsedMicros / 1000.0}")
    }

    private fun handleControlMessageInternal(message: String) {
        recordControllerPacket()
        val parts = message.split('|')
        when (parts.firstOrNull()) {
            "ACK" -> {
                val sentAt = parts.getOrNull(2)?.toLongOrNull() ?: return
                val roundTrip = (android.os.SystemClock.elapsedRealtime() - sentAt).coerceAtLeast(0L)
                val injectMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val captureMs = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                val estimatedEndToEnd = captureMs + roundTrip / 2L + injectMs
                lastControlRoundTripMs = roundTrip
                latestControllerLatencyMs = estimatedEndToEnd
                controllerLatencyTotalMs += estimatedEndToEnd
                controllerLatencySamples++
                controllerLatencyMaxMs = maxOf(controllerLatencyMaxMs, estimatedEndToEnd)
                recentControllerLatencies.addLast(estimatedEndToEnd)
                while (recentControllerLatencies.size > 128) recentControllerLatencies.removeFirst()
                Log.d(TAG, "CONTROL_NETWORK_MS: ${roundTrip / 2L}")
                Log.d(TAG, "CONTROL_TOTAL_LATENCY_MS: $estimatedEndToEnd")
                Log.d(TAG, "CONTROL_PACKET_AGE_MS: ${captureMs + roundTrip / 2L}")
                Log.d(TAG, "CONTROL LATENCY_MS: estimatedEndToEnd=$estimatedEndToEnd capture=$captureMs network=${roundTrip / 2L} inject=$injectMs roundTrip=$roundTrip")
            }
            "KEY" -> {
                val v3 = parts.size >= 10
                val v2 = parts.size >= 9
                val sequence = parts.getOrNull(if (v2) 4 else 1)?.toLongOrNull() ?: return
                val sentAt = parts.getOrNull(if (v2) 5 else 2)?.toLongOrNull() ?: return
                val captureMs = if (v2) parts[6].toLongOrNull() ?: 0L else 0L
                val key = parts.getOrNull(if (v3) 8 else if (v2) 7 else 3)?.toIntOrNull() ?: return
                val action = parts.getOrNull(if (v3) 9 else if (v2) 8 else 4) ?: return
                val session = if (v2) parts[1] else activeSessionId
                if (!acceptRemoteSequence("digital", session, sequence)) return
                val logical = ControllerMapping.logicalForAndroidKey(key)
                if (logical != null) {
                    updateLogicalButton(logical, action == "DOWN")
                    Log.d(TAG, "LOGICAL_CONTROL_EVENT: control=${logical.displayName} state=$action androidKey=$key linuxKey=${logical.linuxCode}")
                }
                val token = key
                if (action == "DOWN" && !heldRemoteButtons.add(token)) { recordDuplicateDrop(); Log.w(TAG, "CONTROL_DUPLICATE_DROPPED: key=$key action=$action"); return }
                if (action == "UP" && !heldRemoteButtons.remove(token)) { recordDuplicateDrop(); Log.w(TAG, "CONTROL_DUPLICATE_DROPPED: key=$key action=$action"); return }
                val started = android.os.SystemClock.elapsedRealtime()
                val context = ControllerEventContext(session, if (v2) parts[2] else "legacy", if (v2) parts[3].toIntOrNull() ?: 2 else 2, sequence, sentAt)
                if (logical != null && isDpadControl(logical)) {
                    val legacyDpad = DpadState(
                        x = (if (KeyEvent.KEYCODE_DPAD_RIGHT in heldRemoteButtons) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_LEFT in heldRemoteButtons) 1 else 0),
                        y = (if (KeyEvent.KEYCODE_DPAD_DOWN in heldRemoteButtons) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_UP in heldRemoteButtons) 1 else 0)
                    )
                    val changed = hostDpadState.update(legacyDpad)
                    val injected = !changed || controllerBackend.updateDpad(context, legacyDpad.x, legacyDpad.y)
                    updateLogicalDpad(legacyDpad)
                    val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    hostRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                    Log.d(TAG, "DPAD_LOGICAL_STATE: side=host state=${legacyDpad.label} source=LEGACY_KEY sequence=$sequence changed=$changed")
                    if (!injected) logBackendUnavailableOnce()
                    return
                }
                val injected = if (action == "DOWN") controllerBackend.keyDown(context, key) else if (action == "UP") controllerBackend.keyUp(context, key) else false
                val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                hostRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                Log.d(TAG, "CONTROL_BUTTON_STATE: key=$key state=$action sequence=$sequence")
                Log.d(TAG, "CONTROL_RECEIVE_TO_INJECT_MS: $injectMs")
                if (injected) Log.d(TAG, "CONTROL INJECTED: key=$key action=$action injectMs=$injectMs") else logBackendUnavailableOnce()
            }
            "AXIS" -> {
                val v3 = parts.size >= 16
                val v2 = parts.size >= 15
                val sequence = parts.getOrNull(if (v2) 4 else 1)?.toLongOrNull() ?: return
                val sentAt = parts.getOrNull(if (v2) 5 else 2)?.toLongOrNull() ?: return
                val captureMs = if (v2) parts[6].toLongOrNull() ?: 0L else 0L
                val session = if (v2) parts[1] else activeSessionId
                if (!acceptRemoteSequence("analog", session, sequence)) return
                val senderRttMs = if (v3) parts[7].toLongOrNull() else null
                val estimatedAgeMs = ControllerTransportPolicy.estimatedPacketAgeMs(captureMs, senderRttMs)
                if (estimatedAgeMs > ControllerTransportPolicy.STALE_ANALOG_RTT_THRESHOLD_MS) {
                    staleAnalogPacketsDropped++
                    updateControllerDiagnostics { copy(controllerPacketAgeMs = estimatedAgeMs, droppedStaleAnalogPackets = staleAnalogPacketsDropped) }
                    Log.w(TAG, "CONTROL_PACKET_AGE_MS: $estimatedAgeMs stale=true sequence=$sequence action=DROP_ANALOG")
                    return
                }
                val start = if (v3) 8 else if (v2) 7 else 3
                if (parts.size >= start + 8) {
                    val axes = FloatArray(8) { index -> parts[start + index].toFloatOrNull() ?: 0f }
                    updateLogicalAxes(axes)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val context = ControllerEventContext(session, if (v2) parts[2] else "legacy", if (v2) parts[3].toIntOrNull() ?: 2 else 2, sequence, sentAt)
                    val injected = controllerBackend.updateAxes(context, axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], axes[6], axes[7])
                    val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    if (++axisAckCounter % 20 == 1) hostRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                    if (injected && axisAckCounter % 20 == 1) {
                        Log.d(TAG, "LOGICAL_CONTROL_EVENT: control=AXES lx=${axes[0]} ly=${axes[1]} rx=${axes[2]} ry=${axes[3]} lt=${axes[4]} rt=${axes[5]} dpad=${axes[6]},${axes[7]}")
                        Log.d(TAG, "CONTROL_AXIS_STATE: sequence=$sequence lx=${axes[0]} ly=${axes[1]} rx=${axes[2]} ry=${axes[3]} lt=${axes[4]} rt=${axes[5]} dpadX=${axes[6]} dpadY=${axes[7]}")
                        Log.d(TAG, "CONTROL_RECEIVE_TO_INJECT_MS: $injectMs")
                    } else if (!injected) logBackendUnavailableOnce()
                }
            }
            "DPAD" -> {
                if (parts.size < 11) return
                val session = parts[1]
                val sequence = parts[4].toLongOrNull() ?: return
                val sentAt = parts[5].toLongOrNull() ?: return
                val captureMs = parts[6].toLongOrNull() ?: 0L
                val x = parts[8].toIntOrNull()?.coerceIn(-1, 1) ?: return
                val y = parts[9].toIntOrNull()?.coerceIn(-1, 1) ?: return
                val source = parts[10]
                if (!acceptRemoteSequence("digital", session, sequence)) return
                val next = DpadState(x, y)
                if (!hostDpadState.update(next)) {
                    dpadDuplicateDrops++
                    Log.d(TAG, "DPAD_DUPLICATE_DROPPED: side=host state=${next.label} sequence=$sequence total=$dpadDuplicateDrops")
                    return
                }
                val context = ControllerEventContext(session, parts[2], parts[3].toIntOrNull() ?: 2, sequence, sentAt)
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                val injected = controllerBackend.updateDpad(context, x, y)
                val injectMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                updateLogicalDpad(next)
                hostRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                Log.d(TAG, "DPAD_LOGICAL_STATE: side=host state=${next.label} source=$source sequence=$sequence")
                if (next == DpadState()) Log.d(TAG, "DPAD_NEUTRAL_SENT: side=host sequence=$sequence")
                if (!injected) logBackendUnavailableOnce()
            }
            "RESET" -> if (parts.getOrNull(1) == activeSessionId) resetRemoteInput(parts.getOrNull(6) ?: "remote reset")
        }
    }

    private fun acceptRemoteSequence(stream: String, session: String, sequence: Long): Boolean {
        if (session != activeSessionId) { Log.w(TAG, "CONTROL_STALE_SESSION_DROPPED: packet=$session active=$activeSessionId stream=$stream"); return false }
        val previous = lastRemoteSequences[stream] ?: 0L
        if (sequence <= previous) {
            outOfOrderControlPacketsDropped++
            updateControllerDiagnostics { copy(outOfOrderControlPacketsDropped = outOfOrderControlPacketsDropped) }
            Log.w(TAG, "CONTROL_OUT_OF_ORDER_DROPPED: stream=$stream sequence=$sequence previous=$previous")
            return false
        }
        if (previous > 0L && sequence > previous + 1L) Log.w(TAG, "CONTROL_SEQUENCE_GAP: stream=$stream expected=${previous + 1L} received=$sequence")
        lastRemoteSequences[stream] = sequence
        return true
    }

    private fun resetRemoteInput(reason: String) {
        if (heldRemoteButtons.isNotEmpty()) Log.w(TAG, "CONTROL_STUCK_INPUT_RECOVERY: reason=$reason held=${heldRemoteButtons.joinToString()}")
        heldRemoteButtons.clear(); lastRemoteSequences.clear(); axisAckCounter = 0
        hostDpadState.reset()
        logicalControllerState = ControllerInputState()
        if (controllerInputTestOpen) runOnUiThread { controllerTestDisplayState = logicalControllerState }
        controllerBackend.resetNeutral(reason)
        Log.d(TAG, "CONTROL_REMOTE_STATE_CLEARED: $reason")
    }

    private fun sendNeutralReset(reason: String) {
        if (activeSessionId == "none") return
        val sentAt = android.os.SystemClock.elapsedRealtime()
        clientRtc.sendControlMessage("RESET|$activeSessionId|device-$lastControllerDeviceId|2|${++digitalSequence}|$sentAt|$reason")
        lastAxes.fill(Float.NaN)
        Log.d(TAG, "CONTROL_NEUTRAL_RESET_SENT: $reason")
    }

    private fun logBackendUnavailableOnce() {
        if (backendUnavailableLogged) return
        backendUnavailableLogged = true
        Log.w(TAG, "CONTROL BACKEND UNAVAILABLE: transport works; system-wide injection requires privileged backend")
    }

    private fun recordDuplicateDrop() {
        duplicateControlPacketsDropped++
        updateControllerDiagnostics { copy(duplicateControlPacketsDropped = duplicateControlPacketsDropped) }
    }

    private fun updateControllerDiagnostics(block: BetaDiagnostics.() -> BetaDiagnostics) {
        if (Looper.myLooper() == Looper.getMainLooper()) betaDiagnostics = betaDiagnostics.block()
        else runOnUiThread { betaDiagnostics = betaDiagnostics.block() }
    }

    private fun mergeControllerDiagnostics(update: BetaDiagnostics): BetaDiagnostics {
        val current = betaDiagnostics
        return update.copy(
            controllerPacketsPerSecond = current.controllerPacketsPerSecond,
            lastControllerLatencyMs = current.lastControllerLatencyMs,
            averageControllerLatencyMs = current.averageControllerLatencyMs,
            maxControllerLatencyMs = current.maxControllerLatencyMs,
            controllerP95LatencyMs = current.controllerP95LatencyMs,
            controllerP50LatencyMs = current.controllerP50LatencyMs,
            controllerPacketAgeMs = current.controllerPacketAgeMs,
            duplicateControlPacketsDropped = current.duplicateControlPacketsDropped,
            outOfOrderControlPacketsDropped = current.outOfOrderControlPacketsDropped,
            player2Status = current.player2Status,
            player2Classification = current.player2Classification
        )
    }

    private fun schedulePlayer2Inspection() {
        mainHandler.postDelayed({ inspectAllPlayer2Devices() }, 250L)
        mainHandler.postDelayed({ inspectAllPlayer2Devices() }, 1_000L)
        mainHandler.postDelayed({
            if (player2Classification == "Unknown" && controllerBackend.status == ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE) {
                Log.w(TAG, "WINLATOR_HOTPLUG_WARNING: Android has not enumerated DroidLink Player 2 yet; start Winlator only after WINLATOR_GAMEPAD_READY")
            }
        }, 2_000L)
    }

    private fun inspectAllPlayer2Devices() = InputDevice.getDeviceIds().forEach(::inspectPlayer2Device)

    private fun inspectPlayer2Device(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (device.name != "DroidLink Player 2") return
        val sources = device.sources
        val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        val keyboard = sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
        val classification = buildList {
            if (gamepad) add("GAMEPAD")
            if (joystick) add("JOYSTICK")
            if (keyboard) add("KEYBOARD")
        }.ifEmpty { listOf("UNKNOWN") }.joinToString(" / ")
        player2Classification = classification
        updateControllerDiagnostics { copy(player2Status = controllerBackend.status.label, player2Classification = classification) }
        Log.d(TAG, "ANDROID_DEVICE_SOURCES: id=$deviceId name=${device.name} sources=0x${sources.toString(16)} descriptor=${device.descriptor}")
        Log.d(TAG, "ANDROID_DEVICE_IS_GAMEPAD: $gamepad")
        Log.d(TAG, "ANDROID_DEVICE_IS_JOYSTICK: $joystick")
        Log.d(TAG, "ANDROID_DEVICE_IS_KEYBOARD: $keyboard")
        Log.d(TAG, "GAMEPAD_VID_PID: %04x:%04x".format(device.vendorId, device.productId))
        if (gamepad && joystick && !keyboard) Log.d(TAG, "WINLATOR_GAMEPAD_READY: Android classified DroidLink Player 2 as GAMEPAD/JOYSTICK; start Winlator container now")
        else Log.w(TAG, "WINLATOR_HOTPLUG_WARNING: classification=$classification; do not start Winlator until GAMEPAD/JOYSTICK classification is confirmed")
    }

    private fun recordControllerPacket() {
        controllerWindowPackets++
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - controllerWindowStart
        if (elapsed >= 1_000L) {
            val rate = controllerWindowPackets * 1_000.0 / elapsed
            val sortedLatencies = recentControllerLatencies.sorted()
            val p95 = if (sortedLatencies.isEmpty()) null else sortedLatencies[((sortedLatencies.size - 1) * 0.95).toInt()]
            val p50 = if (sortedLatencies.isEmpty()) null else sortedLatencies[(sortedLatencies.size - 1) / 2]
            Log.d(TAG, "CONTROL_P50_LATENCY_MS: ${p50 ?: "n/a"}")
            Log.d(TAG, "CONTROL_P95_LATENCY_MS: ${p95 ?: "n/a"}")
            Log.d(TAG, "CONTROL_MAX_LATENCY_MS: ${if (controllerLatencySamples > 0L) controllerLatencyMaxMs else "n/a"}")
            updateControllerDiagnostics {
                copy(
                    controllerPacketsPerSecond = rate,
                    lastControllerLatencyMs = latestControllerLatencyMs,
                    averageControllerLatencyMs = if (controllerLatencySamples == 0L) null else controllerLatencyTotalMs / controllerLatencySamples,
                    maxControllerLatencyMs = controllerLatencyMaxMs.takeIf { controllerLatencySamples > 0L },
                    controllerP95LatencyMs = p95,
                    controllerP50LatencyMs = p50,
                    controllerPacketAgeMs = latestControllerLatencyMs
                )
            }
            controllerWindowPackets = 0; controllerWindowStart = now
        }
    }

    private fun updateLogicalButton(control: LogicalControl, down: Boolean) {
        logicalControllerState = logicalControllerState.withButton(control, down)
        if (controllerInputTestOpen) runOnUiThread { controllerTestDisplayState = logicalControllerState }
    }

    private fun updateLogicalAxes(axes: FloatArray) {
        logicalControllerState = logicalControllerState.copy(
            leftX = axes[0], leftY = axes[1], rightX = axes[2], rightY = axes[3],
            leftTrigger = axes[4], rightTrigger = axes[5]
        )
        val now = android.os.SystemClock.elapsedRealtime()
        if (controllerInputTestOpen && now - lastControllerTestUiMs >= 50L) {
            lastControllerTestUiMs = now
            runOnUiThread { controllerTestDisplayState = logicalControllerState }
        }
    }

    private fun updateLogicalDpad(state: DpadState) {
        logicalControllerState = logicalControllerState.copy(dpadX = state.x.toFloat(), dpadY = state.y.toFloat())
        if (controllerInputTestOpen) runOnUiThread { controllerTestDisplayState = logicalControllerState }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun captureSettings(): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        val sourceWidth = metrics.widthPixels.coerceAtLeast(2)
        val sourceHeight = metrics.heightPixels.coerceAtLeast(2)
        val scale = minOf(1.0, 1280.0 / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val refreshRate = display?.refreshRate ?: 60f
        val fps = if (refreshRate >= 50f) 60 else 30
        Log.d(TAG, "Capture profile selected: ${width}x$height@$fps source=${sourceWidth}x$sourceHeight refreshRate=$refreshRate")
        return Triple(width, height, fps)
    }

    private fun returnToMenu() { cleanupSession(deleteHostRoom = true); mode = "menu" }

    private fun disconnectSession() { cleanupSession(deleteHostRoom = true); mode = "menu" }

    private fun updateSessionActive(active: Boolean) {
        sessionActive = active
        sessionBackCallback.isEnabled = active || sessionMenuOpen || sessionStatsOpen
        if (!active) { sessionMenuOpen = false; sessionStatsOpen = false; controllerInputTestOpen = false }
    }

    private fun cleanupSession(deleteHostRoom: Boolean) {
        sendNeutralReset("session cleanup")
        resetRemoteInput("session cleanup")
        firebase.stopListening()
        if (deleteHostRoom && hostRoomCode.isNotEmpty()) firebase.deleteRoom(hostRoomCode)
        mainHandler.removeCallbacksAndMessages(null)
        hostRtc.close(); clientRtc.close(); stopService(Intent(this, ScreenCaptureService::class.java))
        controllerBackend.close(); controllerBackend = TransportOnlyBackend()
        detachRenderer(); remoteTrack = null; clientControlActive = false; clientConnected = false; clientPeerState = PeerConnection.PeerConnectionState.NEW; sessionStarting = false
        pendingCaptureIntent = null; pendingOffer = null; hostRoomCode = ""; activeSessionId = "none"
        lastAxes.fill(Float.NaN); digitalSequence = 0L; analogSequence = 0L; lastControllerDeviceId = -1; backendUnavailableLogged = false; axisAckCounter = 0
        lastControlRoundTripMs = null; controllerLatencyTotalMs = 0L; controllerLatencySamples = 0L; controllerLatencyMaxMs = 0L
        latestControllerLatencyMs = null; recentControllerLatencies.clear(); controlThreadLogCounter = 0
        duplicateControlPacketsDropped = 0L; outOfOrderControlPacketsDropped = 0L; staleAnalogPacketsDropped = 0L; player2Classification = "Unknown"
        logicalControllerState = ControllerInputState(); controllerTestDisplayState = ControllerInputState(); lastControllerTestUiMs = 0L
        resetLocalDpadState("session cleanup"); hostDpadState.reset(); dpadDuplicateDrops = 0L
        controllerWindowPackets = 0; controllerWindowStart = android.os.SystemClock.elapsedRealtime(); betaDiagnostics = BetaDiagnostics()
        updateSessionActive(false)
        Log.d(TAG, "Session cleanup complete")
    }

    private fun detachRenderer() {
        val view = renderer
        if (view != null) rendererTrack?.let { try { it.removeSink(view) } catch (_: Exception) {} }
        try { view?.release() } catch (_: Exception) {}
        renderer = null; rendererTrack = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (clientControlActive && isController(event.source)) {
            noteController(event.deviceId, event.device?.name)
            if (handleDpadKey(keyCode, down = true, event)) return true
            if (event.repeatCount == 0) sendKey(keyCode, "DOWN", event)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (clientControlActive && isController(event.source)) {
            noteController(event.deviceId, event.device?.name)
            if (handleDpadKey(keyCode, down = false, event)) return true
            sendKey(keyCode, "UP", event)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
    private fun isController(source: Int) = source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD || source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun noteController(deviceId: Int, name: String?) {
        if (lastControllerDeviceId == deviceId) return
        if (lastControllerDeviceId != -1) { sendNeutralReset("controller changed"); resetLocalDpadState("controller changed") }
        lastControllerDeviceId = deviceId
        Log.d(TAG, "CONTROLLER DETECTED: id=$deviceId name=${name ?: "unknown"}")
    }

    private fun sendKey(keyCode: Int, action: String, event: KeyEvent) {
        val captureDelayMs = (android.os.SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
        val logical = ControllerMapping.logicalForAndroidKey(keyCode)
        Log.d(TAG, "RAW_KEY_EVENT: device=${event.deviceId} androidKey=$keyCode action=$action eventTime=${event.eventTime}")
        Log.d(TAG, "LOGICAL_CONTROL_EVENT: control=${logical?.displayName ?: "UNMAPPED"} state=$action")
        Log.d(TAG, "CONTROL_CAPTURE_MS: $captureDelayMs type=KEY")
        val message = "KEY|$activeSessionId|device-$lastControllerDeviceId|2|${++digitalSequence}|${android.os.SystemClock.elapsedRealtime()}|$captureDelayMs|${lastControlRoundTripMs ?: 0L}|$keyCode|$action"
        recordControllerPacket()
        clientRtc.sendControlMessage(message)
    }

    private fun handleDpadKey(keyCode: Int, down: Boolean, event: KeyEvent): Boolean {
        val logical = ControllerMapping.logicalForAndroidKey(keyCode) ?: return false
        if (!isDpadControl(logical)) return false
        Log.d(TAG, "DPAD_RAW_KEY: device=${event.deviceId} control=${logical.displayName} state=${if (down) "DOWN" else "UP"} repeat=${event.repeatCount}")
        val selected = dpadSources[event.deviceId] ?: DpadSource.UNSELECTED
        if (selected == DpadSource.HAT) {
            dpadDuplicateDrops++
            Log.d(TAG, "DPAD_DUPLICATE_DROPPED: side=joiner source=KEY preferred=HAT total=$dpadDuplicateDrops")
            return true
        }
        if (selected == DpadSource.UNSELECTED) {
            dpadSources[event.deviceId] = DpadSource.KEY
            Log.d(TAG, "DPAD_SOURCE_SELECTED: device=${event.deviceId} source=KEY")
        }
        if (down) joinerDpadKeys.add(logical) else joinerDpadKeys.remove(logical)
        val next = DpadState(
            x = (if (LogicalControl.DPAD_RIGHT in joinerDpadKeys) 1 else 0) - (if (LogicalControl.DPAD_LEFT in joinerDpadKeys) 1 else 0),
            y = (if (LogicalControl.DPAD_DOWN in joinerDpadKeys) 1 else 0) - (if (LogicalControl.DPAD_UP in joinerDpadKeys) 1 else 0)
        )
        sendDpadIfChanged(next, event, DpadSource.KEY)
        return true
    }

    private fun isDpadControl(control: LogicalControl) = when (control) {
        LogicalControl.DPAD_UP, LogicalControl.DPAD_DOWN, LogicalControl.DPAD_LEFT, LogicalControl.DPAD_RIGHT -> true
        else -> false
    }

    private fun resetLocalDpadState(reason: String) {
        dpadSources.clear()
        joinerDpadKeys.clear()
        joinerDpadState.reset()
        Log.d(TAG, "DPAD_LOGICAL_STATE: side=joiner state=NEUTRAL reason=$reason")
    }

    private fun sendDpadIfChanged(next: DpadState, event: MotionEvent, source: DpadSource) {
        sendDpadIfChanged(next, event.eventTime, event.deviceId, source)
    }

    private fun sendDpadIfChanged(next: DpadState, event: KeyEvent, source: DpadSource) {
        sendDpadIfChanged(next, event.eventTime, event.deviceId, source)
    }

    private fun sendDpadIfChanged(next: DpadState, eventTime: Long, deviceId: Int, source: DpadSource) {
        if (!joinerDpadState.update(next)) {
            dpadDuplicateDrops++
            if (dpadDuplicateDrops % 120L == 1L) Log.d(TAG, "DPAD_DUPLICATE_DROPPED: side=joiner source=$source state=${next.label} total=$dpadDuplicateDrops")
            return
        }
        val captureMs = (android.os.SystemClock.uptimeMillis() - eventTime).coerceAtLeast(0L)
        val sentAt = android.os.SystemClock.elapsedRealtime()
        val message = "DPAD|$activeSessionId|device-$deviceId|2|${++digitalSequence}|$sentAt|$captureMs|${lastControlRoundTripMs ?: 0L}|${next.x}|${next.y}|$source"
        recordControllerPacket()
        clientRtc.sendControlMessage(message)
        Log.d(TAG, "DPAD_LOGICAL_STATE: side=joiner state=${next.label} source=$source sequence=$digitalSequence")
        if (next == DpadState()) Log.d(TAG, "DPAD_NEUTRAL_SENT: side=joiner sequence=$digitalSequence")
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (clientControlActive && event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            noteController(event.deviceId, event.device?.name)
            val values = FloatArray(ControllerMapping.axisMap.size) { index ->
                val mapping = ControllerMapping.axisMap[index]
                normalizeAxis(event, mapping.androidAxis, mapping.trigger)
            }
            val rawDpad = DpadState(values[6].toInt().coerceIn(-1, 1), values[7].toInt().coerceIn(-1, 1))
            if (rawDpad != joinerDpadState.state || controllerInputTestOpen) Log.d(TAG, "DPAD_RAW_HAT: device=${event.deviceId} state=${rawDpad.label} x=${rawDpad.x} y=${rawDpad.y}")
            val selected = dpadSources[event.deviceId] ?: DpadSource.UNSELECTED
            if (rawDpad != DpadState() || selected == DpadSource.HAT) {
                if (selected != DpadSource.HAT) {
                    dpadSources[event.deviceId] = DpadSource.HAT
                    joinerDpadKeys.clear()
                    Log.d(TAG, "DPAD_SOURCE_SELECTED: device=${event.deviceId} source=HAT previous=$selected")
                }
                sendDpadIfChanged(rawDpad, event, DpadSource.HAT)
            }
            values[6] = 0f; values[7] = 0f
            val now = android.os.SystemClock.uptimeMillis(); if (now - lastAxisSendTime < ControllerTransportPolicy.ANALOG_SEND_INTERVAL_MS) return true; lastAxisSendTime = now
            val meaningful = values.indices.any { lastAxes[it].isNaN() || kotlin.math.abs(values[it] - lastAxes[it]) >= 0.01f }
            if (!meaningful && now - lastAxisHeartbeatTime < ControllerTransportPolicy.ANALOG_HEARTBEAT_MS) return true
            values.copyInto(lastAxes); lastAxisHeartbeatTime = now
            val captureDelayMs = (android.os.SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
            if (analogSequence % 120L == 0L) {
                Log.d(TAG, "RAW_AXIS_EVENT: device=${event.deviceId} x=${values[0]} y=${values[1]} z=${values[2]} rz=${values[3]} lt=${values[4]} rt=${values[5]} hat=${rawDpad.x},${rawDpad.y}")
                Log.d(TAG, "CONTROL_CAPTURE_MS: $captureDelayMs type=AXIS")
            }
            val message = "AXIS|$activeSessionId|device-$lastControllerDeviceId|2|${++analogSequence}|${android.os.SystemClock.elapsedRealtime()}|$captureDelayMs|${lastControlRoundTripMs ?: 0L}|${values.joinToString("|")}"
            recordControllerPacket()
            clientRtc.sendControlMessage(message, realtimeAnalog = true); return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun normalizeAxis(event: MotionEvent, axis: Int, trigger: Boolean): Float {
        var value = event.getAxisValue(axis)
        if (trigger && value < 0f) value = (value + 1f) / 2f
        val deadzone = event.device?.getMotionRange(axis, event.source)?.flat?.coerceAtLeast(if (trigger) 0.02f else 0.12f) ?: if (trigger) 0.02f else 0.12f
        value = if (kotlin.math.abs(value) <= deadzone) 0f else value
        return if (trigger) value.coerceIn(0f, 1f) else value.coerceIn(-1f, 1f)
    }

    override fun onDestroy() {
        cleanupSession(deleteHostRoom = true)
        hostRtc.release(); clientRtc.release()
        if (receiverRegistered) { try { unregisterReceiver(projectionReadyReceiver) } catch (_: Exception) {}; receiverRegistered = false }
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputDeviceListener) } catch (_: Exception) {}
        super.onDestroy()
    }
}
