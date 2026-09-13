// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict grammar checker to stop highlighting specific words because we know what we are doing.
@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.viewmodel

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// Tools for databases, audio management, playing music, and background tasks.
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.paging.*
import com.gallerybox.data.*
import com.gallerybox.engine.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import javax.inject.*
import kotlin.math.*

// --- MODELS & ENUMS ---

/**
 * A data class is a simple digital box.
 * `AudioTrack` holds all the information about a single song (its name, artist, length, and where the MP3 file lives).
 */
data class AudioTrack(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val genre: String = "Unknown",
    val path: String = "",
    val year: Int = 0,
    val dateAdded: Long = 0L,
    val composer: String = "Unknown"
)

/**
 * A snapshot of what the music player is currently doing.
 */
data class PlayerState(
    val track: AudioTrack? = null, // What song is loaded?
    val isPlaying: Boolean = false, // Is it playing or paused?
    val position: Long = 0L // How many seconds into the song are we?
)

/**
 * Enums are just specific lists of options (like a dropdown menu).
 * `Preset` holds predefined Equalizer settings. An equalizer adjusts frequencies (like boosting the bass).
 * For example, "ROCK" bumps up the high and low frequencies, but lowers the middle.
 */
enum class Preset(val levels: List<Float>) {
    NORMAL(List(5){0.5f}),
    CLASSICAL(listOf(0.6f,0.6f,0.6f,0.6f,0.6f)),
    DANCE(listOf(0.4f,0.5f,0.6f,0.7f,0.8f)),
    FLAT(List(5){0.5f}),
    FOLK(listOf(0.5f,0.5f,0.5f,0.6f,0.6f)),
    HEAVY_METAL(listOf(0.7f,0.7f,0.7f,0.5f,0.5f)),
    HIP_HOP(listOf(0.7f,0.7f,0.6f,0.6f,0.5f)),
    JAZZ(listOf(0.5f,0.5f,0.6f,0.6f,0.6f)),
    POP(listOf(0.5f,0.6f,0.7f,0.8f,0.7f)),
    ROCK(listOf(0.7f,0.7f,0.6f,0.5f,0.4f))
}

// Options for sorting the music library
enum class SortOption { TITLE, ARTIST, DATE_ADDED, DURATION }
// Options for forcing sound out of specific speakers
enum class ChannelMode { STEREO, LEFT_ONLY, RIGHT_ONLY }

// --- REPOSITORY & MAPPERS ---

/**
 * THE TRANSLATOR (`MediaStoreMapper`).
 * Android gives us information about files in a format called a "Cursor" (like a giant spreadsheet).
 * This translator reads the raw spreadsheet rows and packs them into nice, clean `AudioTrack` boxes that our app understands.
 */
object MediaStoreMapper {
    fun mapCursorToTracks(cursor: Cursor, uri: Uri, pathColumn: String, expectedSize: Int): List<AudioTrack> {
        val list = ArrayList<AudioTrack>(expectedSize)
        // Find out which column holds which piece of data
        val idC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val pathC = cursor.getColumnIndexOrThrow(pathColumn)
        val dateC = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        val composerC = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
        val yearC = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)

        // Go row by row through the spreadsheet and build our songs
        while (cursor.moveToNext()) {
            list.add(AudioTrack(
                id = cursor.getLong(idC),
                uri = ContentUris.withAppendedId(uri, cursor.getLong(idC)).toString(),
                title = cursor.getString(titleC) ?: "Unknown",
                artist = cursor.getString(artistC) ?: "Unknown",
                album = cursor.getString(albumC) ?: "Unknown",
                albumId = cursor.getLong(albumIdC),
                duration = cursor.getLong(durC),
                path = cursor.getString(pathC) ?: "",
                year = if (yearC != -1) cursor.getInt(yearC) else 0,
                dateAdded = cursor.getLong(dateC),
                composer = if (composerC != -1) cursor.getString(composerC) ?: "Unknown" else "Unknown"
            ))
        }
        return list
    }
}

/**
 * THE LIBRARIAN (`MusicRepository`).
 * This class is responsible for searching the phone's memory to find music files,
 * and saving/loading small settings (like the user's custom equalizer settings) into a little notebook called `SharedPreferences`.
 */
