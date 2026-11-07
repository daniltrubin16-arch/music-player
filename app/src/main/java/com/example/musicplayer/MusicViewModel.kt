package com.example.musicplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.data.MediaRepository
import com.example.musicplayer.data.Recommender
import com.example.musicplayer.data.PlaylistEntity
import com.example.musicplayer.data.PlaylistTrackEntity
import com.example.musicplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistUi(val id: Long, val name: String, val tracks: List<Track>)

enum class SortMode(val label: String) {
    TITLE("По названию"),
    NEWEST("Сначала новые"),
    OLDEST("Сначала старые");

    fun sort(list: List<Track>): List<Track> = when (this) {
        TITLE -> list.sortedBy { it.title.lowercase() }
        NEWEST -> list.sortedByDescending { it.dateAdded }
        OLDEST -> list.sortedBy { it.dateAdded }
    }
}

/** Состояние вкладки «Рекомендации». */
data class RecUi(
    val loading: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val error: String? = null
)

/** Порядок треков внутри плейлиста. Дата добавления здесь — момент, когда трек попал в плейлист. */
enum class PlaylistSortMode(val label: String) {
    ADDED("В порядке добавления"),
    NEWEST("Сначала новые"),
    TITLE("По названию")
}

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val musicApp = app as MusicApplication
    private val dao = musicApp.database.playlistDao()
    val player = musicApp.player

    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())

    private val _sortMode = MutableStateFlow(
        runCatching { SortMode.valueOf(prefs.getString("sort", null) ?: "") }
            .getOrDefault(SortMode.NEWEST)
    )
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** Библиотека в выбранном порядке. Плейлисты сортировку не меняют. */
    val tracks: StateFlow<List<Track>> = combine(_tracks, _sortMode) { list, mode ->
        mode.sort(list)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.edit().putString("sort", mode.name).apply()
    }

    private val _playlistSort = MutableStateFlow(
        runCatching { PlaylistSortMode.valueOf(prefs.getString("playlist_sort", null) ?: "") }
            .getOrDefault(PlaylistSortMode.ADDED)
    )
    val playlistSort: StateFlow<PlaylistSortMode> = _playlistSort.asStateFlow()

    fun setPlaylistSort(mode: PlaylistSortMode) {
        _playlistSort.value = mode
        prefs.edit().putString("playlist_sort", mode.name).apply()
    }

    val playlists: StateFlow<List<PlaylistUi>> = combine(
        dao.observePlaylists(),
        dao.observeAllEntries(),
        _tracks,
        _playlistSort
    ) { lists, entries, tracks, sort ->
        val byId = tracks.associateBy { it.id }
        lists.map { p ->
            PlaylistUi(
                id = p.id,
                name = p.name,
                tracks = entries
                    .filter { it.playlistId == p.id }
                    .let { list ->
                        when (sort) {
                            PlaylistSortMode.ADDED -> list.sortedBy { it.id }
                            PlaylistSortMode.NEWEST -> list.sortedByDescending { it.id }
                            PlaylistSortMode.TITLE -> list.sortedBy {
                                byId[it.mediaId]?.title?.lowercase() ?: ""
                            }
                        }
                    }
                    .mapNotNull { byId[it.mediaId] }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _rec = MutableStateFlow(RecUi())
    val recommendations: StateFlow<RecUi> = _rec.asStateFlow()

    fun loadRecommendations() {
        if (_rec.value.loading) return
        val library = _tracks.value
        if (library.isEmpty()) return
        _rec.value = _rec.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = Recommender.recommend(library)
            _rec.value = if (result.isEmpty()) {
                RecUi(error = "Не удалось подобрать музыку. Проверьте интернет и нажмите «Обновить».")
            } else {
                RecUi(tracks = result)
            }
        }
    }

    /** Android 10 и старше: удаляет файлы напрямую. [onDone] получает число удалённых. */
    fun deleteDirect(list: List<Track>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val deleted = withContext(Dispatchers.IO) {
                list.filter { t ->
                    runCatching { resolver.delete(t.uri, null, null) }.getOrDefault(0) > 0
                }
            }
            onTracksDeleted(deleted)
            onDone(deleted.size)
        }
    }

    /** Вызывается, когда файлы уже удалены: чистит плейлисты, плеер и список библиотеки. */
    fun onTracksDeleted(list: List<Track>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }
        viewModelScope.launch {
            ids.chunked(500).forEach { dao.removeEntriesByMediaIds(it) }
            player.onTracksDeleted(ids.toSet())
            _tracks.value = _tracks.value.filter { it.id !in ids }
        }
    }

    fun refreshTracks() {
        viewModelScope.launch {
            _tracks.value = MediaRepository.loadTracks(getApplication())
        }
    }

    fun createPlaylist(name: String, thenAdd: Track? = null) {
        viewModelScope.launch {
            val id = dao.insertPlaylist(PlaylistEntity(name = name))
            if (thenAdd != null) {
                dao.insertEntry(PlaylistTrackEntity(playlistId = id, mediaId = thenAdd.id))
            }
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { dao.renamePlaylist(id, name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { dao.deletePlaylist(id) }
    }

    /** [onResult] получает true, если трек добавлен, и false, если он уже был в плейлисте. */
    fun addToPlaylist(playlistId: Long, track: Track, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            if (dao.countEntry(playlistId, track.id) > 0) {
                onResult(false)
            } else {
                dao.insertEntry(PlaylistTrackEntity(playlistId = playlistId, mediaId = track.id))
                onResult(true)
            }
        }
    }

    fun removeFromPlaylist(playlistId: Long, track: Track) {
        viewModelScope.launch { dao.removeEntry(playlistId, track.id) }
    }
}
