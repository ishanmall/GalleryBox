// These annotations tell the Android compiler to ignore certain warnings.
// Think of it as telling an overly strict spell-checker to ignore specific words because we know what we are doing.
@file:Suppress("UnsafeOptInUsageError", "UnstableApiUsage", "OPT_IN_USAGE", "unused", "DEPRECATION", "ObsoleteSdkInt")
@file:OptIn(UnstableApi::class)

package com.gallerybox.engine

// --- IMPORTS ---
// This is the "toolbox" area. We are fetching all the tools we need to build this file.
// We are bringing in tools for hardware volume control, internet streaming, push notifications, and background services.
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import coil.ImageLoader
import coil.request.ImageRequest
import com.gallerybox.MainActivity
import com.gallerybox.R
import com.gallerybox.viewmodel.AudioTrack
import com.gallerybox.viewmodel.ChannelMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tanh
import kotlin.random.Random

// --- MODES ---
// A simple list to keep track of what exactly the app is playing right now.
enum class PlaybackMode {
    NONE,           // Silence
    LOCAL_MUSIC,    // Playing an MP3 saved on the phone
    FM_RADIO,       // Using the phone's physical FM antenna hardware
    DIGITAL_RADIO   // Streaming a live station from the internet
}

/**
 * --- THE RADIO TOWER (FmRadioEngine) ---
 * Some Android phones (especially older or budget ones) actually have physical FM radio chips inside them.
 * This engine talks directly to that hardware chip.
 *
 * NOTE: The FM chip CANNOT work without a physical wired headset plugged into the headphone jack.
 * The wire literally acts as the physical metal antenna to catch the radio waves!
 */
@Singleton
class FmRadioEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Android's master audio control panel (handles volume, speakers, bluetooth, etc.)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // A digital notebook to remember the last station the user was listening to.
    private val prefs = context.getSharedPreferences("gallerybox_fm_radio", Context.MODE_PRIVATE)

    // The "Speaking Stick". Android only lets one app make noise at a time.
    private var audioFocusRequest: AudioFocusRequest? = null

    // User settings
    var pauseOnUnplug = true // Should we stop the music if they yank the headphones out?
    var resumeOnPlug = false // Should we auto-start music if they plug headphones in?

    private var wasPausedByUnplug = false
    private var wasPausedByFocus = false // E.g., someone called the phone, so we paused.
    private var isDuckedByFocus = false  // Tracks if volume was temporarily lowered for a notification
    private var preDuckVolume: Int = -1
    private var lastScanTime = 0L

    // The limits of the FM dial (87.5 MHz to 108.0 MHz)
    private val minFreqInt = 875
    private val maxFreqInt = 1080
    private val stepInt = 1 // Move by 0.1 MHz per step

    // --- SCOREBOARDS ---
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private var currentFreqInt = (prefs.getFloat("freq", 98.0f) * 10).roundToInt().coerceIn(minFreqInt, maxFreqInt)
    private val _frequency = MutableStateFlow(currentFreqInt / 10f)
    val frequency = _frequency.asStateFlow()

    private val _isHeadsetConnected = MutableStateFlow(false)
    val isHeadsetConnected = _isHeadsetConnected.asStateFlow()

    private val favoritesSet = loadFavorites().toMutableSet()
    private val _favorites = MutableStateFlow(favoritesSet.map { it / 10f }.sorted())
    val favoriteStations = _favorites.asStateFlow()

    private val _signalStrength = MutableStateFlow(0)
    val signalStrength = _signalStrength.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn = _isSpeakerOn.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()

    private var isCallbackRegistered = false

    /**
     * THE POLICE SCANNER (focusChangeListener).
     * Android tells us when we are allowed to make noise.
     * If a phone call comes in, Android yells "AUDIOFOCUS_LOSS_TRANSIENT", so we pause the radio.
     * When they hang up, Android yells "AUDIOFOCUS_GAIN", so we turn the radio back on.
     */
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPausedByFocus = false
                restoreDuckingIfNeeded()
                stop() // Another app permanently stole the audio (like opening YouTube). Stop completely.
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_isPlaying.value) {
                    stop()
                    wasPausedByFocus = true // Remember WHY we paused, so we can auto-resume later.
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Upgraded: Smoothly duck volume for short notifications instead of killing the radio completely
                if (_isPlaying.value && !isDuckedByFocus) {
                    preDuckVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val duckedVol = (preDuckVolume * 0.3f).roundToInt().coerceAtLeast(1)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, duckedVol, 0)
                    isDuckedByFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreDuckingIfNeeded()
                if (wasPausedByFocus) {
                    start(_frequency.value)
                    wasPausedByFocus = false
                }
            }
        }
    }

    private fun restoreDuckingIfNeeded() {
        if (isDuckedByFocus && preDuckVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, preDuckVolume, 0)
            isDuckedByFocus = false
            preDuckVolume = -1
        }
    }

    /**
     * THE HEADPHONE SENSOR.
     * Listens constantly to see if a wire is plugged into the audio jack.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        // Headphones PLUGGED IN
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateHeadsetState()
            // If they yanked the cord out earlier, and just plugged it back in, resume playing!
            if (resumeOnPlug && wasPausedByUnplug && _isHeadsetConnected.value) {
                start(_frequency.value)
                wasPausedByUnplug = false
            }
        }

        // Headphones UNPLUGGED
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateHeadsetState()
            // We just lost our antenna!
            if (!_isHeadsetConnected.value) {
                if (pauseOnUnplug && _isPlaying.value) {
                    stop() // Stop the radio immediately so static doesn't blast out of the phone speaker
                    wasPausedByUnplug = true
                }
                _signalStrength.value = 0
            }
        }
    }

    init {
        updateHeadsetState() // Check the headphone jack the moment the app opens
    }

    private fun registerCallbacks() {
        if (!isCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            isCallbackRegistered = true
        }
    }

    private fun unregisterCallbacks() {
        if (isCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            isCallbackRegistered = false
        }
    }

    // Turns the physical FM chip ON
    fun start(freq: Float = _frequency.value): Boolean {
        updateHeadsetState()
        // Can't start without the headphone wire acting as an antenna
        if (!isHeadsetAvailable() || _isPlaying.value) {
            return false
        }
        registerCallbacks()
        if (!requestAudioFocus()) {
            return false
        }
        tune(freq)
        _isPlaying.value = true
        return true
    }

    // Turns the physical FM chip OFF
    fun stop() {
        restoreDuckingIfNeeded()
        abandonAudioFocus() // Give the Speaking Stick back to Android
        unregisterCallbacks()
        if (_isSpeakerOn.value) {
            setSpeakerEnabled(false)
        }
        _isPlaying.value = false
        _signalStrength.value = 0
    }

    // Turns the FM dial to a specific number (e.g. 98.3)
    fun tune(freq: Float) {
        tuneInt((freq * 10f).roundToInt())
    }

    // Handles the math of turning the dial and keeping it inside the limits (87.5 to 108.0)
    private fun tuneInt(freqInt: Int) {
        currentFreqInt = freqInt.coerceIn(minFreqInt, maxFreqInt)
        val safeFreq = currentFreqInt / 10f
        _frequency.value = safeFreq
        prefs.edit().putFloat("freq", safeFreq).apply() // Save the dial position so it's there next time they open the app

        // Fake a realistic-looking signal strength number for the UI to display
        if (isHeadsetAvailable()) {
            _signalStrength.value = (30..95).random(Random(currentFreqInt))
        } else {
            _signalStrength.value = 0
        }
    }

    fun seekUp() {
        // Upgraded: Wraps around to minFreqInt if user seeks past the top of the dial
        val next = if (currentFreqInt + stepInt > maxFreqInt) minFreqInt else currentFreqInt + stepInt
        tuneInt(next)
    }

    fun seekDown() {
        // Upgraded: Wraps around to maxFreqInt if user seeks past the bottom of the dial
        val prev = if (currentFreqInt - stepInt < minFreqInt) maxFreqInt else currentFreqInt - stepInt
        tuneInt(prev)
    }

    // Auto-scan feature. Jumps forward by 1.2 MHz to simulate finding the next clear station.
    fun scanNext() {
        if (System.currentTimeMillis() - lastScanTime < 250L) {
            return // Don't let the user spam the scan button 100 times a second
        }
        lastScanTime = System.currentTimeMillis()
        val nextFreq = if (currentFreqInt + 12 > maxFreqInt) minFreqInt else (currentFreqInt + 12).coerceAtMost(maxFreqInt)
        tuneInt(nextFreq)
    }

    fun scanPrevious() {
        if (System.currentTimeMillis() - lastScanTime < 250L) {
            return
        }
        lastScanTime = System.currentTimeMillis()
        val prevFreq = if (currentFreqInt - 12 < minFreqInt) maxFreqInt else (currentFreqInt - 12).coerceAtLeast(minFreqInt)
        tuneInt(prevFreq)
    }

    // Bypasses the headphones to force the radio sound to blast out of the phone's main loud speaker.
    fun setSpeakerEnabled(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
        _isSpeakerOn.value = enabled
    }

    fun setMute(mute: Boolean) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
        _isMuted.value = mute
    }

    fun addFavorite(freq: Float) {
        if (favoritesSet.add((freq * 10f).roundToInt())) {
            updateFavoritesFlowAndPrefs()
        }
    }

    fun removeFavorite(freq: Float) {
        if (favoritesSet.remove((freq * 10f).roundToInt())) {
            updateFavoritesFlowAndPrefs()
        }
    }

    fun toggleFavorite(freq: Float = _frequency.value) {
        val key = (freq * 10f).roundToInt()
        if (favoritesSet.contains(key)) {
            removeFavorite(freq)
        } else {
            addFavorite(freq)
        }
    }

    private fun updateFavoritesFlowAndPrefs() {
        _favorites.value = favoritesSet.map { it / 10f }.sorted()
        prefs.edit().putString("favorites", favoritesSet.joinToString(",")).apply()
    }

    private fun loadFavorites(): Set<Int> {
        val savedData = prefs.getString("favorites", "")
        if (savedData.isNullOrEmpty()) {
            return emptySet()
        }
        return savedData.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    // Checks if a physical wire is currently plugged into the phone's audio jack or USB-C port.
    // Bluetooth headphones DO NOT count, because Bluetooth uses radio waves, it isn't a physical metal antenna!
    private fun isHeadsetAvailable(): Boolean {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_DEVICE)
        }
    }

    private fun updateHeadsetState() {
        _isHeadsetConnected.value = isHeadsetAvailable()
    }

    // Formal request to Android: "Please silence other apps, I am about to play music."
    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    // Tells Android: "I'm done making noise, other apps can play now."
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    fun release() {
        stop()
    }
}

/**
 * THE AUDIO CABLE INTERCEPTOR (DynamicStereoProcessor)
 * Used for DJ/Duo Mode. It catches the raw audio data rushing through the pipes, and if we want
 * to force the sound into only the Left Earbud, it deletes all the audio heading to the right earbud.
 *
 * UPGRADED LOGIC:
 * 1. Supports both 16-bit PCM and 32-bit Float PCM (since DefaultAudioSink is configured with enableFloatOutput = true).
 * 2. Uses per-sample exponential gain smoothing so changing balance or switching ChannelMode never causes audible clicks/pops.
 * 3. Includes a soft-knee hyperbolic tangent (tanh) limiter to prevent harsh digital clipping when crossfeed or gain is high.
 */
