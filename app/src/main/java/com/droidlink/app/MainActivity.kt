package com.droidlink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.hardware.input.InputManager
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "DroidLink"
        private val ACCENT_COLORS = setOf("Red", "Blue", "Green", "Yellow", "Purple")
    }

    private class RemoteInputSession(val playerSlot: Int) {
        var logicalState = ControllerInputState()
        val heldButtons = mutableSetOf<Int>()
        val lastSequences = mutableMapOf<String, Long>()
        val dpadState = DpadStateMachine()
        var lastPacketMs = 0L
        var axisAckCounter = 0
        var routeLogged = false
    }

    private val firebase = FirebaseRoomManager()
    private val hostRtc by lazy { WebRtcManager(this) }
    private val clientRtc by lazy { WebRtcManager(this) }
    private val hostRtcBySlot = java.util.concurrent.ConcurrentHashMap<Int, WebRtcManager>()
    private val hostPeerStates = java.util.concurrent.ConcurrentHashMap<Int, PeerConnection.PeerConnectionState>()
    private val hostDiagnosticsBySlot = java.util.concurrent.ConcurrentHashMap<Int, BetaDiagnostics>()
    private val hostDisconnectGraceRunnables = mutableMapOf<Int, Runnable>()
    private val hostIceRestartRunnables = mutableMapOf<Int, Runnable>()
    private val hostIceRestartAttempts = mutableMapOf<Int, Int>()
    private val hostLastIceRestartMs = mutableMapOf<Int, Long>()
    private val remoteInputSessions = java.util.concurrent.ConcurrentHashMap<Int, RemoteInputSession>()
    private val controllerBackends = java.util.concurrent.ConcurrentHashMap<Int, ControllerBackend>()
    private lateinit var menuMusicController: MenuMusicController
    private lateinit var profileStore: LocalProfileStore
    private lateinit var networkMonitor: NetworkStateMonitor
    private var controllerBackend: ControllerBackend = TransportOnlyBackend()

    private var mode by mutableStateOf("menu")
    private var hostRoomCode by mutableStateOf("")
    private var hostStatus by mutableStateOf("")
    private var hostClaimedSlots by mutableStateOf(emptySet<Int>())
    private var hostConnectedSlots by mutableStateOf(emptySet<Int>())
    private var hostDisplayNames by mutableStateOf(emptyMap<Int, String>())
    private var captureStatus by mutableStateOf("DroidLink is ready")
    private var clientStatus by mutableStateOf("Not connected")
    private var joinSessionState by mutableStateOf(JoinSessionState.Idle)
    private var remoteSlotAssignment by mutableStateOf<RemoteSlotAssignment?>(null)
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
    private var menuMusicEnabled by mutableStateOf(true)
    private var accentName by mutableStateOf("Green")
    private var selectedControllerProfile by mutableStateOf(ControllerProfile.PC_WINLATOR)
    private var localDisplayName by mutableStateOf(ProfilePolicy.DEFAULT_DISPLAY_NAME)
    private var profileAvatar by mutableStateOf<ImageBitmap?>(null)
    private var profileStatus by mutableStateOf("")
    private var onboardingVisible by mutableStateOf(false)
    private var onboardingPage by mutableIntStateOf(0)
    private var readinessVisible by mutableStateOf(false)
    private var readinessShownForSession = false
    private var controlChannelOpen by mutableStateOf(false)
    private var activeNetworkState by mutableStateOf(DroidLinkNetworkState(false, false, "NONE"))
    private var localPhysicalControllerAvailable by mutableStateOf(false)
    private var localPhysicalControllerSummary by mutableStateOf("Not detected")
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
    // Weak keys retain the double-release guard without retaining every renderer from prior sessions.
    private val releasedRenderers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<SurfaceViewRenderer, Boolean>())
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
    private val localControllerDeviceIds = mutableSetOf<Int>()
    private val controllerAxisLayouts = mutableMapOf<Int, ControllerDeviceAxisState>()
    private val dpadSources = mutableMapOf<Int, DpadSource>()
    private val joinerDpadKeys = mutableSetOf<LogicalControl>()
    private val joinerDpadState = DpadStateMachine()
    private var dpadDuplicateDrops = 0L
    private var activeSessionId = "none"
    private var sessionGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controllerHealthRunning = false
    private var controllerHealthTicks = 0
    private var watchdogNeutralResets = 0L
    private val controllerHealthRunnable = object : Runnable {
        override fun run() {
            if (!controllerHealthRunning) return
            controllerHealthTicks++
            hostRtcBySlot.forEach { (slot, rtc) -> rtc.recoverControllerChannels("P$slot periodic health check") }
            val now = android.os.SystemClock.elapsedRealtime()
            remoteInputSessions.forEach { (slot, input) ->
                val state = input.logicalState
                val axesActive = listOf(
                    state.leftX, state.leftY, state.rightX, state.rightY,
                    state.leftTrigger, state.rightTrigger, state.dpadX, state.dpadY
                ).any { kotlin.math.abs(it) > 0.05f }
                if (hostPeerStates[slot] == PeerConnection.PeerConnectionState.CONNECTED && input.lastPacketMs > 0L && axesActive &&
                    now - input.lastPacketMs >= ControllerTransportPolicy.STALE_ACTIVE_INPUT_MS
                ) {
                    watchdogNeutralResets++
                    Log.e(TAG, "P$slot CONTROLLER_WATCHDOG_RESET: staleMs=${now - input.lastPacketMs} resets=$watchdogNeutralResets virtualDeviceRecreated=false")
                    resetRemoteInput(slot, "controller packet watchdog")
                    input.lastPacketMs = now
                }
            }
            if (controllerHealthTicks % ControllerTransportPolicy.HEALTH_LOG_EVERY_TICKS == 0) {
                hostRtcBySlot.forEach { (slot, rtc) -> rtc.logControllerTransportHealth("P$slot periodic") }
                if (mode == "client") clientRtc.logControllerTransportHealth("P${remoteSlotAssignment?.playerSlot ?: 2} periodic")
                controllerBackends.forEach { (slot, backend) -> backend.logHealth("P$slot periodic") }
                Log.d(TAG, "CONTROLLER_SESSION_HEALTH: session=$activeSessionId active=$sessionActive captureEnabled=$clientControlActive physicalDeviceId=$lastControllerDeviceId hostPeers=$hostPeerStates peerClient=$clientPeerState watchdogResets=$watchdogNeutralResets")
            }
            mainHandler.postDelayed(this, ControllerTransportPolicy.HEALTH_INTERVAL_MS)
        }
    }
    private var backendUnavailableLogged = false
    private var controllerWindowStart = android.os.SystemClock.elapsedRealtime()
    private var controllerWindowPackets = 0
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
    private lateinit var sessionBackCallback: OnBackPressedCallback
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { inspectLocalControllerDevice(deviceId); inspectPlayer2Device(deviceId) }
        override fun onInputDeviceChanged(deviceId: Int) { controllerAxisLayouts.remove(deviceId); localControllerDeviceIds.remove(deviceId); inspectLocalControllerDevice(deviceId); inspectPlayer2Device(deviceId) }
        override fun onInputDeviceRemoved(deviceId: Int) {
            controllerAxisLayouts.remove(deviceId)
            localControllerDeviceIds.remove(deviceId)
            if (deviceId == lastControllerDeviceId) {
                Log.w(TAG, "LOCAL_CONTROLLER_DISCONNECTED: active=true")
                sendNeutralReset("controller disconnected")
                resetLocalDpadState("controller disconnected")
                lastAxes.fill(Float.NaN)
                lastControllerDeviceId = localControllerDeviceIds.firstOrNull() ?: -1
            }
            updateLocalControllerAvailability("removed")
        }
    }
    private val disconnectGraceRunnable = Runnable {
        if (joinSessionState == JoinSessionState.Reconnecting) {
            clientControlActive = false
            transitionJoinState(JoinSessionState.Failed, "reconnect grace expired", "Reconnection failed - retry the session")
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
    private val profileImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        profileStatus = "Updating picture..."
        lifecycleScope.launch(Dispatchers.IO) {
            val result = profileStore.importAvatar(uri)
            val avatar = result.getOrNull()?.let { profileStore.loadAvatarBitmap()?.asImageBitmap() }
            runOnUiThread {
                if (result.isSuccess && avatar != null) {
                    profileAvatar = avatar
                    profileStatus = "Picture updated"
                } else {
                    profileStatus = result.exceptionOrNull()?.message ?: "Picture could not be loaded"
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        menuMusicController = MenuMusicController(this)
        profileStore = LocalProfileStore(this)
        networkMonitor = NetworkStateMonitor(this) { state, changed -> runOnUiThread { handleNetworkState(state, changed) } }
        networkMonitor.start()
        profileStore.loadProfile().let { profile ->
            localDisplayName = profile.displayName
            if (profile.hasCustomAvatar) profileAvatar = profileStore.loadAvatarBitmap()?.asImageBitmap()
        }
        enableImmersiveMode()
        ContextCompat.registerReceiver(this, projectionReadyReceiver, IntentFilter(ScreenCaptureService.ACTION_READY), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, mainHandler)
        refreshLocalControllers("startup")
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
            qualityPreset = StreamingPresetPolicy.normalize(preferences.getString("quality", "Auto"))
            if (preferences.getString("quality", "Auto") != qualityPreset) {
                preferences.edit().putString("quality", qualityPreset).apply()
            }
            animatedBackground = preferences.getBoolean("animated_background", true)
            hapticFeedback = preferences.getBoolean("haptics", true)
            uiSoundEffects = preferences.getBoolean("ui_sounds", true)
            menuMusicEnabled = preferences.getBoolean("menu_music", true)
            accentName = preferences.getString("accent_color", "Green")?.takeIf { it in ACCENT_COLORS } ?: "Green"
            selectedControllerProfile = ControllerProfile.fromStorage(preferences.getString("controller_profile", null))
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
                LaunchedEffect(Unit) {
                    if (showIntroAnimation) delay(1_150L)
                    introVisible = false
                }
                LaunchedEffect(keepScreenAwake, sessionActive) {
                    if (keepScreenAwake && sessionActive) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                LaunchedEffect(menuMusicEnabled, mode, introVisible, onboardingVisible, sessionActive, sessionStarting, joinSessionState) {
                    syncMenuMusic()
                }
            }
        }
        requestUnbufferedControllerDispatch()
    }

    override fun onPause() {
        logControllerLifecycle("activity paused")
        menuMusicController.onBackground()
        super.onPause()
    }

    private fun syncMenuMusic() {
        val normalMenuVisible = !introVisible &&
            !onboardingVisible &&
            mode != "disconnecting" &&
            !joinSessionState.showsActiveSession
        menuMusicController.setPlaybackDesired(
            MenuMusicPolicy.shouldPlay(
                enabled = menuMusicEnabled,
                normalMenuVisible = normalMenuVisible,
                sessionActive = sessionActive,
                sessionStarting = sessionStarting
            )
        )
    }

    private fun requestUnbufferedControllerDispatch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.requestUnbufferedDispatch(
                InputDevice.SOURCE_CLASS_BUTTON or InputDevice.SOURCE_CLASS_JOYSTICK
            )
            Log.d(TAG, "CONTROLLER_UNBUFFERED_DISPATCH: button+joystick enabled")
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
                1 -> { title = "HOST"; subtitle = "Start the game."; detail = "Create a room, allow screen capture, and share the six-digit code with up to three players." }
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProfileSummary { mainPage = "profile" }
            Spacer(Modifier.weight(1f))
            val view = LocalView.current
            IconButton(
                onClick = { uiFeedback(view); mainPage = "settings" },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_preferences),
                    contentDescription = "Settings",
                    tint = if (mainPage == "settings") neonGreen else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (mainPage) {
                "profile" -> ProfileScreen()
                "settings" -> SettingsScreen(inSession = false)
                "stats" -> ConnectionStatsSummary(showBack = false)
                "about" -> AboutScreen()
                else -> MainMenuActions()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            NavButton("HOME", mainPage == "home") { mainPage = "home" }
            NavButton("STATS", mainPage == "stats") { mainPage = "stats" }
            NavButton("ABOUT", mainPage == "about") { mainPage = "about" }
        }
    }

    @Composable private fun ProfileSummary(onClick: () -> Unit) {
        val view = LocalView.current
        Row(
            Modifier.clickable { uiFeedback(view); onClick() }.padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProfileAvatar(42.dp)
            Column {
                Text(localDisplayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("PROFILE", color = mutedText, fontSize = 10.sp)
            }
        }
    }

    @Composable private fun ProfileAvatar(size: androidx.compose.ui.unit.Dp) {
        val avatar = profileAvatar
        if (avatar != null) {
            androidx.compose.foundation.Image(
                bitmap = avatar,
                contentDescription = "Profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape).border(1.dp, neonGreen.copy(alpha = 0.7f), CircleShape)
            )
        } else {
            Box(Modifier.size(size).clip(CircleShape).background(neonGreen), contentAlignment = Alignment.Center) {
                Text(
                    ProfilePolicy.normalizeDisplayName(localDisplayName).first().uppercaseChar().toString(),
                    color = Color.Black,
                    fontWeight = FontWeight.Black,
                    fontSize = if (size >= 72.dp) 30.sp else 17.sp
                )
            }
        }
    }

    @Composable private fun ProfileScreen() {
        var draftName by remember(localDisplayName) { mutableStateOf(localDisplayName) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("PROFILE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
            ProfileAvatar(96.dp)
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it.take(ProfilePolicy.MAX_DISPLAY_NAME_LENGTH) },
                label = { Text("Display name") },
                singleLine = true,
                supportingText = { Text("${draftName.length} / ${ProfilePolicy.MAX_DISPLAY_NAME_LENGTH}") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = neonGreen,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
            )
            NeonButton("SAVE NAME") {
                localDisplayName = profileStore.saveDisplayName(draftName)
                draftName = localDisplayName
                profileStatus = "Name saved"
            }
            NeonButton("CHOOSE PICTURE", filled = false) { profileImageLauncher.launch(arrayOf("image/*")) }
            if (profileAvatar != null) NeonTextButton("REMOVE PICTURE", compact = true) {
                if (profileStore.removeAvatar()) {
                    profileAvatar = null
                    profileStatus = "Picture removed"
                } else profileStatus = "Picture could not be removed"
            }
            if (profileStatus.isNotBlank()) Text(profileStatus, color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
            NeonButton("SETTINGS", filled = false) { mainPage = "settings" }
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
        Text(BuildConfig.VERSION_NAME, color = neonGreen, fontWeight = FontWeight.Bold)
        Text("Android-to-Android low-latency game streaming and remote multiplayer.", color = mutedText, textAlign = TextAlign.Center)
        Text("Menu Music: Prod. By S.P.L. BEATS", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
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
        PlayerSlot("PLAYER 1", localDisplayName, "HOST • READY", true)
        PlayerSlot("PLAYER 2", hostDisplayNames[RemotePlayerSlots.PLAYER_2] ?: "Player 2", hostSlotStatus(RemotePlayerSlots.PLAYER_2), RemotePlayerSlots.PLAYER_2 in hostConnectedSlots)
        PlayerSlot("PLAYER 3", hostDisplayNames[RemotePlayerSlots.PLAYER_3] ?: "Player 3", hostSlotStatus(RemotePlayerSlots.PLAYER_3), RemotePlayerSlots.PLAYER_3 in hostConnectedSlots)
        PlayerSlot("PLAYER 4", hostDisplayNames[RemotePlayerSlots.PLAYER_4] ?: "Player 4", hostSlotStatus(RemotePlayerSlots.PLAYER_4), RemotePlayerSlots.PLAYER_4 in hostConnectedSlots)
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
        StatusCard {
            remoteSlotAssignment?.let { Text("PLAYER ${it.playerSlot}", color = neonGreen, fontWeight = FontWeight.Bold) }
            Text(friendlyStatus(clientStatus), color = Color.White, textAlign = TextAlign.Center)
            Text(friendlyAudioStatus(), color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        if (isFailureStatus(clientStatus)) ErrorActions(
            when {
                clientStatus.contains("version mismatch", true) -> "VERSION MISMATCH"
                clientStatus.contains("room", true) -> "ROOM NOT FOUND"
                else -> "CONNECTION FAILED"
            },
            friendlyStatus(clientStatus),
            onRetry = onConnect,
            onBack = onBack
        )
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
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                Log.d(TAG, "Remote video frame size: ${width}x$height rotation=$rotation")
                                runOnUiThread {
                                    val rendered = "${width}×${height}"
                                    betaDiagnostics = betaDiagnostics.copy(resolution = rendered, renderedResolution = rendered)
                                }
                            }
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
        val transportReady = if (host) hostConnectedSlots.isNotEmpty() else controlChannelOpen
        val virtualReady = controllerBackend.status == ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE
        Box(Modifier.fillMaxSize().background(Color(0xD9000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(24.dp).background(panelBlack, RoundedCornerShape(18.dp)).border(1.dp, neonGreen, RoundedCornerShape(18.dp)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PLAYER ${if (host) hostConnectedSlots.minOrNull() ?: 2 else remoteSlotAssignment?.playerSlot ?: 2} READY", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
                ReadinessLine("VIDEO", if (videoReady) "READY" else "CHECKING", videoReady)
                ReadinessLine("AUDIO", if (audioReady) "READY" else if (audioStatus.contains("unavailable", true) || audioStatus.contains("denied", true)) "UNAVAILABLE" else "CHECKING", audioReady)
                ReadinessLine("LOCAL CONTROLLER", if (localPhysicalControllerAvailable) "READY" else "NOT DETECTED", localPhysicalControllerAvailable)
                ReadinessLine("CONTROLLER LINK", if (transportReady) "READY" else "CHECKING", transportReady)
                if (host && hostConnectedSlots.isNotEmpty()) ReadinessLine("REMOTE GAMEPAD", if (virtualReady) "READY" else "UNAVAILABLE", virtualReady)
                ReadinessLine("CONNECTION", connectionQuality(), betaDiagnostics.connectionState.contains("CONNECTED", true))
                ReadinessLine("PING", betaDiagnostics.rttMs?.let { "${it.toInt()} ms" } ?: "CHECKING", betaDiagnostics.rttMs != null)
                val recentLoss = betaDiagnostics.recentPacketLossPercent
                ReadinessLine("PACKET LOSS", recentLoss?.let { "%.2f%%".format(it) } ?: "CHECKING", recentLoss == null || recentLoss < 3.0)
                if (!localPhysicalControllerAvailable) Text("Connect an Android GAMEPAD or JOYSTICK controller. Bluetooth and USB controllers are supported when Android exposes standard gamepad capabilities.", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (host && hostConnectedSlots.isNotEmpty() && !virtualReady) Text("${friendlyControllerStatus()}. Local Player 1 input is separate; remote players require the host virtual-gamepad backend.", color = mutedText, fontSize = 12.sp, textAlign = TextAlign.Center)
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
            SessionActionButton("STATS", filled = false) { sessionMenuOpen = false; sessionStatsOpen = true; controllerInputTestOpen = false }
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
            NeonButton("BACK TO SESSION MENU", filled = false) { sessionGameAudioOpen = false; sessionMenuOpen = true }
        }
    }

    @Composable private fun SessionStats() = Box(Modifier.fillMaxSize().background(Color(0xBB000000)).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        ConnectionStatsSummary(showBack = true)
    }

    @Composable private fun ConnectionStatsSummary(showBack: Boolean) {
        val d = betaDiagnostics
        val connectionQuality = connectionQuality()
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CONNECTION STATS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 2.sp)
            StatSection("CONNECTION") {
                diagnosticLine("Quality", connectionQuality)
                diagnosticLine("Ping", d.rttMs?.let { "%.1f ms".format(it) } ?: "—")
                diagnosticLine("Network", d.activeNetworkTransport)
                diagnosticLine("Internet", when (d.internetValidated) { true -> "Validated"; false -> "Not validated"; null -> "Unknown" })
                diagnosticLine("Recovery", d.networkRecoveryStatus)
                diagnosticLine("ICE restarts", d.iceRestartAttempts.toString())
                diagnosticLine("Relay candidates", d.relayCandidatesGathered.toString())
            }
            StatSection("VIDEO") {
                diagnosticLine("Resolution", d.resolution)
                val fps = d.renderFps ?: d.receiveFps ?: d.captureFps ?: d.encodeFps
                diagnosticLine("FPS", fps?.let { "%.1f".format(it) } ?: "—")
                diagnosticLine("Bitrate", d.videoBitrateBps?.let { "%.2f Mbps".format(it / 1_000_000.0) } ?: "—")
                diagnosticLine("Packet loss", d.recentPacketLossPercent?.let { "%.2f%%".format(it) } ?: "—")
                diagnosticLine("Bottleneck", d.videoBottleneck)
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
        NeonTextButton("VIEW STATS") { onBack(); mainPage = "stats" }
    }

    @Composable private fun ControllerInputTestPanel() {
        val state = controllerTestDisplayState
        Column(Modifier.background(Color(0xEE101010)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CONTROLLER TEST", color = Color.White)
            Text(if (sessionActive && mode == "host") "Remote multiplayer input path" else "Local controller input", color = neonGreen, fontSize = 11.sp)
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
                listOf(listOf("Auto", "Low Latency"), listOf("Balanced")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            FilterChip(selected = qualityPreset == preset, onClick = {
                                qualityPreset = preset
                                getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putString("quality", preset).apply()
                            }, label = { Text(preset, fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                SettingInfo("Resolution / FPS", when (qualityPreset) { "Low Latency" -> "720p / 60 FPS target"; "Balanced" -> "720p / Auto FPS"; else -> "Automatic (recommended)" })
                SettingInfo("Bitrate", "Automatic WebRTC adaptation")
            }
            SettingSection("AUDIO") {
                SettingInfo("Game audio", friendlyAudioStatus())
                SettingInfo("Volume", "Controlled by device")
                SettingSwitch("Menu Music", menuMusicEnabled) { enabled ->
                    menuMusicEnabled = enabled
                    getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putBoolean("menu_music", enabled).apply()
                    syncMenuMusic()
                }
            }
            SettingSection("CONNECTION") {
                SettingInfo("Connection mode", "Automatic (recommended)")
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
                SettingInfo("Version", "DroidLink ${BuildConfig.VERSION_NAME}")
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
        val preferences = getSharedPreferences("droid_link_ui", MODE_PRIVATE)
        val preservedControllerProfile = preferences.getString("controller_profile", null)
        showIntroAnimation = true; keepScreenAwake = true; qualityPreset = "Auto"
        animatedBackground = true; hapticFeedback = true; uiSoundEffects = true; menuMusicEnabled = true
        accentName = "Green"
        preferences.edit().clear().putBoolean("onboarding_complete", true).putBoolean("menu_music", true).also { editor ->
            if (preservedControllerProfile != null) editor.putString("controller_profile", preservedControllerProfile)
        }.apply()
        syncMenuMusic()
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

    private fun setControllerProfile(profile: ControllerProfile) {
        if (sessionActive || sessionStarting) return
        selectedControllerProfile = profile
        getSharedPreferences("droid_link_ui", MODE_PRIVATE).edit().putString("controller_profile", profile.storageValue).apply()
        Log.d(TAG, "CONTROLLER_PROFILE_CONFIGURED: ${profile.label}")
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
        menuMusicController.onForeground()
        syncMenuMusic()
        window.decorView.post {
            enableImmersiveMode()
            refreshLocalControllers("activity resumed")
            requestUnbufferedControllerDispatch()
            hostRtcBySlot.forEach { (slot, rtc) -> rtc.recoverControllerChannels("P$slot activity resumed") }
            logControllerLifecycle("activity resumed")
        }
    }

    private fun logControllerLifecycle(reason: String) {
        if (!sessionActive && !sessionStarting) return
        Log.d(TAG, "CONTROLLER_LIFECYCLE: reason=$reason mode=$mode sessionActive=$sessionActive sessionStarting=$sessionStarting clientCapture=$clientControlActive peerClient=$clientPeerState hostPeers=$hostPeerStates")
        hostRtcBySlot.forEach { (slot, rtc) -> rtc.logControllerTransportHealth("P$slot $reason") }
        if (mode == "client") clientRtc.logControllerTransportHealth("P${remoteSlotAssignment?.playerSlot ?: 2} $reason")
        controllerBackends.forEach { (slot, backend) -> backend.logHealth("P$slot $reason") }
    }

    private fun beginSessionGeneration(role: String): Long {
        sessionGeneration++
        Log.d(TAG, "SESSION_${role.uppercase()}_STARTED: generation=$sessionGeneration")
        return sessionGeneration
    }

    private fun localParticipantMetadata() = MultiplayerParticipantMetadata(
        appVersion = BuildConfig.VERSION_NAME,
        protocolVersion = MultiplayerCompatibility.PROTOCOL_VERSION,
        displayName = ProfilePolicy.normalizeDisplayName(localDisplayName)
    )

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
        val recentLoss = d.recentPacketLossPercent ?: 0.0
        return when {
            rtt >= 220 || jitter >= 55 || recentLoss >= 8.0 -> "POOR"
            rtt >= 140 || jitter >= 35 || recentLoss >= 3.0 -> "FAIR"
            rtt >= 75 || jitter >= 20 || recentLoss >= 1.0 -> "GOOD"
            else -> "EXCELLENT"
        }
    }

    private fun isFailureStatus(status: String) = status.contains("failed", true) || status.contains("error", true) || status.contains("not found", true) || status.contains("denied", true) || status.contains("version mismatch", true)

    private fun hostSlotStatus(slot: Int) = when (slot) {
        in hostConnectedSlots -> "CONNECTED"
        in hostClaimedSlots -> "CONNECTING"
        else -> "WAITING"
    }

    private fun friendlyStatus(status: String): String = when {
        status.contains("version mismatch", true) -> "Both players must use the same DroidLink version to connect."
        status.contains("session is full", true) -> "This session already has a Host, Player 2, Player 3, and Player 4."
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
        firebase.createRoom(localParticipantMetadata(), { code ->
            if (generation != sessionGeneration) { staleSessionCallback("create room", generation); return@createRoom }
            activeSessionId = code
            hostRoomCode = code; hostStatus = "Loading TURN credentials..."
            player2Classification = "Unknown"
            controllerBackends.values.forEach(ControllerBackend::close)
            controllerBackends.clear()
            RemotePlayerSlots.controllerSlotsAtHostStart.forEach { slot ->
                ensureRemoteControllerSlot(slot, "host startup")
            }
            controllerBackend = controllerBackends.getValue(RemotePlayerSlots.PLAYER_2)
            updateControllerDiagnostics { copy(player2Status = controllerBackend.status.label, hostVirtualControllerStatus = controllerBackend.status.label) }
            schedulePlayer2Inspection()
            hostRtcBySlot[RemotePlayerSlots.PLAYER_2] = hostRtc
            hostRtc.initialize()
            configureHostPeer(RemotePlayerSlots.PLAYER_2, hostRtc, code, generation)
            firebase.listenForRemoteClaims(
                code,
                hostRoomOwner(generation),
                { claim -> runOnUiThread {
                    val slot = claim.playerSlot
                    if (!isCurrentSession(generation, code)) return@runOnUiThread staleSessionCallback("P$slot claim joined", generation)
                    ensureRemoteControllerSlot(slot, "remote slot claimed")
                    hostClaimedSlots = hostClaimedSlots + slot
                    hostDisplayNames = hostDisplayNames + (slot to claim.displayName)
                    Log.d(TAG, "P$slot joined room; stable slot assigned")
                } },
                { claim -> runOnUiThread {
                    val slot = claim.playerSlot
                    if (!isCurrentSession(generation, code)) return@runOnUiThread staleSessionCallback("P$slot claim left", generation)
                    hostClaimedSlots = hostClaimedSlots - slot
                    hostDisplayNames = hostDisplayNames - slot
                    cleanupHostRemoteSlot(slot, code, generation, "remote claim removed", rebuild = true)
                } },
                { error -> runOnUiThread { if (isCurrentSession(generation, code)) hostStatus = "Player listener error: $error" } }
            )
            hostRtc.createPeerConnection(true, onSuccess = {
                runOnUiThread {
                    hostStatus = "PeerConnection ready; waiting for screen permission..."
                    listenForHostSlotIce(code, RemotePlayerSlots.PLAYER_2, hostRtc, generation)
                    pendingOffer = { publishInitialHostOffers(code, generation) }
                    requestScreenCapture()
                }
            }, onError = { runOnUiThread { hostStatus = "PeerConnection error: $it"; sessionStarting = false } })
        }, { hostStatus = "Room error: $it"; sessionStarting = false })
    }

    private fun publishInitialHostOffers(code: String, generation: Long) {
        if (!isCurrentSession(generation, code)) return staleSessionCallback("initial host offers", generation)
        publishHostOffer(code, RemotePlayerSlots.PLAYER_2, hostRtc, generation)
        RemotePlayerSlots.activeRemoteSlots.drop(1).forEach { slot ->
            prepareHostSlot(code, slot, generation)
        }
    }

    private fun prepareHostSlot(code: String, slot: Int, generation: Long) {
        if (!isCurrentSession(generation, code) || !SessionSecurityPolicy.validRemoteSlot(slot)) return
        val media = hostRtc.sharedHostMedia() ?: run {
            hostStatus = "Shared host video is unavailable"
            Log.e(TAG, "P$slot host media attach failed: capture track unavailable")
            return
        }
        val rtc = if (slot == RemotePlayerSlots.PLAYER_2) hostRtc else WebRtcManager(this)
        hostRtcBySlot[slot] = rtc
        if (slot == RemotePlayerSlots.PLAYER_2) rtc.initialize()
        else {
            val sharedResources = hostRtc.sharedWebRtcResources() ?: run {
                hostStatus = "Shared WebRTC resources are unavailable"
                return
            }
            rtc.initialize(sharedResources)
        }
        configureHostPeer(slot, rtc, code, generation)
        rtc.createPeerConnection(true, onSuccess = {
            runOnUiThread {
                if (!isCurrentHostPeer(slot, rtc, code, generation)) return@runOnUiThread staleSessionCallback("P$slot peer created", generation)
                try {
                    rtc.attachSharedHostMedia(media)
                    listenForHostSlotIce(code, slot, rtc, generation)
                    publishHostOffer(code, slot, rtc, generation)
                    Log.d(TAG, "P$slot PeerConnection created with shared capture")
                } catch (error: Throwable) {
                    hostStatus = "Player $slot media error: ${error.message}"
                    Log.e(TAG, "P$slot shared host media setup failed", error)
                }
            }
        }, onError = { error -> runOnUiThread {
            if (isCurrentSession(generation, code)) hostStatus = "Player $slot PeerConnection error: $error"
        } })
    }

    private fun publishHostOffer(code: String, slot: Int, rtc: WebRtcManager, generation: Long) {
        if (!isCurrentHostPeer(slot, rtc, code, generation)) return
        hostStatus = "Preparing Player $slot..."
        rtc.createOffer({ offer ->
            if (!isCurrentHostPeer(slot, rtc, code, generation)) return@createOffer staleSessionCallback("P$slot offer", generation)
            firebase.saveOffer(code, slot, offer, {
                if (!isCurrentHostPeer(slot, rtc, code, generation)) return@saveOffer
                hostStatus = if (hostConnectedSlots.isEmpty()) "Waiting for players" else hostStatus
                firebase.listenForAnswer(code, slot, hostSlotOwner(slot, generation), { answer ->
                    if (!isCurrentHostPeer(slot, rtc, code, generation)) return@listenForAnswer staleSessionCallback("P$slot answer", generation)
                    rtc.setRemoteAnswer(answer, { Log.d(TAG, "P$slot answer applied; connecting") }, { hostStatus = "Player $slot answer error: $it" })
                }, { hostStatus = "Player $slot answer listen error: $it" })
            }, { hostStatus = "Player $slot offer save failed: $it" })
        }, { hostStatus = "Player $slot offer error: $it" })
    }

    private fun configureHostPeer(slot: Int, rtc: WebRtcManager, code: String, generation: Long) {
        rtc.onControlMessageReceived = { message -> handleControlMessage(slot, rtc, message) }
        rtc.onAudioStatus = { status -> if (slot == RemotePlayerSlots.PLAYER_2) runOnUiThread { audioStatus = status } }
        rtc.onDiagnostics = { update ->
            hostDiagnosticsBySlot[slot] = update
            if (slot == RemotePlayerSlots.PLAYER_2) runOnUiThread { betaDiagnostics = mergeControllerDiagnostics(update) }
        }
        rtc.onDataChannelStateChanged = { label, state -> runOnUiThread {
            if (!isCurrentHostPeer(slot, rtc, code, generation)) return@runOnUiThread
            Log.d(TAG, "P$slot DataChannel $label $state")
            if (state == DataChannel.State.CLOSING || state == DataChannel.State.CLOSED) resetRemoteInput(slot, "DataChannel $state")
        } }
        rtc.onConnectionStateChanged = { state -> runOnUiThread {
            if (!isCurrentHostPeer(slot, rtc, code, generation)) return@runOnUiThread staleSessionCallback("P$slot PeerConnection $state", generation)
            hostPeerStates[slot] = state
            if (slot == RemotePlayerSlots.PLAYER_2) hostPeerState = state
            Log.d(TAG, "P$slot PeerConnection $state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    cancelHostDisconnectGrace(slot)
                    cancelHostIceRestart(slot)
                    hostIceRestartAttempts.remove(slot)
                    hostLastIceRestartMs.remove(slot)
                    hostConnectedSlots = hostConnectedSlots + slot
                    hostStatus = connectedPlayersStatus()
                    sessionStarting = false
                    updateSessionActive(true)
                    uiFeedback(window.decorView, success = true)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    hostStatus = if (activeNetworkState.available) "Player $slot connection lost — retrying" else "No internet connection"
                    scheduleHostDisconnectGrace(slot, rtc, code, generation)
                    scheduleHostIceRestart(slot, rtc, code, generation, "PeerConnection disconnected")
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    hostConnectedSlots = hostConnectedSlots - slot
                    resetRemoteInput(slot, "PeerConnection FAILED")
                    scheduleHostDisconnectGrace(slot, rtc, code, generation)
                    scheduleHostIceRestart(slot, rtc, code, generation, "PeerConnection failed")
                    hostStatus = if (activeNetworkState.available) "Player $slot connection failed — retrying" else "No internet connection"
                }
                PeerConnection.PeerConnectionState.CLOSED -> {
                    cancelHostDisconnectGrace(slot)
                    cancelHostIceRestart(slot)
                    hostConnectedSlots = hostConnectedSlots - slot
                    resetRemoteInput(slot, "PeerConnection $state")
                    refreshHostSessionState()
                }
                else -> if (hostConnectedSlots.isEmpty()) hostStatus = "Player $slot ${connectionText(state)}"
            }
        } }
        rtc.onIceCandidateReady = { candidate ->
            if (isCurrentHostPeer(slot, rtc, code, generation)) {
                firebase.saveIceCandidate(code, slot, "host", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) {
                    runOnUiThread { if (isCurrentHostPeer(slot, rtc, code, generation)) hostStatus = "Player $slot ICE save error: $it" }
                }
            }
        }
    }

    private fun listenForHostSlotIce(code: String, slot: Int, rtc: WebRtcManager, generation: Long) {
        firebase.listenForIceCandidates(
            code, slot, "client", hostSlotOwner(slot, generation),
            { candidate, mid, line -> if (isCurrentHostPeer(slot, rtc, code, generation)) rtc.addIceCandidate(candidate, mid, line) },
            { error -> runOnUiThread { if (isCurrentHostPeer(slot, rtc, code, generation)) hostStatus = "Player $slot ICE listen error: $error" } }
        )
    }

    private fun cleanupHostRemoteSlot(slot: Int, code: String, generation: Long, reason: String, rebuild: Boolean) {
        val rtc = hostRtcBySlot[slot] ?: return
        Log.d(TAG, "P$slot isolated cleanup started: reason=$reason")
        cancelHostDisconnectGrace(slot)
        cancelHostIceRestart(slot)
        if (hostRtc.sharedHostMedia() == null) {
            resetRemoteInput(slot, reason)
            hostPeerStates.remove(slot)
            hostConnectedSlots = hostConnectedSlots - slot
            Log.d(TAG, "P$slot cleanup deferred until initial host capture is ready")
            refreshHostSessionState()
            return
        }
        firebase.stopListening(hostSlotOwner(slot, generation))
        resetRemoteInput(slot, reason)
        hostPeerStates.remove(slot)
        hostConnectedSlots = hostConnectedSlots - slot
        if (slot == RemotePlayerSlots.PLAYER_2) rtc.closePeerConnectionPreservingLocalMedia()
        else {
            hostRtcBySlot.remove(slot)
            rtc.release()
        }
        refreshHostSessionState()
        firebase.clearSlotSignaling(code, slot) {
            runOnUiThread {
                if (rebuild && isCurrentSession(generation, code)) {
                    prepareHostSlot(code, slot, generation)
                    Log.d(TAG, "P$slot disconnected - isolated cleanup complete; slot prepared for reuse")
                }
            }
        }
    }

    private fun scheduleHostDisconnectGrace(slot: Int, rtc: WebRtcManager, code: String, generation: Long) {
        cancelHostDisconnectGrace(slot)
        val runnable = Runnable {
            val state = hostPeerStates[slot]
            if (isCurrentHostPeer(slot, rtc, code, generation) &&
                (state == PeerConnection.PeerConnectionState.DISCONNECTED || state == PeerConnection.PeerConnectionState.FAILED)) {
                Log.e(TAG, "P$slot reconnect grace expired; other remote players preserved")
                cleanupHostRemoteSlot(slot, code, generation, "network recovery expired", rebuild = true)
            }
        }
        hostDisconnectGraceRunnables[slot] = runnable
        mainHandler.postDelayed(runnable, NetworkRecoveryPolicy.FAILED_SESSION_GRACE_MS)
    }

    private fun cancelHostDisconnectGrace(slot: Int) {
        hostDisconnectGraceRunnables.remove(slot)?.let(mainHandler::removeCallbacks)
    }

    private fun scheduleHostIceRestart(slot: Int, rtc: WebRtcManager, code: String, generation: Long, reason: String) {
        if (!isCurrentHostPeer(slot, rtc, code, generation)) return
        cancelHostIceRestart(slot)
        val runnable = Runnable { performHostIceRestart(slot, rtc, code, generation, reason) }
        hostIceRestartRunnables[slot] = runnable
        mainHandler.postDelayed(runnable, NetworkRecoveryPolicy.NORMAL_RECOVERY_GRACE_MS)
        Log.w(TAG, "ICE_RESTART_SCHEDULED: player=$slot delayMs=${NetworkRecoveryPolicy.NORMAL_RECOVERY_GRACE_MS} reason=$reason")
    }

    private fun performHostIceRestart(slot: Int, rtc: WebRtcManager, code: String, generation: Long, reason: String) {
        hostIceRestartRunnables.remove(slot)
        if (!isCurrentHostPeer(slot, rtc, code, generation)) return
        val state = hostPeerStates[slot]
        if (state != PeerConnection.PeerConnectionState.DISCONNECTED && state != PeerConnection.PeerConnectionState.FAILED) return
        val now = android.os.SystemClock.elapsedRealtime()
        val attempts = hostIceRestartAttempts[slot] ?: 0
        val since = now - (hostLastIceRestartMs[slot] ?: (now - NetworkRecoveryPolicy.RESTART_COOLDOWN_MS))
        if (!NetworkRecoveryPolicy.canRestart(attempts, since, activeNetworkState.available)) {
            val cooldownRemaining = (NetworkRecoveryPolicy.RESTART_COOLDOWN_MS - since).coerceAtLeast(0L)
            Log.w(TAG, "ICE_RESTART_SKIPPED: player=$slot attempts=$attempts networkAvailable=${activeNetworkState.available} cooldownRemainingMs=$cooldownRemaining")
            if (activeNetworkState.available && attempts < NetworkRecoveryPolicy.MAX_ICE_RESTARTS_PER_DISCONNECT && cooldownRemaining > 0L) {
                val runnable = Runnable { performHostIceRestart(slot, rtc, code, generation, reason) }
                hostIceRestartRunnables[slot] = runnable
                mainHandler.postDelayed(runnable, cooldownRemaining)
            }
            return
        }
        hostIceRestartAttempts[slot] = attempts + 1
        hostLastIceRestartMs[slot] = now
        betaDiagnostics = betaDiagnostics.copy(networkRecoveryStatus = "Retrying Player $slot", iceRestartAttempts = attempts + 1)
        rtc.restartIceAndCreateOffer({ offer ->
            if (!isCurrentHostPeer(slot, rtc, code, generation)) return@restartIceAndCreateOffer
            firebase.saveOffer(code, slot, offer, {
                Log.w(TAG, "ICE_RESTART_OFFER_PUBLISHED: player=$slot attempt=${attempts + 1} reason=$reason")
                scheduleHostIceRestart(slot, rtc, code, generation, "previous ICE restart did not recover")
            }, { error ->
                Log.e(TAG, "ICE_RESTART_SIGNALING_FAILED: player=$slot attempt=${attempts + 1} category=signaling error=$error")
                scheduleHostIceRestart(slot, rtc, code, generation, "ICE restart offer save failed")
            })
        }, { error ->
            Log.e(TAG, "ICE_RESTART_FAILED: player=$slot attempt=${attempts + 1} error=$error")
            scheduleHostIceRestart(slot, rtc, code, generation, "ICE restart offer creation failed")
        })
    }

    private fun cancelHostIceRestart(slot: Int) {
        hostIceRestartRunnables.remove(slot)?.let(mainHandler::removeCallbacks)
    }

    private fun refreshHostSessionState() {
        if (mode != "host") return
        val anyConnected = hostConnectedSlots.isNotEmpty()
        sessionStarting = !anyConnected && hostRoomCode.isNotEmpty()
        updateSessionActive(anyConnected)
        hostStatus = if (anyConnected) connectedPlayersStatus() else "Waiting for players"
    }

    private fun connectedPlayersStatus() = hostConnectedSlots.sorted().joinToString(prefix = "Connected: ") { "P$it" }

    private fun isCurrentHostPeer(slot: Int, rtc: WebRtcManager, code: String, generation: Long) =
        isCurrentSession(generation, code) && hostRtcBySlot[slot] === rtc

    private fun hostRoomOwner(generation: Long) = "host-room-$generation"
    private fun hostSlotOwner(slot: Int, generation: Long) = "host-P$slot-$generation"
    private fun joinSlotOwner(assignment: RemoteSlotAssignment, generation: Long) = "join-P${assignment.playerSlot}-${assignment.joinerId}-$generation"

    private fun startJoin(code: String) {
        if (code.length != 6) { clientStatus = "Enter a 6-digit room code"; return }
        if (sessionStarting) return
        cleanupSession(deleteHostRoom = true)
        val generation = beginSessionGeneration("join")
        activeSessionId = code
        sessionStarting = true
        transitionJoinState(JoinSessionState.LookingForRoom, "join button pressed", "Looking for room...")
        val joinerId = java.util.UUID.randomUUID().toString()
        firebase.claimRemoteSlot(code, joinerId, localParticipantMetadata(), { assignment ->
            if (!isCurrentSession(generation, code)) {
                firebase.releaseRemoteSlot(code, assignment)
                staleSessionCallback("join slot claim", generation)
                return@claimRemoteSlot
            }
            remoteSlotAssignment = assignment
            transitionJoinState(JoinSessionState.Negotiating, "P${assignment.playerSlot} assigned", "Player ${assignment.playerSlot} assigned - waiting for host...")
            firebase.listenForOffer(code, assignment.playerSlot, joinSlotOwner(assignment, generation), { offer ->
                if (isCurrentSession(generation, code) && remoteSlotAssignment == assignment) {
                    if (clientRtc.hasPeerConnection()) applyJoinRestartOffer(code, offer, assignment, generation)
                    else prepareJoinPeer(code, offer, assignment, generation)
                }
                else staleSessionCallback("P${assignment.playerSlot} offer", generation)
            }, { error -> if (isCurrentSession(generation, code)) transitionJoinFailure("Offer load failed: $error") })
        }, { if (isCurrentSession(generation, code)) transitionJoinFailure("Join failed: $it") else staleSessionCallback("join failure", generation) })
    }

    private fun prepareJoinPeer(code: String, offer: String, assignment: RemoteSlotAssignment, generation: Long) {
        val slot = assignment.playerSlot
        audioStatus = "Waiting for remote game audio..."
        clientRtc.initialize()
        clientRtc.onAudioStatus = { status -> runOnUiThread { if (isCurrentSession(generation, code)) audioStatus = status else staleSessionCallback("game audio", generation) } }
        clientRtc.onControlMessageReceived = { message -> handleControlMessage(null, clientRtc, message) }
        clientRtc.onDataChannelStateChanged = { label, state -> runOnUiThread {
            if (!isCurrentSession(generation, code)) { staleSessionCallback("data channel", generation); return@runOnUiThread }
            if (label == "droidlink-controls") {
                controlChannelOpen = state == DataChannel.State.OPEN
                updateControllerDiagnostics { copy(remoteControllerTransportStatus = if (controlChannelOpen) "Connected" else state.name) }
            }
            Log.d(TAG, "P$slot client DataChannel $label $state")
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
                    mainHandler.postDelayed(disconnectGraceRunnable, NetworkRecoveryPolicy.FAILED_SESSION_GRACE_MS)
                    Log.w(TAG, "CONNECTION INTERRUPTED: preserving renderer for ${NetworkRecoveryPolicy.FAILED_SESSION_GRACE_MS}-ms automatic WebRTC recovery window")
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    sendNeutralReset("PeerConnection FAILED")
                    transitionJoinState(JoinSessionState.Reconnecting, "PeerConnection FAILED", if (activeNetworkState.available) "Connection lost — retrying" else "No internet connection")
                    mainHandler.removeCallbacks(disconnectGraceRunnable)
                    mainHandler.postDelayed(disconnectGraceRunnable, NetworkRecoveryPolicy.FAILED_SESSION_GRACE_MS)
                }
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
        clientRtc.onIceCandidateReady = { candidate -> if (isCurrentSession(generation, code)) firebase.saveIceCandidate(code, slot, "client", candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex) { error -> runOnUiThread { if (!joinSessionState.showsActiveSession) clientStatus = "ICE save error: $error" else Log.w(TAG, "Late P$slot ICE save error ignored after connection: $error") } } else staleSessionCallback("local ICE", generation) }
        transitionJoinState(JoinSessionState.Negotiating, "preparing PeerConnection", "Loading TURN credentials...")
        clientRtc.createPeerConnection(false, onSuccess = {
            if (!isCurrentSession(generation, code)) { staleSessionCallback("PeerConnection created", generation); return@createPeerConnection }
            firebase.listenForIceCandidates(code, slot, "host", joinSlotOwner(assignment, generation), { c, mid, line -> clientRtc.addIceCandidate(c, mid, line) }, { error -> runOnUiThread { if (!joinSessionState.showsActiveSession) clientStatus = "ICE listen error: $error" else Log.w(TAG, "Late P$slot ICE listener error ignored after connection: $error") } })
            clientRtc.setRemoteOffer(offer, {
                if (!isCurrentSession(generation, code)) { staleSessionCallback("remote description", generation); return@setRemoteOffer }
                transitionJoinState(JoinSessionState.Negotiating, "remote offer set", "Offer found; creating answer...")
                clientRtc.createAnswer({ answer ->
                    Log.d(TAG, "Answer created")
                    firebase.saveAnswer(code, slot, answer, { transitionJoinState(JoinSessionState.Connecting, "P$slot local answer stored", "WebRTC answer sent - connecting as Player $slot...") }, { transitionJoinFailure("Answer save failed: $it") })
                }, { transitionJoinFailure("Answer error: $it") })
            }, { transitionJoinFailure("Offer error: $it") })
        }, onError = { if (isCurrentSession(generation, code)) transitionJoinFailure("PeerConnection error: $it") else staleSessionCallback("PeerConnection error", generation) })
    }

    private fun applyJoinRestartOffer(code: String, offer: String, assignment: RemoteSlotAssignment, generation: Long) {
        if (!isCurrentSession(generation, code) || remoteSlotAssignment != assignment) return
        Log.w(TAG, "ICE_RESTART_OFFER_RECEIVED: player=${assignment.playerSlot}")
        if (joinSessionState == JoinSessionState.Connected) {
            transitionJoinState(JoinSessionState.Reconnecting, "ICE restart offer received", "Connection lost — retrying")
        }
        clientRtc.setRemoteOffer(offer, {
            clientRtc.createAnswer({ answer ->
                firebase.saveAnswer(code, assignment.playerSlot, answer, {
                    Log.w(TAG, "ICE_RESTART_ANSWER_PUBLISHED: player=${assignment.playerSlot}")
                }, { error -> Log.e(TAG, "ICE_RESTART_SIGNALING_FAILED: side=joiner category=answer-save error=$error") })
            }, { error -> Log.e(TAG, "ICE_RESTART_FAILED: side=joiner category=answer-create error=$error") })
        }, { error -> Log.e(TAG, "ICE_RESTART_FAILED: side=joiner category=remote-offer error=$error") })
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
        else {
            remoteSlotAssignment?.let { assignment ->
                firebase.stopListening(joinSlotOwner(assignment, sessionGeneration))
                firebase.releaseRemoteSlot(activeSessionId, assignment)
            }
            remoteSlotAssignment = null
            clientRtc.close()
            transitionJoinState(JoinSessionState.Failed, "signaling failure", message)
        }
    }

    private fun connectionText(state: PeerConnection.PeerConnectionState) = when (state) {
        PeerConnection.PeerConnectionState.CONNECTED -> "Connected!"
        PeerConnection.PeerConnectionState.CONNECTING -> "Connecting..."
        PeerConnection.PeerConnectionState.FAILED -> "Connection failed"
        PeerConnection.PeerConnectionState.DISCONNECTED -> "Disconnected"
        PeerConnection.PeerConnectionState.CLOSED -> "Closed"
        else -> "WebRTC: $state"
    }

    private fun handleControlMessage(playerSlot: Int?, replyRtc: WebRtcManager, message: String) {
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        playerSlot?.takeIf { remoteInputSessions[it] == null || controllerBackends[it] == null }
            ?.let { ensureRemoteControllerSlot(it, "first control message") }
        val input = playerSlot?.let { remoteInputSessions[it] ?: return }
        if (input == null) handleControlMessageInternal(playerSlot, replyRtc, null, message)
        else synchronized(input) { handleControlMessageInternal(playerSlot, replyRtc, input, message) }
        val elapsedMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        if (++controlThreadLogCounter % 120 == 1) Log.d(TAG, "CONTROL_THREAD_SUMMARY: player=${playerSlot?.let { "P$it" } ?: "local"} packets=$controlThreadLogCounter handlerMs=${elapsedMicros / 1000.0}")
    }

    private fun handleControlMessageInternal(playerSlot: Int?, replyRtc: WebRtcManager, input: RemoteInputSession?, message: String) {
        recordControllerPacket()
        val parts = message.split('|')
        val packetType = parts.firstOrNull()
        if (packetType in setOf("KEY", "AXIS", "DPAD", "RESET")) {
            if (input == null) return
            if (!input.routeLogged) {
                input.routeLogged = true
                Log.d(TAG, "REMOTE_INPUT_ROUTE_VERIFIED: receivedFrom=P${input.playerSlot} routedTo=P${input.playerSlot} virtualController=${RemotePlayerSlots.controllerDeviceName(input.playerSlot)}")
            }
            input.lastPacketMs = android.os.SystemClock.elapsedRealtime()
        }
        when (packetType) {
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
                val remote = input ?: return
                val v3 = parts.size >= 10
                val v2 = parts.size >= 9
                val sequence = parts.getOrNull(if (v2) 4 else 1)?.toLongOrNull() ?: return
                val sentAt = parts.getOrNull(if (v2) 5 else 2)?.toLongOrNull() ?: return
                val captureMs = if (v2) parts[6].toLongOrNull() ?: 0L else 0L
                val senderRttMs = if (v3) parts[7].toLongOrNull() else null
                val key = parts.getOrNull(if (v3) 8 else if (v2) 7 else 3)?.toIntOrNull() ?: return
                val action = parts.getOrNull(if (v3) 9 else if (v2) 8 else 4) ?: return
                val session = if (v2) parts[1] else activeSessionId
                val declaredSlot = if (v2) parts.getOrNull(3)?.toIntOrNull() else playerSlot
                if (!RemotePlayerSlots.packetMatches(remote.playerSlot, declaredSlot)) { Log.w(TAG, "P$playerSlot CONTROL_SLOT_MISMATCH_DROPPED: declared=$declaredSlot type=KEY"); return }
                if (!acceptRemoteSequence(remote, "digital", session, sequence)) return
                recordControllerPacketAge(ControllerTransportPolicy.estimatedPacketAgeMs(captureMs, senderRttMs))
                val logical = ControllerMapping.logicalForAndroidKey(key)
                if (logical != null) {
                    updateLogicalButton(remote, logical, action == "DOWN")
                }
                val token = key
                if (action == "DOWN" && !remote.heldButtons.add(token)) { recordDuplicateDrop(); Log.w(TAG, "P$playerSlot CONTROL_DUPLICATE_DROPPED: key=$key action=$action"); return }
                if (action == "UP" && !remote.heldButtons.remove(token)) { recordDuplicateDrop(); Log.w(TAG, "P$playerSlot CONTROL_DUPLICATE_DROPPED: key=$key action=$action"); return }
                val started = android.os.SystemClock.elapsedRealtime()
                val context = ControllerEventContext(session, if (v2) parts[2] else "legacy", remote.playerSlot, sequence, sentAt)
                val backend = controllerBackends[remote.playerSlot] ?: return
                if (backend is DolphinVirtualGamepadBackend) {
                    Log.d(TAG, "PLAYER_${playerSlot}_PACKET_RECEIVED: player=${context.playerSlot} controller=${context.controllerId} type=BUTTON key=${KeyEvent.keyCodeToString(key)} action=$action sequence=$sequence")
                }
                if (logical != null && isDpadControl(logical)) {
                    val legacyDpad = DpadState(
                        x = (if (KeyEvent.KEYCODE_DPAD_RIGHT in remote.heldButtons) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_LEFT in remote.heldButtons) 1 else 0),
                        y = (if (KeyEvent.KEYCODE_DPAD_DOWN in remote.heldButtons) 1 else 0) - (if (KeyEvent.KEYCODE_DPAD_UP in remote.heldButtons) 1 else 0)
                    )
                    val changed = remote.dpadState.update(legacyDpad)
                    val injected = !changed || backend.updateDpad(context, legacyDpad.x, legacyDpad.y)
                    updateLogicalDpad(remote, legacyDpad)
                    val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    replyRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                    Log.d(TAG, "DPAD_LOGICAL_STATE: side=host player=$playerSlot state=${legacyDpad.label} source=LEGACY_KEY sequence=$sequence changed=$changed")
                    if (!injected) logBackendUnavailableOnce()
                    return
                }
                val injected = if (action == "DOWN") backend.keyDown(context, key) else if (action == "UP") backend.keyUp(context, key) else false
                val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                replyRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                if (!injected) logBackendUnavailableOnce()
            }
            "AXIS" -> {
                val remote = input ?: return
                val v3 = parts.size >= 16
                val v2 = parts.size >= 15
                val sequence = parts.getOrNull(if (v2) 4 else 1)?.toLongOrNull() ?: return
                val sentAt = parts.getOrNull(if (v2) 5 else 2)?.toLongOrNull() ?: return
                val captureMs = if (v2) parts[6].toLongOrNull() ?: 0L else 0L
                val session = if (v2) parts[1] else activeSessionId
                val declaredSlot = if (v2) parts.getOrNull(3)?.toIntOrNull() else playerSlot
                if (!RemotePlayerSlots.packetMatches(remote.playerSlot, declaredSlot)) { Log.w(TAG, "P$playerSlot CONTROL_SLOT_MISMATCH_DROPPED: declared=$declaredSlot type=AXIS"); return }
                if (!acceptRemoteSequence(remote, "analog", session, sequence)) return
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
                    updateLogicalAxes(remote, axes)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val context = ControllerEventContext(session, if (v2) parts[2] else "legacy", remote.playerSlot, sequence, sentAt)
                    val backend = controllerBackends[remote.playerSlot] ?: return
                    if (backend is DolphinVirtualGamepadBackend && sequence % 120L == 1L) {
                        Log.d(TAG, "PLAYER_${playerSlot}_PACKET_RECEIVED: player=${context.playerSlot} controller=${context.controllerId} type=AXIS main=${axes[0]},${axes[1]} c=${axes[2]},${axes[3]} triggers=${axes[4]},${axes[5]} sequence=$sequence")
                    }
                    val injected = backend.updateAxes(context, axes[0], axes[1], axes[2], axes[3], axes[4], axes[5], axes[6], axes[7])
                    val injectMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    if (++remote.axisAckCounter % 20 == 1) replyRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                    if (injected && remote.axisAckCounter % 20 == 1) {
                        Log.d(TAG, "LOGICAL_CONTROL_EVENT: control=AXES lx=${axes[0]} ly=${axes[1]} rx=${axes[2]} ry=${axes[3]} lt=${axes[4]} rt=${axes[5]} dpad=${axes[6]},${axes[7]}")
                        Log.d(TAG, "CONTROL_AXIS_STATE: sequence=$sequence lx=${axes[0]} ly=${axes[1]} rx=${axes[2]} ry=${axes[3]} lt=${axes[4]} rt=${axes[5]} dpadX=${axes[6]} dpadY=${axes[7]}")
                        Log.d(TAG, "CONTROL_RECEIVE_TO_INJECT_MS: $injectMs")
                    } else if (!injected) logBackendUnavailableOnce()
                }
            }
            "DPAD" -> {
                val remote = input ?: return
                if (parts.size < 11) return
                val session = parts[1]
                val sequence = parts[4].toLongOrNull() ?: return
                val sentAt = parts[5].toLongOrNull() ?: return
                val captureMs = parts[6].toLongOrNull() ?: 0L
                val x = parts[8].toIntOrNull()?.coerceIn(-1, 1) ?: return
                val y = parts[9].toIntOrNull()?.coerceIn(-1, 1) ?: return
                val source = parts[10]
                val declaredSlot = parts[3].toIntOrNull()
                if (!RemotePlayerSlots.packetMatches(remote.playerSlot, declaredSlot)) { Log.w(TAG, "P$playerSlot CONTROL_SLOT_MISMATCH_DROPPED: declared=$declaredSlot type=DPAD"); return }
                if (!acceptRemoteSequence(remote, "digital", session, sequence)) return
                val next = DpadState(x, y)
                if (!remote.dpadState.update(next)) {
                    dpadDuplicateDrops++
                    Log.d(TAG, "DPAD_DUPLICATE_DROPPED: side=host state=${next.label} sequence=$sequence total=$dpadDuplicateDrops")
                    return
                }
                val context = ControllerEventContext(session, parts[2], remote.playerSlot, sequence, sentAt)
                val backend = controllerBackends[remote.playerSlot] ?: return
                if (backend is DolphinVirtualGamepadBackend) {
                    Log.d(TAG, "PLAYER_${playerSlot}_PACKET_RECEIVED: player=${context.playerSlot} controller=${context.controllerId} type=DPAD state=${next.label} source=$source sequence=$sequence")
                }
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                val injected = backend.updateDpad(context, x, y)
                val injectMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                updateLogicalDpad(remote, next)
                replyRtc.sendControlMessage("ACK|$sequence|$sentAt|$injectMs|$captureMs")
                Log.d(TAG, "DPAD_LOGICAL_STATE: side=host player=$playerSlot state=${next.label} source=$source sequence=$sequence")
                if (next == DpadState()) Log.d(TAG, "DPAD_NEUTRAL_SENT: side=host sequence=$sequence")
                if (!injected) logBackendUnavailableOnce()
            }
            "RESET" -> if (parts.getOrNull(1) == activeSessionId && parts.getOrNull(3)?.toIntOrNull() == playerSlot) {
                resetRemoteInput(playerSlot ?: return, parts.getOrNull(6) ?: "remote reset")
            }
        }
    }

    private fun acceptRemoteSequence(input: RemoteInputSession, stream: String, session: String, sequence: Long): Boolean {
        if (session != activeSessionId) { Log.w(TAG, "CONTROL_STALE_SESSION_DROPPED: packet=$session active=$activeSessionId stream=$stream"); return false }
        val previous = input.lastSequences[stream] ?: 0L
        if (sequence <= previous) {
            outOfOrderControlPacketsDropped++
            updateControllerDiagnostics { copy(outOfOrderControlPacketsDropped = outOfOrderControlPacketsDropped) }
            Log.w(TAG, "CONTROL_OUT_OF_ORDER_DROPPED: stream=$stream sequence=$sequence previous=$previous")
            return false
        }
        if (previous > 0L && sequence > previous + 1L) Log.w(TAG, "CONTROL_SEQUENCE_GAP: stream=$stream expected=${previous + 1L} received=$sequence")
        input.lastSequences[stream] = sequence
        return true
    }

    private fun resetRemoteInput(playerSlot: Int, reason: String) {
        val input = remoteInputSessions.getOrPut(playerSlot) { RemoteInputSession(playerSlot) }
        synchronized(input) {
            if (input.heldButtons.isNotEmpty()) Log.w(TAG, "P$playerSlot CONTROL_STUCK_INPUT_RECOVERY: reason=$reason held=${input.heldButtons.joinToString()}")
            input.heldButtons.clear(); input.lastSequences.clear(); input.axisAckCounter = 0
            input.routeLogged = false
            input.dpadState.reset()
            input.logicalState = ControllerInputState()
            logicalControllerState = input.logicalState
            if (controllerInputTestOpen) runOnUiThread { controllerTestDisplayState = logicalControllerState }
            controllerBackends[playerSlot]?.resetNeutral("P$playerSlot $reason")
        }
        Log.d(TAG, "P$playerSlot CONTROL_REMOTE_STATE_CLEARED: $reason")
    }

    private fun ensureRemoteControllerSlot(playerSlot: Int, reason: String) {
        if (!SessionSecurityPolicy.validRemoteSlot(playerSlot)) return
        remoteInputSessions.computeIfAbsent(playerSlot) { RemoteInputSession(it) }
        controllerBackends.computeIfAbsent(playerSlot) { slot ->
            ControllerBackendSelector.select(selectedControllerProfile, slot).also { backend ->
                Log.d(TAG, "REMOTE_CONTROLLER_REGISTERED: assignedSlot=P$slot virtualController=${RemotePlayerSlots.controllerDeviceName(slot)} reason=$reason status=${backend.status.label}")
            }
        }
    }

    private fun sendNeutralReset(reason: String) {
        if (activeSessionId == "none") return
        val slot = remoteSlotAssignment?.playerSlot ?: return
        val sentAt = android.os.SystemClock.elapsedRealtime()
        clientRtc.sendControlMessage("RESET|$activeSessionId|device-$lastControllerDeviceId|$slot|${++digitalSequence}|$sentAt|$reason")
        lastAxes.fill(Float.NaN)
        Log.d(TAG, "P$slot CONTROL_NEUTRAL_RESET_SENT: $reason")
    }

    private fun logBackendUnavailableOnce() {
        if (backendUnavailableLogged) return
        backendUnavailableLogged = true
        Log.w(TAG, "CONTROL BACKEND UNAVAILABLE: transport works; system-wide injection requires privileged backend")
    }

    @Synchronized private fun recordDuplicateDrop() {
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
            player2Classification = current.player2Classification,
            localPhysicalControllerStatus = current.localPhysicalControllerStatus,
            remoteControllerTransportStatus = current.remoteControllerTransportStatus,
            hostVirtualControllerStatus = current.hostVirtualControllerStatus,
            activeNetworkTransport = current.activeNetworkTransport,
            internetValidated = current.internetValidated,
            networkRecoveryStatus = current.networkRecoveryStatus,
            iceRestartAttempts = current.iceRestartAttempts
        )
    }

    private fun handleNetworkState(state: DroidLinkNetworkState, changed: Boolean) {
        val previous = activeNetworkState
        activeNetworkState = state
        betaDiagnostics = betaDiagnostics.copy(activeNetworkTransport = state.transport, internetValidated = state.validated)
        when {
            !state.available && sessionActive -> {
                betaDiagnostics = betaDiagnostics.copy(networkRecoveryStatus = "Waiting for network")
                if (mode == "client") clientStatus = "No internet connection"
                else if (mode == "host") hostStatus = "No internet connection"
            }
            state.available && (changed || !previous.available) -> {
                Log.w(TAG, "ACTIVE_NETWORK_CHANGED: from=${previous.transport} to=${state.transport} validated=${state.validated} sessionActive=$sessionActive")
                betaDiagnostics = betaDiagnostics.copy(networkRecoveryStatus = if (sessionActive) "Network restored" else "Not needed")
                if (mode == "host") {
                    hostRtcBySlot.forEach { (slot, rtc) ->
                        val peerState = hostPeerStates[slot]
                        if (peerState == PeerConnection.PeerConnectionState.DISCONNECTED || peerState == PeerConnection.PeerConnectionState.FAILED) {
                            scheduleHostIceRestart(slot, rtc, hostRoomCode, sessionGeneration, "active network changed")
                        }
                    }
                } else if (mode == "client" && joinSessionState == JoinSessionState.Reconnecting) {
                    clientStatus = "Network restored — reconnecting"
                }
            }
        }
    }

    private fun refreshLocalControllers(reason: String) {
        localControllerDeviceIds.clear()
        InputDevice.getDeviceIds().forEach(::inspectLocalControllerDevice)
        updateLocalControllerAvailability(reason)
    }

    private fun inspectLocalControllerDevice(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!AndroidControllerDevicePolicy.isEligible(device.sources, device.isVirtual, device.name)) return
        localControllerDeviceIds.add(deviceId)
        if (lastControllerDeviceId == -1) lastControllerDeviceId = deviceId
        val sources = AndroidControllerDevicePolicy.sourceSummary(device.sources)
        Log.d(TAG, "LOCAL_CONTROLLER_DETECTED: id=$deviceId sources=$sources virtual=${device.isVirtual} axes=${device.motionRanges.size}")
        updateLocalControllerAvailability("detected")
    }

    private fun updateLocalControllerAvailability(reason: String) {
        localPhysicalControllerAvailable = localControllerDeviceIds.isNotEmpty()
        localPhysicalControllerSummary = if (localPhysicalControllerAvailable) {
            "${localControllerDeviceIds.size} Android gamepad${if (localControllerDeviceIds.size == 1) "" else "s"}"
        } else {
            "Not detected"
        }
        updateControllerDiagnostics {
            copy(
                localPhysicalControllerStatus = localPhysicalControllerSummary,
                remoteControllerTransportStatus = if (controlChannelOpen) "Connected" else "Not connected",
                hostVirtualControllerStatus = controllerBackend.status.label
            )
        }
        Log.d(TAG, "LOCAL_CONTROLLER_STATUS: available=$localPhysicalControllerAvailable count=${localControllerDeviceIds.size} reason=$reason")
    }

    private fun schedulePlayer2Inspection() {
        mainHandler.postDelayed({ inspectAllPlayer2Devices() }, 250L)
        mainHandler.postDelayed({ inspectAllPlayer2Devices() }, 1_000L)
        mainHandler.postDelayed({
            if (player2Classification == "Unknown" && controllerBackend.status == ControllerBackendStatus.VIRTUAL_GAMEPAD_ACTIVE) {
                if (selectedControllerProfile == ControllerProfile.GAMECUBE_DOLPHIN) {
                    Log.w(TAG, "DOLPHIN_HOTPLUG_WARNING: Android has not enumerated ${GameCubeMapping.DOLPHIN_DEVICE_NAME} yet")
                } else {
                    Log.w(TAG, "WINLATOR_HOTPLUG_WARNING: Android has not enumerated DroidLink Player 2 yet; start Winlator only after WINLATOR_GAMEPAD_READY")
                }
            }
        }, 2_000L)
    }

    private fun inspectAllPlayer2Devices() = InputDevice.getDeviceIds().forEach(::inspectPlayer2Device)

    private fun inspectPlayer2Device(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (selectedControllerProfile == ControllerProfile.GAMECUBE_DOLPHIN) {
            inspectDolphinDevice(deviceId, device)
            return
        }
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

    private fun inspectDolphinDevice(deviceId: Int, device: InputDevice) {
        if (device.name != GameCubeMapping.DOLPHIN_DEVICE_NAME) return
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
        val axes = device.motionRanges.joinToString { "${MotionEvent.axisToString(it.axis)}=${it.min}..${it.max}" }
        Log.d(TAG, "DOLPHIN_ANDROID_DEVICE: id=$deviceId name=${device.name} sources=0x${sources.toString(16)} descriptor=${device.descriptor} vidPid=%04x:%04x axes=[$axes]".format(device.vendorId, device.productId))
        Log.d(TAG, "DOLPHIN_DEVICE_CLASSIFICATION: gamepad=$gamepad joystick=$joystick keyboard=$keyboard")
        if (gamepad && joystick && !keyboard) Log.d(TAG, "DOLPHIN_CONTROLLER_READY: Android classified ${GameCubeMapping.DOLPHIN_DEVICE_NAME} as GAMEPAD/JOYSTICK")
        else Log.w(TAG, "DOLPHIN_HOTPLUG_WARNING: classification=$classification expected=GAMEPAD/JOYSTICK")
    }

    @Synchronized private fun recordControllerPacket() {
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

    @Synchronized private fun recordControllerPacketAge(ageMs: Long) {
        latestControllerPacketAgeMs = ageMs
        controllerPacketAgeTotalMs += ageMs
        controllerPacketAgeSamples++
    }

    private fun updateLogicalButton(input: RemoteInputSession, control: LogicalControl, down: Boolean) {
        input.logicalState = input.logicalState.withButton(control, down)
        logicalControllerState = input.logicalState
        if (controllerInputTestOpen) runOnUiThread { controllerTestDisplayState = logicalControllerState }
    }

    private fun updateLogicalAxes(input: RemoteInputSession, axes: FloatArray) {
        input.logicalState = input.logicalState.copy(
            leftX = axes[0], leftY = axes[1], rightX = axes[2], rightY = axes[3],
            leftTrigger = axes[4], rightTrigger = axes[5]
        )
        logicalControllerState = input.logicalState
        val now = android.os.SystemClock.elapsedRealtime()
        if (controllerInputTestOpen && now - lastControllerTestUiMs >= 50L) {
            lastControllerTestUiMs = now
            runOnUiThread { controllerTestDisplayState = logicalControllerState }
        }
    }

    private fun updateLogicalDpad(input: RemoteInputSession, state: DpadState) {
        input.logicalState = input.logicalState.copy(dpadX = state.x.toFloat(), dpadY = state.y.toFloat())
        logicalControllerState = input.logicalState
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
        val targetLongEdge = 1280.0
        val scale = minOf(1.0, targetLongEdge / maxOf(sourceWidth, sourceHeight))
        val width = ((sourceWidth * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val height = ((sourceHeight * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val refreshRate = currentRefreshRate()
        val fps = when (qualityPreset) { "Balanced" -> 30; else -> if (refreshRate >= 50f) 60 else 30 }
        Log.d(TAG, "Capture profile selected: preset=$qualityPreset ${width}x$height@$fps source=${sourceWidth}x$sourceHeight refreshRate=$refreshRate")
        return Triple(width, height, fps)
    }

    @Suppress("DEPRECATION")
    private fun currentRefreshRate(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.refreshRate ?: 60f
        else windowManager.defaultDisplay.refreshRate

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
        remoteInputSessions.keys.toList().forEach { slot -> resetRemoteInput(slot, "session cleanup") }
        remoteSlotAssignment?.let { assignment ->
            if (activeSessionId != "none") firebase.releaseRemoteSlot(activeSessionId, assignment)
        }
        firebase.stopListening()
        if (deleteHostRoom && hostRoomCode.isNotEmpty()) firebase.deleteRoom(hostRoomCode)
        hostDisconnectGraceRunnables.values.forEach(mainHandler::removeCallbacks)
        hostDisconnectGraceRunnables.clear()
        hostIceRestartRunnables.values.forEach(mainHandler::removeCallbacks)
        hostIceRestartRunnables.clear(); hostIceRestartAttempts.clear(); hostLastIceRestartMs.clear()
        mainHandler.removeCallbacksAndMessages(null)
        // Remove the renderer sink while its receiver-owned VideoTrack and PeerConnection are still valid.
        detachRenderer(); remoteTrack = null
        hostRtcBySlot.filterKeys { it != RemotePlayerSlots.PLAYER_2 }.values.toSet().forEach(WebRtcManager::release)
        hostRtcBySlot.clear()
        hostRtc.close(); clientRtc.close()
        stopService(Intent(this, ScreenCaptureService::class.java))
        controllerBackends.forEach { (slot, backend) ->
            remoteInputSessions[slot]?.let { input -> synchronized(input) { backend.close() } } ?: backend.close()
        }
        controllerBackends.clear(); remoteInputSessions.clear()
        controllerBackend = TransportOnlyBackend()
        clientControlActive = false; controlChannelOpen = false
        if (joinSessionState != JoinSessionState.Idle) Log.d(TAG, "JOIN_SESSION_DISPOSED: previousState=$joinSessionState")
        joinSessionState = JoinSessionState.Idle
        clientPeerState = PeerConnection.PeerConnectionState.NEW; hostPeerState = PeerConnection.PeerConnectionState.NEW; hostPeerStates.clear(); hostDiagnosticsBySlot.clear(); sessionStarting = false
        hostClaimedSlots = emptySet(); hostConnectedSlots = emptySet(); hostDisplayNames = emptyMap(); remoteSlotAssignment = null
        readinessVisible = false; readinessShownForSession = false
        gameAudioEnabled = true; gameAudioVolume = 1f
        pendingCaptureIntent = null; pendingOffer = null; hostRoomCode = ""; activeSessionId = "none"
        lastAxes.fill(Float.NaN); digitalSequence = 0L; analogSequence = 0L; lastControllerDeviceId = -1; controllerAxisLayouts.clear(); backendUnavailableLogged = false
        lastControlRoundTripMs = null; controllerLatencyTotalMs = 0L; controllerLatencySamples = 0L; controllerLatencyMaxMs = 0L
        latestControllerLatencyMs = null; latestControllerPacketAgeMs = null; controllerPacketAgeTotalMs = 0L; controllerPacketAgeSamples = 0L
        recentControllerLatencies.clear(); controlThreadLogCounter = 0; controllerAckLogCounter = 0
        duplicateControlPacketsDropped = 0L; outOfOrderControlPacketsDropped = 0L; staleAnalogPacketsDropped = 0L; player2Classification = "Unknown"
        watchdogNeutralResets = 0L; controllerHealthTicks = 0
        logicalControllerState = ControllerInputState(); controllerTestDisplayState = ControllerInputState(); lastControllerTestUiMs = 0L
        resetLocalDpadState("session cleanup"); dpadDuplicateDrops = 0L
        controllerWindowPackets = 0; controllerWindowStart = android.os.SystemClock.elapsedRealtime(); betaDiagnostics = BetaDiagnostics()
        updateLocalControllerAvailability("session cleanup")
        updateSessionActive(false)
        Log.d(TAG, "Session cleanup complete")
    }

    private fun detachRenderer() {
        renderer?.let { releaseRenderer(it, "session detach") }
    }

    @SuppressLint("RestrictedApi") // Activity-level controller interception intentionally delegates unhandled events.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (clientControlActive && isControllerEvent(event.source, event.device)) {
            val down = event.action == KeyEvent.ACTION_DOWN
            val up = event.action == KeyEvent.ACTION_UP
            if (!down && !up) return true
            noteController(event.deviceId, event.device?.name)
            val keyCode = ControllerButtonNormalization.canonicalKeyCode(event.keyCode)
            if (handleDpadKey(keyCode, down, event)) return true
            if (up || event.repeatCount == 0) sendKey(keyCode, if (down) "DOWN" else "UP", event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isControllerEvent(source: Int, device: InputDevice?): Boolean {
        if (device != null) return AndroidControllerDevicePolicy.isEligible(device.sources, device.isVirtual, device.name)
        return source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private fun noteController(deviceId: Int, name: String?) {
        if (lastControllerDeviceId == deviceId) return
        if (lastControllerDeviceId != -1) { sendNeutralReset("controller changed"); resetLocalDpadState("controller changed") }
        lastControllerDeviceId = deviceId
        localControllerDeviceIds.add(deviceId)
        updateLocalControllerAvailability("input event")
        Log.d(TAG, "CONTROLLER DETECTED: id=$deviceId name=${name ?: "unknown"}")
    }

    private fun sendKey(keyCode: Int, action: String, event: KeyEvent) {
        val slot = remoteSlotAssignment?.playerSlot ?: return
        val captureDelayMs = (android.os.SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
        val message = "KEY|$activeSessionId|device-$lastControllerDeviceId|$slot|${++digitalSequence}|${android.os.SystemClock.elapsedRealtime()}|$captureDelayMs|${lastControlRoundTripMs ?: 0L}|$keyCode|$action"
        recordControllerPacket()
        Log.d(TAG, "JOINER_INPUT_RECEIVED: player=$slot deviceId=${event.deviceId} device=${event.device?.name ?: "unknown"} type=BUTTON key=${KeyEvent.keyCodeToString(keyCode)} action=$action")
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
        val slot = remoteSlotAssignment?.playerSlot ?: return
        if (!joinerDpadState.update(next)) {
            dpadDuplicateDrops++
            if (dpadDuplicateDrops % 120L == 1L) Log.d(TAG, "DPAD_DUPLICATE_DROPPED: side=joiner source=$source state=${next.label} total=$dpadDuplicateDrops")
            return
        }
        val captureMs = (android.os.SystemClock.uptimeMillis() - eventTime).coerceAtLeast(0L)
        val sentAt = android.os.SystemClock.elapsedRealtime()
        val message = "DPAD|$activeSessionId|device-$deviceId|$slot|${++digitalSequence}|$sentAt|$captureMs|${lastControlRoundTripMs ?: 0L}|${next.x}|${next.y}|$source"
        recordControllerPacket()
        clientRtc.sendControlMessage(message)
        Log.d(TAG, "JOINER_INPUT_RECEIVED: player=$slot deviceId=$deviceId type=DPAD state=${next.label} source=$source")
        Log.d(TAG, "DPAD_LOGICAL_STATE: side=joiner state=${next.label} source=$source sequence=$digitalSequence")
        if (next == DpadState()) Log.d(TAG, "DPAD_NEUTRAL_SENT: side=joiner sequence=$digitalSequence")
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleControllerMotionEvent(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun handleControllerMotionEvent(event: MotionEvent): Boolean {
        val joystickEvent = event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 ||
            (event.device?.sources ?: 0) and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (clientControlActive && joystickEvent && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val slot = remoteSlotAssignment?.playerSlot ?: return false
            noteController(event.deviceId, event.device?.name)
            val axisState = controllerAxisLayouts.getOrPut(event.deviceId) {
                val available = ControllerAxisCompatibility.candidateAxes.filterTo(mutableSetOf()) { axis ->
                    controllerMotionRange(event.device, axis) != null
                }
                val layout = ControllerAxisCompatibility.resolve(available)
                val ranges = available.joinToString { axis ->
                    val range = controllerMotionRange(event.device, axis)
                    "${MotionEvent.axisToString(axis)}=${range?.min}..${range?.max} flat=${range?.flat}"
                }
                Log.d(TAG, "JOINER_AXIS_LAYOUT: player=$slot deviceId=${event.deviceId} resolved=[${layout.description()}] available=[$ranges]")
                ControllerDeviceAxisState(layout)
            }
            val layout = axisState.layout
            val leftTrigger = normalizeTriggerAxis(event, layout.leftTrigger, axisState, left = true)
            val rightTrigger = normalizeTriggerAxis(event, layout.rightTrigger, axisState, left = false)
            val values = floatArrayOf(
                normalizeStickAxis(event, layout.leftX),
                normalizeStickAxis(event, layout.leftY),
                normalizeStickAxis(event, layout.rightX),
                normalizeStickAxis(event, layout.rightY),
                leftTrigger,
                rightTrigger,
                0f,
                0f
            )
            val rawDpad = DpadState(
                ControllerAxisNormalizer.dpadDirection(axisValue(event, layout.hatX)),
                ControllerAxisNormalizer.dpadDirection(axisValue(event, layout.hatY))
            )
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
                Log.d(TAG, "JOINER_INPUT_RECEIVED: player=$slot deviceId=${event.deviceId} device=${event.device?.name ?: "unknown"} type=AXIS main=${values[0]},${values[1]} c=${values[2]},${values[3]} triggers=${values[4]},${values[5]}")
                Log.d(TAG, "CONTROL_CAPTURE_MS: $captureDelayMs type=AXIS")
            }
            val message = "AXIS|$activeSessionId|device-$lastControllerDeviceId|$slot|${++analogSequence}|${android.os.SystemClock.elapsedRealtime()}|$captureDelayMs|${lastControlRoundTripMs ?: 0L}|${values.joinToString("|")}"
            recordControllerPacket()
            clientRtc.sendControlMessage(message, realtimeAnalog = true); return true
        }
        return false
    }

    private fun normalizeStickAxis(event: MotionEvent, axis: Int): Float {
        if (axis == ControllerAxisCompatibility.UNAVAILABLE_AXIS) return 0f
        return ControllerAxisNormalizer.normalizeStick(
            event.getAxisValue(axis),
            controllerMotionRange(event.device, axis)?.toAxisRange()
        )
    }

    private fun normalizeTriggerAxis(
        event: MotionEvent,
        axis: Int,
        state: ControllerDeviceAxisState,
        left: Boolean
    ): Float {
        if (axis == ControllerAxisCompatibility.UNAVAILABLE_AXIS) return 0f
        val raw = event.getAxisValue(axis)
        val range = controllerMotionRange(event.device, axis)?.toAxisRange()
        if (ControllerAxisNormalizer.centeredTriggerActivated(raw, range)) {
            if (left) state.leftCenteredTriggerActivated = true else state.rightCenteredTriggerActivated = true
        }
        val activated = if (left) state.leftCenteredTriggerActivated else state.rightCenteredTriggerActivated
        return ControllerAxisNormalizer.normalizeTrigger(raw, range, activated)
    }

    private fun axisValue(event: MotionEvent, axis: Int): Float =
        if (axis == ControllerAxisCompatibility.UNAVAILABLE_AXIS) 0f else event.getAxisValue(axis)

    private fun controllerMotionRange(device: InputDevice?, axis: Int): InputDevice.MotionRange? {
        if (device == null || axis == ControllerAxisCompatibility.UNAVAILABLE_AXIS) return null
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
            ?: device.motionRanges.firstOrNull { range ->
                range.axis == axis && range.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0
            }
    }

    private fun InputDevice.MotionRange.toAxisRange() = ControllerAxisRange(min, max, flat)

    override fun onDestroy() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        cleanupSession(deleteHostRoom = true)
        hostRtc.release(); clientRtc.release()
        menuMusicController.release()
        if (receiverRegistered) { try { unregisterReceiver(projectionReadyReceiver) } catch (_: Exception) {}; receiverRegistered = false }
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputDeviceListener) } catch (_: Exception) {}
        super.onDestroy()
    }
}