@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicDao: MusicDao // Our local database tool
) {
    // A small digital notebook to save settings so they survive when the app is closed.
    private val prefs = context.getSharedPreferences("gallerybox_library", Context.MODE_PRIVATE)

    // Searches the phone for all audio files. Runs in the background (Dispatchers.IO) so it doesn't freeze the screen.
    suspend fun getLocalQueue(sortOption: SortOption = SortOption.DATE_ADDED): List<AudioTrack> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI // The Android "folder" where all music is tracked
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathColumn, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)

        // Figure out how the user wants the list sorted
        val sortOrder = when (sortOption) {
            SortOption.TITLE -> "${MediaStore.Audio.Media.TITLE} ASC" // Alphabetical A-Z
            SortOption.ARTIST -> "${MediaStore.Audio.Media.ARTIST} ASC"
            SortOption.DURATION -> "${MediaStore.Audio.Media.DURATION} DESC" // Longest songs first
            SortOption.DATE_ADDED -> "${MediaStore.Audio.Media.DATE_ADDED} DESC" // Newest songs first
        }

        // Only get files marked as music that actually have a file size larger than 0.
        val selectionStr = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"

        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For modern Android phones (Android 11+)
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selectionStr)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) // Don't include songs in the trash bin
                }
                context.contentResolver.query(uri, projection, bundle, null)?.use {
                    MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
                } ?: emptyList()
            } else {
                // For older Android phones
                context.contentResolver.query(uri, projection, selectionStr, null, sortOrder)?.use {
                    MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
                } ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Finds specific songs by their unique ID numbers.
    suspend fun getTracksByIds(ids: List<Long>): List<AudioTrack> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathColumn, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)

        val selection = "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",")}) AND ${MediaStore.Audio.Media.SIZE} > 0"
        return@withContext try {
            context.contentResolver.query(uri, projection, selection, null, null)?.use {
                MediaStoreMapper.mapCursorToTracks(it, uri, pathColumn, it.count)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Saves the Equalizer sliders (bands) to the digital notebook.
    suspend fun saveEqBands(bands: List<Float>) = withContext(Dispatchers.IO) {
        prefs.edit().putString("eq_bands", bands.joinToString(",")).apply()
    }

    // Reads the Equalizer sliders back from the notebook when the app opens.
    fun loadEqBands(): List<Float>? = prefs.getString("eq_bands", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.isNotEmpty() }

    // Saves a user's custom equalizer preset (e.g., "My Bass Boosted Setting")
    suspend fun saveCustomPreset(name: String, bands: List<Float>) = withContext(Dispatchers.IO) {
        val namesKey = "custom_preset_names"
        val names = (prefs.getString(namesKey, "") ?: "")
            .split("|").filter { it.isNotBlank() }.toMutableSet()
        names.add(name) // Add the new preset name to our list of names
        prefs.edit()
            .putString(namesKey, names.joinToString("|"))
            .putString("custom_preset_$name", bands.joinToString(",")) // Save the actual sliders for this preset
            .apply()
    }

    // Loads all custom presets the user has ever saved.
    fun loadCustomPresets(): Map<String, List<Float>> {
        val names = (prefs.getString("custom_preset_names", "") ?: "").split("|").filter { it.isNotBlank() }
        return names.associateWith { name ->
            prefs.getString("custom_preset_$name", null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: emptyList()
        }.filterValues { it.isNotEmpty() }
    }

    // Deletes a custom preset from the notebook.
    suspend fun deleteCustomPreset(name: String) = withContext(Dispatchers.IO) {
        val names = (prefs.getString("custom_preset_names", "") ?: "")
            .split("|").filter { it.isNotBlank() && it != name }
        prefs.edit()
            .putString("custom_preset_names", names.joinToString("|"))
            .remove("custom_preset_$name")
            .apply()
    }
}

// --- PAGING ENGINE ---

/**
 * THE WAITER (`AudioPagingSource`).
 * If a user has 10,000 songs on their phone, loading all of them into lists on the screen at once would freeze the phone and crash it.
 * This class acts like a waiter. As the user scrolls down the screen, the waiter runs to the kitchen (Database),
 * grabs exactly 50 songs (pageSize), and serves them to the screen.
 */
class AudioPagingSource(private val resolver: ContentResolver, private val query: String, private val sortOption: SortOption) : PagingSource<Int, AudioTrack>() {

    // Tells the waiter where to restart if the list gets interrupted.
    override fun getRefreshKey(state: PagingState<Int, AudioTrack>): Int? = state.anchorPosition?.let {
        state.closestPageToPosition(it)?.prevKey?.plus(state.config.pageSize) ?: state.closestPageToPosition(it)?.nextKey?.minus(state.config.pageSize)
    }

    // The actual "Go to the kitchen and get the next 50 songs" command.
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AudioTrack> = withContext(Dispatchers.IO) {
        try {
            val pos = params.key ?: 0 // Start index
            val size = params.loadSize // How many to grab
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, pathCol, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.COMPOSER, MediaStore.Audio.Media.YEAR)
            val baseSel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.SIZE} > 0"

            // If the user typed in a search bar, add a filter for the title or artist name.
            val finalSel = if (query.isNotBlank()) "$baseSel AND (${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)" else baseSel
            val selArgs = if (query.isNotBlank()) arrayOf("$query%", "$query%") else null

            val sortOrder = when (sortOption) {
                SortOption.TITLE -> "${MediaStore.Audio.Media.TITLE} ASC"
                SortOption.ARTIST -> "${MediaStore.Audio.Media.ARTIST} ASC"
                SortOption.DURATION -> "${MediaStore.Audio.Media.DURATION} DESC"
                SortOption.DATE_ADDED -> "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            }

            var trackList: List<AudioTrack> = emptyList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, finalSel)
                    selArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, size) // Give me 50 songs
                    putInt(ContentResolver.QUERY_ARG_OFFSET, pos) // Starting at song #100
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                }
                resolver.query(uri, proj, bundle, null)?.use {
                    trackList = MediaStoreMapper.mapCursorToTracks(it, uri, pathCol, size)
                }
            } else {
                resolver.query(uri, proj, finalSel, selArgs, "$sortOrder LIMIT $size OFFSET $pos")?.use {
                    trackList = MediaStoreMapper.mapCursorToTracks(it, uri, pathCol, size)
                }
            }

            // Return the tray of songs to the screen, and give it the numbers for the Next Page and Previous Page.
            LoadResult.Page(trackList, if (pos == 0) null else pos - size, if (trackList.size < size) null else pos + size)
        } catch (e: Exception) {
            LoadResult.Error(e) // Dropped the tray.
        }
    }
}

