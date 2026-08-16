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

    fun createAudioDeviceModule(): JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
        .setSampleRate(SAMPLE_RATE).setInputSampleRate(SAMPLE_RATE).setOutputSampleRate(SAMPLE_RATE)
        .setUseStereoInput(true).setUseStereoOutput(true).setUseLowLatency(true)
        .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
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
            if (!pcmLogged && copied > 0) {
                var active = false; var index = 0
                while (index + 1 < copied && !active) { active = buffer.getShort(index).toInt() != 0; index += 32 }
                if (active) { pcmLogged = true; Log.d(TAG, "AUDIO_PCM_ACTIVE: first non-silent playback PCM delivered to WebRTC bytes=$copied") }
                else if (++silentCallbacks == 500) Log.w(TAG, "AUDIO_UNAVAILABLE_REASON: playback capture remained silent for 5 seconds; source may be silent or opted out")
            }
            buffer.rewind()
            captureTimeNs
        }
        .createAudioDeviceModule().also {
            val audioInput = it.javaClass.getField("audioInput").get(it)
            audioInput.javaClass.getDeclaredMethod("setUseAudioRecord", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(audioInput, false)
            it.setMicrophoneMute(false)
            it.setNoiseSuppressorEnabled(false)
            Log.d(TAG, "AudioDeviceModule configured for external playback PCM: sampleRate=$SAMPLE_RATE channels=$CHANNELS")
        }

    fun start(mediaProjection: MediaProjection): Result<Unit> = runCatching {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "AudioPlaybackCapture requires Android 10+" }
        stop()
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
        Log.d(TAG, "AUDIO_CAPTURE_STARTED")
        Unit
    }.onFailure { Log.e(TAG, "AUDIO_UNAVAILABLE_REASON: ${it.message}", it); stop() }

    fun stop() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        Log.d(TAG, "Audio playback capture stopped")
    }
}
