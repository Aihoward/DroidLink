package com.droidlink.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

object MenuMusicPolicy {
    fun shouldPlay(
        enabled: Boolean,
        normalMenuVisible: Boolean,
        sessionActive: Boolean,
        sessionStarting: Boolean
    ) = enabled && normalMenuVisible && !sessionActive && !sessionStarting
}

class MenuMusicController(context: Context) {
    companion object {
        private const val TAG = "DroidLink"
        private const val MENU_VOLUME = 0.35f
    }

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var foreground = false
    private var playbackDesired = false
    private var unavailable = false

    fun setPlaybackDesired(desired: Boolean) {
        playbackDesired = desired
        applyPlaybackState()
    }

    fun onForeground() {
        foreground = true
        applyPlaybackState()
    }

    fun onBackground() {
        foreground = false
        pauseSafely()
    }

    fun release() {
        playbackDesired = false
        foreground = false
        releasePlayer()
    }

    private fun applyPlaybackState() {
        if (!foreground || !playbackDesired) {
            pauseSafely()
            return
        }
        if (unavailable) return
        val activePlayer = player ?: createPlayer() ?: return
        try {
            if (!activePlayer.isPlaying) activePlayer.start()
        } catch (error: Throwable) {
            disableAfterFailure("start", error)
        }
    }

    private fun createPlayer(): MediaPlayer? = try {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val createdPlayer = MediaPlayer.create(
            appContext,
            R.raw.menu_music,
            attributes,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        if (createdPlayer == null) {
            unavailable = true
            Log.w(TAG, "MENU_MUSIC_UNAVAILABLE: MediaPlayer.create returned null")
            null
        } else {
            player = createdPlayer
            createdPlayer.apply {
                isLooping = true
                setVolume(MENU_VOLUME, MENU_VOLUME)
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MENU_MUSIC_UNAVAILABLE: playback error what=$what extra=$extra")
                    unavailable = true
                    releasePlayer()
                    true
                }
                Log.d(TAG, "MENU_MUSIC_READY: single looping player initialized")
            }
        }
    } catch (error: Throwable) {
        disableAfterFailure("initialize", error)
        null
    }

    private fun pauseSafely() {
        val activePlayer = player ?: return
        try {
            if (activePlayer.isPlaying) activePlayer.pause()
        } catch (error: Throwable) {
            disableAfterFailure("pause", error)
        }
    }

    private fun disableAfterFailure(operation: String, error: Throwable) {
        unavailable = true
        Log.w(TAG, "MENU_MUSIC_UNAVAILABLE: $operation failed; app will continue", error)
        releasePlayer()
    }

    private fun releasePlayer() {
        val activePlayer = player ?: return
        player = null
        try { activePlayer.setOnErrorListener(null) }
        catch (error: Throwable) { Log.w(TAG, "MENU_MUSIC_LISTENER_CLEAR_FAILED: app will continue", error) }
        try { activePlayer.release() }
        catch (error: Throwable) {
            Log.w(TAG, "MENU_MUSIC_RELEASE_FAILED: app will continue", error)
        }
    }
}
