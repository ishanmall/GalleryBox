// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to stop highlighting specific words because we know what we are doing.
@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.viewmodel

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We have tools for connecting to the internet, background services, and Coroutines (background workers).
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.gallerybox.engine.MusicService
import com.gallerybox.engine.PlaybackMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.round

// --- DIGITAL RADIO DATA MODEL ---

/**
 * A data class is a simple digital box.
 * `DigitalStation` acts like a business card for an internet radio station.
 * It holds the station's name, where its audio lives on the internet (streamUrl), its logo, and its country.
 */
data class DigitalStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val imageUrl: String,
    val tags: String,
    val country: String,
    val isFavorite: Boolean = false
)

/**
 * THE RADIO MANAGER (`RadioViewModel`).
 * If the user rotates the phone or navigates away, the physical screen is destroyed and rebuilt by Android,
 * but this Manager survives. It keeps track of the current radio station, signal strength, and talks to the Audio Engine.
 *
 * `@HiltViewModel` tells a tool called 'Hilt' to automatically build this Manager and hand it the Application context.
 */
@UnstableApi
@HiltViewModel
class RadioViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {

    // The actual background music player. We think of this as the "Boiler Room" that actually pumps the sound.
    private var musicService: MusicService? = null
    private var isBound = false // Are we currently connected to the Boiler Room?
    private var observeJob: Job? = null // A background worker whose only job is to watch the Boiler Room for updates.

    // Digital notebook for persisting favorite internet radio stations across app launches
    private val digitalPrefs = app.getSharedPreferences("gallerybox_digital_radio", Context.MODE_PRIVATE)
    private val favoriteDigitalIds = loadFavoriteDigitalIds().toMutableSet()

    // --- SCOREBOARDS (StateFlows) ---
    // A StateFlow is like a sports scoreboard. It always shows the current score (state).
    // The screen (UI) stares at these scoreboards and updates itself automatically when the numbers change.

