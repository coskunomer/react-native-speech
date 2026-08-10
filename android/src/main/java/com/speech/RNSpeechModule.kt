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
class RNSpeechModule(reactContext: ReactApplicationContext) : NativeSpeechSpec(reactContext) {

  override fun getName(): String = NAME

  override fun getTypedExportedConstants(): MutableMap<String, Any> =
    mutableMapOf("maxInputLength" to maxInputLength)

  companion object {
    const val NAME = "RNSpeech"
    private const val TAG = "RNSpeech"

    private const val WATCHDOG_TIMEOUT_MS = 5000L
    private const val ENGINE_REINIT_DELAY_MS = 300L
    private const val MAX_ENGINE_FAILURES = 2
    private const val MAX_TOTAL_FAILURES = 4
    private const val PROBE_UTTERANCE_PREFIX = "connectivity-probe-"

    private const val MAX_LANGUAGE_RETRIES = 6
    private const val LANGUAGE_RETRY_BASE_DELAY_MS = 150L
    private const val LANGUAGE_RETRY_MAX_DELAY_MS = 2000L

    private fun languageRetryDelayMs(attempt: Int): Long {
      val exp = LANGUAGE_RETRY_BASE_DELAY_MS * (1L shl attempt)
      return exp.coerceAtMost(LANGUAGE_RETRY_MAX_DELAY_MS)
    }

    private const val MAX_ENGINE_SWITCH_RETRIES = 3
    private const val ENGINE_SWITCH_RETRY_DELAY_MS = 800L

    private val defaultOptions: Map<String, Any> = mapOf(
      "rate" to 0.5f,
      "pitch" to 1.0f,
      "volume" to 1.0f,
      "ducking" to false,
    )
  }

  // ── Constants ────────────────────────────────────────────────────────────
  private val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
  private val isSupportedPausing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
  private val mainHandler = Handler(Looper.getMainLooper())

  // ── TTS engine ───────────────────────────────────────────────────────────
  private lateinit var synthesizer: TextToSpeech
  private var selectedEngine: String? = null
  private var cachedEngines: List<TextToSpeech.EngineInfo>? = null

  // FIX: what setLanguage/setVoice were last successfully applied to the
  // current synthesizer instance. buildParamsForItem()/applyGlobalOptions()
  // skip re-issuing those Binder calls when nothing changed, which cuts
  // down how often we're exposed to the slow-Binder-call race at all. Reset
  // to null on every initializeTTS() since a fresh TextToSpeech instance
  // has no applied state.
  @Volatile private var appliedLanguage: String? = null
  @Volatile private var appliedVoiceId: String? = null

  // ── Init state ───────────────────────────────────────────────────────────
  private var isInitialized = false
  private var isInitializing = false
  private var listenerSet = false
  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()

  // ── Options ──────────────────────────────────────────────────────────────
  private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()

  // ── Queue state ──────────────────────────────────────────────────────────
  // FIX (root cause of the ANRs): this lock must ONLY ever guard the fields
  // below (speechQueue / currentQueueIndex / isPaused / isResuming). No code
  // path may call into `synthesizer` (setLanguage, setVoice, speak,
  // isSpeaking, ...) while holding it — those are synchronous Binder calls
  // to the TTS service and can take an unbounded amount of time on a
  // half-connected or slow OEM engine. Play Console's ANR traces show the
  // main thread blocked on this exact lock while a Binder callback thread
  // held it inside buildParamsForItem() -> setLanguage()/setVoice().
  private val queueLock = Any()
  private val speechQueue = mutableListOf<SpeechQueueItem>()
  private var currentQueueIndex = -1
  private var isPaused = false
  private var isResuming = false

  private var pendingQueueResume = false

  // ── Engine health tracking ───────────────────────────────────────────────
  private val engineFailureCounts = mutableMapOf<String, Int>()
  private var totalConsecutiveFailures = 0
  private var engineDead = false

  private val languageRetryCounts = mutableMapOf<String, Int>()
  private var engineSwitchRetryCount = 0

