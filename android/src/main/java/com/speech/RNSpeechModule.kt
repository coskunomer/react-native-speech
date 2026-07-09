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
    private const val WATCHDOG_TIMEOUT_MS = 5000L

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

    // FIX: bounded retry for the "language data not warmed up yet" race.
    // Right after the engine reports SUCCESS (and even after our
    // connectivity probe confirms the binder is alive), TextToSpeech.
    // setLanguage() can return LANG_MISSING_DATA / LANG_NOT_SUPPORTED for a
    // locale that becomes available moments later — the probe only proves
    // the connection is up, it says nothing about a specific locale's data
    // being ready. Previously the return code was never checked, so speak()
    // was called anyway with no language resolved, got synchronously
    // rejected by the engine, and only "recovered" because the resulting
    // failure-triggered full teardown/rebuild happened to buy enough time
    // for the data to finish loading. These retries fix the actual race
    // directly, without paying for a full engine rebuild every cold start.
    //
    // FIX: a flat 3x200ms budget (600ms total) was enough for Google's TTS
    // engine but not for some OEM engines (observed: Huawei's hiai engine
    // still returned LANG_MISSING_DATA after 600ms, and only actually
    // resolved several seconds later — during which time a full, unrelated
    // engine rebuild happened to occur and mask the real fix). Use
    // exponential backoff with more attempts and a higher ceiling so slower
    // engines get a realistic chance without over-delaying fast ones.
    private const val MAX_LANGUAGE_RETRIES = 6
    private const val LANGUAGE_RETRY_BASE_DELAY_MS = 150L
    private const val LANGUAGE_RETRY_MAX_DELAY_MS = 2000L

    /** attempt is 0-indexed (0 = first retry). Backs off 150ms, 300ms, 600ms,
     * 1200ms, 2000ms(capped), 2000ms(capped) — roughly 6.2s of total budget
     * across MAX_LANGUAGE_RETRIES attempts before giving up. */
    private fun languageRetryDelayMs(attempt: Int): Long {
      val exp = LANGUAGE_RETRY_BASE_DELAY_MS * (1L shl attempt)
      return exp.coerceAtMost(LANGUAGE_RETRY_MAX_DELAY_MS)
    }

    // FIX: TextToSpeech(context, listener, requestedEnginePackage) can fail
    // to bind the requested engine's service and silently fall back to the
    // system default — while still reporting onInit == SUCCESS. This has
    // been observed even when the requested engine is genuinely installed
    // and listed in getEngines(): the bind can lose a race right after
    // process/app cold start, or be blocked by OEM background-service
    // restrictions (observed on a Huawei device where the system default
    // was set to Huawei's own engine). The previous version of this code
    // just accepted the silent fallback and corrected bookkeeping to match
    // it — which "fixed" the symptom of misattributed failures, but never
    // actually gave the requested engine (e.g. Google TTS) a real chance to
    // bind. We now retry the FULL construction a bounded number of times
    // before giving up and accepting the fallback.
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
  private val engineFailureCounts = mutableMapOf<String, Int>()
  private var totalConsecutiveFailures = 0
  private var engineDead = false

  // FIX: per-utterance retry counter for the setLanguage "data not ready
  // yet" race. Keyed by utteranceId; cleaned up once the utterance leaves
  // the queue (onStart / onDone / onError) so it can never leak.
  private val languageRetryCounts = mutableMapOf<String, Int>()

  // FIX: counts consecutive "requested engine not actually bound" mismatches
  // for the CURRENT switch attempt. Reset to 0 whenever selectedEngine is
  // set fresh by setEngine(), and whenever a construction actually lands on
  // the requested engine. See MAX_ENGINE_SWITCH_RETRIES.
  private var engineSwitchRetryCount = 0

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

    // FIX: TextToSpeech(context, listener, requestedEnginePackage) does NOT
    // fail or report a non-SUCCESS status if requestedEnginePackage can't
    // actually be bound — it silently falls back to the real system default
    // and still calls back with SUCCESS. Rather than accepting that on the
    // first attempt (which just papers over a real, sometimes-transient
    // binding race), retry the FULL engine construction up to
    // MAX_ENGINE_SWITCH_RETRIES times. Only once retries are exhausted do we
    // give up, correct our bookkeeping to the engine that's really bound,
    // and tell JS the switch didn't stick.
    val requestedEngine = selectedEngine
    val actuallyBoundEngine = synthesizer.defaultEngine

    if (requestedEngine != null && requestedEngine != actuallyBoundEngine) {
      if (engineSwitchRetryCount < MAX_ENGINE_SWITCH_RETRIES) {
        engineSwitchRetryCount++
        Log.w(
          TAG,
          "Requested engine '$requestedEngine' did not bind (got '$actuallyBoundEngine' instead) — " +
            "retrying construction in ${ENGINE_SWITCH_RETRY_DELAY_MS}ms " +
            "(attempt $engineSwitchRetryCount/$MAX_ENGINE_SWITCH_RETRIES)"
        )
        // Keep selectedEngine as-is (still the real request) and rebuild.
        // preserveQueue = true: this isn't a queue-affecting failure from
        // JS's point of view, just a retry of engine construction itself.
        pendingReinitRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { initializeTTS(preserveQueue = true) }
        pendingReinitRunnable = runnable
        mainHandler.postDelayed(runnable, ENGINE_SWITCH_RETRY_DELAY_MS)
        return
      } else {
        Log.w(
          TAG,
          "Requested engine '$requestedEngine' still not bound after " +
            "$MAX_ENGINE_SWITCH_RETRIES retries — giving up on the switch and " +
            "accepting the system's fallback to '$actuallyBoundEngine'."
        )
        emitOnError(
          errorEventData(
            uniqueId(),
            reason = "engine_switch_failed",
            engine = actuallyBoundEngine,
            requestedEngine = requestedEngine,
          )
        )
        selectedEngine = actuallyBoundEngine
        engineSwitchRetryCount = 0
      }
    } else {
      engineSwitchRetryCount = 0
    }

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
 *
 * NOTE: this probe only proves the binder connection to the engine is
 * alive via playSilentUtterance(), which requires no language data. It is
 * NOT proof that a specific requested locale's voice data is ready — see
 * the language-retry handling in buildParamsForItem/processNextQueueItem
 * for that separate race.
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
        languageRetryCounts.remove(utteranceId)

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
        languageRetryCounts.remove(utteranceId)
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
        languageRetryCounts.remove(utteranceId)
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
        try {
          val result = synthesizer.setLanguage(Locale.forLanguageTag(it as String))
          if (result < TextToSpeech.LANG_AVAILABLE) {
            // FIX: previously this result code was silently discarded. It's
            // not fatal here (completeInitialization() re-applies options
            // once the probe confirms the engine is live, and buildParamsForItem
            // re-applies + retries at actual speak-time), but log it so the
            // "not ready yet" race is visible instead of invisible.
            Log.w(TAG, "applyGlobalOptions(): setLanguage('$it') -> result=$result (not ready)")
          }
        } catch (e: Exception) {
          Log.w(TAG, "applyGlobalOptions(): setLanguage('$it') threw, continuing with other options", e)
        }
        // Re-attach regardless of whether setLanguage succeeded above —
        // setLanguage can orphan the listener on Samsung / AOSP TTS engines
        // even when it doesn't throw.
        if (listenerSet) attachUtteranceListener()
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
      globalOptions["voice"]?.let { voiceId ->
        synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
      }
    } catch (e: Exception) {
      Log.w(TAG, "applyGlobalOptions(): setting voice failed", e)
    }
}

  /**
   * Result of building per-utterance TTS params.
   *
   * @param languageResult the raw TextToSpeech.setLanguage() return code
   *   (or TextToSpeech.LANG_AVAILABLE if no language was requested for this
   *   item). Callers MUST check this before calling speak() — see FIX note
   *   on processNextQueueItem().
   */
  private data class BuiltSpeechParams(val bundle: Bundle, val languageResult: Int)

  /**
   * Build a Bundle for a single queue item. Applies rate/pitch/voice per
   * utterance via direct setters (these are safe on all engines), but
   * intentionally NEVER calls setLanguage() at init time — that stays in
   * applyGlobalOptions. It DOES call setLanguage() here at actual
   * speak-time, since every real JS call sends `language` explicitly and
   * this is the last point before speak() where it can be corrected.
   *
   * FIX (root cause of the bug being patched): setLanguage()'s return value
   * is now surfaced to the caller instead of being silently discarded.
   * TextToSpeech.setLanguage() does NOT throw for an unsupported / not-yet-
   * loaded locale — it returns LANG_MISSING_DATA (-2) or LANG_NOT_SUPPORTED
   * (-1). Previously only exceptions were caught, so a -2/-1 result was
   * treated as success: speak() was then called with no language actually
   * resolved, and Google's engine rejected it synchronously (-1). That
   * failure only "recovered" because the resulting handleEngineFailure()
   * teardown/rebuild happened to buy enough wall-clock time for the
   * engine's locale data to finish loading — not a real fix. The caller
   * (processNextQueueItem) now inspects languageResult and retries a few
   * times with a short delay instead of speaking blind.
   */
  private fun buildParamsForItem(item: SpeechQueueItem): BuiltSpeechParams {
    val opts = globalOptions.toMutableMap().apply { putAll(item.options) }

    var languageResult = TextToSpeech.LANG_AVAILABLE
    (opts["language"] as? String)?.let { langTag ->
      languageResult = try {
        synthesizer.setLanguage(Locale.forLanguageTag(langTag))
      } catch (e: Exception) {
        Log.w(TAG, "buildParamsForItem(): setLanguage('$langTag') threw", e)
        TextToSpeech.LANG_NOT_SUPPORTED
      }
      if (languageResult < TextToSpeech.LANG_AVAILABLE) {
        Log.w(TAG, "buildParamsForItem(): setLanguage('$langTag') -> result=$languageResult (not ready)")
      }
      // setLanguage can orphan the utterance listener on non-Google engines
      // (Samsung/AOSP) — same caveat as during init — re-attach immediately.
      if (listenerSet) attachUtteranceListener()
    }

    // Rate / pitch — safe to set per-utterance, do not reset the listener
    synthesizer.setSpeechRate((opts["rate"] as? Number)?.toFloat() ?: 0.5f)
    synthesizer.setPitch((opts["pitch"] as? Number)?.toFloat() ?: 1.0f)

    // Voice
    (opts["voice"] as? String)?.let { voiceId ->
      synthesizer.voices?.find { it.name == voiceId }?.let { synthesizer.voice = it }
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
   *
   * FIX: before calling speak(), we now check the languageResult that came
   * back from buildParamsForItem(). If the requested locale's data isn't
   * ready yet (LANG_MISSING_DATA / LANG_NOT_SUPPORTED), we do NOT call
   * speak() with a broken language — we reschedule this same item a few
   * times with a short delay (MAX_LANGUAGE_RETRIES /
   * LANGUAGE_RETRY_DELAY_MS) to let the engine finish loading the locale.
   * Only if it's still not ready after those retries do we give up and
   * speak anyway with best-effort language state, rather than looping
   * forever or triggering a full, unrelated engine teardown for what is
   * really just a brief data-loading race.
   */
  private fun processNextQueueItem() {
    var itemToSpeak: SpeechQueueItem? = null
    var paramsToUse: Bundle? = null
    var languageResultForItem = TextToSpeech.LANG_AVAILABLE
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
            // Build params (sets rate/pitch/voice/language)
            val built = buildParamsForItem(item)

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
            paramsToUse = built.bundle
            languageResultForItem = built.languageResult
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

    // FIX: don't speak with an unresolved language — retry first.
    if (languageResultForItem < TextToSpeech.LANG_AVAILABLE) {
      val retries = languageRetryCounts.getOrDefault(item.utteranceId, 0)
      if (retries < MAX_LANGUAGE_RETRIES) {
        languageRetryCounts[item.utteranceId] = retries + 1
        val delayMs = languageRetryDelayMs(retries)
        Log.w(
          TAG,
          "Language not ready for utterance ${item.utteranceId} (result=$languageResultForItem) — " +
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
    languageRetryCounts.clear()
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
  private fun errorEventData(
    utteranceId: String,
    reason: String,
    engine: String? = null,
    requestedEngine: String? = null,
  ): ReadableMap =
    Arguments.createMap().apply {
      putInt("id", utteranceId.hashCode())
      putString("reason", reason)
      engine?.let { putString("engine", it) }
      requestedEngine?.let { putString("requestedEngine", it) }
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
    languageRetryCounts.clear()
    engineSwitchRetryCount = 0
  }
}