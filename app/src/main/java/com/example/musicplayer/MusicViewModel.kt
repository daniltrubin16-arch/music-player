package com.example.musicplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.musicplayer.data.MediaRepository
import com.example.musicplayer.data.PlaylistEntity
import com.example.musicplayer.data.PlaylistTrackEntity
import com.example.musicplayer.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistUi(val id: Long, val name: String, val tracks: List<Track>)

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val musicApp = app as MusicApplication
    private val dao = musicApp.database.playlistDao()
    val player = musicApp.player

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    val playlists: StateFlow<List<PlaylistUi>> = combine(
        dao.observePlaylists(),
        dao.observeAllEntries(),
        _tracks
    ) { lists, entries, tracks ->
        val byId = tracks.associateBy { it.id }
        lists.map { p ->
            PlaylistUi(
                id = p.id,
                name = p.name,
                tracks = entries
                    .filter { it.playlistId == p.id }
                    .sortedBy { it.id }
                    .mapNotNull { byId[it.mediaId] }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