  // ── Audio focus ──────────────────────────────────────────────────────────
  private val audioManager: AudioManager by lazy {
    reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }
  private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isDucking = false

  // FIX: read cross-thread by armSpeakWatchdog()/probeEngineConnectivity()
  // (main thread) and written from initializeTTS() (which can run on a
  // Binder callback thread via TextToSpeech's own onInit delivery on some
  // OEM builds). @Volatile makes writes visible without needing a lock for
  // a single int comparison.
  @Volatile private var initGeneration = 0

  @Volatile private var pendingProbeUtteranceId: String? = null
  private var pendingReinitRunnable: Runnable? = null

  // ────────────────────────────────────────────────────────────────────────
  // Init
  // ────────────────────────────────────────────────────────────────────────
  init {
    initializeTTS()
  }

  private fun initializeTTS(preserveQueue: Boolean = false) {
    isInitializing = true

    if (::synthesizer.isInitialized) {
      try {
        synthesizer.stop()
        synthesizer.shutdown()
      } catch (e: Exception) {
        Log.w(TAG, "Error shutting down TTS engine", e)
      }
    }
    initGeneration++
    val myGen = initGeneration
    isInitialized = false
    listenerSet = false
    appliedLanguage = null
    appliedVoiceId = null

    if (preserveQueue) {
      pendingQueueResume = true
      synchronized(queueLock) { isPaused = false }
    } else {
      pendingQueueResume = false
      resetQueueState()
    }

    synthesizer = TextToSpeech(reactApplicationContext, { status ->
      if (status == TextToSpeech.SUCCESS) {
        Log.d(TAG, "TTS engine callback SUCCESS")
        onEngineConstructed(myGen)
      } else {
        Log.e(TAG, "TTS engine init failed with status: $status")
        isInitialized = false
        isInitializing = false
        rejectPendingOperations()
      }
    }, selectedEngine)
  }

  private fun onEngineConstructed(generation: Int) {
    cachedEngines = synthesizer.engines
    engineSwitchRetryCount = 0

    attachUtteranceListener()
    applyGlobalOptions(setLanguage = true)
    probeEngineConnectivity(generation)
  }

  private fun teardownAndReinitialize(preserveQueue: Boolean = false) {
    pendingReinitRunnable?.let { mainHandler.removeCallbacks(it) }
    val runnable = Runnable { initializeTTS(preserveQueue) }
    pendingReinitRunnable = runnable
    mainHandler.postDelayed(runnable, ENGINE_REINIT_DELAY_MS)
  }

  private fun probeEngineConnectivity(generation: Int) {
    val id = PROBE_UTTERANCE_PREFIX + uniqueId()
    pendingProbeUtteranceId = id

    val result = try {
      synthesizer.playSilentUtterance(1L, TextToSpeech.QUEUE_FLUSH, id)
    } catch (e: Exception) {
      Log.e(TAG, "Connectivity probe threw", e)
      TextToSpeech.ERROR
    }

    if (result == TextToSpeech.ERROR) {
      Log.e(TAG, "Connectivity probe rejected synchronously")
      pendingProbeUtteranceId = null
      handleInitProbeFailure("speak_rejected", generation)
      return
    }

    mainHandler.postDelayed({
      if (generation != initGeneration) return@postDelayed
      if (pendingProbeUtteranceId == id) {
        Log.e(
          TAG,
          "Connectivity probe watchdog: no onStart within ${WATCHDOG_TIMEOUT_MS}ms — " +
            "engine is half-connected, forcing rebuild"
        )
        pendingProbeUtteranceId = null
        handleInitProbeFailure("watchdog_timeout", generation)
      }
    }, WATCHDOG_TIMEOUT_MS)
  }

