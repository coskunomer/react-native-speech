package com.mhpdev.speech

import java.util.UUID
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.Context
import android.speech.tts.Voice
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReadableMap
import android.speech.tts.UtteranceProgressListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule
import android.util.Log

@ReactModule(name = RNSpeechModule.NAME)
class RNSpeechModule(reactContext: ReactApplicationContext) :
  NativeSpeechSpec(reactContext) {

  override fun getName(): String = NAME

  override fun getTypedExportedConstants(): MutableMap<String, Any> =
    mutableMapOf("maxInputLength" to maxInputLength)

  companion object {
    const val NAME = "RNSpeech"
    private const val TAG = "RNSpeech"

    private val defaultOptions: Map<String, Any> = mapOf(
      "rate"     to 0.5f,
      "pitch"    to 1.0f,
      "volume"   to 1.0f,
      "ducking"  to false,
      "language" to Locale.getDefault().toLanguageTag()
    )
  }

  // ── Constants ────────────────────────────────────────────────────────────

  private val maxInputLength       = TextToSpeech.getMaxSpeechInputLength()
  private val isSupportedPausing   = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
  private val mainHandler          = Handler(Looper.getMainLooper())

  // ── TTS engine ───────────────────────────────────────────────────────────

  private lateinit var synthesizer: TextToSpeech
  private var selectedEngine: String? = null
  private var cachedEngines: List<TextToSpeech.EngineInfo>? = null

  // ── Init state ───────────────────────────────────────────────────────────

  private var isInitialized  = false
  private var isInitializing = false
  private var listenerSet    = false
  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()

  // ── Options ──────────────────────────────────────────────────────────────

  private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()

  // ── Queue state ──────────────────────────────────────────────────────────

  private val queueLock        = Any()
  private val speechQueue      = mutableListOf<SpeechQueueItem>()
  private var currentQueueIndex = -1
  private var isPaused         = false
  private var isResuming       = false

  // ── Audio focus ──────────────────────────────────────────────────────────

  private val audioManager: AudioManager by lazy {
    reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }
  private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isDucking = false

  // ────────────────────────────────────────────────────────────────────────
  // Init
  // ────────────────────────────────────────────────────────────────────────

  init {
    initializeTTS()
  }

  private fun initializeTTS() {
    if (isInitializing) return
    isInitializing = true

    synthesizer = TextToSpeech(reactApplicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        Log.d(TAG, "TTS engine callback SUCCESS, verifying voices…")
        verifyTTSReady()
      } else {
        Log.e(TAG, "TTS engine init failed with status: $status")
        isInitialized  = false
        isInitializing = false
        rejectPendingOperations()
      }
    }, selectedEngine)
  }

  /**
   * Polls until the engine has voices and engines available, then attaches
   * the utterance listener and marks the module ready.
   *
   * Retries up to 20 times with escalating back-off (500 ms → 1 s → 2 s).
   */
  private fun verifyTTSReady(retryCount: Int = 0) {
    val maxRetries = 20
    val delay = when {
      retryCount == 0 -> 500L
      retryCount < 5  -> 1000L
      else            -> 2000L
    }

    mainHandler.postDelayed({
      try {
        val voices  = synthesizer.voices
        val engines = synthesizer.engines

        if (!voices.isNullOrEmpty() && !engines.isNullOrEmpty()) {
          Log.d(TAG, "TTS ready: ${voices.size} voices, ${engines.size} engines")
          cachedEngines = engines

          // Attach listener once (or re-attach after an engine switch)
          attachUtteranceListener()

          applyGlobalOptions(setLanguage = true)
          isInitialized  = true
          isInitializing = false
          processPendingOperations()

        } else if (retryCount < maxRetries) {
          Log.w(TAG, "TTS not ready (retry ${retryCount + 1}/$maxRetries)")
          verifyTTSReady(retryCount + 1)
        } else {
          Log.e(TAG, "TTS failed to become ready after $maxRetries retries")
          isInitialized  = false
          isInitializing = false
          rejectPendingOperations()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Exception during TTS verification (retry $retryCount)", e)
        if (retryCount < maxRetries) verifyTTSReady(retryCount + 1)
        else {
          isInitialized  = false
          isInitializing = false
          rejectPendingOperations()
        }
      }
    }, delay)
  }

  // ────────────────────────────────────────────────────────────────────────
  // Utterance listener
  //
  // THE KEY FIX: extracted into its own method so it can be re-attached
  // after any call that might tear it down (setLanguage on non-Google
  // engines, engine switches, resets). Never call setLanguage() without
  // immediately calling attachUtteranceListener() afterwards.
  // ────────────────────────────────────────────────────────────────────────

  private fun attachUtteranceListener() {
    synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

      override fun onStart(utteranceId: String) {
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.status = SpeechStatus.SPEAKING
            if (isResuming && item.position > 0) {
              emitOnResume(eventData(utteranceId))
              isResuming = false
            } else {
              emitOnStart(eventData(utteranceId))
            }
          }
        }
      }

      override fun onDone(utteranceId: String) {
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.status = SpeechStatus.COMPLETED
            deactivateDuckingSession()
            emitOnFinish(eventData(utteranceId))
            if (!isPaused) {
              currentQueueIndex++
              processNextQueueItem()
            }
          }
        }
      }

      override fun onError(utteranceId: String) {
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.status = SpeechStatus.ERROR
            deactivateDuckingSession()
            emitOnError(eventData(utteranceId))
            if (!isPaused) {
              currentQueueIndex++
              processNextQueueItem()
            }
          }
        }
      }

      override fun onStop(utteranceId: String, interrupted: Boolean) {
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            if (isPaused) {
              item.status = SpeechStatus.PAUSED
              emitOnPause(eventData(utteranceId))
            } else {
              item.status = SpeechStatus.COMPLETED
              emitOnStopped(eventData(utteranceId))
            }
          }
        }
      }

      override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.position = item.offset + start
            val data = Arguments.createMap().apply {
              putInt("id",       utteranceId.hashCode())
              putInt("length",   end - start)
              putInt("location", item.position)
            }
            emitOnProgress(data)
          }
        }
      }
    })
    listenerSet = true
  }

  // ────────────────────────────────────────────────────────────────────────
  // Options helpers
  // ────────────────────────────────────────────────────────────────────────

  /**
   * Apply globalOptions to the synthesizer.
   *
   * @param setLanguage  Pass true only during init / engine switch / reset.
   *                     Calling setLanguage() on non-Google engines tears down
   *                     the utterance listener internally, so we avoid it
   *                     during normal queue processing.
   */
  private fun applyGlobalOptions(setLanguage: Boolean = false) {
    if (setLanguage) {
      globalOptions["language"]?.let {
        synthesizer.setLanguage(Locale.forLanguageTag(it as String))
        // Re-attach immediately: setLanguage can orphan the listener on
        // Samsung / AOSP TTS engines.
        if (listenerSet) attachUtteranceListener()
      }
    }
    globalOptions["pitch"]?.let  { synthesizer.setPitch((it as? Number)?.toFloat() ?: 1.0f) }
    globalOptions["rate"]?.let   { synthesizer.setSpeechRate((it as? Number)?.toFloat() ?: 0.5f) }
    globalOptions["voice"]?.let  { voiceId ->
      synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
    }
  }

  /**
   * Build a Bundle for a single queue item. Applies rate/pitch/voice per
   * utterance via direct setters (these are safe on all engines), but
   * intentionally NEVER calls setLanguage() — that stays in applyGlobalOptions.
   */
  private fun buildParamsForItem(item: SpeechQueueItem): Bundle {
    val opts = globalOptions.toMutableMap().apply { putAll(item.options) }

    // Rate / pitch — safe to set per-utterance, do not reset the listener
    synthesizer.setSpeechRate((opts["rate"]  as? Number)?.toFloat() ?: 0.5f)
    synthesizer.setPitch(     (opts["pitch"] as? Number)?.toFloat() ?: 1.0f)

    // Voice
    (opts["voice"] as? String)?.let { voiceId ->
      synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
    }

    // Volume goes into the Bundle (the only param that actually belongs there)
    return Bundle().apply {
      putFloat(
        TextToSpeech.Engine.KEY_PARAM_VOLUME,
        (opts["volume"] as? Number)?.toFloat() ?: 1.0f
      )
    }
  }

  private fun getValidatedOptions(options: ReadableMap): Map<String, Any> {
    val validated = globalOptions.toMutableMap()
    if (options.hasKey("ducking"))  validated["ducking"]  = options.getBoolean("ducking")
    if (options.hasKey("voice"))    options.getString("voice")?.let { validated["voice"] = it }
    if (options.hasKey("language")) validated["language"] = options.getString("language") ?: Locale.getDefault().toLanguageTag()
    if (options.hasKey("pitch"))    validated["pitch"]    = options.getDouble("pitch").toFloat().coerceIn(0.1f, 2.0f)
    if (options.hasKey("volume"))   validated["volume"]   = options.getDouble("volume").toFloat().coerceIn(0f, 1.0f)
    if (options.hasKey("rate"))     validated["rate"]     = options.getDouble("rate").toFloat().coerceIn(0.1f, 2.0f)
    return validated
  }

  // ────────────────────────────────────────────────────────────────────────
  // Queue processing
  // ────────────────────────────────────────────────────────────────────────

  private fun processNextQueueItem() {
    synchronized(queueLock) {
      if (isPaused) return

      if (currentQueueIndex in 0 until speechQueue.size) {
        val item = speechQueue[currentQueueIndex]

        when (item.status) {
          SpeechStatus.PENDING, SpeechStatus.PAUSED -> {
            // Build params (sets rate/pitch/voice — NOT setLanguage)
            val params = buildParamsForItem(item)

            val textToSpeak: String
            if (item.status == SpeechStatus.PAUSED) {
              item.offset = item.position
              textToSpeak = item.text.substring(item.offset)
              isResuming  = true
            } else {
              item.offset = 0
              textToSpeak = item.text
            }

            val queueMode = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            synthesizer.speak(textToSpeak, queueMode, params, item.utteranceId)
          }
          else -> {
            currentQueueIndex++
            processNextQueueItem()
          }
        }
      } else {
        currentQueueIndex = -1
        // Restore global defaults (rate/pitch only — no setLanguage)
        applyGlobalOptions(setLanguage = false)
      }
    }
  }

  private fun pruneCompletedItems() {
    speechQueue.removeAll { it.status == SpeechStatus.COMPLETED || it.status == SpeechStatus.ERROR }
    currentQueueIndex = -1
  }

  private fun resetQueueState() {
    synchronized(queueLock) {
      speechQueue.clear()
      currentQueueIndex = -1
      isPaused   = false
      isResuming = false
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // Pending operations
  // ────────────────────────────────────────────────────────────────────────

  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    when {
      isInitialized -> {
        try { operation() }
        catch (e: Exception) { promise.reject("speech_error", e.message ?: "Unknown error") }
      }
      isInitializing -> pendingOperations.add(Pair(operation, promise))
      else -> {
        pendingOperations.add(Pair(operation, promise))
        if (::synthesizer.isInitialized) {
          try { synthesizer.stop(); synthesizer.shutdown() } catch (_: Exception) {}
        }
        initializeTTS()
      }
    }
  }

  private fun processPendingOperations() {
    val ops = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((op, promise) in ops) {
      try { op() }
      catch (e: Exception) { promise.reject("speech_error", e.message ?: "Unknown error") }
    }
  }

  private fun rejectPendingOperations() {
    val ops = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((_, promise) in ops) promise.reject("speech_error", "Failed to initialize TTS engine")
  }

  // ────────────────────────────────────────────────────────────────────────
  // Audio focus / ducking
  // ────────────────────────────────────────────────────────────────────────

  private fun activateDuckingSession() {
    if (!isDucking) return
    audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener {}

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
      val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
        .build()
      audioFocusRequest = req
      audioManager.requestAudioFocus(req)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        audioFocusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
      )
    }
  }

  private fun deactivateDuckingSession() {
    if (!isDucking || audioFocusChangeListener == null) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(audioFocusChangeListener)
    }
    audioFocusChangeListener = null
    audioFocusRequest        = null
  }

  // ────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────

  private fun eventData(utteranceId: String): ReadableMap =
    Arguments.createMap().apply { putInt("id", utteranceId.hashCode()) }

  private fun voiceItem(voice: Voice): ReadableMap =
    Arguments.createMap().apply {
      putString("quality",    if (voice.quality > Voice.QUALITY_NORMAL) "Enhanced" else "Default")
      putString("name",       voice.name)
      putString("identifier", voice.name)
      putString("language",   voice.locale.toLanguageTag())
    }

  private fun uniqueId(): String = UUID.randomUUID().toString()

  // ────────────────────────────────────────────────────────────────────────
  // Public API — NativeSpeechSpec overrides
  // ────────────────────────────────────────────────────────────────────────

  override fun initialize(options: ReadableMap) {
    val newOptions = globalOptions.toMutableMap()
    newOptions.putAll(getValidatedOptions(options))
    globalOptions = newOptions
    // Only pass setLanguage=true if the language actually changed
    applyGlobalOptions(setLanguage = true)
  }

  override fun reset() {
    globalOptions = defaultOptions.toMutableMap()
    applyGlobalOptions(setLanguage = true)
  }

  override fun speak(text: String?, promise: Promise) {
    if (text == null) { promise.reject("speech_error", "Text cannot be null"); return }
    if (text.length > maxInputLength) {
      promise.reject("speech_error", "Text exceeds max length of $maxInputLength")
      return
    }
    ensureInitialized(promise) {
      isDucking = globalOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()

      val item = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = uniqueId())
      synchronized(queueLock) {
        if (!synthesizer.isSpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun speakWithOptions(text: String?, options: ReadableMap, promise: Promise) {
    if (text == null) { promise.reject("speech_error", "Text cannot be null"); return }
    if (text.length > maxInputLength) {
      promise.reject("speech_error", "Text exceeds max length of $maxInputLength")
      return
    }
    ensureInitialized(promise) {
      val validatedOptions = getValidatedOptions(options)
      isDucking = validatedOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()

      val item = SpeechQueueItem(text = text, options = validatedOptions, utteranceId = uniqueId())
      synchronized(queueLock) {
        if (!synthesizer.isSpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      if (synthesizer.isSpeaking || isPaused) {
        synthesizer.stop()
        deactivateDuckingSession()
        synchronized(queueLock) {
          if (currentQueueIndex in speechQueue.indices) {
            emitOnStopped(eventData(speechQueue[currentQueueIndex].utteranceId))
          }
          resetQueueState()
        }
      }
      promise.resolve(null)
    }
  }

  override fun pause(promise: Promise) {
    ensureInitialized(promise) {
      if (!isSupportedPausing || isPaused || !synthesizer.isSpeaking || speechQueue.isEmpty()) {
        promise.resolve(false)
      } else {
        isPaused = true
        synthesizer.stop()
        deactivateDuckingSession()
        promise.resolve(true)
      }
    }
  }

  override fun resume(promise: Promise) {
    ensureInitialized(promise) {
      if (!isSupportedPausing || !isPaused || speechQueue.isEmpty() || currentQueueIndex < 0) {
        promise.resolve(false)
        return@ensureInitialized
      }
      synchronized(queueLock) {
        val pausedIdx = speechQueue.indexOfFirst { it.status == SpeechStatus.PAUSED }
        if (pausedIdx >= 0) {
          currentQueueIndex = pausedIdx
          isPaused = false
          activateDuckingSession()
          processNextQueueItem()
          promise.resolve(true)
        } else {
          isPaused = false
          promise.resolve(false)
        }
      }
    }
  }

  override fun isSpeaking(promise: Promise) {
    ensureInitialized(promise) {
      promise.resolve(synthesizer.isSpeaking || isPaused)
    }
  }

  override fun getAvailableVoices(language: String?, promise: Promise) {
    ensureInitialized(promise) {
      val arr    = Arguments.createArray()
      val voices = synthesizer.voices
      if (voices == null) { promise.resolve(arr); return@ensureInitialized }

      if (language != null) {
        val lang = language.lowercase()
        voices.forEach { v ->
          if (v.locale.toLanguageTag().lowercase().startsWith(lang)) arr.pushMap(voiceItem(v))
        }
      } else {
        voices.forEach { arr.pushMap(voiceItem(it)) }
      }
      promise.resolve(arr)
    }
  }

  override fun getEngines(promise: Promise) {
    ensureInitialized(promise) {
      val arr = Arguments.createArray()
      cachedEngines?.forEach { engine ->
        arr.pushMap(Arguments.createMap().apply {
          putString("name",      engine.name)
          putString("label",     engine.label)
          putBoolean("isDefault", engine.name == synthesizer.defaultEngine)
        })
      }
      promise.resolve(arr)
    }
  }

  override fun getActiveEngine(promise: Promise) {
    ensureInitialized(promise) {
      promise.resolve(selectedEngine ?: synthesizer.defaultEngine ?: "")
    }
  }

  override fun setEngine(engineName: String, promise: Promise) {
    if (cachedEngines?.any { it.name == engineName } == false) {
      promise.reject("engine_error", "Engine '$engineName' is not available")
      return
    }
    if (isInitialized) {
      val active = selectedEngine ?: synthesizer.defaultEngine
      if (active == engineName) { promise.resolve(null); return }
    }

    if (::synthesizer.isInitialized) {
      try { synthesizer.stop(); synthesizer.shutdown() }
      catch (e: Exception) { Log.w(TAG, "Error shutting down TTS before engine switch", e) }
    }

    selectedEngine = engineName
    isInitialized  = false
    isInitializing = false
    listenerSet    = false
    resetQueueState()
    initializeTTS()
    promise.resolve(null)
  }

  override fun openVoiceDataInstaller(promise: Promise) {
    try {
      val activity = currentActivity
      if (activity == null) {
        promise.reject("ACTIVITY_UNAVAILABLE", "Current activity not available")
        return
      }
      val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
      if (intent.resolveActivity(activity.packageManager) != null) {
        activity.startActivity(intent)
        promise.resolve(null)
      } else {
        promise.reject("UNSUPPORTED_OPERATION", "No activity found to handle TTS voice data installation")
      }
    } catch (e: Exception) {
      promise.reject("INSTALLER_ERROR", "Unexpected error opening TTS voice installer", e)
    }
  }

  override fun invalidate() {
    super.invalidate()
    mainHandler.removeCallbacksAndMessages(null)
    if (::synthesizer.isInitialized) {
      synthesizer.stop()
      synthesizer.shutdown()
      resetQueueState()
    }
    isInitialized  = false
    isInitializing = false
    listenerSet    = false
  }
}