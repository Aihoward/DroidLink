package com.droidlink.app

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

data class VoiceDiagnostics(
    val state: String = "OFF",
    val micLevel: Double = 0.0,
    val remoteLevel: Double = 0.0,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val aecAvailable: Boolean = AcousticEchoCanceler.isAvailable(),
    val nsAvailable: Boolean = NoiseSuppressor.isAvailable(),
    val agcAvailable: Boolean = AutomaticGainControl.isAvailable()
)

/** An opt-in, audio-only WebRTC connection so voice never shares the game-audio capture source. */
class VoiceChatManager(private val context: Context) {
    companion object { private const val TAG = "DroidLink" }

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onDiagnostics: ((VoiceDiagnostics) -> Unit)? = null
    var onLocalSpeakingChanged: ((Boolean) -> Unit)? = null
    var onRemoteSpeakingChanged: ((Boolean) -> Unit)? = null
    var onRenegotiationNeeded: (() -> Unit)? = null

    private val lock = Any()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var adm: JavaAudioDeviceModule? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null
    private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var initializing = false
    private val readyCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private var statsThread: HandlerThread? = null
    private var statsHandler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var diagnostics = VoiceDiagnostics()
    private var microphoneEnabled = false
    private var remoteVoiceEnabled = true
    private var localSpeaking = false
    private var remoteSpeaking = false
    private var localLastActiveMs = 0L
    private var remoteLastActiveMs = 0L
    private var lastDiagnosticsDispatchMs = 0L

    fun initialize(onReady: (Result<Unit>) -> Unit) {
        synchronized(lock) {
            if (peer != null) { onReady(Result.success(Unit)); return }
            readyCallbacks += onReady
            if (initializing) return
            initializing = true; closed = false
        }
        val aec = AcousticEchoCanceler.isAvailable(); val ns = NoiseSuppressor.isAvailable(); val agc = AutomaticGainControl.isAvailable()
        Log.d(TAG, "VOICE_AEC_AVAILABLE: $aec")
        Log.d(TAG, "VOICE_NS_AVAILABLE: $ns")
        Log.d(TAG, "VOICE_AGC_AVAILABLE: $agc")
        adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(aec)
            .setUseHardwareNoiseSuppressor(ns)
            .setUseStereoInput(false).setUseStereoOutput(true).setUseLowLatency(true)
            .createAudioDeviceModule().also { it.setMicrophoneMute(true) }
        Log.d(TAG, "VOICE_AEC_ACTIVE: hardware=$aec softwareRequested=true")
        Log.d(TAG, "VOICE_NS_ACTIVE: hardware=$ns softwareRequested=true")
        Log.d(TAG, "VOICE_AGC_ACTIVE: hardware=$agc softwareRequested=true")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        TurnServerManager.fetchIceServers { result ->
            if (closed) return@fetchIceServers
            val servers = result.getOrElse { listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()) }
            val config = PeerConnection.RTCConfiguration(servers).apply {
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
            val created = factory?.createPeerConnection(config, observer())
            if (created == null) finishInitialization(Result.failure(IllegalStateException("Voice PeerConnection creation failed")))
            else {
                peer = created
                created.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                preferOpus()
                created.setAudioRecording(false); created.setAudioPlayout(true)
                logAudioRoute()
                startStats()
                diagnostics = diagnostics.copy(state = "READY", aecAvailable = aec, nsAvailable = ns, agcAvailable = agc)
                onStatus?.invoke("VOICE_READY")
                finishInitialization(Result.success(Unit))
            }
        }
    }

    private fun finishInitialization(result: Result<Unit>) {
        val callbacks = synchronized(lock) { initializing = false; readyCallbacks.toList().also { readyCallbacks.clear() } }
        callbacks.forEach { it(result) }
    }