  private fun completeInitialization(generation: Int) {
    if (generation != initGeneration) return
    Log.d(TAG, "Connectivity probe succeeded — engine is live")
    try {
      applyGlobalOptions(setLanguage = true)
    } catch (e: Exception) {
      Log.w(TAG, "completeInitialization(): failed to (re)apply options", e)
    }
    isInitialized = true
    isInitializing = false
    engineFailureCounts.remove(selectedEngine ?: synthesizer.defaultEngine ?: "unknown")
    totalConsecutiveFailures = 0
    processPendingOperations()
    if (pendingQueueResume) {
      pendingQueueResume = false
      processNextQueueItem()
    }
  }

  private fun handleInitProbeFailure(reason: String, generation: Int) {
    if (generation != initGeneration) return

    val engineName = selectedEngine ?: (if (::synthesizer.isInitialized) synthesizer.defaultEngine else null) ?: "unknown"
    val engineFailures = (engineFailureCounts[engineName] ?: 0) + 1
    engineFailureCounts[engineName] = engineFailures
    totalConsecutiveFailures++

    Log.e(TAG, "Init probe on '$engineName' failed ($reason). consecutiveForEngine=$engineFailures totalConsecutive=$totalConsecutiveFailures")

    if (totalConsecutiveFailures >= MAX_TOTAL_FAILURES) {
      emitOnError(errorEventData(uniqueId(), reason = "engine_dead", engine = engineName, trigger = reason))
      engineDead = true
      isInitializing = false
      rejectPendingOperations()
      resetQueueState()
      return
    }

    if (engineFailures < MAX_ENGINE_FAILURES) {
      emitOnError(errorEventData(uniqueId(), reason = "engine_retry", engine = engineName, trigger = reason))
      teardownAndReinitialize(preserveQueue = true)
    } else {
      emitOnError(errorEventData(uniqueId(), reason = "engine_unavailable", engine = engineName, trigger = reason))
      engineFailureCounts.remove(engineName)
      selectedEngine = null
      teardownAndReinitialize(preserveQueue = true)
    }
  }