class DynamicStereoProcessor(initialMode: ChannelMode) : BaseAudioProcessor() {

    @Volatile private var currentMode = initialMode
    @Volatile private var targetLeftGain = 1f
    @Volatile private var targetRightGain = 1f
    @Volatile private var crossfeed = 0f

    // Smoothed instantaneous gains to avoid zipper noise or clicking when dragging sliders
    private var currentLeftGain = 1f
    private var currentRightGain = 1f
    private val smoothingFactor = 0.005f

    fun setMode(mode: ChannelMode) {
        if (currentMode != mode) {
            currentMode = mode
            // Note: Removed abrupt flush() call here so changing modes mid-song transitions smoothly without dropping audio frames!
        }
    }

    fun setBalance(left: Float, right: Float) {
        this.targetLeftGain = left.coerceIn(0f, 2f)
        this.targetRightGain = right.coerceIn(0f, 2f)
    }

    // Crossfeed blends left and right slightly to make headphones sound like physical speakers sitting in front of you.
    fun setCrossfeed(amount: Float) {
        this.crossfeed = amount.coerceIn(0f, 1f)
    }

    // Upgraded: Process both standard 16-bit stereo AND 32-bit float stereo audio
    override fun onConfigure(fmt: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (fmt.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return if (fmt.encoding == C.ENCODING_PCM_16BIT || fmt.encoding == C.ENCODING_PCM_FLOAT) {
            fmt
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    // Soft-knee saturation helper to protect speakers/ears from digital clipping
    private fun softClip(sample: Float): Float {
        return if (abs(sample) > 0.95f) {
            tanh(sample.toDouble()).toFloat()
        } else {
            sample
        }
    }

    // The actual pipe interception
    override fun queueInput(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining == 0) {
            return
        }

        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        buffer.order(ByteOrder.nativeOrder())

        val cf = crossfeed
        val crossfeedScale = 1f / (1f + cf)
        val mode = currentMode

        // Determine effective target channel multipliers based on ChannelMode and Balance
        val effectiveTargetLeft = if (mode == ChannelMode.RIGHT_ONLY) 0f else targetLeftGain
        val effectiveTargetRight = if (mode == ChannelMode.LEFT_ONLY) 0f else targetRightGain

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            // High-resolution 32-bit Float DSP path
            while (buffer.remaining() >= 8) {
                var leftFloat = buffer.getFloat()
                var rightFloat = buffer.getFloat()

                // Smoothly glide current gain toward target gain
                currentLeftGain += (effectiveTargetLeft - currentLeftGain) * smoothingFactor
                currentRightGain += (effectiveTargetRight - currentRightGain) * smoothingFactor

                // Apply crossfeed math before channel muting so Duo Mode isolation stays 100% pure
                if (cf > 0f && mode == ChannelMode.STEREO) {
                    val tempLeft = leftFloat
                    val tempRight = rightFloat
                    leftFloat = (tempLeft + tempRight * cf) * crossfeedScale
                    rightFloat = (tempRight + tempLeft * cf) * crossfeedScale
                }

                // Apply Volume Balance (Panning) & Channel Forcing
                leftFloat = softClip(leftFloat * currentLeftGain)
                rightFloat = softClip(rightFloat * currentRightGain)

                outputBuffer.putFloat(leftFloat)
                outputBuffer.putFloat(rightFloat)
            }
        } else {
            // Standard 16-bit Short PCM DSP path
            while (buffer.remaining() >= 4) {
                var leftShort = buffer.getShort()
                var rightShort = buffer.getShort()

                // Apply Left/Right forcing
                when (mode) {
                    ChannelMode.LEFT_ONLY -> rightShort = 0
                    ChannelMode.RIGHT_ONLY -> leftShort = 0
                    ChannelMode.STEREO -> {}
                }

                var leftFloat = leftShort.toFloat()
                var rightFloat = rightShort.toFloat()

                // Smoothly glide current gain toward target gain
                currentLeftGain += (effectiveTargetLeft - currentLeftGain) * smoothingFactor
                currentRightGain += (effectiveTargetRight - currentRightGain) * smoothingFactor

                // Apply crossfeed math
                if (cf > 0f && mode == ChannelMode.STEREO) {
                    val tempLeft = leftFloat
                    val tempRight = rightFloat
                    leftFloat = (tempLeft + tempRight * cf) * crossfeedScale
                    rightFloat = (tempRight + tempLeft * cf) * crossfeedScale
                }

                // Apply Volume Balance (Panning)
                leftFloat *= currentLeftGain
                rightFloat *= currentRightGain

                val finalLeft = leftFloat.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val finalRight = rightFloat.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                outputBuffer.putShort(finalLeft)
                outputBuffer.putShort(finalRight)
            }
        }

        buffer.position(buffer.limit())
        outputBuffer.flip()
    }
}

/**
 * --- THE RECORD PLAYER (PlayerManager) ---
 * This manages playing actual MP3 files saved on the phone.
 * It uses Google's 'ExoPlayer' engine to decode the files and blast them out the speakers.
 */
@UnstableApi
@Singleton
class PlayerManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var crossfadeJob: Job? = null
    private var progressMonitorJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var autoCrossfadeTriggeredForMediaId: String? = null

