package com.example.musicplayer.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.musicplayer.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatAll: Boolean = true
) {
    val current: Track? get() = queue.getOrNull(index)
}

/** Обёртка над MediaPlayer: очередь, перемешивание, повтор, аудиофокус. */
class PlayerManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var ticker: Job? = null

    /** Порядок воспроизведения: индексы очереди. При перемешивании каждый трек встречается по разу за круг. */
    private var order: List<Int> = emptyList()
    private var pos = 0

    private fun buildOrder(queueSize: Int, first: Int, shuffle: Boolean) {
        if (shuffle) {
            val rest = (0 until queueSize).filter { it != first }.shuffled()
            order = listOf(first) + rest
            pos = 0
        } else {
            order = (0 until queueSize).toList()
            pos = first
        }
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) pause()
        }
        .build()

    /** Запускает очередь с позиции [startIndex]. [shuffle] != null — переключает режим перемешивания. */
    fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean? = null) {
        if (queue.isEmpty()) return
        val start = startIndex.coerceIn(queue.indices)
        val shuffleOn = shuffle ?: _state.value.shuffle
        buildOrder(queue.size, start, shuffleOn)
        _state.update { it.copy(queue = queue, index = start, shuffle = shuffleOn) }
        startCurrent()
    }

    private fun startCurrent() {
        val track = _state.value.current ?: return
        releasePlayer()
        audioManager.requestAudioFocus(focusRequest)
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setAudioAttributes(attrs)
            mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(appContext, track.uri)
            mp.setOnPreparedListener { p ->
                if (mediaPlayer !== p) return@setOnPreparedListener
                p.start()
                _state.update {
                    it.copy(isPlaying = true, positionMs = 0, durationMs = p.duration.toLong())
                }
                startTicker()
                ensureService()
            }
            mp.setOnCompletionListener { p ->
                if (mediaPlayer === p) next(auto = true)
            }
            mp.setOnErrorListener { p, _, _ ->
                if (mediaPlayer === p) {
                    releasePlayer()
                    _state.update { it.copy(isPlaying = false) }
                }
                true
            }
            _state.update { it.copy(positionMs = 0, durationMs = track.durationMs) }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun pause() {
        val p = mediaPlayer ?: return
        runCatching { if (p.isPlaying) p.pause() }
        _state.update { it.copy(isPlaying = false) }
    }

    fun resume() {
        val p = mediaPlayer
        if (p == null) {
            if (_state.value.current != null) startCurrent()
            return
        }
        audioManager.requestAudioFocus(focusRequest)
        runCatching { p.start() }
        _state.update { it.copy(isPlaying = true) }
        ensureService()
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun next(auto: Boolean = false) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (pos + 1 < order.size) {
            pos++
        } else if (s.repeatAll || !auto) {
            // Круг закончился: новый круг. При перемешивании — заново, но первым не ставим только что сыгранный.
            if (s.shuffle && s.queue.size > 1) {
                var newOrder: List<Int>
                do {
                    newOrder = (0 until s.queue.size).shuffled()
                } while (newOrder.first() == s.index)
                order = newOrder
            }
            pos = 0
        } else {
            stop()
            return
        }
        _state.update { it.copy(index = order[pos]) }
        startCurrent()
    }

    fun previous() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        if (s.positionMs > 3000) {
            seekTo(0)
            return
        }
        pos = if (pos - 1 >= 0) pos - 1 else order.lastIndex
        _state.update { it.copy(index = order[pos]) }
        startCurrent()
    }

    fun seekTo(ms: Long) {
        runCatching { mediaPlayer?.seekTo(ms.toInt()) }
        _state.update { it.copy(positionMs = ms) }
    }

    /** Файлы удалены: убираем их из очереди. Если играл удалённый трек — останавливаем плеер. */
    fun onTracksDeleted(ids: Set<Long>) {
        val s = _state.value
        if (s.queue.none { it.id in ids }) return
        val currentId = s.current?.id
        val newQueue = s.queue.filter { it.id !in ids }
        val newIndex = if (currentId == null) -1 else newQueue.indexOfFirst { it.id == currentId }
        if (newIndex < 0) {
            releasePlayer()
            order = emptyList()
            pos = 0
            _state.value = PlayerState(shuffle = s.shuffle, repeatAll = s.repeatAll)
        } else {
            buildOrder(newQueue.size, newIndex, s.shuffle)
            _state.update { it.copy(queue = newQueue, index = newIndex) }
        }
    }

    fun toggleShuffle() {
        val s = _state.value
        val on = !s.shuffle
        if (s.queue.isNotEmpty() && s.index in s.queue.indices) {
            buildOrder(s.queue.size, s.index, on)
        }
        _state.update { it.copy(shuffle = on) }
    }

    fun toggleRepeat() = _state.update { it.copy(repeatAll = !it.repeatAll) }

    private fun stop() {
        releasePlayer()
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        ticker = null
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = mediaPlayer
                if (p != null) {
                    val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                    _state.update { it.copy(positionMs = pos) }
                }
                delay(500)
            }
        }
    }

    private fun ensureService() {
        if (PlaybackService.running) return
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, PlaybackService::class.java)
            )
        }
    }
}
