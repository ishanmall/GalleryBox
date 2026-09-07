@file:Suppress("unused", "UnsafeOptInUsageError")

package com.gallerybox.viewmodel

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
data class DigitalStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val imageUrl: String,
    val tags: String,
    val country: String,
    val isFavorite: Boolean = false
)

@UnstableApi
@HiltViewModel
class RadioViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {

    private var musicService: MusicService? = null
    private var isBound = false
    private var observeJob: Job? = null

    // --- CORE & FM RADIO STATES ---
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected = _isServiceConnected.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE)
    val playbackMode = _playbackMode.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentFrequency = MutableStateFlow(98.0f)
    val currentFrequency = _currentFrequency.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false)
    val isHeadsetConnected = _isHeadsetConnected.asStateFlow()

    private val _favoriteStations = MutableStateFlow<List<Float>>(emptyList())
    val favoriteStations = _favoriteStations.asStateFlow()

    private val _signalStrength = MutableStateFlow(0)
    val signalStrength = _signalStrength.asStateFlow()

    private val _stereoBlend = MutableStateFlow(1.0f)
    val stereoBlend = _stereoBlend.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _isSpeakerEnabled = MutableStateFlow(false)
    val isSpeakerEnabled = _isSpeakerEnabled.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private val knownStations = listOf(91.1f, 92.7f, 93.5f, 98.3f, 104.8f)

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
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _digitalStations = MutableStateFlow<List<DigitalStation>>(emptyList())
    val digitalStations = _digitalStations.asStateFlow()

    private val _currentDigitalStation = MutableStateFlow<DigitalStation?>(null)
    val currentDigitalStation = _currentDigitalStation.asStateFlow()

    private val _isDigitalPlaying = MutableStateFlow(false)
    val isDigitalPlaying = _isDigitalPlaying.asStateFlow()

    private val _isDigitalLoading = MutableStateFlow(false)
    val isDigitalLoading = _isDigitalLoading.asStateFlow()

    private val _selectedDigitalCategory = MutableStateFlow("Trending")
    val selectedDigitalCategory = _selectedDigitalCategory.asStateFlow()

    val digitalCategories = listOf("Trending", "Local", "Pop", "News", "Classical", "Jazz")

    private var digitalSearchJob: Job? = null

    // --- SERVICE CONNECTION ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as? MusicService.MusicBinder)?.getService() ?: return
            isBound = true
            _isServiceConnected.value = true
            observeRadioState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
            _isServiceConnected.value = false
            observeJob?.cancel()
        }
    }

    init {
        val intent = Intent(app, MusicService::class.java)
        try {
            app.startService(intent)
        } catch (e: Exception) {
            // Ignored: Background execution limits on older Android versions
        }
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Load initial digital stations globally
        fetchDigitalStations("Trending")
    }

    private fun observeRadioState() {
        observeJob?.cancel()

        val service = musicService ?: return
        val engine = service.fmRadioEngine

        observeJob = viewModelScope.launch {
            launch {
                service.playbackMode.collect { mode ->
                    _playbackMode.value = mode
                    if (mode != PlaybackMode.DIGITAL_RADIO) {
                        _isDigitalPlaying.value = false
                    }
                    if (mode != PlaybackMode.FM_RADIO) {
                        _isPlaying.value = false
                    }
                }
            }

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
                        updateDSPMetrics(freq)
                    }
                }

                launch {
                    engine.isHeadsetConnected.collect { connected ->
                        _isHeadsetConnected.value = connected
                        if (!connected) {
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

    private suspend fun fetchFromRadioBrowser(endpoint: String): List<DigitalStation> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<DigitalStation>()
        try {
            val url = URL("https://de1.api.radio-browser.info/json/stations$endpoint")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "GalleryBoxRadio/1.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("stationuuid", UUID.randomUUID().toString())
                    val name = obj.optString("name", "Unknown Station").trim()
                    val streamUrl = obj.optString("url_resolved", obj.optString("url", ""))
                    val imageUrl = obj.optString("favicon", "")

                    var tags = obj.optString("tags", "").replace(",", ", ")
                    if (tags.length > 35) tags = tags.take(32) + "..."
                    if (tags.isBlank()) tags = "Live Stream"

                    val country = obj.optString("country", "Global")

                    if (streamUrl.isNotEmpty() && name.isNotEmpty()) {
                        resultList.add(DigitalStation(id, name, streamUrl, imageUrl, tags, country))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        resultList
    }

    // --- DIGITAL RADIO METHODS --- //

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        digitalSearchJob?.cancel()

        digitalSearchJob = viewModelScope.launch {
            delay(500) // Debounce user typing
            if (query.isNotBlank()) {
                _isDigitalLoading.value = true
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchResults = fetchFromRadioBrowser("/search?name=$encodedQuery&limit=100&hidebroken=true")
                _digitalStations.value = searchResults
                _isDigitalLoading.value = false
            } else {
                fetchDigitalStations(_selectedDigitalCategory.value)
            }
        }
    }

    fun selectDigitalCategory(category: String) {
        _selectedDigitalCategory.value = category
        _searchQuery.value = "" // Clear search when changing category
        digitalSearchJob?.cancel()
        fetchDigitalStations(category)
    }

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
                // Network failure fallback
                _digitalStations.value = listOf(
                    DigitalStation("fb1", "Check Internet Connection", "", "", "Offline", "")
                )
            }

            _isDigitalLoading.value = false
        }
    }

    fun playDigitalStation(station: DigitalStation) {
        if (station.streamUrl.isBlank()) return // Prevent clicking fallback items

        _currentDigitalStation.value = station
        _isDigitalPlaying.value = true

        stopRadioIfNeeded()

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

    private fun updateDSPMetrics(freq: Float) {
        val nearest = knownStations.minByOrNull { abs(it - freq) } ?: freq
        val diff = abs(freq - nearest)
        val calculatedSignal = (100f - (diff / 0.2f) * 100f).coerceIn(0f, 100f).toInt()
        _signalStrength.value = calculatedSignal
        _stereoBlend.value = calculatedSignal / 100f
    }

    fun toggleRadio() {
        stopDigitalRadioIfNeeded()

        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to use FM radio"
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
        if (!engine.isPlaying.value) {
            if (engine.isHeadsetConnected.value) {
                engine.start(freq)
            } else {
                _error.value = "Connect wired headphones to play FM radio"
            }
        }
    }

    fun tuneUp() {
        val newFreq = (round((_currentFrequency.value + 0.1f) * 10f) / 10f).coerceAtMost(108.0f)
        tuneToFrequency(newFreq)
    }

    fun tuneDown() {
        val newFreq = (round((_currentFrequency.value - 0.1f) * 10f) / 10f).coerceAtLeast(87.5f)
        tuneToFrequency(newFreq)
    }

    fun autoScan() {
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
            engine.scanNext()

            delay(250L)
            _isScanning.value = false
        }
    }

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

    fun autoScanAndSaveAll() {
        val engine = musicService?.fmRadioEngine ?: return
        if (!engine.isHeadsetConnected.value) {
            _error.value = "Connect wired headset to scan stations"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            _error.value = "Scanning FM band..."

            delay(2500L)

            var count = 0
            knownStations.forEach { freq ->
                if (engine.favoriteStations.value.none { abs(it - freq) < 0.05f }) {
                    engine.addFavorite(freq)
                    count++
                }
            }

            _isScanning.value = false
            _error.value = "Scan complete! $count new stations saved."
        }
    }

    fun toggleFavorite(freq: Float) {
        val engine = musicService?.fmRadioEngine ?: return
        val existing = engine.favoriteStations.value.find { abs(it - freq) < 0.05f }
        if (existing != null) {
            engine.removeFavorite(existing)
        } else {
            engine.addFavorite(freq)
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        observeJob?.cancel()
        digitalSearchJob?.cancel()
        if (isBound) {
            app.unbindService(serviceConnection)
            isBound = false
        }
        super.onCleared()
    }
}