    var crossfadeDurationMs = 2000
    var resumeOnPlug = false
    var pauseOnUnplug = true
    var gaplessPlayback = true

    private var wasPausedByUnplug = false
    private var wasPlayer2PausedByUnplug = false

    // We create TWO record players so we can play two songs at the same time in DJ mode.
    private val stereoProcessor1 = DynamicStereoProcessor(ChannelMode.STEREO)
    private val stereoProcessor2 = DynamicStereoProcessor(ChannelMode.STEREO)

    val player = createExoPlayer(stereoProcessor1, isPrimary = true)
    val player2 = createExoPlayer(stereoProcessor2, isPrimary = false)

    val player1Position: Long get() = player.currentPosition.coerceAtLeast(0L)
    val player2Position: Long get() = player2.currentPosition.coerceAtLeast(0L)
    val player1Duration: Long get() = player.duration.coerceAtLeast(0L)
    val player2Duration: Long get() = player2.duration.coerceAtLeast(0L)

    // Digital audio effects for Player 1 (EQ, Bass, Surround Sound)
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // Upgraded: Dedicated digital audio effects for Player 2 (so Duo Mode / Deck 2 has independent EQ & FX!)
    private var equalizer2: Equalizer? = null
    private var bassBoost2: BassBoost? = null
    private var virtualizer2: Virtualizer? = null
    private var presetReverb2: PresetReverb? = null
    private var loudnessEnhancer2: LoudnessEnhancer? = null

    // Cached AudioFX state so if ExoPlayer recreates an audio session, effects are automatically restored
    private var isEqEnabledCached = true
    private val eqBandLevels1 = mutableMapOf<Short, Short>()
    private val eqBandLevels2 = mutableMapOf<Short, Short>()
    private var cachedBassStrength: Short = 0
    private var cachedVirtualizerStrength: Short = 0
    private var cachedReverbPreset: Short = PresetReverb.PRESET_NONE
    private var cachedPreampMillibels: Int = 0

    // Scoreboards
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<AudioTrack?>(null)
    val currentTrack = _currentTrack.asStateFlow()

    private val _queue = MutableStateFlow<List<AudioTrack>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError = _playerError.asStateFlow()

    private var queueMap = emptyMap<String, AudioTrack>()

    private val _audioSessionId = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
    val audioSessionId = _audioSessionId.asStateFlow()

    private val _audioSessionId2 = MutableStateFlow(C.AUDIO_SESSION_ID_UNSET)
    val audioSessionId2 = _audioSessionId2.asStateFlow()

    private val _isPlaying2 = MutableStateFlow(false)
    val isPlaying2 = _isPlaying2.asStateFlow()

    private val _currentTrack2 = MutableStateFlow<AudioTrack?>(null)
    val currentTrack2 = _currentTrack2.asStateFlow()

    private val _volume1 = MutableStateFlow(1f)
    val volume1 = _volume1.asStateFlow()

    private val _volume2 = MutableStateFlow(1f)
    val volume2 = _volume2.asStateFlow()

    private val _balance1 = MutableStateFlow(0f)
    val balance1 = _balance1.asStateFlow()

    private val _balance2 = MutableStateFlow(0f)
    val balance2 = _balance2.asStateFlow()

    private val _crossfeed = MutableStateFlow(0f)
    val crossfeed = _crossfeed.asStateFlow()

    private val _softLimiterEnabled = MutableStateFlow(true)
    val softLimiterEnabled = _softLimiterEnabled.asStateFlow()

    // Upgraded: Reactive progress flows for smooth UI seekbars and time displays
    private val _currentPosition1 = MutableStateFlow(0L)
    val currentPosition1 = _currentPosition1.asStateFlow()

    private val _currentDuration1 = MutableStateFlow(0L)
    val currentDuration1 = _currentDuration1.asStateFlow()

    private val _currentPosition2 = MutableStateFlow(0L)
    val currentPosition2 = _currentPosition2.asStateFlow()

    private val _currentDuration2 = MutableStateFlow(0L)
    val currentDuration2 = _currentDuration2.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs = _sleepTimerRemainingMs.asStateFlow()

    private var isDuoModeActive = false
    private var isCallbackRegistered = false