// --- EXO-PLAYER EXTENSIONS ---

/**
 * A Custom Audio Cable (`DynamicStereoProcessor`).
 * ExoPlayer reads audio as a stream of raw numbers (Bytes) passing through a pipe.
 * This class intercepts those numbers. If we want standard Stereo, it lets them pass.
 * If we want Left-Only, it sets all the Right-speaker numbers to 0 (Silence).
 */
class DynamicStereoProcessor(initialMode: ChannelMode) : BaseAudioProcessor() {
    private var currentMode = initialMode

    // Change the speaker mode dynamically while playing.
    fun setMode(mode: ChannelMode) { if (currentMode != mode) { currentMode = mode; flush() } }

    // Ensures we only mess with standard 2-channel (Stereo) audio.
    override fun onConfigure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (fmt.encoding != C.ENCODING_PCM_16BIT || fmt.channelCount != 2) AudioProcessor.AudioFormat.NOT_SET else fmt

    // The actual interceptor. Reads the incoming audio buffer (byte by byte) and writes the manipulated audio out.
    override fun queueInput(buffer: ByteBuffer) {
        val rem = buffer.remaining()
        if (rem == 0) return
        val out = replaceOutputBuffer(rem)
        while (buffer.hasRemaining()) {
            val l1 = buffer.get()
            val l2 = buffer.get()
            val r1 = buffer.get()
            val r2 = buffer.get()
            when (currentMode) {
                ChannelMode.STEREO -> { out.put(l1); out.put(l2); out.put(r1); out.put(r2) }
                ChannelMode.LEFT_ONLY -> { out.put(l1); out.put(l2); out.put(0); out.put(0) }
                ChannelMode.RIGHT_ONLY -> { out.put(0); out.put(0); out.put(r1); out.put(r2) }
            }
        }
        buffer.position(buffer.limit()); out.flip()
    }
}

// --- MAIN MUSIC VIEWMODEL ---

/**
 * THE DJ / THE MANAGER (`MusicViewModel`).
 * If the user rotates the phone or puts the app in the background, the screen is destroyed and rebuilt.
 * This ViewModel survives that destruction. It holds all the current songs, settings, and talks to the Audio Engine to keep music playing.
 *
 * `@HiltViewModel` tells a tool called 'Hilt' to automatically build this DJ and hand it the tools it needs (like the Repository).
 */