    // --- CORE & FM RADIO STATES ---
    private val _isServiceConnected = MutableStateFlow(false) // Scoreboard: Is the Boiler Room connected?
    val isServiceConnected = _isServiceConnected.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE) // Are we playing FM Radio, Internet Radio, or Local Music?
    val playbackMode = _playbackMode.asStateFlow()

    private val _isPlaying = MutableStateFlow(false) // Is the radio making sound right now?
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentFrequency = MutableStateFlow(98.0f) // The FM dial position (e.g., 98.3 FM)
    val currentFrequency = _currentFrequency.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false) // FM Radio requires physical wired headphones to act as an antenna.
    val isHeadsetConnected = _isHeadsetConnected.asStateFlow()

    private val _favoriteStations = MutableStateFlow<List<Float>>(emptyList()) // List of saved FM stations
    val favoriteStations = _favoriteStations.asStateFlow()

    private val _signalStrength = MutableStateFlow(0) // FM static/signal strength (0 to 100)
    val signalStrength = _signalStrength.asStateFlow()

    private val _stereoBlend = MutableStateFlow(1.0f) // 1.0 = Full Stereo (clear), 0.0 = Mono (fuzzy signal)
    val stereoBlend = _stereoBlend.asStateFlow()

    private val _isScanning = MutableStateFlow(false) // Is the radio currently searching for the next clear station?
    val isScanning = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null) // Scoreboard for showing error popups to the user
    val error = _error.asStateFlow()

    private val _isSpeakerEnabled = MutableStateFlow(false) // Are we forcing the FM sound out of the phone's main speaker?
    val isSpeakerEnabled = _isSpeakerEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    // A list of popular default stations to jump-start the radio.
    private val knownStations = listOf(91.1f, 92.7f, 93.5f, 95.0f, 98.3f, 100.1f, 102.6f, 104.0f, 104.8f, 106.4f)

    // This checks the current frequency. If it's very close to a known station (like 98.3), it displays the name ("Radio Mirchi").
    val rdsStationName = _currentFrequency.map { freq ->
        when {
            abs(freq - 98.3f) < 0.05f -> "Radio Mirchi"
            abs(freq - 93.5f) < 0.05f -> "Red FM"
            abs(freq - 92.7f) < 0.05f -> "Big FM"
            abs(freq - 104.8f) < 0.05f -> "Ishq FM"
            abs(freq - 91.1f) < 0.05f -> "Radio City"
            abs(freq - 95.0f) < 0.05f -> "Hit 95 FM"
            abs(freq - 100.1f) < 0.05f -> "AIR FM Gold"
            abs(freq - 102.6f) < 0.05f -> "AIR FM Rainbow"
            abs(freq - 104.0f) < 0.05f -> "Fever FM"
            abs(freq - 106.4f) < 0.05f -> "Magic FM"
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- DIGITAL RADIO STATES ---
    private val _searchQuery = MutableStateFlow("") // What the user typed in the search bar
    val searchQuery = _searchQuery.asStateFlow()

    private val _digitalStations = MutableStateFlow<List<DigitalStation>>(emptyList()) // The list of internet radio stations we found
    val digitalStations = _digitalStations.asStateFlow()

    private val _currentDigitalStation = MutableStateFlow<DigitalStation?>(null) // The internet station currently playing
    val currentDigitalStation = _currentDigitalStation.asStateFlow()

    private val _isDigitalPlaying = MutableStateFlow(false)
    val isDigitalPlaying = _isDigitalPlaying.asStateFlow()

    private val _isDigitalLoading = MutableStateFlow(false) // Showing a loading spinner while fetching from the internet
    val isDigitalLoading = _isDigitalLoading.asStateFlow()

    private val _selectedDigitalCategory = MutableStateFlow("Trending") // Which tab the user is on (Pop, Jazz, News, etc.)
    val selectedDigitalCategory = _selectedDigitalCategory.asStateFlow()

    val digitalCategories = listOf("Trending", "Favorites", "Local", "Pop", "News", "Classical", "Jazz", "Lo-Fi", "Rock")

    private var digitalSearchJob: Job? = null // Background worker for typing searches
    private var fetchCategoryJob: Job? = null

    /**
     * THE WALKIE-TALKIE CONNECTION (`ServiceConnection`).
     * The background music player (MusicService) lives in its own world so it can keep playing when the app is closed.
     * This connection acts as a direct phone line to that Boiler Room so we can send it commands (Play, Pause, Tune).
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // We successfully connected to the Boiler Room!
            musicService = (service as? MusicService.MusicBinder)?.getService() ?: return
            isBound = true
            _isServiceConnected.value = true
            observeRadioState() // Start watching the Boiler Room's dials and gauges
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The connection broke (e.g., the system killed the audio engine to save battery)
            musicService = null
            isBound = false
            _isServiceConnected.value = false
            observeJob?.cancel() // Stop watching
        }
    }

    /**
     * THE MORNING ROUTINE (`init`)
     * This runs the moment the Manager wakes up when the screen is opened.
     */
    init {
        // Start the background Boiler Room (MusicService)
        val intent = Intent(app, MusicService::class.java)
        try {
            app.startService(intent)
        } catch (e: Exception) {
            // Ignored: On newer Androids, sometimes you aren't allowed to start background services silently.
        }

        // Pick up the Walkie-Talkie and connect to the service
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Immediately fetch a list of trending internet radio stations to show on screen
        fetchDigitalStations("Trending")
    }

    /**
     * Connects our Manager scoreboards to the actual Audio Engine's internal scoreboards.
     */
    private fun observeRadioState() {
        observeJob?.cancel() // Cancel any old watchers

        val service = musicService ?: return
        val engine = service.fmRadioEngine

        observeJob = viewModelScope.launch {
            // Watch what mode the player is in (Local MP3s vs FM Radio vs Internet Radio)
            launch {
                service.playbackMode.collect { mode ->
                    _playbackMode.value = mode
                    // If we switched away from Internet Radio, update the scoreboard so the screen shows it's stopped
                    if (mode != PlaybackMode.DIGITAL_RADIO) {
                        _isDigitalPlaying.value = false
                    }
                    // If we switched away from FM Radio, update that scoreboard too
                    if (mode != PlaybackMode.FM_RADIO) {
                        _isPlaying.value = false
                    }
                }
            }

            // Watch the phone's physical FM Radio engine
            _favoriteStations.value = engine.favoriteStations.value
            _currentFrequency.value = engine.frequency.value
            updateDSPMetrics(engine.frequency.value)

            launch {
                engine.isPlaying.collect { playing ->
                    _isPlaying.value = playing
                    updateDSPMetrics(_currentFrequency.value)
                }
            }

            launch {
                engine.favoriteStations.collect { stations ->
                    _favoriteStations.value = stations
                }
            }

            launch {
                engine.frequency.collect { freq ->
                    _currentFrequency.value = freq
                    updateDSPMetrics(freq) // Calculate signal strength based on how close we are to a real station
                }
            }

            // Sync speaker & mute state from the upgraded FmRadioEngine
            launch {
                engine.isSpeakerOn.collect { speakerOn ->
                    _isSpeakerEnabled.value = speakerOn
                }
            }

            launch {
                engine.isMuted.collect { muted ->
                    _isMuted.value = muted
                }
            }

            // Watch the headphone jack. FM Radio needs the wire to act as a physical antenna.
            launch {
                engine.isHeadsetConnected.collect { connected ->
                    _isHeadsetConnected.value = connected
                    if (!connected) {
                        // If they unplugged the headphones, the antenna is gone. Shut it down.
                        _isSpeakerEnabled.value = false
                        _isMuted.value = false
                        if (_isPlaying.value) {
                            _error.value = "Headset disconnected. Radio stopped."
                            engine.stop()
                        }
                    }
                }
            }
        }
    }

    // --- LIVE RADIO BROWSER API FETCHING --- //

    /**
     * THE PHONEBOOK LOOKUP.
     * Reaches out to a massive open-source database on the internet to find thousands of live radio stations.
     * UPGRADE: Cycles through multiple global mirrors (de1, fi1, at1) so a single server outage never breaks the app.
     */
    private suspend fun fetchFromRadioBrowser(endpoint: String): List<DigitalStation> = withContext(Dispatchers.IO) {
        val mirrors = listOf(
            "https://de1.api.radio-browser.info/json/stations",
            "https://fi1.api.radio-browser.info/json/stations",
            "https://at1.api.radio-browser.info/json/stations"
        )

        for (baseUrl in mirrors) {
            val resultList = mutableListOf<DigitalStation>()
            var connection: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$endpoint")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "GalleryBoxRadio/2.0")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    val seenUrls = HashSet<String>()

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val id = obj.optString("stationuuid", UUID.randomUUID().toString())
                        val name = obj.optString("name", "Unknown Station").trim()
                        val streamUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                        val imageUrl = obj.optString("favicon", "").trim()

                        var tags = obj.optString("tags", "")
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .take(3)
                            .joinToString(", ")
                        if (tags.length > 35) tags = tags.take(32) + "..."
                        if (tags.isBlank()) tags = "Live Stream"

                        val country = obj.optString("country", "Global").trim().ifEmpty { "Global" }

                        // Deduplicate identical stream URLs and mark saved favorites
                        if (streamUrl.isNotEmpty() && name.isNotEmpty() && seenUrls.add(streamUrl)) {
                            val isFav = favoriteDigitalIds.contains(id)
                            resultList.add(
                                DigitalStation(
                                    id = id,
                                    name = name,
                                    streamUrl = streamUrl,
                                    imageUrl = imageUrl,
                                    tags = tags,
                                    country = country,
                                    isFavorite = isFav
                                )
                            )
                        }
                    }

                    if (resultList.isNotEmpty()) {
                        return@withContext resultList
                    }
                }
            } catch (e: Exception) {
                // Try the next mirror automatically
            } finally {
                connection?.disconnect()
            }
        }
        emptyList()
    }

    // --- DIGITAL RADIO FAVORITES PERSISTENCE --- //

    private fun loadFavoriteDigitalIds(): Set<String> {
        return digitalPrefs.getStringSet("fav_ids", emptySet()) ?: emptySet()
    }

    private fun saveFavoriteDigitalStationMeta(station: DigitalStation) {
        val json = JSONObject().apply {
            put("id", station.id)
            put("name", station.name)
            put("streamUrl", station.streamUrl)
            put("imageUrl", station.imageUrl)
            put("tags", station.tags)
            put("country", station.country)
        }
        digitalPrefs.edit()
            .putStringSet("fav_ids", favoriteDigitalIds.toSet())
            .putString("fav_station_${station.id}", json.toString())
            .apply()
    }

    private fun removeFavoriteDigitalStationMeta(stationId: String) {
        digitalPrefs.edit()
            .putStringSet("fav_ids", favoriteDigitalIds.toSet())
            .remove("fav_station_$stationId")
            .apply()
    }

    private fun loadSavedFavoriteDigitalStations(): List<DigitalStation> {
        return favoriteDigitalIds.mapNotNull { id ->
            val raw = digitalPrefs.getString("fav_station_$id", null) ?: return@mapNotNull null
            try {
                val obj = JSONObject(raw)
                DigitalStation(
                    id = obj.optString("id", id),
                    name = obj.optString("name", "Saved Station"),
                    streamUrl = obj.optString("streamUrl", ""),
                    imageUrl = obj.optString("imageUrl", ""),
                    tags = obj.optString("tags", "Live Stream"),
                    country = obj.optString("country", "Global"),
                    isFavorite = true
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    // --- DIGITAL RADIO METHODS --- //

    /**
     * Called every time the user types a letter in the search bar.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        digitalSearchJob?.cancel() // Stop the previous search instantly if they keep typing

        digitalSearchJob = viewModelScope.launch {
            // DEBOUNCING: Wait 400ms before searching the internet if they type multiple characters quickly.
            delay(400)

            if (query.isNotBlank()) {
                _isDigitalLoading.value = true // Turn on the loading spinner
                val encodedQuery = withContext(Dispatchers.IO) {
                    URLEncoder.encode(query.trim(), "UTF-8")
                }
                val searchResults = fetchFromRadioBrowser("/search?name=$encodedQuery&limit=100&hidebroken=true&order=clickcount&reverse=true")
                _digitalStations.value = searchResults // Update the scoreboard with the results
                _isDigitalLoading.value = false // Turn off the loading spinner
            } else {
                // If they cleared the search bar, just fetch the normal categories again
                fetchDigitalStations(_selectedDigitalCategory.value)
            }
        }
    }

    // Called when the user clicks a tab like "Jazz" or "News"
    fun selectDigitalCategory(category: String) {
        _selectedDigitalCategory.value = category
        _searchQuery.value = "" // Clear the search bar
        digitalSearchJob?.cancel() // Stop any ongoing searches
        fetchDigitalStations(category)
    }

    // Figures out the correct internet link based on what category the user clicked.
    private fun fetchDigitalStations(category: String) {
        fetchCategoryJob?.cancel()
        fetchCategoryJob = viewModelScope.launch {
            _isDigitalLoading.value = true

            if (category == "Favorites") {
                _digitalStations.value = loadSavedFavoriteDigitalStations()
                _isDigitalLoading.value = false
                return@launch
            }

            val endpoint = when (category) {
                "Trending" -> "/topclick/100?hidebroken=true"
                "Local" -> "/bycountry/India?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "Pop" -> "/bytag/pop?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "News" -> "/bytag/news?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "Classical" -> "/bytag/classical?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "Jazz" -> "/bytag/jazz?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "Lo-Fi" -> "/bytag/lofi?limit=100&hidebroken=true&order=clickcount&reverse=true"
                "Rock" -> "/bytag/rock?limit=100&hidebroken=true&order=clickcount&reverse=true"
                else -> "/topclick/100?hidebroken=true"
            }

            val fetchedStations = fetchFromRadioBrowser(endpoint)
            _digitalStations.value = fetchedStations
            _isDigitalLoading.value = false
        }
    }

    // Called when the user taps on an Internet Radio station to play it
    fun playDigitalStation(station: DigitalStation) {
        if (station.streamUrl.isBlank()) return

        _currentDigitalStation.value = station
        _isDigitalPlaying.value = true

        stopRadioIfNeeded() // Shut off the FM radio so they don't play over each other!

        val cleanSubtitle = listOf(station.country.trim(), station.tags.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" • ")

        // Tell the Boiler Room to start playing the internet audio stream
        musicService?.playDigitalStream(
            url = station.streamUrl,
            title = station.name,
            subtitle = cleanSubtitle,
            imageUrl = station.imageUrl
        )
    }

    fun toggleDigitalPlayPause() {
        val current = _currentDigitalStation.value ?: return
        val playing = _isDigitalPlaying.value
        _isDigitalPlaying.value = !playing

        if (playing) {
            musicService?.pauseDigitalStream()
        } else {
            if (_playbackMode.value != PlaybackMode.DIGITAL_RADIO) {
                playDigitalStation(current)
            } else {
                musicService?.resumeDigitalStream()
            }
        }
    }

    fun toggleDigitalFavorite(stationId: String) {
        var toggledStation: DigitalStation? = null
        val isNowFav = !favoriteDigitalIds.contains(stationId)

        if (isNowFav) {
            favoriteDigitalIds.add(stationId)
        } else {
            favoriteDigitalIds.remove(stationId)
        }

        // Go through our list of stations. If the ID matches, flip its favorite status.
        _digitalStations.update { currentList ->
            currentList.map { station ->
                if (station.id == stationId) {
                    val updated = station.copy(isFavorite = isNowFav)
                    toggledStation = updated
                    updated
                } else {
                    station
                }
            }
        }

        // Also update the floating Now Playing Bar if the currently playing station was favorited!
        _currentDigitalStation.update { current ->
            if (current?.id == stationId) {
                val updated = current.copy(isFavorite = isNowFav)
                if (toggledStation == null) toggledStation = updated
                updated
            } else {
                current
            }
        }

        if (isNowFav) {
            toggledStation?.let { saveFavoriteDigitalStationMeta(it) }
        } else {
            removeFavoriteDigitalStationMeta(stationId)
            if (_selectedDigitalCategory.value == "Favorites") {
                _digitalStations.update { list -> list.filterNot { it.id == stationId } }
            }
        }
    }

    fun stopDigitalRadioIfNeeded() {
        if (_isDigitalPlaying.value) {
            _isDigitalPlaying.value = false
            musicService?.stopDigitalStream()
        }
    }

    // --- FM RADIO METHODS ---

    /**
     * Calculates a realistic "Signal Strength" meter.
     * Since most Android phones don't let us see the *actual* hardware signal strength of the FM chip,
     * we model it: If you are tuned to a known broadcast carrier (like 98.3), signal peaks near 92-98%.
     * As you detune away, signal drops and stereo blends into mono to suppress static hiss.
     */
    private fun updateDSPMetrics(freq: Float) {
        val nearest = knownStations.minByOrNull { abs(it - freq) } ?: freq
        val diff = abs(freq - nearest)
        val baseSignal = (98f - (diff / 0.25f) * 75f).coerceIn(22f, 98f).toInt()
        val engineSignal = musicService?.fmRadioEngine?.signalStrength?.value ?: 0
        val finalSignal = if (diff < 0.15f) baseSignal else maxOf(baseSignal, engineSignal)

        _signalStrength.value = if (_isHeadsetConnected.value) finalSignal else 0
        _stereoBlend.value = (finalSignal / 100f).coerceIn(0f, 1f)
    }

    // Turns the FM radio on or off
    fun toggleRadio() {
        stopDigitalRadioIfNeeded() // Stop internet music

        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to use FM radio" // Complain if no antenna
            return
        }
        if (engine.isPlaying.value) {
            engine.stop()
        } else {
            engine.start(_currentFrequency.value)
        }
    }

    fun startRadio(freq: Float = _currentFrequency.value) {
        stopDigitalRadioIfNeeded()

        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to use FM radio"
            return
        }
        engine.start(freq)
    }

    fun stopRadio() {
        musicService?.fmRadioEngine?.stop()
    }

    fun stopRadioIfNeeded() {
        val engine = musicService?.fmRadioEngine ?: return
        if (engine.isPlaying.value) {
            engine.stop()
        }
    }

    // Routes the FM audio from the headphones out to the phone's main loud speaker (like a boombox).
    // Note: The headphones still MUST be plugged in to act as the antenna!
    fun toggleSpeaker() {
        if (!_isHeadsetConnected.value) return
        val nextState = !_isSpeakerEnabled.value
        _isSpeakerEnabled.value = nextState
        musicService?.fmRadioEngine?.setSpeakerEnabled(nextState)
    }

    fun toggleMute() {
        if (!_isHeadsetConnected.value) return
        val nextState = !_isMuted.value
        _isMuted.value = nextState
        musicService?.fmRadioEngine?.setMute(nextState)
    }

    fun tuneToFrequency(freq: Float) {
        stopDigitalRadioIfNeeded()

        val safeFreq = (round(freq * 10f) / 10f).coerceIn(87.5f, 108.0f)
        _currentFrequency.value = safeFreq

        val engine = musicService?.fmRadioEngine ?: return
        engine.tune(safeFreq)

        // If the radio is off, turn it on so they can hear the new station.
        if (!engine.isPlaying.value) {
            if (engine.isHeadsetConnected.value) {
                engine.start(safeFreq)
            } else {
                _error.value = "Connect wired headphones to play FM radio"
            }
        }
    }

    // Turns the FM dial up slightly (e.g., 98.3 -> 98.4), wrapping around 108.0 -> 87.5
    fun tuneUp() {
        val next = round((_currentFrequency.value + 0.1f) * 10f) / 10f
        val newFreq = if (next > 108.0f) 87.5f else next
        tuneToFrequency(newFreq)
    }

    // Turns the FM dial down slightly, wrapping around 87.5 -> 108.0
    fun tuneDown() {
        val prev = round((_currentFrequency.value - 0.1f) * 10f) / 10f
        val newFreq = if (prev < 87.5f) 108.0f else prev
        tuneToFrequency(newFreq)
    }

    // Scans upwards automatically until the FM chip locks onto the next strong station and stops.
    fun autoScan() {
        if (_isScanning.value) return
        stopDigitalRadioIfNeeded()

        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true // Show scanning animation
            if (!engine.isPlaying.value) {
                engine.start(_currentFrequency.value)
            }

            // Smart seek: jump to the next known station above current frequency, or step +1.2 MHz
            val current = _currentFrequency.value
            val nextKnown = knownStations.firstOrNull { it > current + 0.05f }
            if (nextKnown != null) {
                engine.tune(nextKnown)
            } else {
                engine.scanNext()
            }

            delay(280L) // Wait a moment for the chip to lock onto a signal
            _isScanning.value = false
        }
    }

    // Scans downwards automatically until it finds a strong station.
    fun scanPrevious() {
        if (_isScanning.value) return
        stopDigitalRadioIfNeeded()

        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            if (!engine.isPlaying.value) {
                engine.start(_currentFrequency.value)
            }

            val current = _currentFrequency.value
            val prevKnown = knownStations.lastOrNull { it < current - 0.05f }
            if (prevKnown != null) {
                engine.tune(prevKnown)
            } else {
                engine.scanPrevious()
            }

            delay(280L)
            _isScanning.value = false
        }
    }

    // Sweeps across the FM band and saves all strong stations to the favorites list.
    fun autoScanAndSaveAll() {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            _error.value = "Scanning FM band..."

            var count = 0
            for (freq in knownStations) {
                engine.tune(freq)
                delay(220L) // Visually step the tuner dial across each station during the sweep
                if (engine.favoriteStations.value.none { abs(it - freq) < 0.05f }) {
                    engine.addFavorite(freq)
                    count++
                }
            }

            _isScanning.value = false
            _error.value = "Scan complete! $count new stations saved."
        }
    }

    // Hearts or Un-hearts an FM radio frequency
    fun toggleFavorite(freq: Float) {
        val engine = musicService?.fmRadioEngine ?: return
        val existing = engine.favoriteStations.value.find { abs(it - freq) < 0.05f }
        if (existing != null) {
            engine.removeFavorite(existing) // Already favorited, so remove it
        } else {
            engine.addFavorite(freq) // Not favorited, so add it
        }
    }

    fun clearError() {
        _error.value = null // Close the popup message
    }

    // Clean up when the app is completely destroyed
    override fun onCleared() {
        observeJob?.cancel() // Stop watching the Boiler Room
        digitalSearchJob?.cancel()
        fetchCategoryJob?.cancel()
        // Hang up the Walkie-Talkie connection to the Boiler Room
        if (isBound) {
            try {
                app.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
            }
            isBound = false
        }
        super.onCleared()
    }
}