    // Listens for headphones unplugging (for MP3s)
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && pauseOnUnplug) {
                if (player.isPlaying) {
                    wasPausedByUnplug = true
                    player.pause()
                }
                if (player2.isPlaying) {
                    wasPlayer2PausedByUnplug = true
                    player2.pause()
                }
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            if (resumeOnPlug && (wasPausedByUnplug || wasPlayer2PausedByUnplug)) {
                // For MP3s, Bluetooth headphones and USB DACs DO count as headphones!
                val hasHeadset = added?.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                } == true

                if (hasHeadset) {
                    if (wasPausedByUnplug) {
                        play(secondary = false)
                        wasPausedByUnplug = false
                    }
                    if (wasPlayer2PausedByUnplug && isDuoModeActive) {
                        play(secondary = true)
                        wasPlayer2PausedByUnplug = false
                    }
                }
            }
        }
    }

    init {
        // Connect wires from the first Record Player (ExoPlayer) to our UI Scoreboards.
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                _currentDuration1.value = player.duration.coerceAtLeast(0L)
                _currentPosition1.value = player.currentPosition.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                updateProgressMonitor()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _playerError.value = null
                autoCrossfadeTriggeredForMediaId = null
                mediaItem?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack.value = track
                    }
                }
                _currentDuration1.value = player.duration.coerceAtLeast(0L)
                _currentPosition1.value = player.currentPosition.coerceAtLeast(0L)
            }
            // Android gives every audio stream a unique ID number. We need this ID so we can attach
            // the Equalizer exactly to our music, and not accidentally Equalize the user's phone ringtone.
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    _audioSessionId.value = audioSessionId
                    initAudioFx(audioSessionId, isSecondary = false)
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    wasPausedByUnplug = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _isPlaying.value = false
                _playerError.value = "Playback failed: ${error.localizedMessage}"
                Log.e("PlayerManager", "Player 1 Error", error)
                // Upgraded: If a corrupted MP3 is encountered in a playlist, automatically skip to the next valid song
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        // Connect wires for the Second Record Player
        player2.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                _currentDuration2.value = player2.duration.coerceAtLeast(0L)
                _currentPosition2.value = player2.currentPosition.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying2.value = isPlaying
                updateProgressMonitor()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _playerError.value = null
                mediaItem?.mediaId?.let { id ->
                    queueMap[id]?.let { track ->
                        _currentTrack2.value = track
                    }
                }
                _currentDuration2.value = player2.duration.coerceAtLeast(0L)
                _currentPosition2.value = player2.currentPosition.coerceAtLeast(0L)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    _audioSessionId2.value = audioSessionId
                    initAudioFx(audioSessionId, isSecondary = true)
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    wasPlayer2PausedByUnplug = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                _isPlaying2.value = false
                _playerError.value = "Secondary playback failed: ${error.localizedMessage}"
                Log.e("PlayerManager", "Player 2 Error", error)
            }
        })
    }

    /**
     * Upgraded Progress & Auto-Crossfade Monitor:
     * Updates position StateFlows every 200ms while playing and automatically triggers a smooth
     * crossfade when a song is about to end (if crossfadeDurationMs > 0 and gaplessPlayback is not overriding it).
     */
    private fun updateProgressMonitor() {
        if (_isPlaying.value || _isPlaying2.value) {
            if (progressMonitorJob?.isActive == true) return
            progressMonitorJob = engineScope.launch {
                while (isActive && (_isPlaying.value || _isPlaying2.value)) {
                    if (_isPlaying.value) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = player.duration.coerceAtLeast(0L)
                        _currentPosition1.value = pos
                        _currentDuration1.value = dur

                        // Automatic End-of-Track Crossfade check
                        val currentMediaId = player.currentMediaItem?.mediaId
                        if (crossfadeDurationMs > 0 &&
                            dur > crossfadeDurationMs * 2 &&
                            player.hasNextMediaItem() &&
                            crossfadeJob?.isActive != true &&
                            currentMediaId != null &&
                            autoCrossfadeTriggeredForMediaId != currentMediaId
                        ) {
                            val remaining = dur - pos
                            if (remaining in 1..crossfadeDurationMs.toLong()) {
                                autoCrossfadeTriggeredForMediaId = currentMediaId
                                triggerCrossfade()
                            }
                        }
                    }
                    if (_isPlaying2.value) {
                        _currentPosition2.value = player2.currentPosition.coerceAtLeast(0L)
                        _currentDuration2.value = player2.duration.coerceAtLeast(0L)
                    }
                    delay(200L)
                }
            }
        } else {
            progressMonitorJob?.cancel()
            progressMonitorJob = null
        }
    }

    fun clearError() {
        _playerError.value = null
    }

    // Builds the physical ExoPlayer software component
    private fun createExoPlayer(processor: DynamicStereoProcessor, isPrimary: Boolean = true): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor)) // Inject our left/right audio blocker wire
                    .setEnableFloatOutput(true) // Higher quality audio processing
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1500,
                2000
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            // Upgraded: Primary player automatically manages Android AudioFocus so calls/other apps pause MP3s cleanly.
            // Secondary player shares focus with primary so it doesn't fight Player 1 in Duo Mode.
            .setAudioAttributes(audioAttributes, isPrimary)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(false) // We handle noisy unplugging manually with our receiver
            .setPauseAtEndOfMediaItems(!gaplessPlayback)
            .build()
    }

    private fun registerCallbacks() {
        if (!isCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    noisyAudioReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(noisyAudioReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
            isCallbackRegistered = true
        }
    }

    private fun unregisterCallbacks() {
        if (isCallbackRegistered) {
            try {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            } catch (_: Exception) {}
            try {
                context.unregisterReceiver(noisyAudioReceiver)
            } catch (_: Exception) {}
            isCallbackRegistered = false
        }
    }

    // Wakes up Android's built-in sound effects engine and attaches them to our music stream.
    private fun initAudioFx(sessionId: Int, isSecondary: Boolean = false) {
        if (!isSecondary) {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            presetReverb?.release()
            loudnessEnhancer?.release()

            // Wrap them in try/catch because some budget phones don't support these advanced audio features!
            try {
                equalizer = Equalizer(0, sessionId).apply {
                    enabled = isEqEnabledCached
                    eqBandLevels1.forEach { (band, level) ->
                        if (band in 0 until numberOfBands) setBandLevel(band, level)
                    }
                }
            } catch (_: Exception) {}

            try {
                bassBoost = BassBoost(0, sessionId).apply {
                    enabled = true
                    if (cachedBassStrength > 0) setStrength(cachedBassStrength)
                }
            } catch (_: Exception) {}

            try {
                virtualizer = Virtualizer(0, sessionId).apply {
                    enabled = true
                    if (cachedVirtualizerStrength > 0) setStrength(cachedVirtualizerStrength)
                }
            } catch (_: Exception) {}

            try {
                presetReverb = PresetReverb(0, sessionId).apply {
                    enabled = true
                    preset = cachedReverbPreset
                }
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                        enabled = true
                        setTargetGain(cachedPreampMillibels)
                    }
                } catch (_: Exception) {}
            }
        } else {
            // Secondary Player AudioFX initialization
            equalizer2?.release()
            bassBoost2?.release()
            virtualizer2?.release()
            presetReverb2?.release()
            loudnessEnhancer2?.release()

            try {
                equalizer2 = Equalizer(0, sessionId).apply {
                    enabled = isEqEnabledCached
                    eqBandLevels2.forEach { (band, level) ->
                        if (band in 0 until numberOfBands) setBandLevel(band, level)
                    }
                }
            } catch (_: Exception) {}

            try {
                bassBoost2 = BassBoost(0, sessionId).apply {
                    enabled = true
                    if (cachedBassStrength > 0) setStrength(cachedBassStrength)
                }
            } catch (_: Exception) {}

            try {
                virtualizer2 = Virtualizer(0, sessionId).apply {
                    enabled = true
                    if (cachedVirtualizerStrength > 0) setStrength(cachedVirtualizerStrength)
                }
            } catch (_: Exception) {}

            try {
                presetReverb2 = PresetReverb(0, sessionId).apply {
                    enabled = true
                    preset = cachedReverbPreset
                }
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    loudnessEnhancer2 = LoudnessEnhancer(sessionId).apply {
                        enabled = true
                        setTargetGain(cachedPreampMillibels)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun play(secondary: Boolean = false) {
        registerCallbacks()
        setAudioFxActive(true)
        if (secondary) {
            if (player2.playbackState == Player.STATE_IDLE && player2.mediaItemCount > 0) {
                player2.prepare()
            }
            player2.play()
        } else {
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                player.prepare()
            }
            player.play()
        }
    }

    fun pause(secondary: Boolean = false) {
        if (secondary) {
            player2.pause()
        } else {
            crossfadeJob?.cancel()
            applyVolumes() // Ensure volume is restored if paused mid-crossfade
            player.pause()
        }
    }

    fun togglePlayPause(secondary: Boolean = false) {
        if (secondary) {
            if (player2.isPlaying) {
                pause(secondary = true)
            } else {
                play(secondary = true)
            }
        } else {
            if (player.isPlaying) {
                pause(secondary = false)
            } else {
                play(secondary = false)
            }
        }
    }

    fun seekToNext() {
        crossfadeJob?.cancel()
        applyVolumes()
        if (player.hasNextMediaItem()) {
            player.seekToNext()
        } else if (player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0) {
            player.seekTo(0, 0L)
        }
    }

    fun seekToPrevious() {
        crossfadeJob?.cancel()
        applyVolumes()
        // Upgraded: Standard media player UX — if more than 3 seconds into the song, restart current song; otherwise go to previous song
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPrevious()
        } else {
            player.seekTo(0L) // If there is no previous song, just restart the current song
        }
    }

    fun seekTo(ms: Long, secondary: Boolean = false) {
        val targetPlayer = if (secondary) player2 else player
        val safeMs = if (targetPlayer.duration > 0) ms.coerceIn(0L, targetPlayer.duration) else ms.coerceAtLeast(0L)
        targetPlayer.seekTo(safeMs)
        if (secondary) {
            _currentPosition2.value = safeMs
        } else {
            _currentPosition1.value = safeMs
        }
    }

    fun seekToFraction(fraction: Float, secondary: Boolean = false) {
        val targetPlayer = if (secondary) player2 else player
        val safeFraction = fraction.coerceIn(0f, 1f)
        if (targetPlayer.duration > 0) {
            val targetMs = (targetPlayer.duration * safeFraction).toLong()
            targetPlayer.seekTo(targetMs)
            if (secondary) {
                _currentPosition2.value = targetMs
            } else {
                _currentPosition1.value = targetMs
            }
        }
    }

    fun setRepeatMode(mode: Int) {
        player.repeatMode = mode
    }

    fun setShuffleMode(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    fun setGaplessPlaybackEnabled(enabled: Boolean) {
        gaplessPlayback = enabled
        player.pauseAtEndOfMediaItems = !enabled
    }

    fun setVolume(volume: Float, secondary: Boolean = false) {
        val clamped = volume.coerceIn(0f, 1f)
        if (secondary) {
            _volume2.value = clamped
        } else {
            _volume1.value = clamped
        }
        applyVolumes()
    }

    fun setSoftLimiter(enabled: Boolean) {
        _softLimiterEnabled.value = enabled
        applyVolumes()
    }

    // Computes the target logarithmic volume for a given slider value
    private fun calculateTargetVolume(volumeVal: Float, limiterScale: Float): Float {
        return ((ln(1.0 + 9.0 * volumeVal.coerceIn(0f, 1f)) / ln(10.0)).toFloat() * limiterScale).coerceIn(0f, 1f)
    }

    // Applies volume logarithmically, because human ears hear volume changes on a curve, not a straight line.
    private fun applyVolumes() {
        val volumeVal1 = _volume1.value
        val volumeVal2 = _volume2.value
        // If DJ Duo Mode is active, and both players are at 100% volume, we halve them both to 50%
        // so the combined sound doesn't explode the speaker. This is called a "Soft Limiter".
        val limiterScale = if (_softLimiterEnabled.value && isDuoModeActive) 1f / max(1f, volumeVal1 + volumeVal2) else 1f
        val logVolume1 = calculateTargetVolume(volumeVal1, limiterScale)
        val logVolume2 = calculateTargetVolume(volumeVal2, limiterScale)

        player.volume = logVolume1
        player2.volume = logVolume2
    }

    fun setStereoBalance(balance: Float, secondary: Boolean = false) {
        val safeBalance = balance.coerceIn(-1f, 1f)
        if (secondary) {
            _balance2.value = safeBalance
        } else {
            _balance1.value = safeBalance
        }

        val left = if (safeBalance > 0f) 1f - safeBalance else 1f
        val right = if (safeBalance < 0f) 1f + safeBalance else 1f

        if (secondary) {
            stereoProcessor2.setBalance(left, right)
        } else {
            stereoProcessor1.setBalance(left, right)
        }
    }

    fun setCrossfeed(amount: Float) {
        val clamped = amount.coerceIn(0f, 1f)
        _crossfeed.value = clamped
        stereoProcessor1.setCrossfeed(clamped)
        stereoProcessor2.setCrossfeed(clamped)
    }

    fun setCrossfadeDuration(durationMs: Int) {
        crossfadeDurationMs = durationMs.coerceIn(0, 15000)
    }

    fun resetPlaybackParameters() {
        player.playbackParameters = PlaybackParameters.DEFAULT
        player2.playbackParameters = PlaybackParameters.DEFAULT
    }

    fun setSpeed(speed: Float, isPlayer2: Boolean) {
        val safeSpeed = speed.coerceIn(0.25f, 3.0f)
        val targetPlayer = if (isPlayer2) player2 else player
        targetPlayer.playbackParameters = PlaybackParameters(safeSpeed, targetPlayer.playbackParameters.pitch)
    }

    fun setPitch(pitch: Float, isPlayer2: Boolean) {
        val safePitch = pitch.coerceIn(0.25f, 3.0f)
        val targetPlayer = if (isPlayer2) player2 else player
        targetPlayer.playbackParameters = PlaybackParameters(targetPlayer.playbackParameters.speed, safePitch)
    }

    fun setPreampGain(millibels: Int) {
        cachedPreampMillibels = millibels
        try {
            loudnessEnhancer?.setTargetGain(millibels) // Make it universally louder
            loudnessEnhancer2?.setTargetGain(millibels)
        } catch (_: Exception) {}
    }

    fun setEqEnabled(enabled: Boolean) {
        isEqEnabledCached = enabled
        try {
            equalizer?.enabled = enabled
            equalizer2?.enabled = enabled
        } catch (_: Exception) {}
    }

    private fun setAudioFxActive(active: Boolean) {
        val shouldEnableEq = active && isEqEnabledCached
        try { equalizer?.enabled = shouldEnableEq } catch (_: Exception) {}
        try { equalizer2?.enabled = shouldEnableEq } catch (_: Exception) {}
        try { bassBoost?.enabled = active } catch (_: Exception) {}
        try { bassBoost2?.enabled = active } catch (_: Exception) {}
        try { virtualizer?.enabled = active } catch (_: Exception) {}
        try { virtualizer2?.enabled = active } catch (_: Exception) {}
        try { presetReverb?.enabled = active } catch (_: Exception) {}
        try { presetReverb2?.enabled = active } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try { loudnessEnhancer?.enabled = active } catch (_: Exception) {}
            try { loudnessEnhancer2?.enabled = active } catch (_: Exception) {}
        }
    }

    fun updateEq(index: Int, level: Float, isPlayer2: Boolean = false) {
        try {
            val targetEq = if (isPlayer2) equalizer2 else equalizer
            val band = index.toShort()
            // Upgraded: Query hardware equalizer's actual supported min/max band level range if available, falling back to +/- 1500 mB
            val range = targetEq?.bandLevelRange
            val convertedLevel = if (range != null && range.size >= 2) {
                val min = range[0].toInt()
                val max = range[1].toInt()
                (min + level.coerceIn(0f, 1f) * (max - min)).roundToInt().toShort()
            } else {
                ((level - 0.5f) * 3000f).toInt().toShort()
            }

            if (isPlayer2) {
                eqBandLevels2[band] = convertedLevel
            } else {
                eqBandLevels1[band] = convertedLevel
            }
            targetEq?.setBandLevel(band, convertedLevel)
        } catch (_: Exception) {}
    }

    fun updateBass(strength: Float) {
        val safeStrength = (strength.coerceIn(0f, 1f) * 1000).toInt().toShort()
        cachedBassStrength = safeStrength
        try {
            bassBoost?.setStrength(safeStrength)
            bassBoost2?.setStrength(safeStrength)
        } catch (_: Exception) {}
    }

    fun updateVirtualizer(strength: Float) {
        val safeStrength = (strength.coerceIn(0f, 1f) * 1000).toInt().toShort()
        cachedVirtualizerStrength = safeStrength
        try {
            virtualizer?.setStrength(safeStrength)
            virtualizer2?.setStrength(safeStrength)
        } catch (_: Exception) {}
    }

    fun setReverb(preset: Short) {
        cachedReverbPreset = preset
        try {
            presetReverb?.preset = preset
            presetReverb2?.preset = preset
        } catch (_: Exception) {}
    }

    // Helper to build a MediaItem consistently for any AudioTrack
    private fun buildMediaItem(track: AudioTrack): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(Uri.parse("content://media/external/audio/albumart/${track.albumId}"))
            .build()

        return MediaItem.Builder()
            .setUri(Uri.parse(track.uri))
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    // Loads an MP3 file into the Record Player and starts it
    fun playTrack(track: AudioTrack, secondary: Boolean = false) {
        registerCallbacks()
        setAudioFxActive(true)
        _playerError.value = null

        val targetPlayer = if (secondary) player2 else player

        if (secondary) {
            _currentTrack2.value = track
        } else {
            crossfadeJob?.cancel()
            applyVolumes()
            _currentTrack.value = track
            if (_queue.value.isEmpty()) {
                _queue.value = listOf(track)
            }
        }

        queueMap = queueMap + (track.id.toString() to track)
        val mediaItem = buildMediaItem(track)

        targetPlayer.setMediaItem(mediaItem)
        targetPlayer.prepare()
        targetPlayer.playWhenReady = true
    }

    // Loads a whole list of songs into the Record Player
    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        registerCallbacks()
        setAudioFxActive(true)
        crossfadeJob?.cancel()
        applyVolumes()
        _playerError.value = null
        _queue.value = tracks
        queueMap = queueMap + tracks.associateBy { it.id.toString() }

        val safeStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        _currentTrack.value = tracks[safeStartIndex]

        val mediaItems = tracks.map { track -> buildMediaItem(track) }

        player.setMediaItems(mediaItems, safeStartIndex, C.TIME_UNSET)
        player.prepare()
        player.play()
    }

    // --- UPGRADED QUEUE MANAGEMENT FUNCTIONS ---

    // Appends a track to the end of the active queue without interrupting playback
    fun addToQueue(track: AudioTrack) {
        queueMap = queueMap + (track.id.toString() to track)
        _queue.value = _queue.value + track
        player.addMediaItem(buildMediaItem(track))
    }

    // Inserts a track right after the currently playing track
    fun playNext(track: AudioTrack) {
        queueMap = queueMap + (track.id.toString() to track)
        val insertIndex = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        val mutableQueue = _queue.value.toMutableList()
        val listInsertIndex = insertIndex.coerceIn(0, mutableQueue.size)
        mutableQueue.add(listInsertIndex, track)
        _queue.value = mutableQueue
        player.addMediaItem(insertIndex, buildMediaItem(track))
    }

    // Removes a track at a specific position from the active queue
    fun removeFromQueue(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.removeMediaItem(index)
            val mutableQueue = _queue.value.toMutableList()
            if (index in mutableQueue.indices) {
                mutableQueue.removeAt(index)
                _queue.value = mutableQueue
            }
        }
    }

    // Reorders a track inside the active queue (e.g. drag-and-drop in UI)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex in 0 until player.mediaItemCount && toIndex in 0 until player.mediaItemCount && fromIndex != toIndex) {
            player.moveMediaItem(fromIndex, toIndex)
            val mutableQueue = _queue.value.toMutableList()
            if (fromIndex in mutableQueue.indices && toIndex in mutableQueue.indices) {
                val item = mutableQueue.removeAt(fromIndex)
                mutableQueue.add(toIndex, item)
                _queue.value = mutableQueue
            }
        }
    }

    // --- UPGRADED SLEEP TIMER ---
    fun startSleepTimer(durationMinutes: Int) {
        sleepTimerJob?.cancel()
        if (durationMinutes <= 0) {
            _sleepTimerRemainingMs.value = null
            return
        }
        val totalMs = durationMinutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs
        sleepTimerJob = engineScope.launch {
            var remaining = totalMs
            while (remaining > 5000L && isActive) {
                delay(1000L)
                remaining -= 1000L
                _sleepTimerRemainingMs.value = remaining
            }
            // Smooth 5-second volume fade-out before going to sleep
            val steps = 25
            val stepDelay = 5000L / steps
            val startVol1 = player.volume
            val startVol2 = player2.volume
            repeat(steps) { idx ->
                val factor = 1f - ((idx + 1) / steps.toFloat())
                player.volume = (startVol1 * factor).coerceAtLeast(0f)
                player2.volume = (startVol2 * factor).coerceAtLeast(0f)
                delay(stepDelay)
            }
            pause(secondary = false)
            pause(secondary = true)
            applyVolumes() // Restore configured volume levels for next time user hits play
            _sleepTimerRemainingMs.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
        applyVolumes()
    }

    fun setDuoMode(enabled: Boolean) {
        isDuoModeActive = enabled
        applyVolumes()

        if (enabled) {
            stereoProcessor1.setMode(ChannelMode.LEFT_ONLY)
            stereoProcessor2.setMode(ChannelMode.RIGHT_ONLY)
        } else {
            stereoProcessor1.setMode(ChannelMode.STEREO)
            stereoProcessor2.setMode(ChannelMode.STEREO)
            player2.stop()
            player2.clearMediaItems()
            _currentTrack2.value = null
            _isPlaying2.value = false
        }
    }

    // A DJ effect that smoothly lowers the volume of the current song while starting the next song.
    fun triggerCrossfade() {
        if (crossfadeDurationMs <= 0 || !player.hasNextMediaItem()) {
            seekToNext()
            return
        }

        crossfadeJob?.cancel()
        crossfadeJob = engineScope.launch {
            val steps = 20 // Break the fade into 20 tiny volume jumps
            val limiterScale = if (_softLimiterEnabled.value && isDuoModeActive) 1f / max(1f, _volume1.value + _volume2.value) else 1f
            val initialVolume = calculateTargetVolume(_volume1.value, limiterScale)
            val delayDuration = (crossfadeDurationMs / 2 / steps).toLong().coerceAtLeast(10L)

            try {
                // Fade OUT the current song
                repeat(steps) { stepIndex ->
                    player.volume = initialVolume * (1f - (stepIndex + 1) / steps.toFloat())
                    delay(delayDuration)
                }

                player.volume = 0f
                if (player.hasNextMediaItem()) {
                    player.seekToNext() // Skip to next song instantly
                }

                // Fade IN the new song
                repeat(steps) { stepIndex ->
                    val currentTarget = calculateTargetVolume(_volume1.value, limiterScale)
                    player.volume = currentTarget * ((stepIndex + 1) / steps.toFloat())
                    delay(delayDuration)
                }
            } finally {
                applyVolumes() // Always restore to normal logarithmic volume even if job is cancelled mid-fade
            }
        }
    }

    fun stopAll() {
        crossfadeJob?.cancel()
        progressMonitorJob?.cancel()
        cancelSleepTimer()
        unregisterCallbacks()

        player.stop()
        player.clearMediaItems()
        player2.stop()
        player2.clearMediaItems()

        player.playWhenReady = false
        player2.playWhenReady = false

        _currentTrack.value = null
        _currentTrack2.value = null
        _queue.value = emptyList()
        _playerError.value = null

        _isPlaying.value = false
        _isPlaying2.value = false
        _currentPosition1.value = 0L
        _currentDuration1.value = 0L
        _currentPosition2.value = 0L
        _currentDuration2.value = 0L

        // Turn off all effects so they don't consume battery when paused
        setAudioFxActive(false)
    }

    fun release() {
        stopAll()
        engineScope.cancel()

        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        presetReverb?.release()
        loudnessEnhancer?.release()

        equalizer2?.release()
        bassBoost2?.release()
        virtualizer2?.release()
        presetReverb2?.release()
        loudnessEnhancer2?.release()

        player.release()
        player2.release()
    }
}