  // ────────────────────────────────────────────────────────────────────────
  // Utterance listener
  // ────────────────────────────────────────────────────────────────────────
  private fun attachUtteranceListener() {
    synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

      override fun onStart(utteranceId: String) {
        if (utteranceId == pendingProbeUtteranceId) {
          pendingProbeUtteranceId = null
          completeInitialization(initGeneration)
          return
        }
        val engineName = selectedEngine ?: synthesizer.defaultEngine
        if (engineName != null) engineFailureCounts.remove(engineName)
        totalConsecutiveFailures = 0
        languageRetryCounts.remove(utteranceId)

        // FIX: only queue-state bookkeeping happens under the lock. The
        // event emission itself happens after release — it isn't a
        // Binder-to-TTS call, but there's no reason to hold the lock for it
        // either, and keeping the pattern consistent everywhere makes the
        // invariant ("nothing but bookkeeping under queueLock") easy to audit.
        var didResume = false
        var didStart = false
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.status = SpeechStatus.SPEAKING
            if (isResuming && item.position > 0) {
              didResume = true
              isResuming = false
            } else {
              didStart = true
            }
          }
        }
        if (didResume) emitOnResume(eventData(utteranceId))
        else if (didStart) emitOnStart(eventData(utteranceId))
      }

      override fun onDone(utteranceId: String) {
        languageRetryCounts.remove(utteranceId)
        var found = false
        var shouldContinue = false
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            found = true
            item.status = SpeechStatus.COMPLETED
            if (!isPaused) {
              currentQueueIndex++
              shouldContinue = true
            }
          }
        }
        if (found) {
          deactivateDuckingSession()
          emitOnFinish(eventData(utteranceId))
        }
        // FIX (ANR root cause): processNextQueueItem() is called AFTER the
        // lock is released. It goes on to call buildParamsForItem(), which
        // performs synchronous TextToSpeech Binder calls (setLanguage /
        // setVoice). Calling it from inside synchronized(queueLock) — as
        // this used to do — meant a Binder callback thread could hold
        // queueLock for as long as the TTS service took to respond, and
        // the main thread's watchdog then hung waiting on the same lock.
        if (shouldContinue) processNextQueueItem()
      }

      override fun onError(utteranceId: String) {
        if (utteranceId == pendingProbeUtteranceId) {
          pendingProbeUtteranceId = null
          handleInitProbeFailure("speak_rejected", initGeneration)
          return
        }
        languageRetryCounts.remove(utteranceId)
        var found = false
        var shouldContinue = false
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            found = true
            item.status = SpeechStatus.ERROR
            if (!isPaused) {
              currentQueueIndex++
              shouldContinue = true
            }
          }
        }
        if (found) {
          deactivateDuckingSession()
          emitOnError(eventData(utteranceId))
        }
        // Same fix as onDone(): call outside the lock.
        if (shouldContinue) processNextQueueItem()
      }

      override fun onStop(utteranceId: String, interrupted: Boolean) {
        var didPause = false
        var didStop = false
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            if (isPaused) {
              item.status = SpeechStatus.PAUSED
              didPause = true
            } else {
              item.status = SpeechStatus.COMPLETED
              didStop = true
            }
          }
        }
        if (didPause) emitOnPause(eventData(utteranceId))
        if (didStop) emitOnStopped(eventData(utteranceId))
      }

      override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
        var data: WritableMap? = null
        synchronized(queueLock) {
          speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
            item.position = item.offset + start
            data = Arguments.createMap().apply {
              putInt("id", utteranceId.hashCode())
              putInt("length", end - start)
              putInt("location", item.position)
            }
          }
        }
        data?.let { emitOnProgress(it) }
      }
    })
    listenerSet = true
  }

  // ────────────────────────────────────────────────────────────────────────
  // Options helpers
  // ────────────────────────────────────────────────────────────────────────
  private fun applyGlobalOptions(setLanguage: Boolean = false) {
    if (setLanguage) {
      (globalOptions["language"] as? String)?.let { langTag ->
        if (langTag != appliedLanguage) {
          try {
            val result = synthesizer.setLanguage(Locale.forLanguageTag(langTag))
            if (result < TextToSpeech.LANG_AVAILABLE) {
              Log.w(TAG, "applyGlobalOptions(): setLanguage('$langTag') -> result=$result (not ready)")
            } else {
              appliedLanguage = langTag
            }
          } catch (e: Exception) {
            Log.w(TAG, "applyGlobalOptions(): setLanguage('$langTag') threw, continuing with other options", e)
          }
          // Re-attach regardless of outcome above — setLanguage can orphan
          // the listener on Samsung / AOSP TTS engines even when it doesn't
          // throw.
          if (listenerSet) attachUtteranceListener()
        }
      }
    }
    try {
      globalOptions["pitch"]?.let { synthesizer.setPitch((it as? Number)?.toFloat() ?: 1.0f) }
    } catch (e: Exception) {
      Log.w(TAG, "applyGlobalOptions(): setPitch failed", e)
    }
    try {
      globalOptions["rate"]?.let { synthesizer.setSpeechRate((it as? Number)?.toFloat() ?: 0.5f) }
    } catch (e: Exception) {
      Log.w(TAG, "applyGlobalOptions(): setSpeechRate failed", e)
    }
    try {
      (globalOptions["voice"] as? String)?.let { voiceId ->
        if (voiceId != appliedVoiceId) {
          synthesizer.voices?.find { it.name == voiceId }?.let {
            synthesizer.voice = it
            appliedVoiceId = voiceId
          }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "applyGlobalOptions(): setting voice failed", e)
    }
  }

  private data class BuiltSpeechParams(val bundle: Bundle, val languageResult: Int)

  /**
   * Builds per-utterance TTS params. Called by processNextQueueItem()
   * OUTSIDE queueLock (see comment there) — this function performs real
   * TextToSpeech Binder calls (setLanguage / setVoice) and must never run
   * while any lock the main thread also needs is held.
   *
   * FIX: skips setLanguage()/setVoice() entirely when the requested value
   * matches what's already applied on the synthesizer (appliedLanguage /
   * appliedVoiceId), instead of reissuing the Binder call for every single
   * utterance regardless of whether anything changed.
   */
  private fun buildParamsForItem(item: SpeechQueueItem): BuiltSpeechParams {
    val opts = globalOptions.toMutableMap().apply { putAll(item.options) }

    var languageResult = TextToSpeech.LANG_AVAILABLE
    (opts["language"] as? String)?.let { langTag ->
      if (langTag == appliedLanguage) {
        languageResult = TextToSpeech.LANG_AVAILABLE
      } else {
        languageResult = try {
          synthesizer.setLanguage(Locale.forLanguageTag(langTag))
        } catch (e: Exception) {
          Log.w(TAG, "buildParamsForItem(): setLanguage('$langTag') threw", e)
          TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (languageResult >= TextToSpeech.LANG_AVAILABLE) {
          appliedLanguage = langTag
        } else {
          Log.w(TAG, "buildParamsForItem(): setLanguage('$langTag') -> result=$languageResult (not ready)")
        }
        if (listenerSet) attachUtteranceListener()
      }
    }

    synthesizer.setSpeechRate((opts["rate"] as? Number)?.toFloat() ?: 0.5f)
    synthesizer.setPitch((opts["pitch"] as? Number)?.toFloat() ?: 1.0f)

    (opts["voice"] as? String)?.let { voiceId ->
      if (voiceId != appliedVoiceId) {
        synthesizer.voices?.find { it.name == voiceId }?.let {
          synthesizer.voice = it
          appliedVoiceId = voiceId
        }
      }
    }

    val bundle = Bundle().apply {
      putFloat(
        TextToSpeech.Engine.KEY_PARAM_VOLUME,
        (opts["volume"] as? Number)?.toFloat() ?: 1.0f
      )
    }
    return BuiltSpeechParams(bundle, languageResult)
  }

  private fun getValidatedOptions(options: ReadableMap): Map<String, Any> {
    val validated = globalOptions.toMutableMap()
    if (options.hasKey("ducking")) validated["ducking"] = options.getBoolean("ducking")
    if (options.hasKey("voice")) options.getString("voice")?.let { validated["voice"] = it }
    if (options.hasKey("language")) validated["language"] =
      options.getString("language") ?: Locale.getDefault().toLanguageTag()
    if (options.hasKey("pitch")) validated["pitch"] =
      options.getDouble("pitch").toFloat().coerceIn(0.1f, 2.0f)
    if (options.hasKey("volume")) validated["volume"] =
      options.getDouble("volume").toFloat().coerceIn(0f, 1.0f)
    if (options.hasKey("rate")) validated["rate"] =
      options.getDouble("rate").toFloat().coerceIn(0.1f, 2.0f)
    return validated
  }

  // ────────────────────────────────────────────────────────────────────────
  // Queue processing
  // ────────────────────────────────────────────────────────────────────────
  /**
   * FIX (ANR root cause): queueLock is held ONLY for the bookkeeping below
   * (picking the next item, computing its resume offset, advancing
   * currentQueueIndex). buildParamsForItem() and synthesizer.speak() — both
   * of which can perform synchronous Binder calls to the TTS service — run
   * strictly AFTER the synchronized block exits. Previously
   * buildParamsForItem() was called from inside synchronized(queueLock),
   * which is exactly what Play Console's traces show: the main thread
   * blocked acquiring queueLock, held by a Binder callback thread stuck
   * inside setLanguage()/setVoice().
   */
  private fun processNextQueueItem() {
    var itemToSpeak: SpeechQueueItem? = null
    var textToSpeak: String? = null
    var queueModeToUse = TextToSpeech.QUEUE_ADD
    var recurse = false
    var applyDefaults = false

    synchronized(queueLock) {
      if (isPaused) return

      if (currentQueueIndex in 0 until speechQueue.size) {
        val item = speechQueue[currentQueueIndex]
        when (item.status) {
          SpeechStatus.PENDING, SpeechStatus.PAUSED -> {
            val text: String
            if (item.status == SpeechStatus.PAUSED) {
              item.offset = item.position
              text = item.text.substring(item.offset)
              isResuming = true
            } else {
              item.offset = 0
              text = item.text
            }
            itemToSpeak = item
            textToSpeak = text
            queueModeToUse = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
          }
          else -> {
            currentQueueIndex++
            recurse = true
          }
        }
      } else {
        currentQueueIndex = -1
        applyDefaults = true
      }
    }

    if (recurse) {
      processNextQueueItem()
      return
    }

    if (applyDefaults) {
      applyGlobalOptions(setLanguage = false)
      return
    }

    val item = itemToSpeak ?: return
    val text = textToSpeak ?: return

    // Outside queueLock from here on.
    val built = buildParamsForItem(item)

    if (built.languageResult < TextToSpeech.LANG_AVAILABLE) {
      val retries = languageRetryCounts.getOrDefault(item.utteranceId, 0)
      if (retries < MAX_LANGUAGE_RETRIES) {
        languageRetryCounts[item.utteranceId] = retries + 1
        val delayMs = languageRetryDelayMs(retries)
        Log.w(
          TAG,
          "Language not ready for utterance ${item.utteranceId} (result=${built.languageResult}) — " +
            "retrying in ${delayMs}ms (attempt ${retries + 1}/$MAX_LANGUAGE_RETRIES)"
        )
        mainHandler.postDelayed({ processNextQueueItem() }, delayMs)
        return
      } else {
        Log.w(
          TAG,
          "Language still not ready for utterance ${item.utteranceId} after $MAX_LANGUAGE_RETRIES " +
            "retries — proceeding to speak anyway with best-effort language state"
        )
        languageRetryCounts.remove(item.utteranceId)
      }
    } else {
      languageRetryCounts.remove(item.utteranceId)
    }

    val generationAtCallTime = initGeneration

    Log.d(TAG, "engine=${synthesizer.defaultEngine}")
    Log.d(TAG, "voice=${synthesizer.voice?.name}")
    Log.d(TAG, "language=${synthesizer.language}")
    Log.d(TAG, "isSpeaking=${synthesizer.isSpeaking}")

    val result = synthesizer.speak(
      text,
      queueModeToUse,
      built.bundle,
      item.utteranceId
    )

    Log.d(TAG, "speak() returned $result")

    if (result == TextToSpeech.ERROR) {
      Log.e(TAG, "speak() rejected synchronously by engine")
      handleEngineFailure(item, "speak_rejected")
      return
    }
    armSpeakWatchdog(item.utteranceId, generationAtCallTime)
  }

  private fun armSpeakWatchdog(utteranceId: String, generation: Int) {
    mainHandler.postDelayed({
      if (generation != initGeneration) return@postDelayed

      // FIX: this read is now the ONLY thing armSpeakWatchdog does under
      // queueLock — a plain field lookup, never blocked behind a Binder
      // call, since nothing in the codebase holds queueLock across a
      // synthesizer call anymore.
      val stuckItem = synchronized(queueLock) {
        speechQueue.find { it.utteranceId == utteranceId && it.status == SpeechStatus.PENDING }
      }

      if (stuckItem != null) {
        Log.e(
          TAG,
          "Watchdog: no onStart for utterance $utteranceId within ${WATCHDOG_TIMEOUT_MS}ms — " +
            "engine appears stuck (half-connected after switch?), forcing rebuild"
        )
        handleEngineFailure(stuckItem, "watchdog_timeout")
      }
    }, WATCHDOG_TIMEOUT_MS)
  }

  private fun handleEngineFailure(item: SpeechQueueItem, reason: String) {
    languageRetryCounts.remove(item.utteranceId)
    val engineName = selectedEngine ?: (if (::synthesizer.isInitialized) synthesizer.defaultEngine else null) ?: "unknown"
    val engineFailures = (engineFailureCounts[engineName] ?: 0) + 1
    engineFailureCounts[engineName] = engineFailures
    totalConsecutiveFailures++

    Log.e(
      TAG,
      "Engine '$engineName' failed ($reason). " +
        "consecutiveForEngine=$engineFailures totalConsecutive=$totalConsecutiveFailures"
    )

    if (totalConsecutiveFailures >= MAX_TOTAL_FAILURES) {
      synchronized(queueLock) { item.status = SpeechStatus.ERROR }
      deactivateDuckingSession()
      emitOnError(errorEventData(item.utteranceId, reason = "engine_dead", engine = engineName, trigger = reason))
      engineDead = true
      rejectPendingOperations()
      resetQueueState()
      return
    }

    if (engineFailures < MAX_ENGINE_FAILURES) {
      emitOnError(errorEventData(item.utteranceId, reason = "engine_retry", engine = engineName, trigger = reason))
      synchronized(queueLock) { item.status = SpeechStatus.PENDING }
      teardownAndReinitialize(preserveQueue = true)
    } else {
      synchronized(queueLock) {
        item.status = SpeechStatus.ERROR
        if (!isPaused) currentQueueIndex++
      }
      deactivateDuckingSession()
      emitOnError(errorEventData(item.utteranceId, reason = "engine_unavailable", engine = engineName, trigger = reason))
      engineFailureCounts.remove(engineName)
      selectedEngine = null
      teardownAndReinitialize(preserveQueue = true)
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
      isPaused = false
      isResuming = false
    }
    languageRetryCounts.clear()
  }

  // ────────────────────────────────────────────────────────────────────────
  // Pending operations
  // ────────────────────────────────────────────────────────────────────────
  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    if (engineDead) {
      promise.reject(
        "speech_error",
        "TTS engine is unavailable after repeated failures; call reset() or setEngine() to retry"
      )
      return
    }
    when {
      isInitialized -> {
        try {
          operation()
        } catch (e: Exception) {
          promise.reject("speech_error", e.message ?: "Unknown error")
        }
      }
      isInitializing -> pendingOperations.add(Pair(operation, promise))
      else -> {
        pendingOperations.add(Pair(operation, promise))
        teardownAndReinitialize()
      }
    }
  }

  private fun processPendingOperations() {
    val ops = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((op, promise) in ops) {
      try {
        op()
      } catch (e: Exception) {
        promise.reject("speech_error", e.message ?: "Unknown error")
      }
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
    audioFocusRequest = null
  }

  // ────────────────────────────────────────────────────────────────────────
  // Helpers
  // ────────────────────────────────────────────────────────────────────────
  private fun eventData(utteranceId: String): ReadableMap =
    Arguments.createMap().apply { putInt("id", utteranceId.hashCode()) }

  private fun errorEventData(
    utteranceId: String,
    reason: String,
    engine: String? = null,
    requestedEngine: String? = null,
    trigger: String? = null,
  ): ReadableMap =
    Arguments.createMap().apply {
      putInt("id", utteranceId.hashCode())
      putString("reason", reason)
      engine?.let { putString("engine", it) }
      requestedEngine?.let { putString("requestedEngine", it) }
      trigger?.let { putString("trigger", it) }
    }

  private fun voiceItem(voice: Voice): ReadableMap =
    Arguments.createMap().apply {
      putString("quality", if (voice.quality > Voice.QUALITY_NORMAL) "Enhanced" else "Default")
      putString("name", voice.name)
      putString("identifier", voice.name)
      putString("language", voice.locale.toLanguageTag())
    }

  private fun uniqueId(): String = UUID.randomUUID().toString()

  // ────────────────────────────────────────────────────────────────────────
  // Public API — NativeSpeechSpec overrides
  // ────────────────────────────────────────────────────────────────────────
  override fun initialize(options: ReadableMap) {
    val newOptions = globalOptions.toMutableMap()
    newOptions.putAll(getValidatedOptions(options))
    globalOptions = newOptions

    if (isInitialized && ::synthesizer.isInitialized) {
      try {
        applyGlobalOptions(setLanguage = true)
      } catch (e: Exception) {
        Log.w(TAG, "initialize(): engine not actually ready, options will apply once init completes", e)
      }
    }
  }

  override fun reset() {
    globalOptions = defaultOptions.toMutableMap()
    if (isInitialized && ::synthesizer.isInitialized) {
      try {
        applyGlobalOptions(setLanguage = true)
      } catch (e: Exception) {
        Log.w(TAG, "reset(): engine not actually ready, options will apply on next init", e)
      }
    }
  }

  override fun speak(text: String?, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null"); return
    }
    if (text.length > maxInputLength) {
      promise.reject("speech_error", "Text exceeds max length of $maxInputLength")
      return
    }
    ensureInitialized(promise) {
      isDucking = globalOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()
      val item = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = uniqueId())
      // FIX: synthesizer.isSpeaking is a call into the TTS service — read
      // it BEFORE taking queueLock, never inside the synchronized block.
      val currentlySpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      synchronized(queueLock) {
        if (!currentlySpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!currentlySpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
        }
      }
      processNextQueueItem()
      promise.resolve(null)
    }
  }

  override fun speakWithOptions(text: String?, options: ReadableMap, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null"); return
    }
    if (text.length > maxInputLength) {
      promise.reject("speech_error", "Text exceeds max length of $maxInputLength")
      return
    }
    ensureInitialized(promise) {
      val validatedOptions = getValidatedOptions(options)
      isDucking = validatedOptions["ducking"] as? Boolean ?: false
      activateDuckingSession()
      val item = SpeechQueueItem(text = text, options = validatedOptions, utteranceId = uniqueId())
      val currentlySpeaking = try { synthesizer.isSpeaking } catch (e: Exception) { false }
      synchronized(queueLock) {
        if (!currentlySpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!currentlySpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
        }
      }
      processNextQueueItem()
      promise.resolve(null)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      if (synthesizer.isSpeaking || isPaused) {
        synthesizer.stop()
        deactivateDuckingSession()
        var stoppedUtteranceId: String? = null
        synchronized(queueLock) {
          if (currentQueueIndex in speechQueue.indices) {
            stoppedUtteranceId = speechQueue[currentQueueIndex].utteranceId
          }
        }
        resetQueueState()
        stoppedUtteranceId?.let { emitOnStopped(eventData(it)) }
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
      var shouldProcess = false
      synchronized(queueLock) {
        val pausedIdx = speechQueue.indexOfFirst { it.status == SpeechStatus.PAUSED }
        if (pausedIdx >= 0) {
          currentQueueIndex = pausedIdx
          isPaused = false
          shouldProcess = true
        } else {
          isPaused = false
        }
      }
      if (shouldProcess) {
        activateDuckingSession()
        processNextQueueItem()
        promise.resolve(true)
      } else {
        promise.resolve(false)
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
      val arr = Arguments.createArray()
      val voices = synthesizer.voices
      if (voices == null) {
        promise.resolve(arr); return@ensureInitialized
      }

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
          putString("name", engine.name)
          putString("label", engine.label)
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
      if (active == engineName) {
        selectedEngine = engineName
        promise.resolve(null)
        return
      }
    }
    selectedEngine = engineName
    engineDead = false
    totalConsecutiveFailures = 0
    engineFailureCounts.clear()
    languageRetryCounts.clear()
    engineSwitchRetryCount = 0
    teardownAndReinitialize(preserveQueue = false)
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
    initGeneration++
    if (::synthesizer.isInitialized) {
      synthesizer.stop()
      synthesizer.shutdown()
      resetQueueState()
    }
    isInitialized = false
    isInitializing = false
    listenerSet = false
    engineFailureCounts.clear()
    totalConsecutiveFailures = 0
    engineDead = false
    languageRetryCounts.clear()
    engineSwitchRetryCount = 0
    appliedLanguage = null
    appliedVoiceId = null
  }
}