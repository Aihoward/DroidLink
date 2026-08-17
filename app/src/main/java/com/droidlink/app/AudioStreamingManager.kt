package com.droidlink.app

import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import org.webrtc.audio.JavaAudioDeviceModule

class AudioStreamingManager(private val context: Context) {
    companion object { const val SAMPLE_RATE = 48_000; const val CHANNELS = 2; private const val TAG = "DroidLink" }
    private var audioRecord: AudioRecord? = null
    @Volatile private var pcmLogged = false
    private var silentCallbacks = 0
    var onStatus: ((String) -> Unit)? = null
    @Volatile var outputState: String = "Not started"
        private set

    private val gameAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setFlags(AudioAttributes.FLAG_LOW_LATENCY) }
        .build()

    fun createAudioDeviceModule(): JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
        .setSampleRate(SAMPLE_RATE).setInputSampleRate(SAMPLE_RATE).setOutputSampleRate(SAMPLE_RATE)
        .setUseStereoInput(true).setUseStereoOutput(true).setUseLowLatency(true)
        .setAudioAttributes(gameAudioAttributes)
        .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                outputState = "Playing"
                Log.d(TAG, "GAME_AUDIO_OUTPUT_STARTED: usage=GAME content=MUSIC lowLatency=true")
                onStatus?.invoke("GAME_AUDIO_OUTPUT_STARTED")
            }
            override fun onWebRtcAudioTrackStop() {
                outputState = "Stopped"
                Log.d(TAG, "GAME_AUDIO_OUTPUT_STOPPED")
            }
        })
        .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                outputState = "Initialization error"
                Log.e(TAG, "GAME_AUDIO_OUTPUT_INIT_ERROR: $errorMessage")
                onStatus?.invoke("GAME_AUDIO_OUTPUT_ERROR")
            }
            override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?, errorMessage: String?) {
                outputState = "Start error"
                Log.e(TAG, "GAME_AUDIO_OUTPUT_START_ERROR: $errorCode $errorMessage")
                onStatus?.invoke("GAME_AUDIO_OUTPUT_ERROR")
            }
            override fun onWebRtcAudioTrackError(errorMessage: String?) {
                outputState = "Playback error"
                Log.e(TAG, "GAME_AUDIO_OUTPUT_ERROR: $errorMessage")
                onStatus?.invoke("GAME_AUDIO_OUTPUT_ERROR")
            }
        })
        .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() { Log.d(TAG, "GAME_AUDIO_WEBRTC_INPUT_STARTED") }
            override fun onWebRtcAudioRecordStop() { Log.d(TAG, "GAME_AUDIO_WEBRTC_INPUT_STOPPED") }
        })
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String?) { Log.e(TAG, "GAME_AUDIO_WEBRTC_INPUT_INIT_ERROR: $errorMessage"); onStatus?.invoke("GAME_AUDIO_CAPTURE_ERROR") }
            override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?, errorMessage: String?) { Log.e(TAG, "GAME_AUDIO_WEBRTC_INPUT_START_ERROR: $errorCode $errorMessage"); onStatus?.invoke("GAME_AUDIO_CAPTURE_ERROR") }
            override fun onWebRtcAudioRecordError(errorMessage: String?) { Log.e(TAG, "GAME_AUDIO_WEBRTC_INPUT_ERROR: $errorMessage"); onStatus?.invoke("GAME_AUDIO_CAPTURE_ERROR") }
        })
        .setAudioBufferCallback { buffer, _, _, _, _, captureTimeNs ->
            val wanted = buffer.capacity()
            buffer.clear()
            val copied = audioRecord?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }
                ?.read(buffer, wanted, AudioRecord.READ_BLOCKING) ?: run {
                repeat(wanted) { buffer.put(0) }
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
                wanted
            }
            if (copied < wanted) while (buffer.position() < wanted) buffer.put(0)
            buffer.rewind()
            if (copied > 0 && !pcmLogged) {
                var sumSquares = 0.0; var samples = 0; var index = 0
                while (index + 1 < copied) { val sample = buffer.getShort(index).toDouble() / Short.MAX_VALUE; sumSquares += sample * sample; samples++; index += 32 }
                val rms = if (samples > 0) kotlin.math.sqrt(sumSquares / samples) else 0.0
                if (rms > 0.0005) {
                    pcmLogged = true
                    Log.d(TAG, "GAME_AUDIO_CAPTURE_ACTIVE: first non-silent PCM rms=$rms bytes=$copied")
                    onStatus?.invoke("GAME_AUDIO_CAPTURE_ACTIVE")
                } else if (++silentCallbacks == 500) {
                    Log.w(TAG, "GAME_AUDIO_CAPTURE_SILENT: playback capture remained silent for 5 seconds; source may be silent or opted out")
                    onStatus?.invoke("GAME_AUDIO_CAPTURE_SILENT")
                }
            }
            buffer.rewind()
            captureTimeNs
        }
        .createAudioDeviceModule().also {
            val audioInput = it.javaClass.getField("audioInput").get(it)
            audioInput.javaClass.getDeclaredMethod("setUseAudioRecord", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(audioInput, false)
            it.setMicrophoneMute(false)
            it.setNoiseSuppressorEnabled(false)
            Log.d(TAG, "AudioDeviceModule configured for external playback PCM: sampleRate=$SAMPLE_RATE channels=$CHANNELS outputUsage=GAME outputContent=MUSIC lowLatency=true")
        }

    fun start(mediaProjection: MediaProjection): Result<Unit> = runCatching {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "AudioPlaybackCapture requires Android 10+" }
        stop()
        onStatus?.invoke("GAME_AUDIO_CAPTURE_STARTING")
        Log.d(TAG, "AUDIO_CAPTURE_PERMISSION_READY")
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioRecord reported invalid minimum buffer size: $min" }
        val record = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(min * 4)
            .setAudioPlaybackCaptureConfig(config).build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Playback AudioRecord failed to initialize" }
        audioRecord = record
        Log.d(TAG, "AUDIO_PLAYBACK_CAPTURE_CREATED")
        Log.d(TAG, "AUDIO_SAMPLE_RATE: ${record.sampleRate}")
        Log.d(TAG, "AUDIO_CHANNEL_COUNT: ${record.channelCount}")
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Playback AudioRecord did not enter RECORDING state" }
        pcmLogged = false; silentCallbacks = 0
        Log.d(TAG, "GAME_AUDIO_CAPTURE_STARTING: AudioRecord started; waiting for non-zero PCM")
        Unit
    }.onFailure {
        val state = if (it is SecurityException) "GAME_AUDIO_CAPTURE_BLOCKED" else "GAME_AUDIO_CAPTURE_ERROR"
        Log.e(TAG, "$state: ${it.message}", it); onStatus?.invoke(state); stop()
    }

    fun stop() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Audio playback capture stopped")
    }
}