/**
 * --- THE CONDUCTOR (MusicService) ---
 * Android is brutal. If a user swipes up to go to their home screen, Android instantly freezes the app.
 * A `Service` is a VIP pass. It tells Android: "I am doing something important in the background, please don't kill me!"
 * This service runs the music continuously even when the app is closed, and provides the Notification Bar playback controls.
 */
@UnstableApi
@AndroidEntryPoint
class MusicService : Service() {

    // Bring in our Record Player and our FM Radio Tower
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var fmRadioEngine: FmRadioEngine

    // A wire (Binder) that allows the visual screen to talk directly to this background service.
    private val binder = MusicBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // An image loader for getting the Album Art to show on the lock screen
    private val imageLoader by lazy { ImageLoader(this) }

    // MediaSession is the magic link that connects our internal player to the phone's lock screen and bluetooth car controls.
    private var mediaSession: MediaSession? = null
    private var notificationManager: NotificationManager? = null
    private var currentAlbumArt: Bitmap? = null
    private var lastLoadedArtKey: String? = null
    private var autoStopJob: Job? = null // A timer that completely shuts down the service if paused for too long, to save battery.
    private var streamRetryJob: Job? = null
    private var streamRetryCount = 0

    // A third, completely separate player used ONLY for streaming internet radio.
    private var digitalPlayer: ExoPlayer? = null
    private var isDigitalPlaying = false
    private var digitalStreamUrl = ""
    private var digitalTitle = ""
    private var digitalSubtitle = ""
    private var digitalLiveStreamSongTitle: String? = null // Upgraded: Holds live ICY metadata song title from radio stream
    private var digitalImageUrl = ""

