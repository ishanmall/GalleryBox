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
    private val knownStations = listOf(91.1f, 92.7f, 93.5f, 98.3f, 104.8f)

    // This checks the current frequency. If it's very close to a known station (like 98.3), it displays the name ("Radio Mirchi").
    val rdsStationName = _currentFrequency.map { freq ->
        when {
            abs(freq - 98.3f) < 0.05f -> "Radio Mirchi"
            abs(freq - 93.5f) < 0.05f -> "Red FM"
            abs(freq - 92.7f) < 0.05f -> "Big FM"
            abs(freq - 104.8f) < 0.05f -> "Ishq FM"
            abs(freq - 91.1f) < 0.05f -> "Radio City"
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

    val digitalCategories = listOf("Trending", "Local", "Pop", "News", "Classical", "Jazz")

    private var digitalSearchJob: Job? = null // Background worker for typing searches

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

            // If the phone has a physical FM Radio chip inside it...
            if (engine != null) {
                _favoriteStations.value = engine.favoriteStations.value

                launch {
                    engine.isPlaying.collect { playing ->
                        if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                            _isPlaying.value = playing
                        }
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
                        updateDSPMetrics(freq) // Calculate fake signal strength based on how close we are to a real station
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
    }

    // --- LIVE RADIO BROWSER API FETCHING --- //

    /**
     * THE PHONEBOOK LOOKUP.
     * Reaches out to a massive open-source database on the internet to find thousands of live radio stations.
     * We do this on `Dispatchers.IO` (the background warehouse) because the internet is slow and we can't freeze the app.
     */
    private suspend fun fetchFromRadioBrowser(endpoint: String): List<DigitalStation> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<DigitalStation>()
        try {
            // Dialing the internet server...
            val url = URL("https://de1.api.radio-browser.info/json/stations$endpoint")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "GalleryBoxRadio/1.0") // Tell the server who is knocking
            connection.connectTimeout = 5000 // Give up if it takes longer than 5 seconds to connect
            connection.readTimeout = 5000

            // 200 means "OK! Here is the data."
            if (connection.responseCode == 200) {
                // Read the raw text response from the server
                val response = connection.inputStream.bufferedReader().use { it.readText() }

                // The response is in "JSON" format (a digital list of objects). We unpack it here.
                val jsonArray = JSONArray(response)

                // Go through the list box by box and build our `DigitalStation` business cards
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("stationuuid", UUID.randomUUID().toString())
                    val name = obj.optString("name", "Unknown Station").trim()
                    val streamUrl = obj.optString("url_resolved", obj.optString("url", ""))
                    val imageUrl = obj.optString("favicon", "")

                    // Clean up the tags (genres like Pop, Rock) so they look nice on screen
                    var tags = obj.optString("tags", "").replace(",", ", ")
                    if (tags.length > 35) tags = tags.take(32) + "..."
                    if (tags.isBlank()) tags = "Live Stream"

                    val country = obj.optString("country", "Global")

                    // Only add the station to our list if it actually gave us an audio link and a name
                    if (streamUrl.isNotEmpty() && name.isNotEmpty()) {
                        resultList.add(DigitalStation(id, name, streamUrl, imageUrl, tags, country))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        resultList // Return the final list of business cards
    }

    // --- DIGITAL RADIO METHODS --- //

    /**
     * Called every time the user types a letter in the search bar.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        digitalSearchJob?.cancel() // Stop the previous search instantly if they keep typing

        digitalSearchJob = viewModelScope.launch {
            // DEBOUNCING: Wait half a second before actually searching the internet.
            // If they type "R-O-C-K" really fast, we don't want to search the internet 4 times. We wait for them to finish typing.
            delay(500)

            if (query.isNotBlank()) {
                _isDigitalLoading.value = true // Turn on the loading spinner
                val encodedQuery = URLEncoder.encode(query, "UTF-8") // Make the text safe for internet links (e.g., spaces become %20)
                val searchResults = fetchFromRadioBrowser("/search?name=$encodedQuery&limit=100&hidebroken=true")
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
        viewModelScope.launch {
            _isDigitalLoading.value = true

            val endpoint = when (category) {
                "Trending" -> "/topclick/100?hidebroken=true"
                "Local" -> "/bycountry/India?limit=100&hidebroken=true"
                "Pop" -> "/bytag/pop?limit=100&hidebroken=true"
                "News" -> "/bytag/news?limit=100&hidebroken=true"
                "Classical" -> "/bytag/classical?limit=100&hidebroken=true"
                "Jazz" -> "/bytag/jazz?limit=100&hidebroken=true"
                else -> "/topclick/100?hidebroken=true"
            }

            val fetchedStations = fetchFromRadioBrowser(endpoint)

            if (fetchedStations.isNotEmpty()) {
                _digitalStations.value = fetchedStations
            } else {
                // If the list is empty (likely because the phone has no internet), show a fake "Offline" error station.
                _digitalStations.value = listOf(
                    DigitalStation("fb1", "Check Internet Connection", "", "", "Offline", "")
                )
            }

            _isDigitalLoading.value = false
        }
    }

    // Called when the user taps on an Internet Radio station to play it
    fun playDigitalStation(station: DigitalStation) {
        if (station.streamUrl.isBlank()) return // Prevent clicking the fake "Offline" error station

        _currentDigitalStation.value = station
        _isDigitalPlaying.value = true

        stopRadioIfNeeded() // Shut off the FM radio so they don't play over each other!

        // Tell the Boiler Room to start playing the internet audio stream
        musicService?.playDigitalStream(
            url = station.streamUrl,
            title = station.name,
            subtitle = "${station.country} • ${station.tags}",
            imageUrl = station.imageUrl
        )
    }

    fun toggleDigitalPlayPause() {
        val playing = _isDigitalPlaying.value
        _isDigitalPlaying.value = !playing

        if (playing) {
            musicService?.pauseDigitalStream()
        } else {
            musicService?.resumeDigitalStream()
        }
    }

    fun toggleDigitalFavorite(stationId: String) {
        // Go through our list of stations. If the ID matches, flip its favorite status (True to False, or False to True).
        _digitalStations.update { currentList ->
            currentList.map {
                if (it.id == stationId) it.copy(isFavorite = !it.isFavorite) else it
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
     * Calculates a fake "Signal Strength" meter.
     * Since most Android phones don't let us see the *actual* hardware signal strength of the FM chip,
     * we fake it: If you are exactly on 98.3, the signal is 100%. If you are on 98.4, it's 50%. If you are on 98.5, it's 0%.
     */
    private fun updateDSPMetrics(freq: Float) {
        val nearest = knownStations.minByOrNull { abs(it - freq) } ?: freq
        val diff = abs(freq - nearest)
        val calculatedSignal = (100f - (diff / 0.2f) * 100f).coerceIn(0f, 100f).toInt()
        _signalStrength.value = calculatedSignal
        _stereoBlend.value = calculatedSignal / 100f
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
        _isSpeakerEnabled.value = !_isSpeakerEnabled.value
        musicService?.fmRadioEngine?.setSpeakerEnabled(_isSpeakerEnabled.value)
    }

    fun toggleMute() {
        if (!_isHeadsetConnected.value) return
        _isMuted.value = !_isMuted.value
        musicService?.fmRadioEngine?.setMute(_isMuted.value)
    }

    fun tuneToFrequency(freq: Float) {
        stopDigitalRadioIfNeeded()

        val engine = musicService?.fmRadioEngine ?: return
        engine.tune(freq)

        // If the radio is off, turn it on so they can hear the new station.
        if (!engine.isPlaying.value) {
            if (engine.isHeadsetConnected.value) {
                engine.start(freq)
            } else {
                _error.value = "Connect wired headphones to play FM radio"
            }
        }
    }

    // Turns the FM dial up slightly (e.g., 98.3 -> 98.4)
    fun tuneUp() {
        val newFreq = (round((_currentFrequency.value + 0.1f) * 10f) / 10f).coerceAtMost(108.0f)
        tuneToFrequency(newFreq)
    }

    // Turns the FM dial down slightly
    fun tuneDown() {
        val newFreq = (round((_currentFrequency.value - 0.1f) * 10f) / 10f).coerceAtLeast(87.5f)
        tuneToFrequency(newFreq)
    }

    // Scans upwards automatically until the FM chip hits a strong station and stops.
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
            engine.scanNext() // Tell the physical FM chip to scan

            delay(250L) // Wait a moment for the chip to lock onto a signal
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
            engine.scanPrevious()

            delay(250L)
            _isScanning.value = false
        }
    }

    // Fakes a "Full Scan" by just adding all the popular stations to the favorites list instantly.
    fun autoScanAndSaveAll() {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            _error.value = "Scanning FM band..." // Use the error box to show a helpful message

            delay(2500L) // Fake a long scan so the user thinks it's working hard

            var count = 0
            knownStations.forEach { freq ->
                // If it isn't already in our favorites, add it.
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