    fun enableMicrophone(enabled: Boolean): Result<Unit> = runCatching {
        val currentPeer = peer ?: error("Voice connection is not ready")
        if (enabled && micTrack == null) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            micSource = factory?.createAudioSource(constraints) ?: error("Voice microphone source creation failed")
            micTrack = factory?.createAudioTrack("DROIDLINK_VOICE", micSource)?.apply { setEnabled(true) } ?: error("Voice microphone track creation failed")
            check(currentPeer.addTrack(micTrack, listOf("DROIDLINK_VOICE_STREAM")) != null) { "Voice microphone sender creation failed" }
            preferOpus()
            Log.d(TAG, "VOICE_MIC_TRACK_CREATED: id=${micTrack?.id()}")
            onRenegotiationNeeded?.invoke()
        }
        micTrack?.setEnabled(enabled)
        adm?.setMicrophoneMute(!enabled)
        currentPeer.setAudioRecording(enabled)
        microphoneEnabled = enabled
        if (!enabled) setLocalSpeaking(false)
        diagnostics = diagnostics.copy(state = if (enabled) "MIC_ACTIVE" else "MIC_MUTED")
        Log.d(TAG, "VOICE_MIC_ENABLED: $enabled")
    }

    fun setRemoteVoiceEnabled(enabled: Boolean) { remoteVoiceEnabled = enabled; remoteTrack?.setEnabled(enabled); peer?.setAudioPlayout(enabled); if (!enabled) setRemoteSpeaking(false); Log.d(TAG, "VOICE_REMOTE_ENABLED: $enabled") }
    fun setRemoteVolume(volume: Float) { remoteTrack?.setVolume(volume.coerceIn(0f, 1f).toDouble()) }

    fun createOffer(onReady: (String) -> Unit, onError: (String) -> Unit) = createDescription(true, onReady, onError)
    fun createAnswer(onReady: (String) -> Unit, onError: (String) -> Unit) = createDescription(false, onReady, onError)
    private fun createDescription(offer: Boolean, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val current = peer ?: return onError("Voice connection is not ready")
        val observer = object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return onError("Voice SDP was empty")
                current.setLocalDescription(object : Sdp() { override fun onSetSuccess() { onReady(sdp.description) }; override fun onSetFailure(error: String?) { onError(error ?: "Voice local SDP failed") } }, sdp)
            }
            override fun onCreateFailure(error: String?) { onError(error ?: "Voice SDP creation failed") }
        }
        if (offer) current.createOffer(observer, MediaConstraints()) else current.createAnswer(observer, MediaConstraints())
    }

    fun setRemoteOffer(sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = setRemote(SessionDescription.Type.OFFER, sdp, onSuccess, onError)
    fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = setRemote(SessionDescription.Type.ANSWER, sdp, onSuccess, onError)
    private fun setRemote(type: SessionDescription.Type, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val current = peer ?: return onError("Voice connection is not ready")
        current.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                val queued = synchronized(lock) { remoteDescriptionSet = true; pendingIce.toList().also { pendingIce.clear() } }
                queued.forEach { current.addIceCandidate(it) }
                onSuccess()
            }
            override fun onSetFailure(error: String?) { onError(error ?: "Voice remote SDP failed") }
        }, SessionDescription(type, sdp))
    }

    fun addIceCandidate(candidate: String, mid: String?, line: Int) {
        val ice = IceCandidate(mid, line, candidate)
        synchronized(lock) { if (!remoteDescriptionSet) { pendingIce += ice; return } }
        peer?.addIceCandidate(ice)
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let(onIceCandidate ?: {}) }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) { state ?: return; diagnostics = diagnostics.copy(state = state.name); onStatus?.invoke("VOICE_${state.name}") }
        override fun onTrack(transceiver: RtpTransceiver?) { val track = transceiver?.receiver?.track() as? AudioTrack ?: return; remoteTrack = track; track.setEnabled(true); peer?.setAudioPlayout(true); Log.d(TAG, "VOICE_REMOTE_TRACK_RECEIVED: id=${track.id()}") }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { val track = receiver?.track() as? AudioTrack ?: return; remoteTrack = track; track.setEnabled(true) }
        override fun onRenegotiationNeeded() = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onIceCandidateError(event: IceCandidateErrorEvent?) = Unit
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) = Unit
    }

    private fun preferOpus() {
        val codecs = factory?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)?.codecs.orEmpty().sortedBy { if (it.name.equals("opus", true)) 0 else 1 }
        peer?.transceivers?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }?.forEach { if (codecs.isNotEmpty()) it.setCodecPreferences(codecs) }
        Log.d(TAG, "VOICE_CODEC: Opus preferred")
    }

    private fun logAudioRoute() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val routes = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.productName}/${it.type}" }
        Log.d(TAG, "VOICE_AUDIO_ROUTES: $routes mode=${manager.mode} volume=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}")
    }

    private fun startStats() {
        if (statsThread != null) return
        statsThread = HandlerThread("DroidLinkVoiceStats").also { it.start() }
        statsHandler = Handler(statsThread!!.looper).also { it.postDelayed(statsRunnable, 200L) }
    }
    private val statsRunnable = object : Runnable {
        override fun run() {
            peer?.getStats { report ->
                var sent = 0L; var received = 0L; var mic = 0.0; var remote = 0.0
                report.statsMap.values.forEach { stat ->
                    val kind = (stat.members["kind"] ?: stat.members["mediaType"])?.toString()
                    if (kind == "audio" && stat.type == "outbound-rtp") { sent += (stat.members["bytesSent"] as? Number)?.toLong() ?: 0L; mic = (stat.members["audioLevel"] as? Number)?.toDouble() ?: mic }
                    if (kind == "audio" && stat.type == "inbound-rtp") { received += (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L; remote = (stat.members["audioLevel"] as? Number)?.toDouble() ?: remote }
                    if (kind == "audio" && stat.type == "media-source") mic = (stat.members["audioLevel"] as? Number)?.toDouble() ?: mic
                }
                diagnostics = diagnostics.copy(micLevel = mic, remoteLevel = remote, bytesSent = sent, bytesReceived = received)
                updateVoiceActivity(mic, remote)
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastDiagnosticsDispatchMs >= 1_000L) { lastDiagnosticsDispatchMs = now; onDiagnostics?.invoke(diagnostics) }
            }
            statsHandler?.postDelayed(this, 200L)
        }
    }

    private fun updateVoiceActivity(micLevel: Double, remoteLevel: Double) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (microphoneEnabled && micLevel >= 0.015) { localLastActiveMs = now; setLocalSpeaking(true) }
        else if (!microphoneEnabled || now - localLastActiveMs >= 500L) setLocalSpeaking(false)
        if (remoteVoiceEnabled && remoteLevel >= 0.015) { remoteLastActiveMs = now; setRemoteSpeaking(true) }
        else if (!remoteVoiceEnabled || now - remoteLastActiveMs >= 500L) setRemoteSpeaking(false)
    }

    private fun setLocalSpeaking(value: Boolean) { if (localSpeaking != value) { localSpeaking = value; Log.d(TAG, "VOICE_LOCAL_SPEAKING: $value"); onLocalSpeakingChanged?.invoke(value) } }
    private fun setRemoteSpeaking(value: Boolean) { if (remoteSpeaking != value) { remoteSpeaking = value; Log.d(TAG, "VOICE_REMOTE_SPEAKING: $value"); onRemoteSpeakingChanged?.invoke(value) } }

    fun close() {
        if (closed) return
        closed = true
        setLocalSpeaking(false); setRemoteSpeaking(false)
        onIceCandidate = null; onStatus = null; onDiagnostics = null; onLocalSpeakingChanged = null; onRemoteSpeakingChanged = null; onRenegotiationNeeded = null
        statsHandler?.removeCallbacksAndMessages(null); statsThread?.quitSafely(); statsHandler = null; statsThread = null
        runCatching { peer?.setAudioRecording(false) }; runCatching { adm?.setMicrophoneMute(true) }
        runCatching { micTrack?.dispose() }; runCatching { micSource?.dispose() }; runCatching { peer?.close(); peer?.dispose() }
        runCatching { factory?.dispose() }; runCatching { adm?.release() }
        micTrack = null; micSource = null; remoteTrack = null; peer = null; factory = null; adm = null
        synchronized(lock) { pendingIce.clear(); remoteDescriptionSet = false; initializing = false; readyCallbacks.clear() }
        diagnostics = VoiceDiagnostics()
        microphoneEnabled = false; remoteVoiceEnabled = true; localLastActiveMs = 0L; remoteLastActiveMs = 0L; lastDiagnosticsDispatchMs = 0L
        Log.d(TAG, "VOICE_CHAT_CLOSED")
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