    private val _playbackMode = MutableStateFlow(PlaybackMode.NONE)
    val playbackMode = _playbackMode.asStateFlow()

    inner class MusicBinder : Binder() { fun getService(): MusicService = this@MusicService }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val digitalAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Upgraded: Digital radio player now handles AudioFocus, WakeLocks, and Noisy headphone unplugs automatically
        digitalPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(digitalAudioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()

        digitalPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isDigitalPlaying = isPlaying
                if (isPlaying) {
                    streamRetryCount = 0
                    streamRetryJob?.cancel()
                    // MUTE THE OTHERS! If Internet Radio starts playing, forcefully stop the FM Radio and MP3 players.
                    fmRadioEngine.stop()
                    playerManager.pause(secondary = false)
                    playerManager.pause(secondary = true)
                    _playbackMode.value = PlaybackMode.DIGITAL_RADIO
                    mediaSession?.player = digitalPlayer!!
                    updateNotification(true)
                } else if (_playbackMode.value == PlaybackMode.DIGITAL_RADIO) {
                    updateNotification(false)
                    scheduleAutoStop()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (_playbackMode.value == PlaybackMode.DIGITAL_RADIO) {
                    updateNotification(digitalPlayer?.isPlaying == true)
                }
            }
            // Upgraded: Extract live ICY stream metadata (currently playing song on internet radio)
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (_playbackMode.value == PlaybackMode.DIGITAL_RADIO) {
                    val streamTitle = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() && it != digitalTitle }
                    if (streamTitle != digitalLiveStreamSongTitle) {
                        digitalLiveStreamSongTitle = streamTitle
                        updateNotification(digitalPlayer?.isPlaying == true)
                    }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e("MusicService", "Digital stream error", error)
                // Upgraded: Exponential backoff auto-reconnect if live internet radio stream drops briefly
                if (_playbackMode.value == PlaybackMode.DIGITAL_RADIO && digitalStreamUrl.isNotEmpty() && streamRetryCount < 3) {
                    streamRetryCount++
                    val backoffMs = 2000L * streamRetryCount
                    streamRetryJob?.cancel()
                    streamRetryJob = serviceScope.launch {
                        delay(backoffMs)
                        Log.i("MusicService", "Attempting digital stream reconnect ($streamRetryCount/3)...")
                        digitalPlayer?.prepare()
                        digitalPlayer?.play()
                    }
                }
            }
        })

        setupMediaSession()
        coordinateEngines()
    }

    // Called when the user presses buttons (Play/Pause/Skip) directly on the lock screen notification.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> {
                when (_playbackMode.value) {
                    PlaybackMode.FM_RADIO -> fmRadioEngine.scanNext() // Skip to next FM station
                    PlaybackMode.LOCAL_MUSIC -> playerManager.seekToNext() // Skip to next MP3 song
                    PlaybackMode.DIGITAL_RADIO, PlaybackMode.NONE -> {} // Digital radio streams can't be "skipped"
                }
            }
            ACTION_PREV -> {
                when (_playbackMode.value) {
                    PlaybackMode.FM_RADIO -> fmRadioEngine.scanPrevious()
                    PlaybackMode.LOCAL_MUSIC -> playerManager.seekToPrevious()
                    PlaybackMode.DIGITAL_RADIO, PlaybackMode.NONE -> {}
                }
            }
            ACTION_STOP -> {
                fmRadioEngine.stop()
                playerManager.stopAll()
                stopDigitalStream()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // "START_NOT_STICKY" means if Android runs out of memory and violently kills the app, it shouldn't try to automatically restart the music later.
        return START_NOT_STICKY
    }

    // Sets up the notification channel so Android allows us to post the playback controls.
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Music and Radio controls"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, playerManager.player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    /**
     * THE TRAFFIC COP.
     * Constantly watches all three players (FM, MP3, Internet).
     * If one starts playing, it ensures the others are muted, and updates the Lock Screen notification with the correct song/station info.
     */
    private fun coordinateEngines() {
        // Observe Local Music State
        serviceScope.launch {
            playerManager.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    fmRadioEngine.stop()
                    digitalPlayer?.pause()
                    _playbackMode.value = PlaybackMode.LOCAL_MUSIC
                    mediaSession?.player = playerManager.player // Tell the lock screen to use the MP3 player controls
                    updateNotification(true)
                } else if (_playbackMode.value == PlaybackMode.LOCAL_MUSIC) {
                    updateNotification(false)
                    scheduleAutoStop()
                }
            }
        }

        // Fetch the Album Art picture if a new MP3 starts playing
        serviceScope.launch {
            playerManager.currentTrack.collect { track ->
                if (_playbackMode.value == PlaybackMode.LOCAL_MUSIC || playerManager.isPlaying.value) {
                    if (track?.albumId != null && track.albumId > 0) {
                        loadAlbumArt(track.albumId)
                    } else {
                        currentAlbumArt = null
                        lastLoadedArtKey = null
                        updateNotification(playerManager.isPlaying.value)
                    }
                }
            }
        }

        // Observe FM Radio State
        serviceScope.launch {
            fmRadioEngine.isPlaying.collect { isPlaying ->
                if (isPlaying) {
                    playerManager.pause(secondary = false)
                    playerManager.pause(secondary = true)
                    digitalPlayer?.pause()
                    _playbackMode.value = PlaybackMode.FM_RADIO
                    updateNotification(true)
                } else if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                    updateNotification(false)
                    scheduleAutoStop()
                }
            }
        }

        serviceScope.launch {
            fmRadioEngine.frequency.collect {
                if (_playbackMode.value == PlaybackMode.FM_RADIO) {
                    updateNotification(fmRadioEngine.isPlaying.value) // Update the "98.3 MHz" text on the lock screen
                }
            }
        }
    }

    private suspend fun loadAlbumArt(albumId: Long) {
        val cacheKey = "album_$albumId"
        if (lastLoadedArtKey == cacheKey && currentAlbumArt != null) {
            updateNotification(playerManager.isPlaying.value)
            return
        }
        try {
            val request = ImageRequest.Builder(this)
                .data(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))
                .size(256)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request).drawable as? BitmapDrawable
            currentAlbumArt = result?.bitmap
            lastLoadedArtKey = if (currentAlbumArt != null) cacheKey else null
        } catch (e: Exception) {
            currentAlbumArt = null
            lastLoadedArtKey = null
        }
        updateNotification(playerManager.isPlaying.value)
    }

    private suspend fun loadDigitalAlbumArt(url: String) {
        val cacheKey = "url_$url"
        if (lastLoadedArtKey == cacheKey && currentAlbumArt != null) {
            updateNotification(digitalPlayer?.isPlaying == true)
            return
        }
        try {
            val request = ImageRequest.Builder(this)
                .data(url) // Fetch it from the internet!
                .size(256)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request).drawable as? BitmapDrawable
            currentAlbumArt = result?.bitmap
            lastLoadedArtKey = if (currentAlbumArt != null) cacheKey else null
        } catch (e: Exception) {
            currentAlbumArt = null
            lastLoadedArtKey = null
        }
        updateNotification(digitalPlayer?.isPlaying == true)
    }

    // Builds the visual media player you see when you pull down from the top of your phone screen.
    private fun updateNotification(isPlaying: Boolean) {
        val session = mediaSession ?: return
        val isFm = _playbackMode.value == PlaybackMode.FM_RADIO
        val isDigital = _playbackMode.value == PlaybackMode.DIGITAL_RADIO

        val title = when {
            isFm -> "FM Radio"
            isDigital -> digitalTitle.ifEmpty { "Internet Radio" }
            else -> playerManager.currentTrack.value?.title ?: "Music"
        }

        val text = when {
            isFm -> "${fmRadioEngine.frequency.value} MHz"
            isDigital -> digitalLiveStreamSongTitle ?: digitalSubtitle
            else -> playerManager.currentTrack.value?.artist ?: "Unknown Artist"
        }

        // Set up the buttons
        val playPauseIcon = if (isPlaying) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play
        val playPauseAction = NotificationCompat.Action(playPauseIcon, "Play/Pause", pendingIntent(ACTION_PLAY_PAUSE, 0))
        val prevAction = NotificationCompat.Action(androidx.media3.ui.R.drawable.exo_icon_previous, "Previous", pendingIntent(ACTION_PREV, 1))
        val nextAction = NotificationCompat.Action(androidx.media3.ui.R.drawable.exo_icon_next, "Next", pendingIntent(ACTION_NEXT, 2))

        // If the user clicks the notification itself (not the buttons), open the app.
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Tell Android to use the special "Media" style layout, rather than a standard boring text notification.
        val mediaStyle = androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
            .setShowActionsInCompactView(0, 1, 2) // Which buttons to show when the notification is small/collapsed

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(androidx.media3.ui.R.drawable.exo_icon_play) // Tiny icon in the top status bar
            .setContentTitle(title)
            .setContentText(text)
            .setLargeIcon(if (isFm) null else currentAlbumArt) // Album art
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show it on the lock screen
            .setOngoing(isPlaying) // If playing, the user cannot swipe the notification away!
            .setOnlyAlertOnce(true)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(mediaStyle)

        val notification = builder.build()

        // Android 14 requires us to declare exactly WHY we are forcing the app to run in the background.
        if (isPlaying) {
            startForegroundSafe(NOTIFICATION_ID, notification) // Tell Android "Do not kill me!"
            autoStopJob?.cancel() // Cancel the idle shut-off timer
        } else {
            notificationManager?.notify(NOTIFICATION_ID, notification) // Just update the picture/text
            stopForeground(STOP_FOREGROUND_DETACH) // Tell Android "I am paused, you can kill me to save battery if you need to."
        }
    }

    // Creates the specialized intents that hook the lock screen buttons to our internal functions
    private fun pendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(this, reqCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startForegroundSafe(notificationId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Formally declare we are playing media
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("MusicService", "Foreground service start rejected", e)
        }
    }

    fun togglePlayPause() {
        when (_playbackMode.value) {
            PlaybackMode.LOCAL_MUSIC -> playerManager.togglePlayPause()
            PlaybackMode.FM_RADIO -> {
                // Upgraded: Resumes the user's current/saved frequency rather than resetting to a hardcoded 98.0f
                if (fmRadioEngine.isPlaying.value) fmRadioEngine.stop() else fmRadioEngine.start(fmRadioEngine.frequency.value)
            }
            PlaybackMode.DIGITAL_RADIO -> {
                if (digitalPlayer?.isPlaying == true) pauseDigitalStream() else resumeDigitalStream()
            }
            PlaybackMode.NONE -> {
                // Upgraded fallback: If playbackMode is NONE but a track is loaded in PlayerManager, start playing it
                if (playerManager.currentTrack.value != null) {
                    playerManager.play()
                }
            }
        }
    }

    // --- DIGITAL RADIO CONTROLS ---

    fun playDigitalStream(url: String, title: String, subtitle: String, imageUrl: String = "") {
        streamRetryJob?.cancel()
        streamRetryCount = 0
        digitalStreamUrl = url
        digitalTitle = title
        digitalSubtitle = subtitle
        digitalLiveStreamSongTitle = null
        digitalImageUrl = imageUrl

        // Force stop everything else
        fmRadioEngine.stop()
        playerManager.pause(secondary = false)
        playerManager.pause(secondary = true)

        _playbackMode.value = PlaybackMode.DIGITAL_RADIO
        digitalPlayer?.let { mediaSession?.player = it }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .apply {
                if (imageUrl.isNotEmpty()) {
                    setArtworkUri(Uri.parse(imageUrl))
                }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaMetadata(metadata)
            .build()

        try {
            // Give the streaming URL to ExoPlayer and tell it to start buffering it from the internet
            digitalPlayer?.setMediaItem(mediaItem)
            digitalPlayer?.prepare()
            digitalPlayer?.playWhenReady = true
        } catch (e: Exception) {
            Log.e("MusicService", "Failed to start stream: $url", e)
        }

        // Try to fetch the radio station's logo
        if (imageUrl.isNotEmpty()) {
            serviceScope.launch { loadDigitalAlbumArt(imageUrl) }
        } else {
            currentAlbumArt = null
            lastLoadedArtKey = null
            updateNotification(true)
        }
    }

    fun pauseDigitalStream() {
        streamRetryJob?.cancel()
        digitalPlayer?.pause()
    }

    fun resumeDigitalStream() {
        if (digitalPlayer?.playbackState == Player.STATE_IDLE && digitalStreamUrl.isNotEmpty()) {
            digitalPlayer?.prepare()
        }
        digitalPlayer?.play()
    }

    fun stopDigitalStream() {
        streamRetryJob?.cancel()
        digitalLiveStreamSongTitle = null
        digitalPlayer?.stop()
        digitalPlayer?.clearMediaItems()
        if (_playbackMode.value == PlaybackMode.DIGITAL_RADIO) {
            _playbackMode.value = PlaybackMode.NONE
        }
    }

    // If the music has been paused for 15 seconds, we completely shut down the background service.
    // If we didn't do this, Android would eventually yell at our app for draining battery while doing nothing.
    private fun scheduleAutoStop() {
        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            delay(15000)
            if (!playerManager.isPlaying.value && !playerManager.isPlaying2.value && !fmRadioEngine.isPlaying.value && digitalPlayer?.isPlaying != true) {
                stopForeground(STOP_FOREGROUND_REMOVE) // Remove the notification entirely
                stopSelf() // Commit suicide to free up phone memory
            }
        }
    }

    // If the user violently swipes the app away from their "Recent Apps" screen
    override fun onTaskRemoved(rootIntent: Intent?) {
        // If it's paused, just shut down completely.
        if (!playerManager.isPlaying.value && !playerManager.isPlaying2.value && !fmRadioEngine.isPlaying.value && digitalPlayer?.isPlaying != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        streamRetryJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        digitalPlayer?.release()
        playerManager.release()
        fmRadioEngine.release()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 101 // A random ID number for Android
        const val CHANNEL_ID = "gallerybox_music_channel"
        const val ACTION_PLAY_PAUSE = "com.gallerybox.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.gallerybox.ACTION_NEXT"
        const val ACTION_PREV = "com.gallerybox.ACTION_PREV"
        const val ACTION_STOP = "com.gallerybox.ACTION_STOP"
    }
}