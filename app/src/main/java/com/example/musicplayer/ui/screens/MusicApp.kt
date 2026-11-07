package com.example.musicplayer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.musicplayer.MusicViewModel
import com.example.musicplayer.PlaylistSortMode
import com.example.musicplayer.RecUi
import com.example.musicplayer.PlaylistUi
import com.example.musicplayer.SortMode
import com.example.musicplayer.data.Track
import com.example.musicplayer.player.PlayerManager
import com.example.musicplayer.player.PlayerState
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicApp(vm: MusicViewModel) {
    val context = LocalContext.current

    // ---- Разрешения ----
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun isGranted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted(audioPermission)) }
    val allPermissions = remember {
        buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = isGranted(audioPermission) }

    LaunchedEffect(Unit) {
        val missing = allPermissions.filter { !isGranted(it) }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    LaunchedEffect(granted) { if (granted) vm.refreshTracks() }

    // ---- Состояние ----
    val tracks by vm.tracks.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val playerState by vm.player.state.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val playlistSort by vm.playlistSort.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }

    var tab by remember { mutableIntStateOf(0) }
    var openPlaylistId by remember { mutableStateOf<Long?>(null) }
    var trackToAdd by remember { mutableStateOf<Track?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var createThenAdd by remember { mutableStateOf<Track?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistUi?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistUi?>(null) }

    val openPlaylist = playlists.firstOrNull { it.id == openPlaylistId }
    BackHandler(enabled = tab == 1 && openPlaylist != null) { openPlaylistId = null }

    val rec by vm.recommendations.collectAsState()
    // Первый заход на вкладку «Рекомендации» запускает подбор автоматически.
    LaunchedEffect(tab, tracks.isNotEmpty()) {
        if (tab == 2 && tracks.isNotEmpty() && rec.tracks.isEmpty() && !rec.loading && rec.error == null) {
            vm.loadRecommendations()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            tab == 0 -> "Библиотека"
                            tab == 2 -> "Рекомендации"
                            openPlaylist != null -> openPlaylist.name
                            else -> "Плейлисты"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (tab == 1 && openPlaylist != null) {
                        IconButton(onClick = { openPlaylistId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    // Сортировка: в библиотеке и внутри открытого плейлиста.
                    if (tab == 0 || (tab == 1 && openPlaylist != null)) {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Сортировка")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false }
                            ) {
                                if (tab == 0) {
                                    SortMode.values().forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            leadingIcon = {
                                                if (mode == sortMode) {
                                                    Icon(Icons.Filled.Check, contentDescription = null)
                                                }
                                            },
                                            onClick = {
                                                vm.setSortMode(mode)
                                                sortMenuOpen = false
                                            }
                                        )
                                    }
                                } else {
                                    PlaylistSortMode.values().forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            leadingIcon = {
                                                if (mode == playlistSort) {
                                                    Icon(Icons.Filled.Check, contentDescription = null)
                                                }
                                            },
                                            onClick = {
                                                vm.setPlaylistSort(mode)
                                                sortMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                if (playerState.current != null) PlayerBar(vm.player, playerState)
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                        label = { Text("Библиотека") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                        label = { Text("Плейлисты") }
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Explore, contentDescription = null) },
                        label = { Text("Рекомендации") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 1 && openPlaylist == null) {
                ExtendedFloatingActionButton(
                    onClick = { createThenAdd = null; showCreate = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Новый плейлист") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !granted -> PermissionMessage { launcher.launch(allPermissions.toTypedArray()) }

                tab == 2 -> RecommendationsScreen(
                    rec = rec,
                    hasLibrary = tracks.isNotEmpty(),
                    currentId = playerState.current?.id,
                    onRefresh = { vm.loadRecommendations() },
                    onPlay = { index -> vm.player.playQueue(rec.tracks, index) }
                )

                tab == 0 -> TrackList(
                    tracks = tracks,
                    currentId = playerState.current?.id,
                    emptyText = "На устройстве не найдено музыки",
                    onPlay = { index -> vm.player.playQueue(tracks, index) },
                    menuContent = { track, dismiss ->
                        DropdownMenuItem(
                            text = { Text("Добавить в плейлист") },
                            onClick = { dismiss(); trackToAdd = track }
                        )
                    }
                )

                openPlaylist == null -> PlaylistList(
                    playlists = playlists,
                    onOpen = { openPlaylistId = it.id },
                    onRename = { renameTarget = it },
                    onDelete = { deleteTarget = it }
                )

                else -> PlaylistDetail(
                    playlist = openPlaylist,
                    currentId = playerState.current?.id,
                    onPlayAll = { shuffle ->
                        val list = openPlaylist.tracks
                        if (list.isNotEmpty()) {
                            vm.player.playQueue(
                                list,
                                if (shuffle) Random.nextInt(list.size) else 0,
                                shuffle
                            )
                        }
                    },
                    onPlayAt = { index -> vm.player.playQueue(openPlaylist.tracks, index) },
                    onRemove = { track -> vm.removeFromPlaylist(openPlaylist.id, track) }
                )
            }
        }
    }

    // ---- Диалоги ----
    trackToAdd?.let { track ->
        ChoosePlaylistDialog(
            playlists = playlists,
            onChoose = { p ->
                vm.addToPlaylist(p.id, track) { added ->
                    val msg = if (added) "Добавлено в «${p.name}»" else "Уже есть в «${p.name}»"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                trackToAdd = null
            },
            onNew = {
                createThenAdd = track
                showCreate = true
                trackToAdd = null
            },
            onDismiss = { trackToAdd = null }
        )
    }

    if (showCreate) {
        NameDialog(
            title = "Новый плейлист",
            initial = "",
            confirmText = "Создать",
            onConfirm = { name ->
                vm.createPlaylist(name, createThenAdd)
                showCreate = false
                createThenAdd = null
            },
            onDismiss = { showCreate = false; createThenAdd = null }
        )
    }

    renameTarget?.let { p ->
        NameDialog(
            title = "Переименовать плейлист",
            initial = p.name,
            confirmText = "Сохранить",
            onConfirm = { name -> vm.renamePlaylist(p.id, name); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить плейлист?") },
            text = { Text("Плейлист «${p.name}» будет удалён. Сами песни на устройстве останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(p.id)
                    if (openPlaylistId == p.id) openPlaylistId = null
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }
}

// ---------------- Списки ----------------

@Composable
private fun PermissionMessage(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Чтобы показать вашу музыку, приложению нужен доступ к аудиофайлам.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onRequest) { Text("Разрешить доступ") }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    currentId: Long?,
    emptyText: String,
    onPlay: (Int) -> Unit,
    menuContent: @Composable (Track, () -> Unit) -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    if (tracks.isEmpty() && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emptyText) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            TrackRow(track, track.id == currentId, { onPlay(index) }, menuContent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    menuContent: @Composable (Track, () -> Unit) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = accent)
        },
        supportingContent = {
            Text(
                "${track.artist} · ${formatTime(track.durationMs)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuContent(track) { menuOpen = false }
                }
            }
        }
    )
}

@Composable
private fun PlaylistList(
    playlists: List<PlaylistUi>,
    onOpen: (PlaylistUi) -> Unit,
    onRename: (PlaylistUi) -> Unit,
    onDelete: (PlaylistUi) -> Unit
) {
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Плейлистов пока нет.\nНажмите «Новый плейлист».", textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(playlists, key = { it.id }) { p ->
            PlaylistRow(p, { onOpen(p) }, { onRename(p) }, { onDelete(p) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistUi,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("Треков: ${playlist.tracks.size}") },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    )
}

@Composable
private fun PlaylistDetail(
    playlist: PlaylistUi,
    currentId: Long?,
    onPlayAll: (shuffle: Boolean) -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemove: (Track) -> Unit
) {
    TrackList(
        tracks = playlist.tracks,
        currentId = currentId,
        emptyText = "",
        onPlay = onPlayAt,
        menuContent = { track, dismiss ->
            DropdownMenuItem(
                text = { Text("Убрать из плейлиста") },
                onClick = { dismiss(); onRemove(track) }
            )
        },
        header = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Треков: ${playlist.tracks.size}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(4.dp))
                if (playlist.tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlayAll(false) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text("Играть")
                        }
                        OutlinedButton(onClick = { onPlayAll(true) }) {
                            Icon(Icons.Filled.Shuffle, contentDescription = null)
                            Text("Вперемешку")
                        }
                    }
                } else {
                    Text(
                        "Плейлист пуст. Откройте «Библиотеку» и добавьте треки через меню ⋮.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

// ---------------- Рекомендации ----------------

@Composable
private fun RecommendationsScreen(
    rec: RecUi,
    hasLibrary: Boolean,
    currentId: Long?,
    onRefresh: () -> Unit,
    onPlay: (Int) -> Unit
) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    when {
        !hasLibrary -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Рекомендации подбираются по вашей музыке. Сначала добавьте песни на устройство.",
                textAlign = TextAlign.Center
            )
        }

        rec.loading && rec.tracks.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.padding(8.dp))
                Text("Подбираю музыку…")
            }
        }

        else -> TrackList(
            tracks = rec.tracks,
            currentId = currentId,
            emptyText = "",
            onPlay = onPlay,
            menuContent = { track, dismiss ->
                if (track.webUrl != null) {
                    DropdownMenuItem(
                        text = { Text("Открыть в Deezer") },
                        onClick = { dismiss(); open(track.webUrl) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Найти на YouTube") },
                    onClick = {
                        dismiss()
                        val q = Uri.encode("${track.artist} ${track.title}")
                        open("https://www.youtube.com/results?search_query=$q")
                    }
                )
            },
            header = {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Подобрано по исполнителям из вашей библиотеки. " +
                            "Играют 30-секундные превью; полную версию можно открыть через меню ⋮.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.padding(4.dp))
                    OutlinedButton(onClick = onRefresh, enabled = !rec.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text(if (rec.loading) "Обновляю…" else "Обновить")
                    }
                    if (rec.error != null) {
                        Spacer(Modifier.padding(4.dp))
                        Text(rec.error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }
}

// ---------------- Мини-плеер ----------------

@Composable
private fun PlayerBar(player: PlayerManager, s: PlayerState) {
    val track = s.current ?: return
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            var drag by remember { mutableStateOf<Float?>(null) }
            val duration = s.durationMs.coerceAtLeast(1L)
            val progress = (s.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = drag ?: progress,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let { player.seekTo((it * duration).toLong()) }
                    drag = null
                }
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatTime(((drag ?: progress) * duration).toLong()),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Вперемешку",
                        tint = if (s.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { player.previous() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущий")
                }
                FilledIconButton(onClick = { player.togglePlayPause() }) {
                    Icon(
                        if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (s.isPlaying) "Пауза" else "Играть"
                    )
                }
                IconButton(onClick = { player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Следующий")
                }
                IconButton(onClick = { player.toggleRepeat() }) {
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = "Повтор списка",
                        tint = if (s.repeatAll) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------- Диалоги ----------------

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Название") }
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ChoosePlaylistDialog(
    playlists: List<PlaylistUi>,
    onChoose: (PlaylistUi) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить в плейлист") },
        text = {
            if (playlists.isEmpty()) {
                Text("Плейлистов пока нет — создайте первый.")
            } else {
                LazyColumn {
                    items(playlists, key = { it.id }) { p ->
                        Text(
                            p.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChoose(p) }
                                .padding(vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("Новый плейлист") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
