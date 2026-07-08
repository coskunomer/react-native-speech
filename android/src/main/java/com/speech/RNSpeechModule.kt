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

    // How long we wait for onStart() to fire after calling speak() before
    // we treat the engine connection as stuck and force a rebuild.
    private const val WATCHDOG_TIMEOUT_MS = 4000L

    // Small delay inserted between shutdown() of the old engine and
    // construction of the new TextToSpeech instance, to reduce the odds of
    // hitting the Android race where the new engine reports SUCCESS /
    // returns cached voices & engines before its binder connection is
    // actually wired up.
    private const val ENGINE_REINIT_DELAY_MS = 300L

    // How many consecutive failures we tolerate on the SAME named engine
    // before we stop hammering it and fall back to the system default.
    private const val MAX_ENGINE_FAILURES = 2

    // Hard backstop across engine switches. If we still can't get a single
    // successful onStart after this many total failures (even after having
    // fallen back to the default engine), we stop the automatic rebuild loop
    // entirely instead of silently retrying forever.
    private const val MAX_TOTAL_FAILURES = 4

    private const val PROBE_UTTERANCE_PREFIX = "connectivity-probe-"

    private val defaultOptions: Map<String, Any> = mapOf(
      "rate" to 0.5f,
      "pitch" to 1.0f,
      "volume" to 1.0f,
      "ducking" to false,
      "language" to Locale.getDefault().toLanguageTag()
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

  // ── Init state ───────────────────────────────────────────────────────────
  private var isInitialized = false
  private var isInitializing = false
  private var listenerSet = false
  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()

  // ── Options ──────────────────────────────────────────────────────────────
  private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()

  // ── Queue state ──────────────────────────────────────────────────────────
  private val queueLock = Any()
  private val speechQueue = mutableListOf<SpeechQueueItem>()
  private var currentQueueIndex = -1
  private var isPaused = false
  private var isResuming = false

  // If a teardown/reinit was triggered by an engine FAILURE (as opposed to a
  // manual stop() or setEngine()), we want to resume the existing queue once
  // the new engine instance is ready, instead of wiping it.
  private var pendingQueueResume = false

  // ── Engine health tracking ───────────────────────────────────────────────
  // FIX: previously, a failed speak() or a stuck watchdog would silently
  // drop the utterance and rebuild the SAME engine forever (fatal on engines
  // like Huawei's, which reject speak() synchronously and consistently), or
  // would rebuild but never inform JS that anything went wrong (the only
  // failure signal, onError, was only listened to for paragraph-reading
  // state on the JS side). This map + the "dead" flag give us a bounded,
  // observable retry policy instead of an infinite silent loop.
  private val engineFailureCounts = mutableMapOf<String, Int>()
  private var totalConsecutiveFailures = 0
  private var engineDead = false

  // ── Audio focus ──────────────────────────────────────────────────────────
  private val audioManager: AudioManager by lazy {
    reactApplicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }
  private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
  private var audioFocusRequest: AudioFocusRequest? = null
  private var isDucking = false

  // Bumped on every engine (re)construction. Any callback / watchdog that
  // captured an older generation number is stale and must be ignored.
  private var initGeneration = 0

  // Utterance id of an in-flight init-time connectivity probe, or null if
  // none outstanding. Only one is ever in flight at a time.
  private var pendingProbeUtteranceId: String? = null

  // ────────────────────────────────────────────────────────────────────────
  // Init
  // ────────────────────────────────────────────────────────────────────────
  init {
    initializeTTS()
  }

  private fun initializeTTS() {
    if (isInitializing) return
    isInitializing = true
    val myGen = ++initGeneration

    synthesizer = TextToSpeech(reactApplicationContext, { status ->
      if (myGen != initGeneration) return@TextToSpeech // stale callback, ignore
      if (status == TextToSpeech.SUCCESS) {
        Log.d(TAG, "TTS engine callback SUCCESS, verifying voices…")
        verifyTTSReady(generation = myGen)
      } else {
        Log.e(TAG, "TTS engine init failed with status: $status")
        isInitialized = false
        isInitializing = false
        rejectPendingOperations()
      }
    }, selectedEngine)
  }

  /**
   * Tears down the current engine (if any) and schedules a fresh
   * initializeTTS() after a short delay.
   *
   * The delay exists because immediately constructing a new TextToSpeech
   * right after shutdown() on the old one can hit an Android race: the new
   * instance's connection to the underlying ITextToSpeechService can be left
   * in limbo even though onInit(SUCCESS) fires and .voices / .engines return
   * real (PackageManager-backed, not binder-backed) data. Waiting a beat
   * before reconstructing reduces the odds of landing in that half-connected
   * state.
   *
   * @param preserveQueue FIX: when true (engine-failure recovery), the
   *   pending speech queue is kept intact and resumed automatically once the
   *   new engine reports ready. Previously this method ALWAYS wiped the
   *   queue via resetQueueState(), which meant any auto-recovery attempt
   *   silently threw away the utterance that was being spoken — the text
   *   was gone forever even when the rebuild succeeded.
   */
  private fun teardownAndReinitialize(preserveQueue: Boolean = false) {
    if (::synthesizer.isInitialized) {
      try {
        synthesizer.stop()
        synthesizer.shutdown()
      } catch (e: Exception) {
        Log.w(TAG, "Error shutting down TTS engine", e)
      }
    }
    initGeneration++ // invalidate any in-flight callbacks/watchdogs tied to the old engine
    isInitialized = false
    isInitializing = false
    listenerSet = false

    if (preserveQueue) {
      pendingQueueResume = true
      synchronized(queueLock) { isPaused = false }
    } else {
      pendingQueueResume = false
      resetQueueState()
    }

    mainHandler.postDelayed({
      initializeTTS()
    }, ENGINE_REINIT_DELAY_MS)
  }

  /**
   * Polls until the engine has voices and engines available, then attaches
   * the utterance listener and marks the module ready.
   *
   * Retries up to 20 times with escalating back-off (500 ms → 1 s → 2 s).
   */
  private fun verifyTTSReady(retryCount: Int = 0, generation: Int) {
  val maxRetries = 20
  val delay = when {
    retryCount == 0 -> 500L
    retryCount < 5 -> 1000L
    else -> 2000L
  }

  mainHandler.postDelayed({
    if (generation != initGeneration) return@postDelayed
    try {
      val voices = synthesizer.voices
      val engines = synthesizer.engines
      if (!voices.isNullOrEmpty() && !engines.isNullOrEmpty()) {
        Log.d(TAG, "Voices/engines available (${voices.size}/${engines.size}), probing connectivity…")
        cachedEngines = engines
        attachUtteranceListener()
        applyGlobalOptions(setLanguage = true)
        // FIX: non-empty voices/engines is NOT proof the engine is usable —
        // it can be served from cached PackageManager metadata before the
        // binder connection to the service is actually live. Do NOT mark
        // isInitialized here. Only a real onStart on a probe utterance
        // (see probeEngineConnectivity) proves the pipe works, and only
        // completeInitialization() — called from that onStart — is allowed
        // to flip isInitialized.
        probeEngineConnectivity(generation)
      } else if (retryCount < maxRetries) {
        Log.w(TAG, "TTS not ready (retry ${retryCount + 1}/$maxRetries)")
        verifyTTSReady(retryCount + 1, generation)
      } else {
        Log.e(TAG, "TTS failed to become ready after $maxRetries retries")
        isInitialized = false
        isInitializing = false
        rejectPendingOperations()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exception during TTS verification (retry $retryCount)", e)
      if (retryCount < maxRetries) verifyTTSReady(retryCount + 1, generation)
      else {
        isInitialized = false
        isInitializing = false
        rejectPendingOperations()
      }
    }
  }, delay)
}

/**
 * FIX (root cause): fires an inaudible canary utterance right after
 * voices/engines look populated, and only trusts the engine once that
 * canary's onStart actually fires. If the engine is half-connected, this
 * fails exactly the way a real utterance would — synchronous ERROR
 * (Google's failure mode) or no onStart within WATCHDOG_TIMEOUT_MS
 * (Huawei's failure mode) — and gets run through the same
 * retry/fallback/give-up policy, automatically, before speak() is ever
 * called from JS. Previously that policy only ever triggered off of a
 * real, user-audible failed utterance.
 */
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
    if (generation != initGeneration) return@postDelayed // superseded, ignore
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
    // FIX: re-apply globalOptions here too, not just pre-probe in
    // verifyTTSReady — picks up any initialize() calls that arrived during
    // the probe window and were deliberately skipped above because
    // `synthesizer` wasn't confirmed live yet.
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

/** Same policy as handleEngineFailure(), but for the init-time probe — no
 * SpeechQueueItem exists yet, just engine-health bookkeeping. */
private fun handleInitProbeFailure(reason: String, generation: Int) {
  if (generation != initGeneration) return

  val engineName = selectedEngine ?: (if (::synthesizer.isInitialized) synthesizer.defaultEngine else null) ?: "unknown"
  val engineFailures = (engineFailureCounts[engineName] ?: 0) + 1
  engineFailureCounts[engineName] = engineFailures
  totalConsecutiveFailures++

  Log.e(TAG, "Init probe on '$engineName' failed ($reason). consecutiveForEngine=$engineFailures totalConsecutive=$totalConsecutiveFailures")

  if (totalConsecutiveFailures >= MAX_TOTAL_FAILURES) {
    emitOnError(errorEventData(uniqueId(), reason = "engine_dead", engine = engineName))
    engineDead = true
    isInitializing = false
    rejectPendingOperations()
    resetQueueState()
    return
  }

  if (engineFailures < MAX_ENGINE_FAILURES) {
    teardownAndReinitialize(preserveQueue = true)
  } else {
    emitOnError(errorEventData(uniqueId(), reason = "engine_unavailable", engine = engineName))
    engineFailureCounts.remove(engineName)
    selectedEngine = null
    teardownAndReinitialize(preserveQueue = true)
  }
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
        if (utteranceId == pendingProbeUtteranceId) {
          pendingProbeUtteranceId = null
          completeInitialization(initGeneration)
          return
        }
        val engineName = selectedEngine ?: synthesizer.defaultEngine
        if (engineName != null) engineFailureCounts.remove(engineName)
        totalConsecutiveFailures = 0

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
        if (utteranceId == pendingProbeUtteranceId) {
          pendingProbeUtteranceId = null
          handleInitProbeFailure("speak_rejected", initGeneration)
          return
        }
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
              putInt("id", utteranceId.hashCode())
              putInt("length", end - start)
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
   * @param setLanguage Pass true only during init / engine switch / reset.
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
    globalOptions["pitch"]?.let { synthesizer.setPitch((it as? Number)?.toFloat() ?: 1.0f) }
    globalOptions["rate"]?.let { synthesizer.setSpeechRate((it as? Number)?.toFloat() ?: 0.5f) }
    globalOptions["voice"]?.let { voiceId ->
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
    synthesizer.setSpeechRate((opts["rate"] as? Number)?.toFloat() ?: 0.5f)
    synthesizer.setPitch((opts["pitch"] as? Number)?.toFloat() ?: 1.0f)

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
   * THE KEY FIX: the actual synthesizer.speak() call now happens OUTSIDE
   * queueLock. Only the bookkeeping (picking the next item, computing its
   * params/text, advancing currentQueueIndex) happens under the lock. This
   * way, if speak() ever hangs on a half-connected engine, it can't block
   * stop()/pause()/speak() calls from other threads that also need
   * queueLock.
   *
   * A watchdog is armed right after the speak() call: if onStart doesn't
   * fire within WATCHDOG_TIMEOUT_MS, we assume the engine connection is
   * stuck and force a full teardown/rebuild instead of waiting forever.
   */
  private fun processNextQueueItem() {
    var itemToSpeak: SpeechQueueItem? = null
    var paramsToUse: Bundle? = null
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
            // Build params (sets rate/pitch/voice — NOT setLanguage)
            val params = buildParamsForItem(item)

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
            paramsToUse = params
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
      // Restore global defaults (rate/pitch only — no setLanguage)
      applyGlobalOptions(setLanguage = false)
      return
    }

    val item = itemToSpeak ?: return
    val generationAtCallTime = initGeneration

    Log.d(TAG, "engine=${synthesizer.defaultEngine}")
    Log.d(TAG, "voice=${synthesizer.voice?.name}")
    Log.d(TAG, "language=${synthesizer.language}")
    Log.d(TAG, "isSpeaking=${synthesizer.isSpeaking}")

    // Call into the engine OUTSIDE queueLock.
    val result = synthesizer.speak(
      textToSpeak,
      queueModeToUse,
      paramsToUse,
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

  /**
   * If onStart hasn't fired for this utteranceId within WATCHDOG_TIMEOUT_MS,
   * the engine connection is presumed stuck (the classic "voices loaded but
   * speak() never actually starts" state after an engine switch). Force a
   * full teardown/rebuild rather than leaving the queue wedged forever.
   */
  private fun armSpeakWatchdog(utteranceId: String, generation: Int) {
    mainHandler.postDelayed({
      if (generation != initGeneration) return@postDelayed // engine already rebuilt, stale watchdog

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

  /**
   * FIX: centralizes what used to be two divergent, both-broken failure
   * paths (the synchronous ERROR branch in processNextQueueItem, and the
   * watchdog timeout). Previously both would:
   *   1. Call teardownAndReinitialize(), which wiped the ENTIRE queue,
   *      silently discarding the utterance that failed — and anything
   *      queued behind it.
   *   2. Rebuild using the SAME selectedEngine, so a consistently-broken
   *      engine (e.g. Huawei's, which rejects speak() synchronously every
   *      time) would repeat this forever, every ~4 seconds, with no way for
   *      JS to ever find out or offer the user a different engine.
   *
   * Now:
   *   - The failing item is kept (not dropped) and retried once after a
   *     rebuild of the SAME engine, in case it was a transient hiccup.
   *   - If the same engine fails twice in a row, we give up on it, notify
   *     JS via onError with reason="engine_unavailable", and fall back to
   *     the system default engine (selectedEngine = null) instead of
   *     retrying the same broken one indefinitely.
   *   - If failures keep happening even after falling back (or there's
   *     nowhere left to fall back to), a hard backstop
   *     (MAX_TOTAL_FAILURES) stops the automatic rebuild loop entirely and
   *     marks the engine "dead" — any further speak() calls reject
   *     immediately with a clear message instead of silently queuing
   *     forever, until the caller explicitly calls reset() or setEngine().
   */
  private fun handleEngineFailure(item: SpeechQueueItem, reason: String) {
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
      emitOnError(errorEventData(item.utteranceId, reason = "engine_dead", engine = engineName))
      engineDead = true
      rejectPendingOperations()
      resetQueueState()
      return
    }

    if (engineFailures < MAX_ENGINE_FAILURES) {
      // Transient — put the item back at the front of the line and rebuild
      // the same engine.
      synchronized(queueLock) { item.status = SpeechStatus.PENDING }
      teardownAndReinitialize(preserveQueue = true)
    } else {
      // This engine looks consistently broken. Give up on it, tell JS why,
      // and fall back to whatever the system default is instead of looping
      // on the same dead engine.
      synchronized(queueLock) {
        item.status = SpeechStatus.ERROR
        if (!isPaused) currentQueueIndex++
      }
      deactivateDuckingSession()
      emitOnError(errorEventData(item.utteranceId, reason = "engine_unavailable", engine = engineName))
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
  }

  // ────────────────────────────────────────────────────────────────────────
  // Pending operations
  // ────────────────────────────────────────────────────────────────────────
  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    // FIX: without this check, a permanently broken engine (see
    // handleEngineFailure) would just keep getting re-queued and re-tried
    // via the `else` branch below forever, on every single call, with the
    // caller never finding out. Now we fail fast and loudly instead.
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

  // FIX: previously emitOnError only ever carried the utterance id, giving
  // JS no way to distinguish "this one utterance failed" from "the engine
  // itself is unusable" — which is why the JS onError listener could never
  // do anything useful beyond resetting paragraph-reading state. The extra
  // fields are additive (existing consumers reading only `id` are
  // unaffected); on the JS side we read them via an `any`-typed callback
  // parameter since the bundled TS defs don't know about them.
  private fun errorEventData(utteranceId: String, reason: String, engine: String? = null): ReadableMap =
    Arguments.createMap().apply {
      putInt("id", utteranceId.hashCode())
      putString("reason", reason)
      engine?.let { putString("engine", it) }
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

    // FIX: previously called applyGlobalOptions(setLanguage = true)
    // unconditionally. JS calls this fire-and-forget, and with the
    // connectivity probe now extending rebuild latency, it's easy for this
    // to land inside teardownAndReinitialize()'s window where `synthesizer`
    // still points to an already-shutdown instance — silently broken, same
    // failure mode reset() was already guarded against. globalOptions is
    // updated above regardless, and completeInitialization() re-applies it
    // once the probe actually confirms the engine is live, so a call landing
    // mid-rebuild isn't lost — we just don't touch a dead object here.
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
    // FIX: this used to call applyGlobalOptions(setLanguage = true)
    // unconditionally, touching `synthesizer` directly. reset() has no
    // Promise, so JS calls it fire-and-forget — including, per the JS
    // reinitializeTTS() flow, potentially WHILE a teardownAndReinitialize()
    // is mid-flight (old instance already shut down, new one not
    // constructed yet). Calling setLanguage() on a shut-down TextToSpeech
    // instance at that moment is silently broken and was a real
    // contributor to "the engine just doesn't come back" symptoms.
    // Guard: only touch the live synthesizer if it's actually ready. If
    // it's not, verifyTTSReady() will apply these (now-updated)
    // globalOptions itself once the new instance comes up.
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
      synchronized(queueLock) {
        if (!synthesizer.isSpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!synthesizer.isSpeaking && !isPaused) {
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
      synchronized(queueLock) {
        if (!synthesizer.isSpeaking && !isPaused) pruneCompletedItems()
        speechQueue.add(item)
        if (!synthesizer.isSpeaking && !isPaused) {
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
        promise.resolve(null); return
      }
    }
    // Set the target engine BEFORE tearing down: teardownAndReinitialize()
    // schedules initializeTTS() after a short delay, and initializeTTS()
    // reads selectedEngine at that point.
    selectedEngine = engineName
    // FIX: an explicit engine switch is a deliberate user/app choice, so
    // reset all failure bookkeeping — we don't want a previous engine's
    // failure count (or a prior engineDead state) to linger and affect the
    // newly-chosen engine.
    engineDead = false
    totalConsecutiveFailures = 0
    engineFailureCounts.clear()
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
    initGeneration++ // invalidate any pending watchdogs/callbacks
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
  }
}