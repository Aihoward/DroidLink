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
import android.net.Uri
import android.content.pm.PackageManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.droidlink.app.ui.theme.DroidLinkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.*

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "DroidLink"
        private val ACCENT_COLORS = setOf("Red", "Blue", "Green", "Yellow", "Purple")
    }
    private val firebase = FirebaseRoomManager()
    private val hostRtc by lazy { WebRtcManager(this) }
    private val clientRtc by lazy { WebRtcManager(this) }
    private var controllerBackend: ControllerBackend = TransportOnlyBackend()

    private var mode by mutableStateOf("menu")
    private var hostRoomCode by mutableStateOf("")
    private var hostStatus by mutableStateOf("")
    private var captureStatus by mutableStateOf("DroidLink is ready")
    private var clientStatus by mutableStateOf("Not connected")
    private var joinSessionState by mutableStateOf(JoinSessionState.Idle)
    private var audioStatus by mutableStateOf("Audio not evaluated")
    private var betaDiagnostics by mutableStateOf(BetaDiagnostics())
    private var sessionActive by mutableStateOf(false)
    private var sessionMenuOpen by mutableStateOf(false)
    private var sessionStatsOpen by mutableStateOf(false)
    private var sessionSettingsOpen by mutableStateOf(false)
    private var sessionGameAudioOpen by mutableStateOf(false)
    private var menuButtonVisible by mutableStateOf(false)
    private var menuRevealGeneration by mutableLongStateOf(0L)
    private var mainPage by mutableStateOf("home")
    private var introVisible by mutableStateOf(true)
    private var showIntroAnimation by mutableStateOf(true)
    private var keepScreenAwake by mutableStateOf(true)
    private var qualityPreset by mutableStateOf("Auto")
    private var animatedBackground by mutableStateOf(true)
    private var hapticFeedback by mutableStateOf(true)
    private var uiSoundEffects by mutableStateOf(true)
    private var accentName by mutableStateOf("Green")
    private var onboardingVisible by mutableStateOf(false)
    private var onboardingPage by mutableIntStateOf(0)
    private var readinessVisible by mutableStateOf(false)
    private var readinessShownForSession = false
    private var controlChannelOpen by mutableStateOf(false)
    private var gameAudioEnabled by mutableStateOf(true)
    private var gameAudioVolume by mutableFloatStateOf(1f)
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
    private val releasedRenderers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<SurfaceViewRenderer, Boolean>())
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
    private var sessionGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerHealthRunning = false
    private var controllerHealthTicks = 0
    private var lastRemoteInputPacketMs = 0L
    private var watchdogNeutralResets = 0L
    private val controllerHealthRunnable = object : Runnable {
        override fun run() {
            if (!controllerHealthRunning) return
            controllerHealthTicks++
            hostRtc.recoverControllerChannels("periodic health check")
            val now = android.os.SystemClock.elapsedRealtime()
            val axesActive = listOf(
                logicalControllerState.leftX, logicalControllerState.leftY,
                logicalControllerState.rightX, logicalControllerState.rightY,
                logicalControllerState.leftTrigger, logicalControllerState.rightTrigger,
                logicalControllerState.dpadX, logicalControllerState.dpadY
            ).any { kotlin.math.abs(it) > 0.05f }
            if (hostPeerState == PeerConnection.PeerConnectionState.CONNECTED &&
                lastRemoteInputPacketMs > 0L && axesActive &&
                now - lastRemoteInputPacketMs >= ControllerTransportPolicy.STALE_ACTIVE_INPUT_MS
            ) {
                watchdogNeutralResets++
                Log.e(TAG, "CONTROLLER_WATCHDOG_RESET: staleMs=${now - lastRemoteInputPacketMs} resets=$watchdogNeutralResets virtualDeviceRecreated=false")
                resetRemoteInput("controller packet watchdog")
                lastRemoteInputPacketMs = now
            }
            if (controllerHealthTicks % ControllerTransportPolicy.HEALTH_LOG_EVERY_TICKS == 0) {
                hostRtc.logControllerTransportHealth("periodic")
                clientRtc.logControllerTransportHealth("periodic")
                controllerBackend.logHealth("periodic")
                Log.d(TAG, "CONTROLLER_SESSION_HEALTH: session=$activeSessionId active=$sessionActive captureEnabled=$clientControlActive physicalDeviceId=$lastControllerDeviceId backend=${controllerBackend.status.label} backendIdentity=${System.identityHashCode(controllerBackend)} packetsWindow=$controllerWindowPackets peerHost=$hostPeerState peerClient=$clientPeerState watchdogResets=$watchdogNeutralResets")
            }
            mainHandler.postDelayed(this, ControllerTransportPolicy.HEALTH_INTERVAL_MS)
        }
    }
    private var backendUnavailableLogged = false
    private var controllerWindowStart = android.os.SystemClock.elapsedRealtime()
    private var controllerWindowPackets = 0
    private var axisAckCounter = 0
    private var controlThreadLogCounter = 0
    private var controllerAckLogCounter = 0
    private var lastControlRoundTripMs: Long? = null
    private var controllerLatencyTotalMs = 0L
    private var controllerLatencySamples = 0L
    private var controllerLatencyMaxMs = 0L
    private var latestControllerLatencyMs: Long? = null
    private var latestControllerPacketAgeMs: Long? = null
    private var controllerPacketAgeTotalMs = 0L
    private var controllerPacketAgeSamples = 0L
    private val recentControllerLatencies = ArrayDeque<Long>()
    private var duplicateControlPacketsDropped = 0L
    private var outOfOrderControlPacketsDropped = 0L
    private var staleAnalogPacketsDropped = 0L
    private var player2Classification = "Unknown"
    private var clientPeerState = PeerConnection.PeerConnectionState.NEW
    private var hostPeerState = PeerConnection.PeerConnectionState.NEW
    private val heldRemoteButtons = mutableSetOf<Int>()
    private val lastRemoteSequences = mutableMapOf<String, Long>()
    private lateinit var sessionBackCallback: OnBackPressedCallback
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { inspectPlayer2Device(deviceId) }
        override fun onInputDeviceChanged(deviceId: Int) { inspectPlayer2Device(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) { if (deviceId == lastControllerDeviceId) { Log.w(TAG, "Controller disconnected: $deviceId"); sendNeutralReset("controller disconnected"); resetLocalDpadState("controller disconnected") } }
    }
    private val disconnectGraceRunnable = Runnable {
        if (joinSessionState == JoinSessionState.Reconnecting) {
            clientControlActive = false
            transitionJoinState(JoinSessionState.Failed, "reconnect grace expired", "Reconnection failed - retry the session")
            Log.e(TAG, "RECONNECT GRACE EXPIRED: PeerConnection remained DISCONNECTED for 15 seconds")
        }
    }
    private val hostDisconnectGraceRunnable = Runnable {
        if (hostPeerState == PeerConnection.PeerConnectionState.DISCONNECTED) {
            hostStatus = "Reconnection failed - retry the session"
            sessionStarting = false
            updateSessionActive(false)
            Log.e(TAG, "HOST RECONNECT GRACE EXPIRED: PeerConnection remained DISCONNECTED for 15 seconds")
        }
    }

    private val projectionReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_READY) return
            val permission = pendingCaptureIntent ?: return
            try {
                Log.d(TAG, "MediaProjection service ready; attaching host video track")
                val capture = captureSettings()
                hostRtc.startScreenShare(permission, capture.first, capture.second, capture.third, qualityPreset)
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
        enableImmersiveMode()
        ContextCompat.registerReceiver(this, projectionReadyReceiver, IntentFilter(ScreenCaptureService.ACTION_READY), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, mainHandler)
        sessionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when { controllerInputTestOpen -> controllerInputTestOpen = false
                    sessionGameAudioOpen -> { sessionGameAudioOpen = false; sessionMenuOpen = true }
                    sessionStatsOpen -> { sessionStatsOpen = false; sessionMenuOpen = true }
                    sessionSettingsOpen -> { sessionSettingsOpen = false; sessionMenuOpen = true }
                    sessionMenuOpen -> sessionMenuOpen = false
                    sessionActive -> sessionMenuOpen = true }
            }
        }
        onBackPressedDispatcher.addCallback(this, sessionBackCallback)
        getSharedPreferences("droid_link_ui", MODE_PRIVATE).also { preferences ->
            showIntroAnimation = preferences.getBoolean("show_intro", true)
            keepScreenAwake = preferences.getBoolean("keep_awake", true)
            qualityPreset = preferences.getString("quality", "Auto") ?: "Auto"
            animatedBackground = preferences.getBoolean("animated_background", true)
            hapticFeedback = preferences.getBoolean("haptics", true)
            uiSoundEffects = preferences.getBoolean("ui_sounds", true)
            accentName = preferences.getString("accent_color", "Green")?.takeIf { it in ACCENT_COLORS } ?: "Green"
            onboardingVisible = !preferences.getBoolean("onboarding_complete", false)
            introVisible = showIntroAnimation
        }
        setContent {
            DroidLinkTheme {
                var joinCode by remember { mutableStateOf("") }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (animatedBackground && !sessionActive && mode != "disconnecting") AnimatedNeonBackground()
                    if (introVisible && showIntroAnimation) {
                        IntroScreen()
                    } else if (onboardingVisible) {
                        OnboardingScreen()
                    } else {
                        when (mode) {
                            "host" -> if (sessionActive) HostGameplayScreen() else HostScreen(onStart = ::startHost, onBack = ::returnToMenu)
                            "client" -> if (joinSessionState.showsActiveSession) VideoScreen() else JoinScreen(
                                code = joinCode,
                                onCode = { joinCode = it.filter(Char::isDigit).take(6) },
                                onConnect = { startJoin(joinCode) },
                                onBack = ::returnToMenu
                            )
                            "disconnecting" -> DisconnectedScreen()
                            else -> MainShell()
                        }
                        if (sessionActive && !readinessVisible && !sessionMenuOpen && !sessionStatsOpen && !sessionSettingsOpen && !sessionGameAudioOpen) SessionMenuRevealLayer()
                        if (sessionMenuOpen) SessionMenu()
                        if (sessionStatsOpen) SessionStats()
                        if (sessionSettingsOpen) SettingsScreen(inSession = true)
                        if (sessionGameAudioOpen) GameAudioPanel()
                        if (sessionActive && readinessVisible) PreGameReadiness()
                    }
                }
                LaunchedEffect(sessionStatsOpen) { setPerformanceDiagnosticsActive(sessionStatsOpen) }
                LaunchedEffect(Unit) {
                    if (showIntroAnimation) delay(1_150L)
                    introVisible = false
                }
                LaunchedEffect(keepScreenAwake, sessionActive) {
                    if (keepScreenAwake && sessionActive) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private val neonGreen: Color get() = accentColor(accentName)
    private val panelBlack = Color(0xFF111411)
    private val mutedText = Color(0xFFA9B1AA)

    @Composable private fun AnimatedNeonBackground() = AndroidView(
        factory = { NeonBackgroundView(it) },
        modifier = Modifier.fillMaxSize()
    )

    @Composable private fun IntroScreen() {
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(350), label = "introAlpha")
        val scale by animateFloatAsState(if (entered) 1f else 0.94f, tween(350), label = "introScale")
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.alpha(alpha).scale(scale)) { BrandHeader(logoSize = 154.dp, subtitle = "CONNECT  •  STREAM  •  PLAY") }
        }
    }

    @Composable private fun OnboardingScreen() = Box(Modifier.fillMaxSize().background(Color(0xD9050705)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NeonTextButton("SKIP", compact = true) { completeOnboarding() } }
            androidx.compose.foundation.Image(painterResource(R.drawable.droidlink_logo), "Droid Link", Modifier.size(112.dp))
            val title: String
            val subtitle: String
            val detail: String
            when (onboardingPage) {
                1 -> { title = "HOST"; subtitle = "Start the game."; detail = "Create a room, allow screen capture, and share the six-digit code with Player 2." }
                2 -> { title = "JOIN"; subtitle = "Play together."; detail = "Enter the room code on another Android device and play using your physical controller." }
                else -> { title = "DROID LINK"; subtitle = "Play Together. Anywhere."; detail = "Low-latency Android-to-Android game streaming and remote multiplayer." }
            }
            Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 3.sp)
            Text(subtitle, color = neonGreen, fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center)
            Text(detail, color = mutedText, textAlign = TextAlign.Center, lineHeight = 22.sp)
            Text("${onboardingPage + 1} / 3", color = mutedText, fontSize = 12.sp)
            NeonButton(if (onboardingPage == 2) "GET STARTED" else "NEXT") { if (onboardingPage == 2) completeOnboarding() else onboardingPage++ }
        }
    }

    @Composable private fun DisconnectedScreen() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DISCONNECTED", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 3.sp)
            Text("Returning to Droid Link…", color = neonGreen)
        }
    }

    @Composable private fun MainShell() = Column(Modifier.fillMaxSize().background(Color(0xA8050705)).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeonTextButton("SETTINGS", compact = true) { mainPage = "settings" }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (mainPage) {
                "settings" -> SettingsScreen(inSession = false)
                "stats" -> ConnectionStatsSummary(showBack = false)
                "about" -> AboutScreen()
                else -> MainMenuActions()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavButton("HOME", mainPage == "home") { mainPage = "home" }
            NavButton("STATS", mainPage == "stats") { mainPage = "stats" }
            NavButton("SETTINGS", mainPage == "settings") { mainPage = "settings" }
            NavButton("ABOUT", mainPage == "about") { mainPage = "about" }
        }
    }

    @Composable private fun MainMenuActions() = BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 430.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = if (compact) 4.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BrandHeader(logoSize = if (compact) 72.dp else 116.dp, subtitle = "ANDROID-TO-ANDROID GAME STREAMING")
            Spacer(Modifier.height(if (compact) 6.dp else 18.dp))
            MainActionButton("HOST", "Stream this device") { mode = "host" }
            Spacer(Modifier.height(10.dp))
            MainActionButton("JOIN", "Enter a room code", filled = false) { mode = "client" }
            Spacer(Modifier.height(8.dp))
            ReadinessBadge()
        }
    }

    @Composable private fun AboutScreen() = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(painterResource(R.drawable.droidlink_logo), "Droid Link", Modifier.size(128.dp))
        Text("DROID LINK", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 3.sp)
        Text("2.7", color = neonGreen, fontWeight = FontWeight.Bold)
        Text("Android-to-Android low-latency game streaming and remote multiplayer.", color = mutedText, textAlign = TextAlign.Center)
        NeonButton("GITHUB", filled = false) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Aihoward/DroidLink"))) }
        SettingInfo("Build type", "Beta / Debug")
        SettingInfo("Open source", "WebRTC • AndroidX • Firebase")
        Text("Open-source notices for included libraries are available through their respective project repositories and licenses.", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
    }

    @Composable private fun BrandHeader(logoSize: androidx.compose.ui.unit.Dp, subtitle: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(painterResource(R.drawable.droidlink_logo), "Droid Link", Modifier.size(logoSize))
        Text("DROID LINK", color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 3.sp)
        Text(subtitle, color = neonGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 2.sp)
    }

    @Composable private fun HostScreen(onStart: () -> Unit, onBack: () -> Unit) = ScreenFrame("DROID LINK HOST", onBack) {
        StatusCard {
            Text("ROOM CODE", color = mutedText, fontSize = 12.sp, letterSpacing = 2.sp)
            Text(hostRoomCode.ifEmpty { "— — — — — —" }, color = neonGreen, fontWeight = FontWeight.Black, fontSize = 38.sp, letterSpacing = 6.sp)
            if (hostRoomCode.isNotEmpty()) NeonTextButton("COPY CODE", compact = true) { copyRoomCode() }
            Text(friendlyStatus(hostStatus.ifEmpty { "Ready to start" }), color = Color.White, textAlign = TextAlign.Center)
        }
        NeonButton(if (sessionStarting) "STARTING…" else "START HOST", enabled = !sessionStarting, onClick = onStart)
        PlayerSlot("PLAYER 1", "Player 1", "HOST • ${if (sessionActive) "CONNECTED" else "READY"}", true)
        PlayerSlot("PLAYER 2", "Player 2", if (sessionActive) "CONNECTED" else "WAITING", sessionActive)
        PlayerSlot("PLAYER 3", "", "COMING SOON", false)
        PlayerSlot("PLAYER 4", "", "COMING SOON", false)
        Text(captureStatus, color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
        Text(friendlyAudioStatus(), color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (isFailureStatus(hostStatus)) ErrorActions("CONNECTION FAILED", friendlyStatus(hostStatus), onRetry = onStart, onBack = onBack)
    }

    @Composable private fun JoinScreen(code: String, onCode: (String) -> Unit, onConnect: () -> Unit, onBack: () -> Unit) = ScreenFrame("DROID LINK JOIN", onBack) {
        Text("ENTER ROOM CODE", color = mutedText, fontSize = 12.sp, letterSpacing = 2.sp)
        OutlinedTextField(
            value = code, onValueChange = onCode, singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6 && !sessionStarting) onConnect() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = neonGreen, unfocusedBorderColor = Color.DarkGray, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
        )
        Text("Enter the 6-digit code shown on the host device.", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
        NeonButton(if (sessionStarting) "CONNECTING…" else "JOIN HOST", enabled = !sessionStarting, onClick = onConnect)
        StatusCard { Text(friendlyStatus(clientStatus), color = Color.White, textAlign = TextAlign.Center); Text(friendlyAudioStatus(), color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center) }
        if (isFailureStatus(clientStatus)) ErrorActions(if (clientStatus.contains("room", true)) "ROOM NOT FOUND" else "CONNECTION FAILED", friendlyStatus(clientStatus), onRetry = onConnect, onBack = onBack)
    }

    @Composable private fun VideoScreen() {
        LaunchedEffect(Unit) { Log.d(TAG, "JOIN_ACTIVE_SESSION_SHOWN") }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        releasedRenderers.remove(this)
                        setZOrderMediaOverlay(false); setZOrderOnTop(false)
                        init(clientRtc.eglContext(), object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { Log.d(TAG, "FIRST REMOTE FRAME RENDERED"); runOnUiThread { clientStatus = "Connected - video playing" } }
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) { Log.d(TAG, "Remote video frame size: ${width}x$height rotation=$rotation") }
                        })
                        setEnableHardwareScaler(true); disableFpsReduction(); setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT); setMirror(false)
                        renderer = this
                        Log.d(TAG, "JOIN_RENDERER_CREATED: renderer=${System.identityHashCode(this)} hardwareScaler=true fpsReduction=disabled directVideoSink=true")
                        remoteTrack?.let { attachTrack(this, it) }
                    }
                },
                update = { view -> remoteTrack?.let { if (renderer !== view || rendererTrack !== it) attachTrack(view, it) } },
                onRelease = { view -> releaseRenderer(view, "AndroidView onRelease") }
            )
            if (remoteTrack == null) Text(clientStatus, modifier = Modifier.align(Alignment.Center))
        }
    }

    @Composable private fun HostGameplayScreen() = Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DROID LINK HOST", color = neonGreen, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(hostRoomCode, color = Color.White, fontWeight = FontWeight.Black, fontSize = 42.sp, letterSpacing = 7.sp)
            Text(hostStatus, color = mutedText)
        }
    }

    @Composable private fun PreGameReadiness() {
        val host = mode == "host"
        val videoReady = if (host) captureStatus.contains("started", true) else remoteTrack != null
        val audioReady = audioStatus.contains("active", true) || audioStatus.contains("streaming", true)
        val controllerReady = if (host) controllerBackend.status == ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE else controlChannelOpen && lastControllerDeviceId != -1
        Box(Modifier.fillMaxSize().background(Color(0xD9000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(24.dp).background(panelBlack, RoundedCornerShape(18.dp)).border(1.dp, neonGreen, RoundedCornerShape(18.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PLAYER 2 READY", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
                ReadinessLine("VIDEO", if (videoReady) "READY" else "CHECKING", videoReady)
                ReadinessLine("AUDIO", if (audioReady) "READY" else if (audioStatus.contains("unavailable", true) || audioStatus.contains("denied", true)) "UNAVAILABLE" else "CHECKING", audioReady)
                ReadinessLine("CONTROLLER", if (controllerReady) "READY" else if (host && controllerBackend.status != ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE) "UNAVAILABLE" else "CHECKING", controllerReady)
                ReadinessLine("CONNECTION", connectionQuality(), betaDiagnostics.connectionState.contains("CONNECTED", true))
                ReadinessLine("PING", betaDiagnostics.rttMs?.let { "${it.toInt()} ms" } ?: "CHECKING", betaDiagnostics.rttMs != null)
                ReadinessLine("PACKET LOSS", betaDiagnostics.packetLoss.toString(), betaDiagnostics.packetLoss < 10)
                if (!controllerReady) Text(if (host) friendlyControllerStatus() else "Connect or move the Player 2 controller to verify input.", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (!audioReady && !audioStatus.contains("waiting", true)) Text(friendlyAudioStatus(), color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
                NeonButton("START PLAYING") { readinessVisible = false }
                NeonTextButton("CONNECTION STATS") { readinessVisible = false; sessionStatsOpen = true }
            }
        }
    }

    @Composable private fun ReadinessLine(label: String, value: String, ready: Boolean) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = mutedText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("${if (ready) "✓" else "•"} $value", color = if (ready) neonGreen else Color(0xFFFFC857), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }

    @Composable private fun SessionMenuRevealLayer() {
        LaunchedEffect(menuButtonVisible, menuRevealGeneration, sessionMenuOpen) {
            if (menuButtonVisible && !sessionMenuOpen) {
                delay(2_500L)
                menuButtonVisible = false
            }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxSize().pointerInput(sessionActive) {
                detectTapGestures { revealSessionMenuButton() }
            })
            AnimatedVisibility(
                visible = menuButtonVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
            ) {
                Box(
                    Modifier.size(48.dp).clickable { sessionMenuOpen = true }
                        .padding(6.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC080B08)).border(1.dp, neonGreen.copy(alpha = 0.75f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⋮", color = neonGreen, fontWeight = FontWeight.Black, fontSize = 24.sp, lineHeight = 24.sp)
                }
            }
        }
    }

    @Composable private fun SessionMenu() = Box(Modifier.fillMaxSize().background(Color(0x78000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 310.dp).fillMaxWidth().padding(18.dp).background(Color(0xF2111411), RoundedCornerShape(20.dp)).border(1.dp, neonGreen.copy(alpha = 0.65f), RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SESSION", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 3.sp)
            Text(if (mode == "host") "Hosting • $hostRoomCode" else "Connected to host", color = mutedText, fontSize = 11.sp)
            SessionActionButton("RESUME") { sessionMenuOpen = false; revealSessionMenuButton() }
            SessionActionButton("DIAGNOSTICS", filled = false) { sessionMenuOpen = false; sessionStatsOpen = true; controllerInputTestOpen = false }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactSessionButton("AUDIO") { sessionMenuOpen = false; sessionGameAudioOpen = true }
                CompactSessionButton("SETTINGS") { sessionMenuOpen = false; sessionSettingsOpen = true }
            }
            SessionActionButton("DISCONNECT", destructive = true) { disconnectSession() }
        }
    }

    @Composable private fun GameAudioPanel() = Box(Modifier.fillMaxSize().background(Color(0xE8000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(24.dp).background(panelBlack, RoundedCornerShape(18.dp)).border(1.dp, neonGreen, RoundedCornerShape(18.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GAME AUDIO", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
            SettingSwitch("Game Audio", gameAudioEnabled) { enabled ->
                gameAudioEnabled = enabled; hostRtc.setGameAudioEnabled(enabled); clientRtc.setGameAudioEnabled(enabled)
            }
            Text("Volume ${(gameAudioVolume * 100).toInt()}%", color = mutedText)
            Slider(value = gameAudioVolume, onValueChange = { gameAudioVolume = it; clientRtc.setGameAudioVolume(it) }, enabled = gameAudioEnabled, colors = SliderDefaults.colors(thumbColor = neonGreen, activeTrackColor = neonGreen))
            SettingInfo("Capture", friendlyAudioStatus())
            SettingInfo("RTP sent", "${betaDiagnostics.gameAudioPacketsSent} packets")
            SettingInfo("RTP received", "${betaDiagnostics.gameAudioPacketsReceived} packets")
            NeonButton("BACK TO SESSION MENU", filled = false) { sessionGameAudioOpen = false; sessionMenuOpen = true }
        }
    }

    @Composable private fun SessionStats() = Box(Modifier.fillMaxSize().background(Color(0xBB000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        if (controllerInputTestOpen) ControllerInputTestPanel() else ConnectionStatsSummary(showBack = true)
    }

    @Composable private fun ConnectionStatsSummary(showBack: Boolean) {
        val d = betaDiagnostics
        val connectionQuality = connectionQuality()
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CONNECTION STATS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
            StatSection("SESSION") { diagnosticLine("Player 1", "Host"); diagnosticLine("Player 2", "Joiner") }
            StatSection("CONNECTION") { diagnosticLine("Quality", connectionQuality); diagnosticLine("State", "${d.connectionState} / ICE ${d.iceState}"); diagnosticLine("Route", d.route); diagnosticLine("RTT / Ping", d.rttMs?.let { "%.1f ms".format(it) } ?: "—") }
            StatSection("VIDEO") {
                diagnosticLine("Resolution", d.resolution); diagnosticLine("Host capture FPS", d.captureFps?.let { "%.1f".format(it) } ?: "Unavailable on this peer")
                diagnosticLine("Requested FPS / adaptation", "${d.requestedCaptureFps ?: "Unavailable on this peer"} / level ${d.videoAdaptationLevel}")
                diagnosticLine("Host encode FPS", d.encodeFps?.let { "%.1f".format(it) } ?: "Unavailable on this peer"); diagnosticLine("Receive / decode FPS", "${d.receiveFps?.let { "%.1f".format(it) } ?: "Unavailable"} / ${d.decodeFps?.let { "%.1f".format(it) } ?: "Unavailable"}")
                diagnosticLine("Render FPS", d.renderFps?.let { "%.1f".format(it) } ?: "Unavailable from WebRTC stats"); diagnosticLine("Video bitrate", d.videoBitrateBps?.let { "${it / 1_000} kbps" } ?: "Unavailable")
                diagnosticLine("Available outbound (host only)", d.availableOutgoingBitrateBps?.let { "${it / 1_000} kbps" } ?: "Unavailable on this peer"); diagnosticLine("Packets received", d.packetsReceived.toString()); diagnosticLine("Dropped frames", d.framesDropped.toString())
                diagnosticLine("Estimated pipeline delay", d.frameAgeAtRenderMs?.let { "~%.1f ms".format(it) } ?: "Unavailable")
                diagnosticLine("Capture / encode", "${d.captureLatencyMs?.let { "%.1f ms".format(it) } ?: "—"} / ${d.averageEncodeTimeMs?.let { "%.1f ms".format(it) } ?: "—"}")
                diagnosticLine("Jitter actual / target", "${d.videoJitterBufferMs?.let { "%.1f ms".format(it) } ?: "Unavailable"} / ${d.videoJitterBufferTargetMs?.let { "%.1f ms".format(it) } ?: "Unavailable"}")
                diagnosticLine("Jitter min / observed range", "${d.videoJitterBufferMinimumMs?.let { "%.1f ms".format(it) } ?: "Unavailable"} / ${d.videoJitterBufferObservedMinMs?.let { "%.1f".format(it) } ?: "—"}–${d.videoJitterBufferObservedMaxMs?.let { "%.1f ms".format(it) } ?: "—"}")
                diagnosticLine("Jitter trend / decode", "${d.videoJitterBufferTrend} / ${d.averageDecodeTimeMs?.let { "%.1f ms".format(it) } ?: "Unavailable"}")
                diagnosticLine("Encoder / decoder", "${d.encoderImplementation} / ${d.decoderImplementation}")
                diagnosticLine("Video queues E / D / R", "${d.encoderQueueDepth ?: "Unavailable"} / ${d.decoderQueueDepth ?: "Unavailable"} / ${d.renderQueueDepth ?: "Unavailable"}")
                diagnosticLine("Bottleneck", d.videoBottleneck)
            }
            StatSection("NETWORK") { diagnosticLine("Packet loss", d.packetLoss.toString()); diagnosticLine("Jitter", d.jitterMs?.let { "%.1f ms".format(it) } ?: "—"); diagnosticLine("Path", d.route) }
            StatSection("CONTROLLER") { diagnosticLine("Player 2", d.player2Status); diagnosticLine("Latency", d.lastControllerLatencyMs?.let { "$it ms" } ?: "—"); diagnosticLine("Packet age", d.controllerPacketAgeMs?.let { "$it ms (avg ${d.averageControllerPacketAgeMs ?: it} ms)" } ?: "—"); diagnosticLine("Packets/sec", "%.1f".format(d.controllerPacketsPerSecond)) }
            StatSection("GAME AUDIO") {
                diagnosticLine("Capture", friendlyAudioStatus()); diagnosticLine("RTP sent", "${d.gameAudioPacketsSent} packets / ${d.gameAudioBytesSent} bytes")
                diagnosticLine("RTP received", "${d.gameAudioPacketsReceived} packets / ${d.gameAudioBytesReceived} bytes"); diagnosticLine("Playing", if (gameAudioEnabled) "Enabled" else "Disabled")
                diagnosticLine("Output / state", "${d.audioOutputRoute} / ${d.audioTrackState}")
                diagnosticLine("Jitter actual / target", "${d.audioJitterBufferMs?.let { "%.1f ms".format(it) } ?: "Unavailable"} / ${d.audioJitterBufferTargetMs?.let { "%.1f ms".format(it) } ?: "Unavailable"}")
                diagnosticLine("Jitter minimum", d.audioJitterBufferMinimumMs?.let { "%.1f ms".format(it) } ?: "Unavailable")
                diagnosticLine("Concealed samples / events", "${d.audioConcealedSamples} / ${d.audioConcealmentEvents}")
                diagnosticLine("Playout delay / underruns", "${d.audioPlayoutDelayMs?.let { "%.1f ms".format(it) } ?: "Unavailable"} / ${d.audioUnderruns ?: "Unavailable"}")
                diagnosticLine("A/V sync", d.avSyncMode)
            }
            var advanced by remember { mutableStateOf(false) }
            NeonTextButton(if (advanced) "HIDE ADVANCED DIAGNOSTICS" else "ADVANCED DIAGNOSTICS") { advanced = !advanced }
            if (advanced) {
                diagnosticLine("Candidate pair", d.candidatePair); diagnosticLine("Frames encoded / received / decoded / rendered", "${d.framesEncoded} / ${d.framesReceived} / ${d.framesDecoded} / ${d.framesRendered ?: "Unavailable"}")
                diagnosticLine("Encode / decode time", "${d.averageEncodeTimeMs?.let { "%.2f ms".format(it) } ?: "—"} / ${d.averageDecodeTimeMs?.let { "%.2f ms".format(it) } ?: "—"}")
                diagnosticLine("Queue digital / analog", "${d.digitalQueueDepth} / ${d.analogQueueDepth}"); diagnosticLine("DataChannel buffer", "${d.controlBufferedBytes} bytes")
                diagnosticLine("Duplicate / out-of-order", "${d.duplicateControlPacketsDropped} / ${d.outOfOrderControlPacketsDropped}")
            }
            if (showBack) NeonTextButton("RETURN TO GAME") { sessionStatsOpen = false; sessionMenuOpen = false }
        }
    }

    @Composable private fun ConnectionQualityBadge() {
        val quality = connectionQuality()
        val color = when (quality) { "POOR" -> Color(0xFFFF6B6B); "FAIR" -> Color(0xFFFFC857); "OFFLINE" -> mutedText; else -> neonGreen }
        Row(Modifier.background(Color(0xCC111411), RoundedCornerShape(20.dp)).border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("● $quality", color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            betaDiagnostics.rttMs?.let { Text("  ${it.toInt()} ms", color = mutedText, fontSize = 11.sp) }
        }
    }

    @Composable private fun ReadinessBadge() {
        val disconnected = mode == "disconnecting"
        val color = if (disconnected) Color(0xFFFFC857) else neonGreen
        Row(Modifier.background(Color(0xCC111411), RoundedCornerShape(20.dp)).border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (disconnected) "DISCONNECTED" else "● ONLINE", color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }

    @Composable private fun ErrorActions(title: String, detail: String, onRetry: () -> Unit, onBack: () -> Unit) = StatusCard {
        Text(title, color = Color(0xFFFFC857), fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text(detail, color = mutedText, textAlign = TextAlign.Center)
        NeonButton("RETRY", onClick = onRetry)
        NeonTextButton("BACK TO HOME") { onBack() }
        NeonTextButton("VIEW DIAGNOSTICS") { onBack(); mainPage = "stats" }
    }

    @Composable private fun ControllerInputTestPanel() {
        val state = controllerTestDisplayState
        Column(Modifier.background(Color(0xEE101010)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CONTROLLER TEST", color = Color.White)
            Text(if (sessionActive && mode == "host") "Remote Player 2 input path" else "Local controller input", color = neonGreen, fontSize = 11.sp)
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
            Button(onClick = {
                controllerInputTestOpen = false
                sessionStatsOpen = false
                if (sessionActive) sessionSettingsOpen = true else mainPage = "settings"
            }) { Text("Back to Settings") }
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

    @Composable private fun SettingsScreen(inSession: Boolean) {
        Column(Modifier.fillMaxSize().background(if (inSession) Color(0xE8000000) else Color.Transparent).windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SETTINGS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp, letterSpacing = 3.sp)
            SettingSection("UI COLOR") {
                Text("Accent color", color = mutedText, fontSize = 12.sp)
                listOf(listOf("Red", "Blue", "Green"), listOf("Yellow", "Purple")).forEach { colors ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        colors.forEach { colorName ->
                            FilterChip(
                                selected = accentName == colorName,
                                onClick = { setAccentColor(colorName) },
                                label = { Text(colorName, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accentColor(colorName),
                                    selectedLabelColor = Color.Black
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = accentName == colorName,
                                    borderColor = accentColor(colorName).copy(alpha = 0.7f),
                                    selectedBorderColor = accentColor(colorName)
                                )
                            )
                        }
                        if (colors.size == 2) Spacer(Modifier.weight(1f))
                    }
                }
                NeonTextButton("RESET TO DEFAULT", compact = true) { setAccentColor("Green") }
            }
            SettingSection("STREAMING") {
                Text("Quality preset", color = mutedText)
                listOf(listOf("Auto", "Low Latency"), listOf("Balanced", "High Quality")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            FilterChip(selected = qualityPreset == preset, onClick = {
                                qualityPreset = preset
                                getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putString("quality", preset).apply()
                            }, label = { Text(preset, fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                SettingInfo("Resolution / FPS", when (qualityPreset) { "Low Latency" -> "720p / 60 FPS target"; "Balanced" -> "720p / Auto FPS"; "High Quality" -> "1080p / 30 FPS target"; else -> "Automatic (recommended)" })
                SettingInfo("Bitrate", "Automatic WebRTC adaptation")
            }
            SettingSection("CONTROLLER") {
                SettingInfo("Player 2", betaDiagnostics.player2Status)
                SettingInfo("Virtual gamepad", friendlyControllerStatus())
                SettingInfo("Controller slot", "Automatic")
                SettingInfo("Stick deadzone", "Hardware/default mapping")
                SettingInfo("Players 3–4", "Coming later")
                NeonTextButton("CONTROLLER TEST") { controllerInputTestOpen = true; sessionSettingsOpen = false; sessionStatsOpen = true }
            }
            SettingSection("AUDIO") {
                SettingInfo("Game audio", friendlyAudioStatus())
                SettingInfo("Volume", "Controlled by device")
            }
            SettingSection("CONNECTION") {
                SettingInfo("Connection mode", "Automatic (recommended)")
                SettingInfo("Current route", betaDiagnostics.route)
                SettingInfo("Auto reconnect", "Enabled by session recovery")
            }
            SettingSection("APP") {
                SettingSwitch("Show intro animation", showIntroAnimation) {
                    showIntroAnimation = it; getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("show_intro", it).apply()
                }
                SettingSwitch("Keep screen awake while hosting", keepScreenAwake) {
                    keepScreenAwake = it; getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("keep_awake", it).apply()
                }
                SettingSwitch("Haptic feedback", hapticFeedback) {
                    hapticFeedback = it; getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("haptics", it).apply()
                }
                SettingSwitch("UI sound effects", uiSoundEffects) {
                    uiSoundEffects = it; getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("ui_sounds", it).apply()
                }
                SettingSwitch("Animated menu background", animatedBackground) {
                    animatedBackground = it; getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("animated_background", it).apply()
                }
                if (!inSession) NeonTextButton("SHOW FIRST-RUN GUIDE AGAIN") { onboardingPage = 0; onboardingVisible = true }
                if (!inSession) NeonTextButton("RESET SETTINGS") { resetUiSettings() }
                SettingInfo("Theme", "Droid Link Neon")
                SettingInfo("Version", "DroidLink 2.7")
            }
            if (inSession) NeonButton("BACK TO SESSION MENU", filled = false) { sessionSettingsOpen = false; sessionMenuOpen = true }
        }
    }

    @Composable private fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxSize().background(Color(0xB8050705)).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(20.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NeonTextButton("‹ BACK", compact = true, onClick = onBack)
                Text(title, Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, letterSpacing = 2.sp)
                Spacer(Modifier.width(62.dp))
            }
            Spacer(Modifier.height(6.dp)); content()
        }
    }

    @Composable private fun NeonButton(label: String, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
        val view = LocalView.current
        Button(onClick = { uiFeedback(view); onClick() }, enabled = enabled, modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (filled) neonGreen else panelBlack, contentColor = if (filled) Color.Black else neonGreen, disabledContainerColor = Color(0xFF263026)),
            border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, neonGreen)) { Text(label, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp) }
    }

    @Composable private fun MainActionButton(label: String, detail: String, filled: Boolean = true, onClick: () -> Unit) {
        val view = LocalView.current
        Button(
            onClick = { uiFeedback(view); onClick() },
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (filled) neonGreen else Color(0xEE111411), contentColor = if (filled) Color.Black else Color.White),
            border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, neonGreen.copy(alpha = 0.8f))
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
                Text(detail, fontSize = 10.sp, color = if (filled) Color.Black.copy(alpha = 0.68f) else mutedText)
            }
        }
    }

    @Composable private fun SessionActionButton(label: String, filled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
        val view = LocalView.current
        val accent = if (destructive) Color(0xFFFF6B6B) else neonGreen
        Button(
            onClick = { uiFeedback(view); onClick() },
            modifier = Modifier.width(230.dp).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (filled && !destructive) neonGreen else Color(0xFF171B17), contentColor = if (filled && !destructive) Color.Black else accent),
            border = if (filled && !destructive) null else androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.75f))
        ) { Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.5.sp) }
    }

    @Composable private fun CompactSessionButton(label: String, onClick: () -> Unit) {
        val view = LocalView.current
        OutlinedButton(
            onClick = { uiFeedback(view); onClick() },
            modifier = Modifier.width(111.dp).height(44.dp),
            shape = RoundedCornerShape(11.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF466246)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = mutedText)
        ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
    }

    @Composable private fun NeonTextButton(label: String, compact: Boolean = false, onClick: () -> Unit) { val view = LocalView.current; TextButton(onClick = { uiFeedback(view); onClick() }, modifier = if (compact) Modifier else Modifier.fillMaxWidth()) { Text(label, color = neonGreen, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 13.sp) } }

    @Composable private fun NavButton(label: String, selected: Boolean, onClick: () -> Unit) { val view = LocalView.current; Text(label, color = if (selected) neonGreen else mutedText, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable { uiFeedback(view); onClick() }.padding(14.dp)) }

    @Composable private fun StatusCard(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.widthIn(max = 460.dp).fillMaxWidth().background(panelBlack, RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF244324), RoundedCornerShape(14.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

    @Composable private fun PlayerSlot(slot: String, name: String, status: String, active: Boolean) = Row(Modifier.widthIn(max = 460.dp).fillMaxWidth().background(panelBlack, RoundedCornerShape(10.dp)).border(1.dp, if (active) neonGreen.copy(alpha = .45f) else Color.Transparent, RoundedCornerShape(10.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(slot, color = mutedText, fontSize = 10.sp, letterSpacing = 1.sp); if (name.isNotBlank()) Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Text(status, color = if (active) neonGreen else mutedText, fontSize = 11.sp, textAlign = TextAlign.End)
    }

    @Composable private fun StatSection(title: String, content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth().background(panelBlack, RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF244324), RoundedCornerShape(12.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = neonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp); content() }

    @Composable private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) = StatSection(title, content)
    @Composable private fun SettingInfo(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label, Modifier.weight(1f), color = Color.White); Text(value, color = mutedText, textAlign = TextAlign.End) }
    @Composable private fun SettingSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { val view = LocalView.current; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), color = Color.White); Switch(checked, { uiFeedback(view); onChecked(it) }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = neonGreen)) } }

    private fun completeOnboarding() {
        onboardingVisible = false
        onboardingPage = 0
        getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("onboarding_complete", true).apply()
    }

    private fun resetUiSettings() {
        showIntroAnimation = true; keepScreenAwake = true; qualityPreset = "Auto"
        animatedBackground = true; hapticFeedback = true; uiSoundEffects = true
        accentName = "Green"
        getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().clear().putBoolean("onboarding_complete", true).apply()
    }

    private fun revealSessionMenuButton() {
        menuButtonVisible = true
        menuRevealGeneration++
    }

    private fun accentColor(name: String): Color = when (name) {
        "Red" -> Color(0xFFFF4D5A)
        "Blue" -> Color(0xFF4DA3FF)
        "Yellow" -> Color(0xFFFFD740)
        "Purple" -> Color(0xFFB47CFF)
        else -> Color(0xFF55FF33)
    }

    private fun setAccentColor(name: String) {
        if (name !in ACCENT_COLORS) return
        accentName = name
        getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putString("accent_color", name).apply()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { enableImmersiveMode() }
    }

    private fun beginSessionGeneration(role: String): Long {
        sessionGeneration++
        Log.d(TAG, "SESSION_${role.uppercase()}_STARTED: generation=$sessionGeneration")
        return sessionGeneration
    }

    private fun isCurrentSession(generation: Long, code: String): Boolean = generation == sessionGeneration && activeSessionId == code

    private fun staleSessionCallback(source: String, generation: Long) {
        Log.w(TAG, "STALE_SESSION_CALLBACK_DROPPED: source=$source callbackGeneration=$generation activeGeneration=$sessionGeneration")
    }

    private fun uiFeedback(view: android.view.View, success: Boolean = false) {
        if (hapticFeedback) view.performHapticFeedback(if (success) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
        if (uiSoundEffects) view.playSoundEffect(if (success) SoundEffectConstants.NAVIGATION_UP else SoundEffectConstants.CLICK)
    }

    private fun copyRoomCode() {
        if (hostRoomCode.isEmpty()) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Droid Link room code", hostRoomCode))
        android.widget.Toast.makeText(this, "Room code copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun connectionQuality(): String {
        val d = betaDiagnostics
        if (!d.connectionState.contains("CONNECTED", true)) return "OFFLINE"
        val rtt = d.rttMs ?: 0.0
        val jitter = d.jitterMs ?: 0.0
        return when {
            rtt >= 220 || jitter >= 55 || d.packetLoss >= 20 -> "POOR"
            rtt >= 140 || jitter >= 35 || d.packetLoss >= 10 -> "FAIR"
            rtt >= 75 || jitter >= 20 || d.packetLoss >= 3 -> "GOOD"
            else -> "EXCELLENT"
        }
    }

    private fun isFailureStatus(status: String) = status.contains("failed", true) || status.contains("error", true) || status.contains("not found", true) || status.contains("denied", true)

    private fun friendlyStatus(status: String): String = when {
        status.contains("room not found", true) -> "Check the room code and try again."
        status.contains("no WebRTC offer", true) -> "The host room exists but is not ready yet. Try again shortly."
        status.contains("screen capture permission denied", true) -> "Screen capture permission is required to host a game."
        status.contains("connected, but no video", true) -> "Connected, but no remote video is currently available."
        status.contains("TURN", true) && status.contains("fail", true) -> "Network relay is unavailable. Droid Link will try a direct connection."
        status.contains("failed", true) || status.contains("error", true) -> "We couldn't reach the other device. You can retry or view diagnostics."
        else -> status
    }

    private fun friendlyAudioStatus(): String = when {
        audioStatus.contains("active", true) -> "Streaming"
        audioStatus.contains("waiting", true) || audioStatus.contains("preparing", true) || audioStatus.contains("starting", true) || audioStatus.contains("remote_track", true) -> "Checking captured game audio…"
        audioStatus.contains("silent", true) -> "Game Audio Unavailable: the current game/device may block playback capture."
        audioStatus.contains("blocked", true) -> "Game Audio Unavailable: playback capture was blocked."
        audioStatus.contains("not evaluated", true) -> "Available when a session starts"
        else -> "Audio unavailable: this app or device may not allow playback capture."
    }

    private fun friendlyControllerStatus(): String = when (controllerBackend.status) {
        ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE -> "Virtual gamepad ready"
        ControllerBackendStatus.TRANSPORT_ONLY -> "Transport connected; virtual gamepad unavailable"
        ControllerBackendStatus.PERMISSION_REQUIRED -> "Virtual gamepad permission required"
        ControllerBackendStatus.UNSUPPORTED -> "Virtual gamepad unavailable on this device"
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
        val previousView = renderer
        if (previousView != null && previousView !== view) releaseRenderer(previousView, "renderer replaced")
        rendererTrack?.let { old -> try { old.removeSink(view) } catch (_: Exception) {} }
        track.setEnabled(true)
        track.addSink(view); renderer = view; rendererTrack = track
        Log.d(TAG, "JOIN_VIDEO_SINK_ATTACHED: trackId=${track.id()} renderer=${System.identityHashCode(view)}")
        Log.d(TAG, "REMOTE VIDEO SINK ATTACHED: trackId=${track.id()} enabled=${track.enabled()} renderer=${System.identityHashCode(view)}")
    }

    private fun releaseRenderer(view: SurfaceViewRenderer, reason: String) {
        if (!releasedRenderers.add(view)) {
            Log.d(TAG, "JOIN_CRASH_GUARD: duplicate renderer release ignored renderer=${System.identityHashCode(view)} reason=$reason")
            return
        }
        val track = rendererTrack
        if (track != null) {
            try { track.removeSink(view); Log.d(TAG, "JOIN_VIDEO_SINK_REMOVED: trackId=${track.id()} renderer=${System.identityHashCode(view)} reason=$reason") }
            catch (error: Exception) { Log.w(TAG, "JOIN_CRASH_GUARD: sink removal failed renderer=${System.identityHashCode(view)} reason=$reason", error) }
        }
        try { view.release(); Log.d(TAG, "JOIN_RENDERER_RELEASED: renderer=${System.identityHashCode(view)} reason=$reason") }
        catch (error: Exception) { Log.w(TAG, "JOIN_CRASH_GUARD: renderer release failed renderer=${System.identityHashCode(view)} reason=$reason", error) }
        if (renderer === view) { renderer = null; rendererTrack = null }
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
        val generation = beginSessionGeneration("host")
        sessionStarting = true; hostStatus = "Creating room..."
        audioStatus = "Preparing playback audio capture..."
        firebase.createRoom({ code ->
            if (generation != sessionGeneration) { staleSessionCallback("create room", generation); return@createRoom }
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
            hostRtc.onDataChannelStateChanged = { label, state -> runOnUiThread {
                if (label == "droidlink-controls") controlChannelOpen = state == DataChannel.State.OPEN
                if (state == DataChannel.State.CLOSING || state == DataChannel.State.CLOSED) resetRemoteInput("DataChannel $state")
            } }
            hostRtc.onConnectionStateChanged = { state -> runOnUiThread {
                hostPeerState = state
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        mainHandler.removeCallbacks(hostDisconnectGraceRunnable)
                        hostStatus = "Connected"; sessionStarting = false; updateSessionActive(true)
                        uiFeedback(window.decorView, success = true)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        hostStatus = "Reconnecting…"
                        Log.w(TAG, "CONTROLLER_PATH_PRESERVED: transient host disconnect; virtual controller identity=${System.identityHashCode(controllerBackend)}")
                        mainHandler.removeCallbacks(hostDisconnectGraceRunnable)
                        mainHandler.postDelayed(hostDisconnectGraceRunnable, 15_000L)
                    }
                    PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> {
                        mainHandler.removeCallbacks(hostDisconnectGraceRunnable)
                        hostStatus = "Connection failed"; sessionStarting = false; resetRemoteInput("PeerConnection $state"); updateSessionActive(false)
                    }
                    else -> hostStatus = connectionText(state)
                }
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
        val generation = beginSessionGeneration("join")
        activeSessionId = code
        sessionStarting = true
        transitionJoinState(JoinSessionState.LookingForRoom, "join button pressed", "Looking for room...")
        firebase.joinRoom(code, {
            if (!isCurrentSession(generation, code)) { staleSessionCallback("join room success", generation); return@joinRoom }
            transitionJoinState(JoinSessionState.Negotiating, "room found", "Loading WebRTC offer...")
            firebase.getOffer(code, { offer -> if (isCurrentSession(generation, code)) prepareJoinPeer(code, offer, generation) else staleSessionCallback("offer loaded", generation) }, { if (isCurrentSession(generation, code)) transitionJoinFailure("Offer load failed: $it") else staleSessionCallback("offer failure", generation) })
        }, { if (isCurrentSession(generation, code)) transitionJoinFailure("Join failed: $it") else staleSessionCallback("join failure", generation) })
    }

    private fun prepareJoinPeer(code: String, offer: String, generation: Long) {
        audioStatus = "Waiting for remote game audio..."
        clientRtc.initialize()
        clientRtc.onAudioStatus = { status -> runOnUiThread { if (isCurrentSession(generation, code)) audioStatus = status else staleSessionCallback("game audio", generation) } }
        clientRtc.onControlMessageReceived = ::handleControlMessage
        clientRtc.onDataChannelStateChanged = { label, state -> runOnUiThread {
            if (!isCurrentSession(generation, code)) { staleSessionCallback("data channel", generation); return@runOnUiThread }
            if (label == "droidlink-controls") controlChannelOpen = state == DataChannel.State.OPEN
            if (state == DataChannel.State.CLOSING || state == DataChannel.State.CLOSED) Log.w(TAG, "Client DataChannel closed: $label")
        } }
        clientRtc.onDiagnostics = { update -> runOnUiThread { if (isCurrentSession(generation, code)) betaDiagnostics = mergeControllerDiagnostics(update) else staleSessionCallback("stats diagnostics", generation) } }
        clientRtc.onRemoteVideoTrack = { track -> Log.d(TAG, "Remote video track stored for renderer"); runOnUiThread {
            if (!isCurrentSession(generation, code) || mode != "client") { staleSessionCallback("remote video track", generation); return@runOnUiThread }
            remoteTrack = track
            if (joinSessionState.showsActiveSession) clientStatus = "Connected - video track received"
        } }
        clientRtc.onConnectionStateChanged = { state -> runOnUiThread {
            if (!isCurrentSession(generation, code) || mode != "client") { staleSessionCallback("PeerConnection $state", generation); return@runOnUiThread }
            clientPeerState = state
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    clientControlActive = true; sessionStarting = false
                    transitionJoinState(JoinSessionState.Connected, "PeerConnection CONNECTED", if (remoteTrack == null) "Connected - waiting for video..." else "Connected - video playing")
                    uiFeedback(window.decorView, success = true)
                    mainHandler.postDelayed({
                        if (joinSessionState.showsActiveSession && remoteTrack == null) {
                            clientStatus = "Connected, but no video track received"
                            Log.e(TAG, "VIDEO TRACK UNAVAILABLE: connected for 10 seconds without a remote video track")
                        }
                    }, 10_000L)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "CONTROLLER_CAPTURE_PRESERVED: transient disconnect; DataChannel state gates sends during in-place recovery")
                    transitionJoinState(JoinSessionState.Reconnecting, "PeerConnection DISCONNECTED", "Connection interrupted - recovering...")
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    mainHandler.postDelayed(disconnectGraceRunnable, 15_000L)
                    Log.w(TAG, "CONNECTION INTERRUPTED: preserving renderer for 15-second automatic WebRTC recovery window")
                }
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    sendNeutralReset("PeerConnection $state")
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    clientControlActive = false; sessionStarting = false
                    transitionJoinState(if (state == PeerConnection.PeerConnectionState.FAILED) JoinSessionState.Failed else JoinSessionState.Disconnected, "PeerConnection $state", "Connection failed")
                }
                PeerConnection.PeerConnectionState.CONNECTING -> transitionJoinState(JoinSessionState.Connecting, "PeerConnection CONNECTING", "Connecting...")
                else -> if (!joinSessionState.showsActiveSession) clientStatus = connectionText(state)
            }
        } }
        clientRtc.onIceCandidateReady = { candidate -> if (isCurrentSession(generation, code)) firebase.saveIceCandidate(code, "client", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) { error -> runOnUiThread { if (!joinSessionState.showsActiveSession) clientStatus = "ICE save error: $error" else Log.w(TAG, "Late ICE save error ignored after connection: $error") } } else staleSessionCallback("local ICE", generation) }
        transitionJoinState(JoinSessionState.Negotiating, "preparing PeerConnection", "Loading TURN credentials...")
        clientRtc.createPeerConnection(false, onSuccess = {
            if (!isCurrentSession(generation, code)) { staleSessionCallback("PeerConnection created", generation); return@createPeerConnection }
            firebase.listenForIceCandidates(code, "host", { c, mid, line -> clientRtc.addIceCandidate(c, mid, line) }, { error -> runOnUiThread { if (!joinSessionState.showsActiveSession) clientStatus = "ICE listen error: $error" else Log.w(TAG, "Late ICE listener error ignored after connection: $error") } })
            clientRtc.setRemoteOffer(offer, {
                if (!isCurrentSession(generation, code)) { staleSessionCallback("remote description", generation); return@setRemoteOffer }
                transitionJoinState(JoinSessionState.Negotiating, "remote offer set", "Offer found; creating answer...")
                clientRtc.createAnswer({ answer ->
                    Log.d(TAG, "Answer created")
                    firebase.saveAnswer(code, answer, { transitionJoinState(JoinSessionState.Connecting, "local answer stored", "WebRTC answer sent - connecting...") }, { transitionJoinFailure("Answer save failed: $it") })
                }, { transitionJoinFailure("Answer error: $it") })
            }, { transitionJoinFailure("Offer error: $it") })
        }, onError = { if (isCurrentSession(generation, code)) transitionJoinFailure("PeerConnection error: $it") else staleSessionCallback("PeerConnection error", generation) })
    }

    private fun transitionJoinState(next: JoinSessionState, reason: String, status: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { transitionJoinState(next, reason, status) }
            return false
        }
        val previous = joinSessionState
        if (!previous.allows(next)) {
            Log.d(TAG, "JOIN_SESSION_STATE: ignored $previous -> $next reason=$reason")
            return false
        }
        joinSessionState = next
        clientStatus = status
        Log.d(TAG, "JOIN_SESSION_STATE: $previous -> $next reason=$reason")
        Log.d(TAG, "JOIN_UI_STATE: state=$next status=$status")
        if (next.showsActiveSession && !previous.showsActiveSession) {
            updateSessionActive(true, showReadiness = false)
            Log.d(TAG, "JOIN_CONNECTING_SCREEN_HIDDEN")
        } else if (!next.showsActiveSession && previous.showsActiveSession) {
            updateSessionActive(false)
        }
        if (!next.showsActiveSession && next !in setOf(JoinSessionState.Failed, JoinSessionState.Disconnected, JoinSessionState.Idle)) Log.d(TAG, "JOIN_CONNECTING_SCREEN_SHOWN: state=$next")
        return true
    }

    private fun transitionJoinFailure(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { transitionJoinFailure(message) }; return }
        sessionStarting = false
        if (joinSessionState.showsActiveSession) Log.w(TAG, "JOIN_SESSION_STATE: late signaling failure ignored after connection: $message")
        else transitionJoinState(JoinSessionState.Failed, "signaling failure", message)
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
        if (++controlThreadLogCounter % 120 == 1) Log.d(TAG, "CONTROL_THREAD_SUMMARY: packets=$controlThreadLogCounter handlerMs=${elapsedMicros / 1000.0}")
    }

    private fun handleControlMessageInternal(message: String) {
        recordControllerPacket()
        val parts = message.split('|')
        if (parts.firstOrNull() in setOf("KEY", "AXIS", "DPAD", "RESET")) {
            lastRemoteInputPacketMs = android.os.SystemClock.elapsedRealtime()
        }
        when (parts.firstOrNull()) {
            "ACK" -> {
                val sentAt = parts.getOrNull(2)?.toLongOrNull() ?: return
                val roundTrip = (android.os.SystemClock.elapsedRealtime() - sentAt).coerceAtLeast(0L)
                val injectMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val captureMs = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                val estimatedEndToEnd = captureMs + roundTrip / 2L + injectMs
                val packetAge = captureMs + roundTrip / 2L
                lastControlRoundTripMs = roundTrip
                latestControllerLatencyMs = estimatedEndToEnd
                controllerLatencyTotalMs += estimatedEndToEnd
                controllerLatencySamples++
                controllerLatencyMaxMs = maxOf(controllerLatencyMaxMs, estimatedEndToEnd)
                recentControllerLatencies.addLast(estimatedEndToEnd)
                while (recentControllerLatencies.size > 128) recentControllerLatencies.removeFirst()
                recordControllerPacketAge(packetAge)
                if (++controllerAckLogCounter % 20 == 1) Log.d(TAG, "CONTROL_LATENCY_SUMMARY: e2eMs=$estimatedEndToEnd packetAgeMs=$packetAge captureMs=$captureMs networkMs=${roundTrip / 2L} injectMs=$injectMs")
            }
            "KEY" -> {
                val v3 = parts.size >= 10
                val v2 = parts.size >= 9
                val sequence = parts.getOrNull(if (v2) 4 else 1)?.toLongOrNull() ?: return
                val sentAt = parts.getOrNull(if (v2) 5 else 2)?.toLongOrNull() ?: return
                val captureMs = if (v2) parts[6].toLongOrNull() ?: 0L else 0L
                val senderRttMs = if (v3) parts[7].toLongOrNull() else null
                val key = parts.getOrNull(if (v3) 8 else if (v2) 7 else 3)?.toIntOrNull() ?: return
                val action = parts.getOrNull(if (v3) 9 else if (v2) 8 else 4) ?: return
                val session = if (v2) parts[1] else activeSessionId
                if (!acceptRemoteSequence("digital", session, sequence)) return
                recordControllerPacketAge(ControllerTransportPolicy.estimatedPacketAgeMs(captureMs, senderRttMs))
                val logical = ControllerMapping.logicalForAndroidKey(key)
                if (logical != null) {
                    updateLogicalButton(logical, action == "DOWN")
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
                if (!injected) logBackendUnavailableOnce()
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
                recordControllerPacketAge(estimatedAgeMs)
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
            averageControllerPacketAgeMs = current.averageControllerPacketAgeMs,
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
                    controllerPacketAgeMs = latestControllerPacketAgeMs,
                    averageControllerPacketAgeMs = if (controllerPacketAgeSamples == 0L) null else controllerPacketAgeTotalMs / controllerPacketAgeSamples
                )
            }
            controllerWindowPackets = 0; controllerWindowStart = now
        }
    }

    private fun recordControllerPacketAge(ageMs: Long) {
        latestControllerPacketAgeMs = ageMs
        controllerPacketAgeTotalMs += ageMs
        controllerPacketAgeSamples++
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
        val targetLongEdge = when (qualityPreset) { "Low Latency" -> 1280.0; "High Quality" -> 1920.0; else -> 1280.0 }
        val scale = minOf(1.0, targetLongEdge / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val refreshRate = display?.refreshRate ?: 60f
        val fps = when (qualityPreset) { "Balanced", "High Quality" -> 30; else -> if (refreshRate >= 50f) 60 else 30 }
        Log.d(TAG, "Capture profile selected: preset=$qualityPreset ${width}x$height@$fps source=${sourceWidth}x$sourceHeight refreshRate=$refreshRate")
        return Triple(width, height, fps)
    }

    private fun returnToMenu() { cleanupSession(deleteHostRoom = true); mode = "menu" }

    private fun disconnectSession() {
        mode = "disconnecting"
        cleanupSession(deleteHostRoom = true)
        lifecycleScope.launch {
            delay(700L)
            mode = "menu"
            mainPage = "home"
        }
    }

    private fun updateSessionActive(active: Boolean, showReadiness: Boolean = true) {
        if (active && !controllerHealthRunning) {
            controllerHealthRunning = true
            mainHandler.removeCallbacks(controllerHealthRunnable)
            controllerHealthTicks = 0
            mainHandler.postDelayed(controllerHealthRunnable, ControllerTransportPolicy.HEALTH_INTERVAL_MS)
            Log.d(TAG, "CONTROLLER_DIAGNOSTICS_STARTED: intervalMs=${ControllerTransportPolicy.HEALTH_INTERVAL_MS}")
        } else if (!active && controllerHealthRunning) {
            controllerHealthRunning = false
            mainHandler.removeCallbacks(controllerHealthRunnable)
            Log.d(TAG, "CONTROLLER_DIAGNOSTICS_STOPPED: session inactive")
        }
        sessionActive = active
        if (active) revealSessionMenuButton()
        sessionBackCallback.isEnabled = active || sessionMenuOpen || sessionStatsOpen || sessionSettingsOpen || sessionGameAudioOpen
        if (active && showReadiness && !readinessShownForSession) {
            readinessShownForSession = true
            readinessVisible = true
        }
        if (active && !showReadiness) {
            readinessShownForSession = true
            readinessVisible = false
            Log.d(TAG, "JOIN_UI_STATE: optional capability readiness overlay skipped for active joiner session")
        }
        if (!active) { readinessVisible = false; sessionMenuOpen = false; sessionStatsOpen = false; sessionSettingsOpen = false; sessionGameAudioOpen = false; menuButtonVisible = false; controllerInputTestOpen = false }
    }

    private fun cleanupSession(deleteHostRoom: Boolean) {
        sessionGeneration++
        controllerHealthRunning = false
        mainHandler.removeCallbacks(controllerHealthRunnable)
        Log.d(TAG, "CONTROLLER_CLEANUP_TRIGGERED: reason=session cleanup deleteHostRoom=$deleteHostRoom session=$activeSessionId")
        sendNeutralReset("session cleanup")
        resetRemoteInput("session cleanup")
        firebase.stopListening()
        if (deleteHostRoom && hostRoomCode.isNotEmpty()) firebase.deleteRoom(hostRoomCode)
        mainHandler.removeCallbacksAndMessages(null)
        // Remove the renderer sink while its receiver-owned VideoTrack and PeerConnection are still valid.
        detachRenderer(); remoteTrack = null
        hostRtc.close(); clientRtc.close()
        stopService(Intent(this, ScreenCaptureService::class.java))
        controllerBackend.close(); controllerBackend = TransportOnlyBackend()
        clientControlActive = false; controlChannelOpen = false
        if (joinSessionState != JoinSessionState.Idle) Log.d(TAG, "JOIN_SESSION_DISPOSED: previousState=$joinSessionState")
        joinSessionState = JoinSessionState.Idle
        clientPeerState = PeerConnection.PeerConnectionState.NEW; hostPeerState = PeerConnection.PeerConnectionState.NEW; sessionStarting = false
        readinessVisible = false; readinessShownForSession = false
        gameAudioEnabled = true; gameAudioVolume = 1f
        pendingCaptureIntent = null; pendingOffer = null; hostRoomCode = ""; activeSessionId = "none"
        lastAxes.fill(Float.NaN); digitalSequence = 0L; analogSequence = 0L; lastControllerDeviceId = -1; backendUnavailableLogged = false; axisAckCounter = 0
        lastControlRoundTripMs = null; controllerLatencyTotalMs = 0L; controllerLatencySamples = 0L; controllerLatencyMaxMs = 0L
        latestControllerLatencyMs = null; latestControllerPacketAgeMs = null; controllerPacketAgeTotalMs = 0L; controllerPacketAgeSamples = 0L
        recentControllerLatencies.clear(); controlThreadLogCounter = 0; controllerAckLogCounter = 0
        duplicateControlPacketsDropped = 0L; outOfOrderControlPacketsDropped = 0L; staleAnalogPacketsDropped = 0L; player2Classification = "Unknown"
        lastRemoteInputPacketMs = 0L; watchdogNeutralResets = 0L; controllerHealthTicks = 0
        logicalControllerState = ControllerInputState(); controllerTestDisplayState = ControllerInputState(); lastControllerTestUiMs = 0L
        resetLocalDpadState("session cleanup"); hostDpadState.reset(); dpadDuplicateDrops = 0L
        controllerWindowPackets = 0; controllerWindowStart = android.os.SystemClock.elapsedRealtime(); betaDiagnostics = BetaDiagnostics()
        updateSessionActive(false)
        Log.d(TAG, "Session cleanup complete")
    }

    private fun detachRenderer() {
        renderer?.let { releaseRenderer(it, "session detach") }
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
            val values = ControllerMapping.axisMap.map { normalizeAxis(event, it.androidAxis, it.trigger) }.toFloatArray()
            val rawDpad = DpadState(values[6].toInt().coerceIn(-1, 1), values[7].toInt().coerceIn(-1, 1))
            if (rawDpad != joinerDpadState.state) Log.d(TAG, "DPAD_RAW_HAT: device=${event.deviceId} state=${rawDpad.label} x=${rawDpad.x} y=${rawDpad.y}")
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
