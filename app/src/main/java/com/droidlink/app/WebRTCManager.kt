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
    private var videoSender: RtpSender? = null
    private var adaptiveProfile = "Auto"
    private var baseCaptureWidth = 1280
    private var baseCaptureHeight = 720
    private var baseCaptureFps = 60
    private var adaptationLevel = 0
    private var lastAdaptationMs = 0L
    private var lastPacketsLost = 0L
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteGameAudioTrack: AudioTrack? = null
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
    private var lastFramesCaptured = 0L
    private var lastFramesReceived = 0L
    private var lastFramesRendered = 0L
    private var lastTotalCaptureDelaySeconds = 0.0
    private var lastTotalEncodeTimeSeconds = 0.0
    private var lastTotalDecodeTimeSeconds = 0.0
    private var lastJitterBufferDelaySeconds = 0.0
    private var lastJitterBufferEmittedCount = 0L
    private var lastAudioBytesSent = 0L
    private var lastAudioBytesReceived = 0L
    private var captureWindowStartMs = 0L
    private var captureWindowFrames = 0
    private var gameAudioSendConfirmed = false
    private var gameAudioReceiveConfirmed = false
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
            audioStreaming.onStatus = { status ->
                Log.d(TAG, status)
                onAudioStatus?.invoke(status)
            }
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
                enableDscp = true
                enableCpuOveruseDetection = true
                suspendBelowMinBitrate = false
                screencastMinBitrate = 100_000
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
            remoteGameAudioTrack = mediaTrack
            peer?.setAudioPlayout(true)
            Log.d(TAG, "GAME_AUDIO_REMOTE_TRACK_RECEIVED: callback=$callback id=${mediaTrack.id()}")
            Log.d(TAG, "GAME_AUDIO_PLAYOUT_ACTIVE: enabled=${mediaTrack.enabled()}")
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val routes = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.productName}/${it.type}" }
            Log.d(TAG, "GAME_AUDIO_ROUTE: outputs=$routes mediaVolume=${audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)} muted=${audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)}")
            onAudioStatus?.invoke("GAME_AUDIO_REMOTE_TRACK_RECEIVED")
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
            try {
                current.getStats { report ->
                    try {
                        val collectionMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        Log.d(TAG, "WEBRTC_STATS_COLLECTION_MS: $collectionMs")
                        logStats(report)
                    } catch (error: Exception) {
                        Log.e(TAG, "OPTIONAL_STATS_DISABLED: WebRTC stats processing failed; media session continues", error)
                        statsHandler?.removeCallbacksAndMessages(null)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "OPTIONAL_STATS_DISABLED: WebRTC getStats failed; media session continues", error)
                statsHandler?.removeCallbacksAndMessages(null)
                return
            }
            statsHandler?.postDelayed(this, 5_000L)
        }
    }

    private fun logStats(report: RTCStatsReport) {
        var bytesSent = 0L; var bytesReceived = 0L
        var framesEncoded = 0L; var framesDecoded = 0L; var framesDropped = 0L
        var framesCaptured = 0L; var framesReceived = 0L; var framesRendered = 0L
        var packetsLost = 0L; var rttMs: Double? = null; var jitterMs: Double? = null; var availableSendBps: Double? = null
        var totalEncodeTimeSeconds = 0.0; var totalDecodeTimeSeconds = 0.0; var totalCaptureDelaySeconds = 0.0
        var jitterBufferDelaySeconds = 0.0; var jitterBufferEmittedCount = 0L
        var audioBytesSent = 0L; var audioPacketsSent = 0L; var audioBytesReceived = 0L; var audioPacketsReceived = 0L
        var codecId: String? = null; var encoderImplementation: String? = null; var decoderImplementation: String? = null
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
                    encoderImplementation = m["encoderImplementation"]?.toString() ?: encoderImplementation
                } else if (kind == "audio") { audioBytesSent += number(m["bytesSent"]); audioPacketsSent += number(m["packetsSent"]) }
                "inbound-rtp" -> if (kind == "video") {
                    bytesReceived += number(m["bytesReceived"])
                    framesReceived += number(m["framesReceived"])
                    framesDecoded += number(m["framesDecoded"])
                    framesRendered += number(m["framesRendered"])
                    framesDropped += number(m["framesDropped"])
                    packetsLost += number(m["packetsLost"])
                    jitterMs = (m["jitter"] as? Number)?.toDouble()?.times(1_000.0)
                    codecId = m["codecId"]?.toString() ?: codecId
                    decoderImplementation = m["decoderImplementation"]?.toString() ?: decoderImplementation
                    totalDecodeTimeSeconds += (m["totalDecodeTime"] as? Number)?.toDouble() ?: 0.0
                    jitterBufferDelaySeconds += (m["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                    jitterBufferEmittedCount += number(m["jitterBufferEmittedCount"])
                } else if (kind == "audio") { audioBytesReceived += number(m["bytesReceived"]); audioPacketsReceived += number(m["packetsReceived"]) }
                "media-source" -> if (kind == "video") {
                    framesCaptured += number(m["frames"] ?: m["framesCaptured"])
                    totalCaptureDelaySeconds += (m["totalCaptureDelay"] as? Number)?.toDouble() ?: 0.0
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
        val decodedFps = if (lastStatsTimestampMs == 0L) null else (framesDecoded - lastFramesDecoded).coerceAtLeast(0L) * 1_000.0 / elapsed
        val captureDelta = (framesCaptured - lastFramesCaptured).coerceAtLeast(0L)
        val encodeDelta = (framesEncoded - lastFramesEncoded).coerceAtLeast(0L)
        val receiveDelta = (framesReceived - lastFramesReceived).coerceAtLeast(0L)
        val decodeDelta = (framesDecoded - lastFramesDecoded).coerceAtLeast(0L)
        val renderDelta = (framesRendered - lastFramesRendered).coerceAtLeast(0L)
        val encoderQueueDepth = (captureDelta - encodeDelta).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val decoderQueueDepth = (receiveDelta - decodeDelta).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val renderQueueDepth = if (framesRendered > 0L) (decodeDelta - renderDelta).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val jitterEmittedDelta = (jitterBufferEmittedCount - lastJitterBufferEmittedCount).coerceAtLeast(0L)
        val captureLatencyMs = if (captureDelta > 0L) (totalCaptureDelaySeconds - lastTotalCaptureDelaySeconds).coerceAtLeast(0.0) * 1_000.0 / captureDelta else null
        val encodeTimeMs = if (encodeDelta > 0L) (totalEncodeTimeSeconds - lastTotalEncodeTimeSeconds).coerceAtLeast(0.0) * 1_000.0 / encodeDelta else null
        val decodeTimeMs = if (decodeDelta > 0L) (totalDecodeTimeSeconds - lastTotalDecodeTimeSeconds).coerceAtLeast(0.0) * 1_000.0 / decodeDelta else null
        val processingLatencyMs = decodeTimeMs
        val jitterBufferMs = if (jitterEmittedDelta > 0L) (jitterBufferDelaySeconds - lastJitterBufferDelaySeconds).coerceAtLeast(0.0) * 1_000.0 / jitterEmittedDelta else null
        val recentPacketsLost = (packetsLost - lastPacketsLost).coerceAtLeast(0L)
        lastStatsTimestampMs = now; lastBytesSent = bytesSent; lastBytesReceived = bytesReceived
        if (framesDecoded > 0L && framesDecoded == lastFramesDecoded && diagnostics.connectionState == PeerConnection.PeerConnectionState.CONNECTED.name) {
            stagnantDecodeIntervals++
            if (stagnantDecodeIntervals >= 2) Log.e(TAG, "REMOTE VIDEO STALLED: framesDecoded has not advanced for ${stagnantDecodeIntervals * 5} seconds")
        } else {
            stagnantDecodeIntervals = 0
        }
        lastFramesDecoded = framesDecoded; lastFramesEncoded = framesEncoded; lastPacketsLost = packetsLost
        lastFramesCaptured = framesCaptured; lastFramesReceived = framesReceived; lastFramesRendered = framesRendered
        lastTotalCaptureDelaySeconds = totalCaptureDelaySeconds; lastTotalEncodeTimeSeconds = totalEncodeTimeSeconds
        lastTotalDecodeTimeSeconds = totalDecodeTimeSeconds
        lastJitterBufferDelaySeconds = jitterBufferDelaySeconds; lastJitterBufferEmittedCount = jitterBufferEmittedCount
        val pair = selectedPair ?: selectedPairId?.let(report.statsMap::get)
        val local = pair?.members?.get("localCandidateId")?.toString()?.let(report.statsMap::get)
        val remote = pair?.members?.get("remoteCandidateId")?.toString()?.let(report.statsMap::get)
        val localType = local?.members?.get("candidateType")?.toString() ?: "unknown"
        val remoteType = remote?.members?.get("candidateType")?.toString() ?: "unknown"
        val protocol = local?.members?.get("protocol")?.toString() ?: remote?.members?.get("protocol")?.toString() ?: "unknown"
        val route = if (localType == "relay" || remoteType == "relay") "TURN relay" else if (pair != null) "Direct P2P" else "Unknown"
        val pairText = "$localType ↔ $remoteType ($protocol)"
        val codec = codecId?.let(report.statsMap::get)?.members?.get("mimeType")?.toString() ?: "unknown"
        val renderLatencyMs = if (renderQueueDepth > 0 && decodedFps != null && decodedFps > 0.0) renderQueueDepth * 1_000.0 / decodedFps else 0.0
        val frameAgeAtRenderMs = listOfNotNull(captureLatencyMs, encodeTimeMs, rttMs?.div(2.0), jitterBufferMs, processingLatencyMs, renderLatencyMs).sum().takeIf { it > 0.0 }
        val bottleneck = when {
            encoderQueueDepth >= 3 -> "ENCODER QUEUE LIMITED"
            jitterBufferMs != null && jitterBufferMs >= 120.0 -> "RECEIVE BUFFER LIMITED"
            decoderQueueDepth >= 3 -> "DECODER QUEUE LIMITED"
            renderQueueDepth >= 2 -> "RENDER QUEUE LIMITED"
            diagnostics.captureFps != null && diagnostics.captureFps!! < 24.0 -> "HOST PERFORMANCE LIMITED"
            encodedFps != null && diagnostics.captureFps != null && encodedFps < diagnostics.captureFps!! * 0.72 -> "ENCODER LIMITED"
            availableSendBps != null && sendBitrate > 0L && availableSendBps!! < sendBitrate * 1.15 -> "NETWORK LIMITED"
            decodedFps != null && decodedFps < 24.0 && framesDecoded > 0L -> "DECODER LIMITED"
            diagnostics.renderFps != null && decodedFps != null && diagnostics.renderFps!! < decodedFps * 0.72 -> "RENDER LIMITED"
            diagnostics.connectionState == PeerConnection.PeerConnectionState.CONNECTED.name && (encodedFps ?: decodedFps ?: 0.0) >= 24.0 -> "HEALTHY"
            else -> "UNKNOWN"
        }
        if (pair != null) Log.d(TAG, "SELECTED ICE CANDIDATE PAIR: route=$route pair=$pairText local=${candidateAddress(local)} remote=${candidateAddress(remote)}")
        if (codec != "unknown") Log.d(TAG, "SELECTED VIDEO CODEC: $codec encoder=${encoderImplementation ?: "unknown"} decoder=${decoderImplementation ?: "unknown"}")
        Log.d(TAG, "VIDEO_ENCODER_IMPLEMENTATION: ${encoderImplementation ?: "unknown"} type=${codecImplementationType(encoderImplementation)}")
        Log.d(TAG, "VIDEO_DECODER_IMPLEMENTATION: ${decoderImplementation ?: "unknown"} type=${codecImplementationType(decoderImplementation)}")
        adaptVideoIfNeeded(now, encodedFps, rttMs, jitterMs, recentPacketsLost, availableSendBps, sendBitrate, encoderQueueDepth)
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
                encodeFps = encodedFps,
                decodeFps = decodedFps,
                renderFps = fps,
                availableOutgoingBitrateBps = availableSendBps?.toLong() ?: 0L,
                averageEncodeTimeMs = encodeTimeMs,
                averageDecodeTimeMs = decodeTimeMs,
                captureLatencyMs = captureLatencyMs,
                videoJitterBufferMs = jitterBufferMs,
                renderLatencyMs = renderLatencyMs,
                frameAgeAtRenderMs = frameAgeAtRenderMs,
                encoderQueueDepth = encoderQueueDepth,
                decoderQueueDepth = decoderQueueDepth,
                renderQueueDepth = renderQueueDepth,
                encoderImplementation = encoderImplementation ?: "Unknown",
                decoderImplementation = decoderImplementation ?: "Unknown",
                videoBottleneck = bottleneck,
                gameAudioPacketsSent = audioPacketsSent,
                gameAudioBytesSent = audioBytesSent,
                gameAudioPacketsReceived = audioPacketsReceived,
                gameAudioBytesReceived = audioBytesReceived,
                controlBufferedBytes = controlBufferedBytes,
                digitalQueueDepth = digitalQueueDepth,
                analogQueueDepth = analogQueueDepth,
                droppedStaleAnalogPackets = droppedAnalogPackets
            )
        }
        Log.d(TAG, "WEBRTC STATS: VIDEO BITRATE send=$sendBitrate receive=$receiveBitrate FRAMES ENCODED=$framesEncoded FRAMES DECODED=$framesDecoded FRAMES DROPPED=$framesDropped RTT_MS=${format(rttMs)} PACKET LOSS=$packetsLost JITTER_MS=${format(jitterMs)} AVAILABLE SEND BITRATE=${format(availableSendBps)}")
        Log.d(TAG, "VIDEO_ENCODE_FPS: ${format(encodedFps)}")
        Log.d(TAG, "VIDEO_ENCODE_TIME: intervalAvgMs=${format(encodeTimeMs)}")
        Log.d(TAG, "VIDEO_PIPELINE_LATENCY: captureMs=${format(captureLatencyMs)} encodeMs=${format(encodeTimeMs)} jitterBufferMs=${format(jitterBufferMs)} decodeMs=${format(processingLatencyMs)} renderMs=${format(renderLatencyMs)}")
        Log.d(TAG, "VIDEO_QUEUE_DEPTH: encoder=$encoderQueueDepth decoder=$decoderQueueDepth renderer=$renderQueueDepth")
        Log.d(TAG, "VIDEO_FRAME_AGE_AT_RENDER: estimatedMs=${format(frameAgeAtRenderMs)} method=capture+encode+rtt/2+jitterBuffer+decode+renderQueue")
        Log.d(TAG, "VIDEO_DROPPED_FRAMES: $framesDropped")
        if (audioPacketsSent > 0L || audioBytesSent > lastAudioBytesSent) {
            Log.d(TAG, "GAME_AUDIO_RTP_PACKETS_SENT: $audioPacketsSent GAME_AUDIO_RTP_BYTES_SENT: $audioBytesSent")
            if (!gameAudioSendConfirmed) { gameAudioSendConfirmed = true; onAudioStatus?.invoke("GAME_AUDIO_SENDING_ACTIVE") }
        }
        if (audioPacketsReceived > 0L || audioBytesReceived > lastAudioBytesReceived) {
            Log.d(TAG, "GAME_AUDIO_RTP_PACKETS_RECEIVED: $audioPacketsReceived GAME_AUDIO_RTP_BYTES_RECEIVED: $audioBytesReceived")
            if (!gameAudioReceiveConfirmed) { gameAudioReceiveConfirmed = true; onAudioStatus?.invoke("GAME_AUDIO_PLAYOUT_ACTIVE") }
        }
        lastAudioBytesSent = audioBytesSent; lastAudioBytesReceived = audioBytesReceived
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
    private fun codecImplementationType(value: String?): String = when {
        value == null -> "unknown"
        value.contains("MediaCodec", true) || value.contains("OMX.", true) || value.contains("c2.", true) -> "hardware-or-platform"
        value.contains("libvpx", true) || value.contains("openh264", true) || value.contains("software", true) -> "software"
        else -> "unknown"
    }

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

    fun startScreenShare(permissionData: Intent, width: Int = 1280, height: Int = 720, fps: Int = 60, profile: String = "Auto") {
        val currentFactory = factory ?: error("WebRTC factory has not been initialized")
        val currentPeer = peer ?: error("PeerConnection has not been created")
        check(screenCapturer == null) { "Screen capture is already active" }
        screenCapturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() { override fun onStop() { Log.d(TAG, "Screen capture projection stopped") } })
        textureHelper = SurfaceTextureHelper.create("DroidLinkScreenCapture", eglBase.eglBaseContext)
        screenSource = currentFactory.createVideoSource(true)
        screenSource!!.adaptOutputFormat(width, height, fps)
        val real = screenSource!!.capturerObserver
        var frames = 0
        val debug = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) { Log.d(TAG, "Screen capturer started: $success"); real.onCapturerStarted(success) }
            override fun onCapturerStopped() { Log.d(TAG, "Screen capturer stopped"); real.onCapturerStopped() }
            override fun onFrameCaptured(frame: VideoFrame) {
                frames++
                val now = android.os.SystemClock.elapsedRealtime()
                if (captureWindowStartMs == 0L) captureWindowStartMs = now
                captureWindowFrames++
                if (now - captureWindowStartMs >= 5_000L) {
                    val captureFps = captureWindowFrames * 1_000.0 / (now - captureWindowStartMs)
                    updateDiagnostics { copy(captureFps = captureFps) }
                    Log.d(TAG, "VIDEO_CAPTURE_FPS: ${format(captureFps)}")
                    captureWindowStartMs = now; captureWindowFrames = 0
                }
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
        videoSender = sender
        adaptiveProfile = profile; baseCaptureWidth = width; baseCaptureHeight = height; baseCaptureFps = fps; adaptationLevel = 0; lastAdaptationMs = 0L
        configureVideoSender(sender, profile, fps)
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
            Log.d(TAG, "GAME_AUDIO_TRACK_CREATED: id=${localAudioTrack?.id()}")
            Log.d(TAG, "GAME_AUDIO_TRACK_ENABLED: ${localAudioTrack?.enabled()}")
            val sender = currentPeer.addTrack(localAudioTrack, listOf("DROIDLINK_STREAM"))
            if (sender == null) {
                val reason = "PeerConnection rejected local playback audio track"
                Log.e(TAG, "AUDIO_UNAVAILABLE_REASON: $reason"); onAudioStatus?.invoke(reason)
            } else {
                currentPeer.setAudioRecording(true)
                Log.d(TAG, "LOCAL_AUDIO_TRACK_ADDED: id=${localAudioTrack?.id()} sender=${sender.id()}")
                onAudioStatus?.invoke("GAME_AUDIO_CAPTURE_STARTING")
            }
        }.onFailure { onAudioStatus?.invoke(it.message ?: "Playback audio unavailable") }
    }

    private fun configureVideoSender(sender: RtpSender, profile: String, fps: Int) {
        val lowLatency = profile == "Low Latency"
        val startBitrate = if (lowLatency) 2_500_000 else 4_000_000
        val maxBitrate = if (lowLatency) 3_500_000 else 8_000_000
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { encoding ->
            encoding.active = true
            encoding.minBitrateBps = 150_000
            encoding.maxBitrateBps = maxBitrate
            encoding.maxFramerate = fps
            encoding.bitratePriority = 2.0
        }
        val parametersSet = sender.setParameters(parameters)
        val bitrateSet = peer?.setBitrate(150_000, startBitrate, maxBitrate) == true
        Log.d(TAG, "VIDEO_LOW_LATENCY_SENDER: profile=$profile parametersSet=$parametersSet bitrateSet=$bitrateSet startBitrate=$startBitrate maxBitrate=$maxBitrate fps=$fps degradation=MAINTAIN_FRAMERATE")
    }

    private fun adaptVideoIfNeeded(now: Long, encodedFps: Double?, rttMs: Double?, jitterMs: Double?, recentLoss: Long, availableSendBps: Double?, sendBitrate: Long, encoderQueueDepth: Int) {
        if (adaptiveProfile != "Auto" && adaptiveProfile != "Low Latency") return
        val since = now - lastAdaptationMs
        val lowLatency = adaptiveProfile == "Low Latency"
        val constrained = (rttMs ?: 0.0) >= (if (lowLatency) 120.0 else 170.0) || (jitterMs ?: 0.0) >= (if (lowLatency) 25.0 else 40.0) || recentLoss >= (if (lowLatency) 3L else 8L) ||
            (availableSendBps != null && sendBitrate > 500_000L && availableSendBps < sendBitrate * 1.05) ||
            (encodedFps != null && encodedFps in 1.0..(if (lowLatency) 42.0 else 22.0)) || encoderQueueDepth >= (if (lowLatency) 3 else 8)
        val healthy = (rttMs ?: 999.0) < 80.0 && (jitterMs ?: 999.0) < 20.0 && recentLoss <= 1L &&
            (availableSendBps == null || availableSendBps > 3_000_000.0) && (encodedFps == null || encodedFps >= (if (lowLatency) 50.0 else 28.0)) && encoderQueueDepth == 0
        val degradeDelay = if (lowLatency) 5_000L else 15_000L
        when {
            constrained && adaptationLevel < 3 && since >= degradeDelay -> applyVideoAdaptation(adaptationLevel + 1, now, "freshness pressure queue=$encoderQueueDepth")
            healthy && adaptationLevel > 0 && since >= 45_000L -> applyVideoAdaptation(adaptationLevel - 1, now, "sustained recovery")
        }
    }

    private fun applyVideoAdaptation(level: Int, now: Long, reason: String) {
        val sender = videoSender ?: return
        adaptationLevel = level.coerceIn(0, 3); lastAdaptationMs = now
        val scale = when (adaptationLevel) { 3 -> 0.5; 2 -> 0.75; else -> 1.0 }
        val fps = when (adaptationLevel) { 0, 1 -> baseCaptureFps; 2 -> minOf(baseCaptureFps, 45); else -> minOf(baseCaptureFps, 30) }
        val maxBitrate = when (adaptationLevel) { 0 -> if (adaptiveProfile == "Low Latency") 3_500_000 else 5_000_000; 1 -> 2_500_000; 2 -> 1_500_000; else -> 800_000 }
        val parameters = sender.parameters
        parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        parameters.encodings.forEach { it.maxBitrateBps = maxBitrate; it.maxFramerate = fps }
        val set = sender.setParameters(parameters)
        val width = ((baseCaptureWidth * scale).toInt() / 2 * 2).coerceAtLeast(320)
        val height = ((baseCaptureHeight * scale).toInt() / 2 * 2).coerceAtLeast(240)
        if (adaptationLevel >= 2) runCatching {
            screenSource?.adaptOutputFormat(width, height, fps)
            screenCapturer?.changeCaptureFormat(width, height, fps)
        }
        Log.w(TAG, "VIDEO_ADAPTATION: profile=$adaptiveProfile level=$adaptationLevel ${width}x$height@$fps maxBitrate=$maxBitrate reason=$reason parametersSet=$set")
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

    fun setGameAudioEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
        remoteGameAudioTrack?.setEnabled(enabled)
        peer?.setAudioPlayout(enabled)
        Log.d(TAG, "GAME_AUDIO_USER_ENABLED: $enabled")
    }

    fun setGameAudioVolume(volume: Float) {
        remoteGameAudioTrack?.setVolume(volume.coerceIn(0f, 1f).toDouble())
        Log.d(TAG, "GAME_AUDIO_VOLUME: ${(volume.coerceIn(0f, 1f) * 100).toInt()}")
    }

    fun close() {
        if (closed) return
        closed = true; Log.d(TAG, "Closing WebRTC session")
        // Break Activity callback references before native close/dispose can emit late events.
        onControlMessageReceived = null
        onConnectionStateChanged = null
        onRemoteVideoTrack = null
        onIceCandidateReady = null
        onDiagnostics = null
        onAudioStatus = null
        onDataChannelStateChanged = null
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
        screenCapturer = null; textureHelper = null; screenTrack = null; screenSource = null; videoSender = null
        remoteVideoTrack = null; remoteFrameSink = null; remoteGameAudioTrack = null; localAudioTrack = null; localAudioSource = null
        audioDeviceModule = null; dataChannel = null; axisDataChannel = null; peer = null; factory = null
        synchronized(lock) { pendingCandidates.clear(); remoteDescriptionSet = false }
        diagnostics = BetaDiagnostics()
        lastStatsTimestampMs = 0L; lastBytesSent = 0L; lastBytesReceived = 0L; lastFramesDecoded = 0L; lastFramesEncoded = 0L
        lastFramesCaptured = 0L; lastFramesReceived = 0L; lastFramesRendered = 0L; stagnantDecodeIntervals = 0
        lastTotalCaptureDelaySeconds = 0.0; lastTotalEncodeTimeSeconds = 0.0; lastTotalDecodeTimeSeconds = 0.0
        lastJitterBufferDelaySeconds = 0.0; lastJitterBufferEmittedCount = 0L
        lastAudioBytesSent = 0L; lastAudioBytesReceived = 0L; captureWindowStartMs = 0L; captureWindowFrames = 0
        gameAudioSendConfirmed = false; gameAudioReceiveConfirmed = false
        adaptationLevel = 0; lastAdaptationMs = 0L; lastPacketsLost = 0L
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
