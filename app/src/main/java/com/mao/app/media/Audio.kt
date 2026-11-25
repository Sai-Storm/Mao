package com.mao.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Records AAC voice notes into the app cache. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null
    val elapsedMs get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    fun start() {
        stopQuietly()
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
    }

    /** @return the recorded file and its duration, or null if it was too short to be useful. */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val duration = (SystemClock.elapsedRealtime() - startedAt).toInt()
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file
        file = null
        if (!ok || f == null || duration < 700) { f?.delete(); return null }
        return f to duration
    }

    fun cancel() { stopQuietly() }

    private fun stopQuietly() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
    }
}

data class PlaybackState(val key: String? = null, val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0)

/** Streams one voice message at a time. */
class AudioPlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    fun toggle(key: String, url: String) {
        val current = _state.value
        val p = player
        if (current.key == key && p != null) {
            if (p.isPlaying) { p.pause(); _state.value = current.copy(playing = false) }
            else { p.start(); _state.value = current.copy(playing = true); startTicker() }
            return
        }
        release()
        _state.value = PlaybackState(key = key, playing = false)
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        mp.setDataSource(url)
        mp.setOnPreparedListener {
            it.start()
            _state.value = PlaybackState(key, true, 0, it.duration)
            startTicker()
        }
        mp.setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = 0) }
        mp.setOnErrorListener { _, _, _ -> release(); true }
        mp.prepareAsync()
        player = mp
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (!p.isPlaying) break
                _state.value = _state.value.copy(positionMs = p.currentPosition)
                delay(200)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = PlaybackState()
    }
}