@HiltViewModel
class MusicViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val musicDao: MusicDao,
    private val playerManager: PlayerManager, // The actual Audio Engine that plays the MP3s
    application: Application
) : AndroidViewModel(application) {

    private var observeJob: Job? = null
    private var eqSaveJob: Job? = null

    // --- SCOREBOARDS (StateFlows) ---
    // A StateFlow is like a sports scoreboard. It always shows the current score (state).
    // The screen (UI) stares at these scoreboards and updates itself automatically when the numbers change.

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _currentSortOption = MutableStateFlow(SortOption.DATE_ADDED)
    val currentSortOption = _currentSortOption.asStateFlow()

    private val _allAudioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val allAudioTracks = _allAudioTracks.asStateFlow()

    // Transforms the `_isLoading` boolean into an `isLoadComplete` boolean for convenience.
    val isLoadComplete: StateFlow<Boolean> = _isLoading
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val totalSongCount: StateFlow<Int> = _allAudioTracks
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // The Assembly Line for the Waiter (PagingSource). It takes the search text, sorts it, and feeds it to the Waiter.
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val pagedAudio: Flow<PagingData<AudioTrack>> = combine(_searchQuery.debounce(300), _currentSortOption, ::Pair).flatMapLatest { (q, s) ->
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            AudioPagingSource(getApplication<Application>().contentResolver, q, s)
        }.flow
    }.cachedIn(viewModelScope)

    // Multi-Select State (For when a user long-presses songs to select multiple at once)
    private val _selectedTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTrackIds = _selectedTrackIds.asStateFlow()

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setSortOption(o: SortOption) { _currentSortOption.value = o }

    // Holds the ID numbers of all the songs the user has "Hearted".
    private val _favoriteIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteIds = _favoriteIds.asStateFlow()

    // The Queues (Playlists)
    private val _originalQueue = MutableStateFlow<List<AudioTrack>>(emptyList()) // The un-shuffled list
    private val _queue = MutableStateFlow<List<AudioTrack>>(emptyList()) // The current playing list (might be shuffled)
    val currentQueue = _queue.asStateFlow()
    private val _currentQueueIndex = MutableStateFlow(0) // Which song in the queue we are on right now

    // PLAYER 1 (The main record player)
    private val _currentTrack1 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack = _currentTrack1.asStateFlow()
    private val _isPlaying1 = MutableStateFlow(false)
    val isPlaying = _isPlaying1.asStateFlow()
    private val _currentPosition1 = MutableStateFlow(0L) // Current time in the song
    val currentPosition = _currentPosition1.asStateFlow()

    // PLAYER 2 (The secondary record player for "Duo Mode")
    private val _currentTrack2 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack2 = _currentTrack2.asStateFlow()
    private val _isPlaying2 = MutableStateFlow(false)
    val isPlaying2 = _isPlaying2.asStateFlow()
    private val _currentPosition2 = MutableStateFlow(0L)
    val currentPosition2 = _currentPosition2.asStateFlow()

    private val _duration1 = MutableStateFlow(0L) // Total length of Player 1's song
    val duration1 = _duration1.asStateFlow()
    private val _duration2 = MutableStateFlow(0L)
    val duration2 = _duration2.asStateFlow()

    // If either player is playing, we want animations (like spinning records) to be active.
    val isAnimationActive: StateFlow<Boolean> = combine(_isPlaying1, _isPlaying2) { p1, p2 ->
        p1 || p2
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Audio Effects dials
    private val _speedPlayer1 = MutableStateFlow(1f); val speedPlayer1 = _speedPlayer1.asStateFlow()
    private val _pitchPlayer1 = MutableStateFlow(1f); val pitchPlayer1 = _pitchPlayer1.asStateFlow()
    private val _speedPlayer2 = MutableStateFlow(1f); val speedPlayer2 = _speedPlayer2.asStateFlow()
    private val _pitchPlayer2 = MutableStateFlow(1f); val pitchPlayer2 = _pitchPlayer2.asStateFlow()

    // Equalizer sliders (Bands). Loads from the notebook or defaults to perfectly middle (0.5f)
    private val _eqBands1 = MutableStateFlow(repository.loadEqBands() ?: List(9) { 0.5f })
    val eqBands1 = _eqBands1.asStateFlow()
    private val _eqBands2 = MutableStateFlow(repository.loadEqBands() ?: List(9) { 0.5f })
    val eqBands2 = _eqBands2.asStateFlow()

    private val _customPresets = MutableStateFlow(repository.loadCustomPresets())
    val customPresets = _customPresets.asStateFlow()
    private val _activeCustomPresetName = MutableStateFlow<String?>(null)
    val activeCustomPresetName = _activeCustomPresetName.asStateFlow()

    private val _currentPreset = MutableStateFlow(Preset.NORMAL)
    val currentPreset = _currentPreset.asStateFlow()
    private val _eqEnabled = MutableStateFlow(true)
    val eqEnabled = _eqEnabled.asStateFlow()

    private val _volume1 = MutableStateFlow(1.0f); val volume1 = _volume1.asStateFlow()
    private val _volume2 = MutableStateFlow(1.0f); val volume2 = _volume2.asStateFlow()

    // Stereo panning (Left/Right ear focus)
    private val _balance1 = MutableStateFlow(0f); val balance1 = _balance1.asStateFlow()
    private val _balance2 = MutableStateFlow(0f); val balance2 = _balance2.asStateFlow()

    // Advanced Audio Effects
    private val _crossfeed = MutableStateFlow(0f); val crossfeed = _crossfeed.asStateFlow() // Blends left/right channels to make headphones sound like live speakers
    private val _bassBoost = MutableStateFlow(0f); val bassBoost = _bassBoost.asStateFlow()
    private val _virtualizer = MutableStateFlow(0f); val virtualizer = _virtualizer.asStateFlow() // Surround sound effect
    private val _reverbPreset = MutableStateFlow(0.toShort()); val reverbPreset = _reverbPreset.asStateFlow() // Echo effect (like a Concert Hall or Bathroom)

    // Regular state variables for standard UI switches
    var isShuffleEnabled by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var isDuoModeActive by mutableStateOf(false)

    // Sleep Timer variables
    private var sleepTimerJob: Job? = null
    var sleepTimeRemaining by mutableLongStateOf(0L)

    // Output Device tracking (Is music coming out of the Phone Speaker or Bluetooth?)
    private val _outputDevice = MutableStateFlow("Speaker")
    val outputDevice = _outputDevice.asStateFlow()

    // A sensor that listens to Android. When a bluetooth device connects or disconnects, it updates our output string.
    private val outputDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>?) = updateOutput()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>?) = updateOutput()
    }

    /**
     * THE MORNING ROUTINE (`init`)
     * This block runs once the moment the Manager wakes up when the app starts.
     */
    init {
        loadAllAudioTracks() // Go find all the music on the phone
        observePlayerManager() // Start watching the Audio Engine
        loadRoomData() // Load the favorite songs from the local database

        // Apply the saved equalizer settings to the Audio Engine immediately
        _eqBands1.value.forEachIndexed { i, v -> playerManager.updateEq(i, v, false) }
        _eqBands2.value.forEachIndexed { i, v -> playerManager.updateEq(i, v, true) }
    }

    fun loadAllAudioTracks() {
        // 'viewModelScope.launch' creates a background warehouse worker to do heavy lifting so the UI doesn't freeze.
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _allAudioTracks.value = repository.getLocalQueue(_currentSortOption.value)
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Failed to load music library"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadRoomData() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = musicDao.getAllStats()
            _favoriteIds.value = stats.filter { it.isFavorite }.map { it.trackId }.toSet()
        }
    }

    /**
     * Connects our Manager (ViewModel) scoreboards to the actual Audio Engine.
     */
    private fun observePlayerManager() {
        observeJob?.cancel() // Stop any previous watchers
        observeJob = viewModelScope.launch {
            // Keep our scoreboards updated when the Engine switches songs or pauses
            launch { playerManager.currentTrack.collect { _currentTrack1.value = it } }
            launch { playerManager.currentTrack2.collect { _currentTrack2.value = it } }
            launch { playerManager.isPlaying.collect { _isPlaying1.value = it } }
            launch { playerManager.isPlaying2.collect { _isPlaying2.value = it } }

            // A ticking clock loop to keep the song's progress bar moving
            launch {
                while (isActive) {
                    val p1Playing = _isPlaying1.value
                    val p2Playing = _isPlaying2.value

                    // Only poll the timestamp rapidly if music is actively playing.
                    if (p1Playing || p2Playing) {
                        _currentPosition1.value = playerManager.player1Position
                        _currentPosition2.value = playerManager.player2Position

                        val d1 = playerManager.player1Duration
                        if (d1 > 0 && d1 != C.TIME_UNSET) _duration1.value = d1

                        val d2 = playerManager.player2Duration
                        if (d2 > 0 && d2 != C.TIME_UNSET) _duration2.value = d2

                        delay(250) // Tick 4 times a second
                    } else {
                        delay(1000) // Sleep/Tick slower to save CPU battery when paused
                    }
                }
            }
        }
    }

    // Tells Android we want to be notified if headphones are plugged in or Bluetooth is connected.
    fun startOutputMonitoring() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(outputDeviceCallback, null)
        updateOutput()
    }

    // Stops listening to save battery when the app is closed.
    fun stopOutputMonitoring() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(outputDeviceCallback)
    }

    // Figures out what the music is currently playing out of.
    private fun updateOutput() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        _outputDevice.value = when {
            d.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } -> "Bluetooth"
            d.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "Headset"
            else -> "Speaker"
        }
    }

    // --- MULTI-SELECT FUNCTIONS ---
    // Handles clicking checkboxes next to songs to perform bulk actions.

    fun toggleSelection(trackId: Long) {
        val current = _selectedTrackIds.value.toMutableSet()
        if (current.contains(trackId)) current.remove(trackId) else current.add(trackId)
        _selectedTrackIds.value = current
    }

    fun selectAll(tracks: List<AudioTrack>) {
        _selectedTrackIds.value = tracks.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedTrackIds.value = emptySet()
    }

    // Takes all checked songs and inserts them immediately after the currently playing song in the queue.
    fun playSelectedNext(tracksToResolve: List<AudioTrack>) {
        val selectedIds = _selectedTrackIds.value
        if (selectedIds.isEmpty()) return

        val selectedTracks = tracksToResolve.filter { selectedIds.contains(it.id) }
        val currentList = _queue.value.toMutableList()
        var insertIndex = if (_currentQueueIndex.value + 1 <= currentList.size) _currentQueueIndex.value + 1 else currentList.size

        selectedTracks.forEach { track ->
            if (currentList.none { it.id == track.id }) {
                currentList.add(insertIndex++, track)
            }
        }
        _queue.value = currentList
        playerManager.setPlaylist(currentList, _currentQueueIndex.value.coerceAtLeast(0)) // Update the Audio Engine's playlist
        clearSelection()
    }

    // Takes all checked songs and puts them at the very end of the playlist.
    fun addSelectedToQueue(tracksToResolve: List<AudioTrack>) {
        val selectedIds = _selectedTrackIds.value
        if (selectedIds.isEmpty()) return

        val selectedTracks = tracksToResolve.filter { selectedIds.contains(it.id) }
        val currentList = _queue.value.toMutableList()

        selectedTracks.forEach { track ->
            if (currentList.none { it.id == track.id }) {
                currentList.add(track)
            }
        }
        _queue.value = currentList
        playerManager.setPlaylist(currentList, _currentQueueIndex.value)
        clearSelection()
    }

    // Hearts (Favorites) all checked songs simultaneously.
    fun favoriteSelected() {
        val selectedIds = _selectedTrackIds.value.toList()
        if (selectedIds.isEmpty()) return
        toggleFavorite(selectedIds)
        clearSelection()
    }

    // --- PLAYBACK CONTROL FUNCTIONS ---

    // Plays a specific single song immediately.
    fun playTrack(track: AudioTrack, secondary: Boolean = false) {
        if (!secondary) {
            setDuoMode(false) // Turn off DJ mode if playing normally
            updateStats(track.id) // Add a "Play" counter point to the database

            _currentPosition1.value = 0L // Reset progress bar to 0:00
            _duration1.value = track.duration

            viewModelScope.launch {
                val full = if (_allAudioTracks.value.isNotEmpty()) _allAudioTracks.value else {
                    val loaded = repository.getLocalQueue(_currentSortOption.value)
                    _allAudioTracks.value = loaded
                    loaded
                }

                _originalQueue.value = full

                // Organize the playlist. Put the clicked song first.
                val q = if (isShuffleEnabled) {
                    listOf(track) + (full - track).shuffled()
                } else {
                    val idx = full.indexOfFirst { it.id == track.id }
                    if (idx != -1) full.drop(idx) + full.take(idx) else listOf(track)
                }

                _queue.value = q
                _currentQueueIndex.value = 0
                playerManager.setPlaylist(q, 0) // Tell the engine to play!
            }
        } else {
            // If playing on the Secondary Turntable (Duo Mode)
            _currentPosition2.value = 0L
            _duration2.value = track.duration
            playerManager.playTrack(track, true)
        }
    }

    fun playNext(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        if (currentList.none { it.id == track.id }) {
            val insertIndex = if (_currentQueueIndex.value + 1 <= currentList.size) _currentQueueIndex.value + 1 else currentList.size
            currentList.add(insertIndex, track)
            _queue.value = currentList
            playerManager.setPlaylist(currentList, _currentQueueIndex.value.coerceAtLeast(0))
        }
    }

    fun addToQueue(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        if (currentList.none { it.id == track.id }) {
            currentList.add(track)
            _queue.value = currentList
            playerManager.setPlaylist(currentList, _currentQueueIndex.value)
        }
    }

    fun removeFromQueue(track: AudioTrack) {
        val currentList = _queue.value.toMutableList()
        val indexToRemove = currentList.indexOfFirst { it.id == track.id }
        if (indexToRemove != -1) {
            currentList.removeAt(indexToRemove)
            _queue.value = currentList
            // If we deleted a song that was BEFORE our current song, we need to shift our index pointer back by 1.
            if (indexToRemove < _currentQueueIndex.value) _currentQueueIndex.value -= 1
            playerManager.setPlaylist(currentList, _currentQueueIndex.value)
        }
    }

    // Handles dragging and dropping a song up or down in the playlist.
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val currentList = _queue.value.toMutableList()
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _queue.value = currentList

            // Adjust current queue index so playback doesn't randomly jump to a different song while reordering.
            if (_currentQueueIndex.value == fromIndex) {
                _currentQueueIndex.value = toIndex
            } else if (_currentQueueIndex.value in (fromIndex + 1)..toIndex) {
                _currentQueueIndex.value -= 1
            } else if (_currentQueueIndex.value in toIndex until fromIndex) {
                _currentQueueIndex.value += 1
            }

            playerManager.setPlaylist(currentList, _currentQueueIndex.value.coerceAtLeast(0))
        }
    }

    // Helper functions for Duo Mode (Both Turntables at once)
    fun playBothSynced() {
        play(false)
        play(true)
    }

    fun pauseBothSynced() {
        pause(false)
        pause(true)
    }

    // Plays a specific list of songs (like an Album or a Playlist)
    // Overload 1: Takes an index (number) of where to start in the list
    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        setDuoMode(false)
        _originalQueue.value = tracks

        // Figure out order based on if Shuffle is active
        val q = if (isShuffleEnabled) {
            listOf(tracks[startIndex]) + (tracks - tracks[startIndex]).shuffled()
        } else if (startIndex > 0) {
            tracks.drop(startIndex) + tracks.take(startIndex) // Rotate the list so chosen song is first
        } else {
            tracks
        }

        _queue.value = q
        _currentQueueIndex.value = 0
        q.firstOrNull()?.let { updateStats(it.id) }
        playerManager.setPlaylist(q, 0)
    }

    // Overload 2: Takes the specific AudioTrack box you clicked instead of a number
    fun playQueue(tracks: List<AudioTrack>, trackToPlay: AudioTrack) {
        val idx = tracks.indexOfFirst { it.id == trackToPlay.id }.coerceAtLeast(0)
        playQueue(tracks, idx)
    }

    // Loads a song onto a specific turntable in Duo Mode
    fun playDuoTrack(track: AudioTrack, isPlayer2: Boolean) {
        setDuoMode(true)
        updateStats(track.id)
        if (isPlayer2) {
            _currentTrack2.value = track
            _currentPosition2.value = 0L
            _duration2.value = track.duration
        } else {
            _currentTrack1.value = track
            _currentPosition1.value = 0L
            _duration1.value = track.duration
        }
        playerManager.playTrack(track, isPlayer2)
        updateVolume(1f, isPlayer2) // Ensure the new song isn't muted
    }

    fun setPlaying(play: Boolean, secondary: Boolean = false) {
        if (play) playerManager.play(secondary) else playerManager.pause(secondary)
    }

    fun play(secondary: Boolean = false) = setPlaying(true, secondary)
    fun pause(secondary: Boolean = false) = setPlaying(false, secondary)

    fun togglePlayPause(secondary: Boolean = false) {
        playerManager.togglePlayPause(secondary)
    }

    fun skipNext() {
        val qSize = _queue.value.size
        if (qSize > 0) {
            playerManager.seekToNext()
            _currentQueueIndex.value = (_currentQueueIndex.value + 1) % qSize // Loop around to the start if at the end
        }
    }

    fun skipPrevious() {
        val qSize = _queue.value.size
        if (qSize > 0) {
            playerManager.seekToPrevious()
            _currentQueueIndex.value = (_currentQueueIndex.value - 1 + qSize) % qSize
        }
    }

    fun toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled
        playerManager.setShuffleMode(isShuffleEnabled) // Tell the Engine to shuffle upcoming songs

        // If we turned shuffle OFF, reset the playlist back to the normal alphabetical/date order
        if (!isShuffleEnabled && _originalQueue.value.isNotEmpty()) {
            _queue.value = _originalQueue.value
        }
    }

    fun toggleRepeat() {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL // Loop the whole playlist
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE // Loop just this one song endlessly
            else -> Player.REPEAT_MODE_OFF
        }
        playerManager.setRepeatMode(repeatMode)
    }

    // User clicked somewhere on the progress bar (like 50% through the song)
    fun seekToFraction(fraction: Float, secondary: Boolean = false) {
        playerManager.seekToFraction(fraction, secondary)
    }

    // User wants to jump to an exact millisecond timestamp
    fun seekTo(ms: Long, secondary: Boolean = false) {
        playerManager.seekTo(ms, secondary)
    }

    // Fast Forward or Rewind dynamically (Jumps 1% of the song length, or minimum 10 seconds)
    fun seekDynamic(forward: Boolean, isPlayer2: Boolean = false) {
        val currentPos = if (isPlayer2) _currentPosition2.value else _currentPosition1.value
        val duration = if (isPlayer2) _duration2.value else _duration1.value
        val seekAmount = max(10000L, (duration * 0.01).toLong())
        val targetPos = if (forward) currentPos + seekAmount else currentPos - seekAmount
        playerManager.seekTo(targetPos.coerceIn(0L, duration), isPlayer2)
    }

    // DJ effect: Fades the volume of Player 1 down while fading Player 2 up simultaneously.
    fun crossfadePlayers() = playerManager.triggerCrossfade()

    fun setDuoMode(enabled: Boolean) {
        isDuoModeActive = enabled
        playerManager.setDuoMode(enabled)
    }

    // Sets all effects (Speed, Pitch) back to normal
    fun resetAudioEffects() {
        _speedPlayer1.value = 1f
        _pitchPlayer1.value = 1f
        _speedPlayer2.value = 1f
        _pitchPlayer2.value = 1f
        playerManager.resetPlaybackParameters()
    }

    // Plays the song faster or slower
    fun setPlayerSpeed(isPlayer2: Boolean, speed: Float) {
        if (isPlayer2) _speedPlayer2.value = speed else _speedPlayer1.value = speed
        playerManager.setSpeed(speed, isPlayer2)
    }

    // Changes the key of the song (like Alvin and the Chipmunks if high, or a deep monster voice if low)
    fun setPlayerPitch(isPlayer2: Boolean, pitch: Float) {
        if (isPlayer2) _pitchPlayer2.value = pitch else _pitchPlayer1.value = pitch
        playerManager.setPitch(pitch, isPlayer2)
    }

    // Fun presets that combine Speed and Pitch edits
    fun setPlaybackEffect(isPlayer2: Boolean, effectName: String) {
        val params = when (effectName) {
            "Nightcore" -> Pair(1.25f, 1.25f) // Fast and high pitched
            "Slowed" -> Pair(0.85f, 0.85f)    // Slow and deep
            "Vaporwave" -> Pair(0.8f, 0.6f)   // Slightly slow, very deep
            else -> Pair(1.0f, 1.0f)          // Normal
        }
        setPlayerSpeed(isPlayer2, params.first)
        setPlayerPitch(isPlayer2, params.second)
    }

    // Adjusts overall volume before the equalizer is applied
    fun setPreampGain(millibels: Int) = playerManager.setPreampGain(millibels)

    // Turns the Equalizer On or Off
    fun toggleEq(enabled: Boolean) {
        _eqEnabled.value = enabled
        playerManager.setEqEnabled(enabled)
    }

    // Updates a specific slider on the Equalizer (e.g., boosting the Bass slider)
    fun updateEq(index: Int, value: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) {
            val list = _eqBands2.value.toMutableList()
            if (index in list.indices) {
                list[index] = value
                _eqBands2.value = list
                playerManager.updateEq(index, value, true) // Tell the Engine to adjust the sound
            }
        } else {
            val list = _eqBands1.value.toMutableList()
            if (index in list.indices) {
                list[index] = value
                _eqBands1.value = list
                _activeCustomPresetName.value = null // Clear custom preset display, since they just changed a setting manually

                // Debounce saving: Wait until they stop sliding the knob for 300ms before we save it to the notebook, so we don't save 100 times a second.
                eqSaveJob?.cancel()
                eqSaveJob = viewModelScope.launch {
                    delay(300)
                    repository.saveEqBands(list)
                }
                playerManager.updateEq(index, value, false)
                if (_currentPreset.value != Preset.NORMAL) _currentPreset.value = Preset.NORMAL
            }
        }
    }

    // Sets all Equalizer sliders instantly based on a preset (like 'Jazz' or 'Pop')
    fun applyPreset(p: Preset) {
        _currentPreset.value = p
        _activeCustomPresetName.value = null // Clear custom preset display
        val tSize = _eqBands1.value.size
        // Ensure the preset has the exact right number of sliders. If not, stretch/shrink it to fit.
        val safeLevels = if (p.levels.size == tSize) p.levels else resampleLevels(p.levels, tSize)
        _eqBands1.value = safeLevels
        viewModelScope.launch { repository.saveEqBands(safeLevels) } // Save this choice to the notebook
        safeLevels.forEachIndexed { i, level -> playerManager.updateEq(i, level, false) }
    }

    // Saves the current slider positions into the notebook under a custom name
    fun saveCustomPreset(name: String) {
        val bands = _eqBands1.value
        viewModelScope.launch {
            repository.saveCustomPreset(name, bands)
            _customPresets.value = repository.loadCustomPresets() // Refresh our list from the notebook
            _activeCustomPresetName.value = name
        }
    }

    fun applyCustomPreset(name: String) {
        val bands = _customPresets.value[name] ?: return
        val tSize = _eqBands1.value.size
        val safeLevels = if (bands.size == tSize) bands else resampleLevels(bands, tSize)
        _eqBands1.value = safeLevels
        _activeCustomPresetName.value = name
        viewModelScope.launch { repository.saveEqBands(safeLevels) }
        safeLevels.forEachIndexed { i, level -> playerManager.updateEq(i, level, false) }
    }

    fun deleteCustomPreset(name: String) {
        viewModelScope.launch {
            repository.deleteCustomPreset(name)
            _customPresets.value = repository.loadCustomPresets()
            if (_activeCustomPresetName.value == name) _activeCustomPresetName.value = null
        }
    }

    /**
     * Helper tool: If a preset was created on a phone with 5 EQ sliders, but this phone has 9 sliders,
     * this math stretches the 5 values across the 9 sliders smoothly.
     */
    private fun resampleLevels(input: List<Float>, targetSize: Int): List<Float> {
        if (input.isEmpty()) return List(targetSize) { 0.5f }
        if (targetSize == 1) return listOf(input.first())
        return List(targetSize) { i ->
            val pos = i * (input.size - 1).toFloat() / (targetSize - 1)
            val left = pos.toInt()
            val right = minOf(left + 1, input.lastIndex)
            val frac = pos - left
            input[left] * (1 - frac) + input[right] * frac
        }
    }

    // Adjusts the volume. Uses math (Logarithms) because human ears hear volume changes on a curve, not a straight line.
    fun updateVolume(v: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) _volume2.value = v else _volume1.value = v
        val v1 = _volume1.value
        val v2 = _volume2.value

        // If DJ Duo mode is active, we lower the maximum volume slightly so the two songs combined don't blow out the speaker.
        val limiterScale = if (isDuoModeActive) 1f / max(1f, v1 + v2) else 1f
        val logV1 = (ln(1.0 + 9.0 * v1) / ln(10.0)).toFloat() * limiterScale
        val logV2 = (ln(1.0 + 9.0 * v2) / ln(10.0)).toFloat() * limiterScale

        playerManager.setVolume(logV1, false)
        if (isDuoModeActive) playerManager.setVolume(logV2, true)
    }

    // Shifts the sound purely to the Left ear or Right ear
    fun updateBalance(balance: Float, isPlayer2: Boolean = false) {
        if (isPlayer2) _balance2.value = balance else _balance1.value = balance
        playerManager.setStereoBalance(balance, isPlayer2)
    }

    fun updateCrossfeed(amount: Float) {
        _crossfeed.value = amount
        playerManager.setCrossfeed(amount)
    }

    fun updateBass(v: Float) {
        _bassBoost.value = v
        playerManager.updateBass(v)
    }

    fun updateVirtualizer(v: Float) {
        _virtualizer.value = v
        playerManager.updateVirtualizer(v)
    }

    fun setReverb(p: Short) {
        _reverbPreset.value = p
        playerManager.setReverb(p)
    }

    /**
     * A countdown clock. Once it hits zero, it shuts down the music engine so the user can sleep peacefully.
     */
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer() // Stop any previous timer
        val ms = minutes * 60000L // Convert minutes to milliseconds
        val end = System.currentTimeMillis() + ms
        sleepTimeRemaining = ms

        sleepTimerJob = viewModelScope.launch {
            // A ticking loop that updates the countdown display
            while (System.currentTimeMillis() < end) {
                sleepTimeRemaining = end - System.currentTimeMillis()
                delay(minOf(5000L, sleepTimeRemaining.coerceAtLeast(100L)))
            }
            // Time is up! Stop the music.
            playerManager.stopAll()
            sleepTimeRemaining = 0
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimeRemaining = 0
    }

    // Updates our local database whenever a song is played, so we can track "Most Played" songs.
    private fun updateStats(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = musicDao.getStat(id)
            // If the song is already in the database, increase its playCount by 1. Otherwise, create a new record for it.
            val newStat = existing?.copy(playCount = existing.playCount + 1, lastPlayed = System.currentTimeMillis())
                ?: TrackStatEntity(id, 1, System.currentTimeMillis(), false)
            musicDao.insertStat(newStat)
        }
    }

    // Handles the "Heart" button to like or unlike a list of songs.
    fun toggleFavorite(ids: List<Long>) {
        val currentFavs = _favoriteIds.value.toMutableSet()
        ids.forEach { id ->
            if (currentFavs.contains(id)) currentFavs.remove(id) else currentFavs.add(id)
        }
        _favoriteIds.value = currentFavs // Update the scoreboard instantly

        // Save the change permanently to the database in the background.
        viewModelScope.launch(Dispatchers.IO) {
            val currentStats = musicDao.getAllStats().associateBy { it.trackId }
            val statsToInsert = ids.map { id ->
                val existing = currentStats[id]
                val newFavState = currentFavs.contains(id)
                existing?.copy(isFavorite = newFavState) ?: TrackStatEntity(id, 0, 0L, newFavState)
            }
            musicDao.insertStats(statsToInsert)
        }
    }

    // Taking out the trash when the user closes the app or screen. Clean up our background workers to prevent crashes!
    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
        eqSaveJob?.cancel()
        stopOutputMonitoring()
    }
}