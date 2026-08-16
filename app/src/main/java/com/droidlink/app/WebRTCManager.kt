package com.droidlink.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcManager(private val context: Context) {
    companion object { private const val TAG = "DroidLink"; private const val CONTROL_CHANNEL = "droidlink-controls"; private const val AXIS_CHANNEL = "droidlink-axes" }

    var onControlMessageReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onIceCandidateReady: ((IceCandidate) -> Unit)? = null
    var onDiagnostics: ((BetaDiagnostics) -> Unit)? = null
    var onAudioStatus: ((String) -> Unit)? = null
    var onDataChannelStateChanged: ((String, DataChannel.State) -> Unit)? = null

    private val lock = Any()
    private val eglBase = EglBase.create()
    private val audioStreaming = AudioStreamingManager(context)
    private var audioDeviceModule: org.webrtc.audio.JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var axisDataChannel: DataChannel? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteFrameSink: VideoSink? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var statsThread: HandlerThread? = null
    private var statsHandler: Handler? = null
    private var lastStatsTimestampMs = 0L
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L
    private var lastFramesDecoded = 0L
    private var lastFramesEncoded = 0L
    private var stagnantDecodeIntervals = 0
    private var axisSendLogCounter = 0
    private var axisReceiveLogCounter = 0
    private var droppedAnalogPackets = 0L
    @Volatile private var controlBufferedBytes = 0L
    @Volatile private var digitalQueueDepth = 0
    @Volatile private var analogQueueDepth = 0
    private var lastBufferedAmountLogMs = 0L
    private var pendingLatestAnalogMessage: String? = null
    @Volatile private var diagnostics = BetaDiagnostics()
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var closed = false

    fun initialize() {
        synchronized(lock) {
            if (factory != null) return
            closed = false
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            audioDeviceModule = audioStreaming.createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
        }
        Log.d(TAG, "WebRTC factory initialized")
    }

    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun createPeerConnection(createControlChannel: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val currentFactory = synchronized(lock) {
            if (peer != null) { Log.w(TAG, "PeerConnection creation skipped: already created"); onSuccess(); return }
            factory
        }
        if (currentFactory == null) { onError("WebRTC factory has not been initialized"); return }
        Log.d(TAG, "TURN request started")
        TurnServerManager.fetchIceServers { result ->
            if (closed) return@fetchIceServers
            val servers = result.getOrElse { error ->
                Log.e(TAG, "TURN request failure; using STUN fallback", error)
                listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
            }
            if (result.isSuccess) Log.d(TAG, "TURN request success")
            Log.d(TAG, "ICE servers loaded: ${servers.size}")
            val config = PeerConnection.RTCConfiguration(servers).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }
            val created = currentFactory.createPeerConnection(config, observer())
            if (created == null) { onError("WebRTC failed to create PeerConnection"); return@fetchIceServers }
            synchronized(lock) { peer = created }
            Log.d(TAG, "PeerConnection created")
            if (createControlChannel) {
                registerDataChannel(created.createDataChannel(CONTROL_CHANNEL, DataChannel.Init().apply { ordered = true }), "local")
                registerDataChannel(created.createDataChannel(AXIS_CHANNEL, DataChannel.Init().apply { ordered = false; maxRetransmits = 0 }), "local")
            }
            startStatsCollector()
            onSuccess()
        }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            Log.d(TAG, "ICE candidate generated: mid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex} type=${candidateType(candidate.sdp)}")
            onIceCandidateReady?.invoke(candidate)
        }
        override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
            event ?: return
            Log.e(TAG, "ICE CANDIDATE ERROR: url=${event.url} address=${event.address}:${event.port} code=${event.errorCode} text=${event.errorText}")
        }
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            event ?: return
            Log.d(TAG, "ICE CANDIDATE PAIR CHANGED: reason=${event.reason} localType=${candidateType(event.local.sdp)} remoteType=${candidateType(event.remote.sdp)} estimatedDisconnectedMs=${event.estimatedDisconnectedTimeMs}")
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "PeerConnection state: $state")
            state?.let { updateDiagnostics { copy(connectionState = it.name) }; onConnectionStateChanged?.invoke(it) }
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state: $state")
            state?.let { updateDiagnostics { copy(iceState = it.name) } }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) { Log.d(TAG, "ICE gathering state: $state") }
        override fun onSignalingChange(state: PeerConnection.SignalingState?) { Log.d(TAG, "Signaling state: $state") }
        override fun onDataChannel(channel: DataChannel?) { registerDataChannel(channel, "remote") }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            handleRemoteVideoTrack(receiver?.track(), "onAddTrack")
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            if (transceiver == null) return
            Log.d(TAG, "Remote transceiver: mid=${transceiver.mid} direction=${transceiver.direction} currentDirection=${transceiver.currentDirection} stopped=${transceiver.isStopped}")
            handleRemoteVideoTrack(transceiver.receiver.track(), "onTrack")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onRenegotiationNeeded() { Log.d(TAG, "Renegotiation needed") }
    }

    private fun handleRemoteVideoTrack(mediaTrack: MediaStreamTrack?, callback: String) {
        if (mediaTrack is AudioTrack) {
            mediaTrack.setEnabled(true)
            peer?.setAudioPlayout(true)
            Log.d(TAG, "REMOTE AUDIO TRACK RECEIVED: callback=$callback id=${mediaTrack.id()}")
            Log.d(TAG, "AUDIO PLAYBACK STARTED: enabled=${mediaTrack.enabled()}")
            return
        }
        val track = mediaTrack as? VideoTrack ?: return
        if (remoteVideoTrack === track) {
            Log.d(TAG, "Duplicate remote video callback ignored: callback=$callback id=${track.id()}")
            return
        }
        remoteVideoTrack?.let { old -> remoteFrameSink?.let { sink -> try { old.removeSink(sink) } catch (_: Exception) {} } }
        remoteVideoTrack = track
        track.setEnabled(true)
        track.setShouldReceive(true)
        Log.d(TAG, "REMOTE VIDEO TRACK RECEIVED: callback=$callback id=${track.id()} kind=${track.kind()} state=${track.state()}")
        Log.d(TAG, "REMOTE VIDEO TRACK ENABLED: enabled=${track.enabled()} shouldReceive=${track.shouldReceive()}")
        var firstFrame = true
        var frameWindowStart = android.os.SystemClock.elapsedRealtime()
        var frameWindowCount = 0
        val sink = VideoSink { frame ->
            frameWindowCount++
            if (firstFrame) {
                firstFrame = false
                Log.d(TAG, "FIRST REMOTE FRAME RECEIVED: ${frame.buffer.width}x${frame.buffer.height} rotation=${frame.rotation} timestampNs=${frame.timestampNs}")
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - frameWindowStart >= 5_000L) {
                val fps = frameWindowCount * 1_000.0 / (now - frameWindowStart)
                Log.d(TAG, "REMOTE VIDEO FPS: ${format(fps)}")
                updateDiagnostics { copy(fps = fps, resolution = "${frame.buffer.width}×${frame.buffer.height}") }
                frameWindowStart = now; frameWindowCount = 0
            }
        }
        remoteFrameSink = sink
        track.addSink(sink)
        Log.d(TAG, "Remote diagnostic video sink attached: id=${track.id()}")
        onRemoteVideoTrack?.invoke(track)
    }

    private fun registerDataChannel(channel: DataChannel?, origin: String) {
        channel ?: return
        val isAxis = channel.label() == AXIS_CHANNEL
        val previous = if (isAxis) axisDataChannel else dataChannel
        previous?.takeIf { it !== channel }?.let { try { it.unregisterObserver(); it.close(); it.dispose() } catch (_: Exception) {} }
        if (isAxis) axisDataChannel = channel else dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() { Log.d(TAG, "DataChannel state ($origin/${channel.label()}): ${channel.state()}"); onDataChannelStateChanged?.invoke(channel.label(), channel.state()) }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer ?: return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                String(bytes, Charsets.UTF_8).let {
                    if (!it.startsWith("AXIS|") || ++axisReceiveLogCounter % 120 == 1) Log.d(TAG, "CONTROL RECEIVED: $it")
                    onControlMessageReceived?.invoke(it)
                }
            }
            override fun onBufferedAmountChange(previousAmount: Long) {
                val currentAmount = channel.bufferedAmount()
                controlBufferedBytes = currentAmount
                if (isAxis) analogQueueDepth = if (currentAmount == 0L) 0 else 1
                else digitalQueueDepth = if (currentAmount == 0L) 0 else 1
                val now = android.os.SystemClock.elapsedRealtime()
                if (currentAmount >= ControllerTransportPolicy.MAX_ANALOG_BUFFERED_BYTES || now - lastBufferedAmountLogMs >= 1_000L) {
                    lastBufferedAmountLogMs = now
                    Log.d(TAG, "CONTROL_BUFFERED_AMOUNT: channel=${channel.label()} previous=$previousAmount current=$currentAmount")
                }
                if (isAxis && currentAmount < ControllerTransportPolicy.MAX_ANALOG_BUFFERED_BYTES) {
                    val latest = synchronized(lock) { pendingLatestAnalogMessage.also { pendingLatestAnalogMessage = null } }
                    if (latest != null) {
                        Log.d(TAG, "CONTROL_ANALOG_QUEUE_DEPTH: 1 action=FLUSH_LATEST")
                        sendControlMessage(latest, realtimeAnalog = true)
                    }
                }
            }
        })
        Log.d(TAG, "DataChannel received/created ($origin): label=${channel.label()} state=${channel.state()}")
    }

    private fun startStatsCollector() {
        if (statsThread != null) return
        statsThread = HandlerThread("DroidLinkStats").also { it.start() }
        statsHandler = Handler(statsThread!!.looper)
        statsHandler?.postDelayed(statsRunnable, 5_000L)
    }

    private val statsRunnable = object : Runnable {
        override fun run() {
            val current = peer ?: return
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            current.getStats { report ->
                val collectionMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                Log.d(TAG, "WEBRTC_STATS_COLLECTION_MS: $collectionMs")
                logStats(report)
            }
            statsHandler?.postDelayed(this, 5_000L)
        }
    }

    private fun logStats(report: RTCStatsReport) {
        var bytesSent = 0L; var bytesReceived = 0L
        var framesEncoded = 0L; var framesDecoded = 0L; var framesDropped = 0L
        var packetsLost = 0L; var rttMs: Double? = null; var jitterMs: Double? = null; var availableSendBps: Double? = null
        var totalEncodeTimeSeconds = 0.0
        var codecId: String? = null; var codecImplementation: String? = null
        var selectedPair: RTCStats? = null
        val selectedPairId = report.statsMap.values.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId")?.toString()
        report.statsMap.values.forEach { stat ->
            val m = stat.members
            val kind = (m["kind"] ?: m["mediaType"])?.toString()
            when (stat.type) {
                "outbound-rtp" -> if (kind == "video") {
                    bytesSent += number(m["bytesSent"])
                    framesEncoded += number(m["framesEncoded"])
                    totalEncodeTimeSeconds += (m["totalEncodeTime"] as? Number)?.toDouble() ?: 0.0
                    codecId = m["codecId"]?.toString() ?: codecId
                    codecImplementation = m["encoderImplementation"]?.toString() ?: codecImplementation
                }
                "inbound-rtp" -> if (kind == "video") {
                    bytesReceived += number(m["bytesReceived"])
                    framesDecoded += number(m["framesDecoded"])
                    framesDropped += number(m["framesDropped"])
                    packetsLost += number(m["packetsLost"])
                    jitterMs = (m["jitter"] as? Number)?.toDouble()?.times(1_000.0)
                    codecId = m["codecId"]?.toString() ?: codecId
                    codecImplementation = m["decoderImplementation"]?.toString() ?: codecImplementation
                }
                "remote-inbound-rtp" -> if (kind == "video") {
                    packetsLost += number(m["packetsLost"])
                    rttMs = (m["roundTripTime"] as? Number)?.toDouble()?.times(1_000.0)
                    jitterMs = jitterMs ?: (m["jitter"] as? Number)?.toDouble()?.times(1_000.0)
                }
                "candidate-pair" -> if (m["state"] == "succeeded" && m["nominated"] == true) {
                    if (selectedPairId == null || stat.id == selectedPairId) selectedPair = stat
                    availableSendBps = (m["availableOutgoingBitrate"] as? Number)?.toDouble()
                    rttMs = rttMs ?: (m["currentRoundTripTime"] as? Number)?.toDouble()?.times(1_000.0)
                }
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = (now - lastStatsTimestampMs).coerceAtLeast(1L)
        val sendBitrate = if (lastStatsTimestampMs == 0L) 0L else ((bytesSent - lastBytesSent).coerceAtLeast(0L) * 8_000L / elapsed)
        val receiveBitrate = if (lastStatsTimestampMs == 0L) 0L else ((bytesReceived - lastBytesReceived).coerceAtLeast(0L) * 8_000L / elapsed)
        val encodedFps = if (lastStatsTimestampMs == 0L) null else (framesEncoded - lastFramesEncoded).coerceAtLeast(0L) * 1_000.0 / elapsed
        lastStatsTimestampMs = now; lastBytesSent = bytesSent; lastBytesReceived = bytesReceived
        if (framesDecoded > 0L && framesDecoded == lastFramesDecoded && diagnostics.connectionState == PeerConnection.PeerConnectionState.CONNECTED.name) {
            stagnantDecodeIntervals++
            if (stagnantDecodeIntervals >= 2) Log.e(TAG, "REMOTE VIDEO STALLED: framesDecoded has not advanced for ${stagnantDecodeIntervals * 5} seconds")
        } else {
            stagnantDecodeIntervals = 0
        }
        lastFramesDecoded = framesDecoded; lastFramesEncoded = framesEncoded
        val pair = selectedPair ?: selectedPairId?.let(report.statsMap::get)
        val local = pair?.members?.get("localCandidateId")?.toString()?.let(report.statsMap::get)
        val remote = pair?.members?.get("remoteCandidateId")?.toString()?.let(report.statsMap::get)
        val localType = local?.members?.get("candidateType")?.toString() ?: "unknown"
        val remoteType = remote?.members?.get("candidateType")?.toString() ?: "unknown"
        val protocol = local?.members?.get("protocol")?.toString() ?: remote?.members?.get("protocol")?.toString() ?: "unknown"
        val route = if (localType == "relay" || remoteType == "relay") "TURN relay" else if (pair != null) "Direct P2P" else "Unknown"
        val pairText = "$localType ↔ $remoteType ($protocol)"
        val codec = codecId?.let(report.statsMap::get)?.members?.get("mimeType")?.toString() ?: "unknown"
        if (pair != null) Log.d(TAG, "SELECTED ICE CANDIDATE PAIR: route=$route pair=$pairText local=${candidateAddress(local)} remote=${candidateAddress(remote)}")
        if (codec != "unknown") Log.d(TAG, "SELECTED VIDEO CODEC: $codec implementation=${codecImplementation ?: "unknown"}")
        updateDiagnostics {
            copy(
                route = route,
                candidatePair = pairText,
                rttMs = rttMs,
                fps = fps ?: encodedFps,
                videoBitrateBps = maxOf(sendBitrate, receiveBitrate),
                packetLoss = packetsLost,
                jitterMs = jitterMs,
                framesEncoded = framesEncoded,
                framesDecoded = framesDecoded,
                framesDropped = framesDropped,
                controlBufferedBytes = controlBufferedBytes,
                digitalQueueDepth = digitalQueueDepth,
                analogQueueDepth = analogQueueDepth,
                droppedStaleAnalogPackets = droppedAnalogPackets
            )
        }
        Log.d(TAG, "WEBRTC STATS: VIDEO BITRATE send=$sendBitrate receive=$receiveBitrate FRAMES ENCODED=$framesEncoded FRAMES DECODED=$framesDecoded FRAMES DROPPED=$framesDropped RTT_MS=${format(rttMs)} PACKET LOSS=$packetsLost JITTER_MS=${format(jitterMs)} AVAILABLE SEND BITRATE=${format(availableSendBps)}")
        Log.d(TAG, "VIDEO_ENCODE_FPS: ${format(encodedFps)}")
        Log.d(TAG, "VIDEO_ENCODE_TIME: avgMs=${if (framesEncoded > 0L) format(totalEncodeTimeSeconds * 1_000.0 / framesEncoded) else "n/a"}")
        Log.d(TAG, "VIDEO_DROPPED_FRAMES: $framesDropped")
        Log.d(TAG, "CONTROL_DIGITAL_QUEUE_DEPTH: $digitalQueueDepth")
        Log.d(TAG, "CONTROL_ANALOG_QUEUE_DEPTH: $analogQueueDepth")
        Log.d(TAG, "CONTROL_BUFFERED_AMOUNT: aggregateBytes=$controlBufferedBytes")
    }

    private fun candidateAddress(stat: RTCStats?): String {
        val members = stat?.members ?: return "unknown"
        val address = members["address"] ?: members["ip"] ?: "unknown"
        return "$address:${members["port"] ?: "?"}"
    }

    private fun updateDiagnostics(block: BetaDiagnostics.() -> BetaDiagnostics) {
        val updated = synchronized(lock) { diagnostics.block().also { diagnostics = it } }
        onDiagnostics?.invoke(updated)
    }

    private fun number(value: Any?) = (value as? Number)?.toLong() ?: 0L
    private fun format(value: Double?) = value?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "n/a"

    fun createOffer(onReady: (String) -> Unit, onError: (String) -> Unit) = createDescription(true, onReady, onError)
    fun createAnswer(onReady: (String) -> Unit, onError: (String) -> Unit) = createDescription(false, onReady, onError)

    private fun createDescription(isOffer: Boolean, onReady: (String) -> Unit, onError: (String) -> Unit) {
        val current = peer ?: return onError("PeerConnection has not been created")
        val sdpObserver = object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription?) {
                description ?: return onError("${if (isOffer) "Offer" else "Answer"} was empty")
                current.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set: ${description.type}; videoMLine=${description.description.contains("m=video")}")
                        logVideoTransceivers("local ${description.type}")
                        onReady(description.description)
                    }
                    override fun onSetFailure(error: String?) = onError(error ?: "Failed to set local description")
                }, description)
            }
            override fun onCreateFailure(error: String?) = onError(error ?: "Failed to create description")
        }
        if (isOffer) current.createOffer(sdpObserver, MediaConstraints()) else current.createAnswer(sdpObserver, MediaConstraints())
    }

    fun setRemoteOffer(sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = setRemote(SessionDescription.Type.OFFER, sdp, onSuccess, onError)
    fun setRemoteAnswer(sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = setRemote(SessionDescription.Type.ANSWER, sdp, onSuccess, onError)

    private fun setRemote(type: SessionDescription.Type, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val current = peer ?: return onError("PeerConnection has not been created")
        if (remoteDescriptionSet) { Log.w(TAG, "Duplicate remote description ignored: $type"); onSuccess(); return }
        current.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                val queued = synchronized(lock) { remoteDescriptionSet = true; pendingCandidates.toList().also { pendingCandidates.clear() } }
                Log.d(TAG, "Remote description set: $type; applying ${queued.size} queued ICE candidates")
                logVideoTransceivers("remote $type")
                queued.forEach(::applyCandidate); onSuccess()
            }
            override fun onSetFailure(error: String?) = onError(error ?: "Failed to set remote $type")
        }, SessionDescription(type, sdp))
    }

    fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(lock) {
            if (!remoteDescriptionSet) { pendingCandidates += ice; Log.d(TAG, "ICE candidate queued: mid=$sdpMid mLine=$sdpMLineIndex pending=${pendingCandidates.size}"); return }
        }
        applyCandidate(ice)
    }

    private fun applyCandidate(candidate: IceCandidate) {
        val added = peer?.addIceCandidate(candidate) == true
        Log.d(TAG, "ICE candidate applied: success=$added mid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}")
    }

    fun sendControlMessage(message: String, realtimeAnalog: Boolean = false): Boolean {
        val channel = if (realtimeAnalog) axisDataChannel else dataChannel
        if (channel?.state() != DataChannel.State.OPEN) { Log.d(TAG, "Control message skipped; DataChannel state=${channel?.state()}"); return false }
        val buffered = channel.bufferedAmount()
        if (realtimeAnalog && ControllerTransportPolicy.shouldDropAnalog(buffered)) {
            droppedAnalogPackets++
            controlBufferedBytes = buffered; analogQueueDepth = 1
            synchronized(lock) { pendingLatestAnalogMessage = message }
            Log.w(TAG, "CONTROL_BACKPRESSURE: channel=${channel.label()} bufferedBytes=$buffered action=DROP_STALE_ANALOG dropped=$droppedAnalogPackets")
            Log.w(TAG, "CONTROL_ANALOG_DROPPED: reason=backpressure total=$droppedAnalogPackets")
            return false
        }
        if (!realtimeAnalog && buffered >= ControllerTransportPolicy.MAX_ANALOG_BUFFERED_BYTES) {
            Log.w(TAG, "CONTROL_BACKPRESSURE: channel=${channel.label()} bufferedBytes=$buffered action=PRESERVE_DIGITAL")
        }
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val sent = channel.send(DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)), false))
        val sendMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000L
        controlBufferedBytes = channel.bufferedAmount()
        if (realtimeAnalog) analogQueueDepth = if (controlBufferedBytes > 0L) 1 else 0
        else digitalQueueDepth = if (controlBufferedBytes > 0L) 1 else 0
        if (!realtimeAnalog || axisSendLogCounter % 120 == 0) Log.d(TAG, "CONTROL_SEND_MS: ${sendMicros / 1000.0} channel=${channel.label()} bufferedBytes=${channel.bufferedAmount()}")
        if (!message.startsWith("AXIS|") || ++axisSendLogCounter % 120 == 1) Log.d(TAG, "CONTROL SENT: $message | success=$sent")
        return sent
    }

    fun startScreenShare(permissionData: Intent, width: Int = 1280, height: Int = 720, fps: Int = 60) {
        val currentFactory = factory ?: error("WebRTC factory has not been initialized")
        val currentPeer = peer ?: error("PeerConnection has not been created")
        check(screenCapturer == null) { "Screen capture is already active" }
        screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() { override fun onStop() { Log.d(TAG, "Screen capture projection stopped") } })
        textureHelper = SurfaceTextureHelper.create("DroidLinkScreenCapture", eglBase.eglBaseContext)
        screenSource = currentFactory.createVideoSource(true)
        val real = screenSource!!.capturerObserver
        var frames = 0
        val debug = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) { Log.d(TAG, "Screen capturer started: $success"); real.onCapturerStarted(success) }
            override fun onCapturerStopped() { Log.d(TAG, "Screen capturer stopped"); real.onCapturerStopped() }
            override fun onFrameCaptured(frame: VideoFrame) {
                frames++
                if (frames == 1) {
                    Log.d(TAG, "First host video frame captured: ${frame.buffer.width}x${frame.buffer.height}")
                    updateDiagnostics { copy(resolution = "${frame.buffer.width}×${frame.buffer.height}") }
                }
                real.onFrameCaptured(frame)
            }
        }
        screenCapturer!!.initialize(textureHelper, context.applicationContext, debug)
        screenTrack = currentFactory.createVideoTrack("DROIDLINK_SCREEN", screenSource)
        screenTrack!!.setEnabled(true)
        val sender = currentPeer.addTrack(screenTrack, listOf("DROIDLINK_STREAM"))
        check(sender != null) { "Failed to add screen video track to PeerConnection" }
        configureVideoSender(sender)
        preferH264WithFallback()
        Log.d(TAG, "Host video sender created: senderId=${sender.id()} trackId=${sender.track()?.id()} enabled=${sender.track()?.enabled()}")
        logVideoTransceivers("screen track attached before offer")
        screenCapturer!!.startCapture(width, height, fps)
        Log.d(TAG, "Screen video track attached and capture requested: ${width}x$height@$fps")
        startPlaybackAudio(currentFactory, currentPeer)
    }

    private fun startPlaybackAudio(currentFactory: PeerConnectionFactory, currentPeer: PeerConnection) {
        val projection = screenCapturer?.mediaProjection
        if (projection == null) {
            val reason = "MediaProjection was unavailable after screen capture start"
            Log.e(TAG, "AUDIO_UNAVAILABLE_REASON: $reason"); onAudioStatus?.invoke(reason); return
        }
        audioStreaming.start(projection).onSuccess {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            }
            localAudioSource = currentFactory.createAudioSource(constraints)
            localAudioTrack = currentFactory.createAudioTrack("DROIDLINK_GAME_AUDIO", localAudioSource).apply { setEnabled(true) }
            val sender = currentPeer.addTrack(localAudioTrack, listOf("DROIDLINK_STREAM"))
            if (sender == null) {
                val reason = "PeerConnection rejected local playback audio track"
                Log.e(TAG, "AUDIO_UNAVAILABLE_REASON: $reason"); onAudioStatus?.invoke(reason)
            } else {
                currentPeer.setAudioRecording(true)
                Log.d(TAG, "LOCAL_AUDIO_TRACK_ADDED: id=${localAudioTrack?.id()} sender=${sender.id()}")
                onAudioStatus?.invoke("Game audio capture active")
            }
        }.onFailure { onAudioStatus?.invoke(it.message ?: "Playback audio unavailable") }
    }

    private fun configureVideoSender(sender: RtpSender) {
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { encoding ->
            encoding.active = true
            encoding.minBitrateBps = 200_000
            encoding.maxBitrateBps = 8_000_000
            encoding.maxFramerate = 60
            encoding.bitratePriority = 2.0
        }
        val parametersSet = sender.setParameters(parameters)
        val bitrateSet = peer?.setBitrate(300_000, 4_000_000, 8_000_000) == true
        Log.d(TAG, "Video sender low-latency tuning: parametersSet=$parametersSet bitrateSet=$bitrateSet degradation=MAINTAIN_FRAMERATE")
    }

    private fun preferH264WithFallback() {
        val codecs = factory?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs.orEmpty()
        val ordered = codecs.sortedBy { if (it.name.equals("H264", ignoreCase = true)) 0 else 1 }
        val transceiver = peer?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        val result = if (transceiver != null && ordered.isNotEmpty()) transceiver.setCodecPreferences(ordered) else null
        Log.d(TAG, "Video codec preference: H264-first with fallback; supported=${ordered.joinToString { it.name }} result=${result?.isSuccess}")
    }

    private fun logVideoTransceivers(stage: String) {
        val video = peer?.transceivers?.filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }.orEmpty()
        Log.d(TAG, "Video transceivers at $stage: count=${video.size}")
        video.forEachIndexed { index, transceiver ->
            Log.d(TAG, "Video transceiver[$index]: mid=${transceiver.mid} direction=${transceiver.direction} currentDirection=${transceiver.currentDirection} senderTrack=${transceiver.sender.track()?.id()} receiverTrack=${transceiver.receiver.track()?.id()}")
        }
    }

    fun close() {
        if (closed) return
        closed = true; Log.d(TAG, "Closing WebRTC session")
        statsHandler?.removeCallbacksAndMessages(null)
        statsThread?.quitSafely()
        statsHandler = null; statsThread = null
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        try { screenCapturer?.dispose() } catch (_: Exception) {}
        try { textureHelper?.dispose() } catch (_: Exception) {}
        try { screenTrack?.dispose() } catch (_: Exception) {}
        try { screenSource?.dispose() } catch (_: Exception) {}
        audioStreaming.stop()
        try { localAudioTrack?.dispose() } catch (_: Exception) {}
        try { localAudioSource?.dispose() } catch (_: Exception) {}
        remoteVideoTrack?.let { track -> remoteFrameSink?.let { sink -> try { track.removeSink(sink) } catch (_: Exception) {} } }
        try { dataChannel?.unregisterObserver(); dataChannel?.close(); dataChannel?.dispose() } catch (_: Exception) {}
        try { axisDataChannel?.unregisterObserver(); axisDataChannel?.close(); axisDataChannel?.dispose() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { audioDeviceModule?.release() } catch (_: Exception) {}
        screenCapturer = null; textureHelper = null; screenTrack = null; screenSource = null
        remoteVideoTrack = null; remoteFrameSink = null; localAudioTrack = null; localAudioSource = null
        audioDeviceModule = null; dataChannel = null; axisDataChannel = null; peer = null; factory = null
        synchronized(lock) { pendingCandidates.clear(); remoteDescriptionSet = false }
        diagnostics = BetaDiagnostics()
        lastStatsTimestampMs = 0L; lastBytesSent = 0L; lastBytesReceived = 0L; lastFramesDecoded = 0L; lastFramesEncoded = 0L; stagnantDecodeIntervals = 0
        axisSendLogCounter = 0; axisReceiveLogCounter = 0
        droppedAnalogPackets = 0L
        controlBufferedBytes = 0L; digitalQueueDepth = 0; analogQueueDepth = 0; lastBufferedAmountLogMs = 0L
        synchronized(lock) { pendingLatestAnalogMessage = null }
    }

    fun release() { close(); try { eglBase.release() } catch (_: Exception) {} }
    private fun candidateType(sdp: String) = Regex(" typ (\\w+)").find(sdp)?.groupValues?.get(1) ?: "unknown"

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}
