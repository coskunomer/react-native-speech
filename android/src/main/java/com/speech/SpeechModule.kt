package com.speech

import java.util.UUID
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.speech.tts.Voice
import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReadableMap
import com.facebook.fbreact.specs.NativeSpeechSpec
import android.speech.tts.UtteranceProgressListener
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = SpeechModule.NAME)
class SpeechModule(reactContext: ReactApplicationContext) :
  NativeSpeechSpec(reactContext) {

  override fun getName(): String {
    return NAME
  }

  companion object {
    const val NAME = "Speech"

    @SuppressLint("ConstantLocale")
    private val defaultOptions: Map<String, Any> = mapOf(
      "rate" to 0.5f,
      "pitch" to 1.0f,
      "volume" to 1.0f,
      "language" to Locale.getDefault().toLanguageTag()
    )
  }
  private val queueLock = Any()

  private val isSupportedPausing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
  private lateinit var synthesizer: TextToSpeech
  private var isInitialized = false
  private var isInitializing = false
  private val pendingOperations = mutableListOf<Pair<() -> Unit, Promise>>()
  private var globalOptions: MutableMap<String, Any> = defaultOptions.toMutableMap()
  private var isPaused = false
  private var isResuming = false
  private var currentQueueIndex = -1
  private val speechQueue = mutableListOf<SpeechQueueItem>()

  init {
    initializeTTS()
  }

  private fun processPendingOperations() {
    val operations = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((operation, promise) in operations) {
      try {
        operation()
      } catch (e: Exception) {
        promise.reject("speech_error", e.message ?: "Unknown error")
      }
    }
  }

  private fun rejectPendingOperations() {
    val operations = ArrayList(pendingOperations)
    pendingOperations.clear()
    for ((_, promise) in operations) {
      promise.reject("speech_error", "Failed to initialize TTS engine")
    }
  }

  private fun getSpeechParams(): Bundle {
    val params = Bundle()
    val volume = (globalOptions["volume"] as? Number)?.toFloat() ?: 1.0f
    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
    return params
  }

  private fun getEventData(utteranceId: String): ReadableMap {
    return Arguments.createMap().apply {
      putInt("id", utteranceId.hashCode())
    }
  }

  private fun getVoiceItem(voice: Voice): ReadableMap {
    val quality = if (voice.quality > Voice.QUALITY_NORMAL) "Enhanced" else "Default"
    return Arguments.createMap().apply {
      putString("quality", quality)
      putString("name", voice.name)
      putString("identifier", voice.name)
      putString("language", voice.locale.toLanguageTag())
    }
  }

  private fun getUniqueID(): String {
    return UUID.randomUUID().toString()
  }

  private fun resetQueueState() {
    synchronized(queueLock) {
      speechQueue.clear()
      currentQueueIndex = -1
      isPaused = false
      isResuming = false
    }
  }

  private fun initializeTTS() {
    if (isInitializing) return
    isInitializing = true

    synthesizer = TextToSpeech(reactApplicationContext) { status ->
      isInitialized = status == TextToSpeech.SUCCESS
      isInitializing = false

      if (isInitialized) {
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String) {
            synchronized(queueLock) {
              speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                item.status = SpeechStatus.SPEAKING
                if (isResuming && item.position > 0) {
                  emitOnResume(getEventData(utteranceId))
                  isResuming = false
                } else {
                  emitOnStart(getEventData(utteranceId))
                }
              }
            }
          }
          override fun onDone(utteranceId: String) {
            synchronized(queueLock) {
              speechQueue.find { it.utteranceId == utteranceId }?.let { item ->
                item.status = SpeechStatus.COMPLETED
                emitOnFinish(getEventData(utteranceId))
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
                emitOnError(getEventData(utteranceId))
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
                  emitOnPause(getEventData(utteranceId))
                } else {
                  item.status = SpeechStatus.COMPLETED
                  emitOnStopped(getEventData(utteranceId))
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
        applyGlobalOptions()
        processPendingOperations()
      } else {
        rejectPendingOperations()
      }
    }
  }

  private fun ensureInitialized(promise: Promise, operation: () -> Unit) {
    when {
      isInitialized -> {
        try {
          operation()
        } catch (e: Exception) {
          promise.reject("speech_error", e.message ?: "Unknown error")
        }
      }
      isInitializing -> {
        pendingOperations.add(Pair(operation, promise))
      }
      else -> {
        pendingOperations.add(Pair(operation, promise))
        initializeTTS()
      }
    }
  }

  private fun applyGlobalOptions() {
    globalOptions["language"]?.let {
      val locale = Locale.forLanguageTag(it as String)
      synthesizer.setLanguage(locale)
    }
    globalOptions["pitch"]?.let {
      synthesizer.setPitch(it as Float)
    }
    globalOptions["rate"]?.let {
      synthesizer.setSpeechRate(it as Float)
    }
    globalOptions["voice"]?.let { voiceId ->
      synthesizer.voices?.forEach { voice ->
        if (voice.name == voiceId) {
          synthesizer.setVoice(voice)
          return@forEach
        }
      }
    }
  }

  private fun applyOptions(options: Map<String, Any>) {
    val tempOptions = globalOptions.toMutableMap().apply {
      putAll(options)
    }
    tempOptions["language"]?.let {
      val locale = Locale.forLanguageTag(it as String)
      synthesizer.setLanguage(locale)
    }
    tempOptions["pitch"]?.let {
      synthesizer.setPitch(it as Float)
    }
    tempOptions["rate"]?.let {
      synthesizer.setSpeechRate(it as Float)
    }
    tempOptions["voice"]?.let { voiceId ->
      synthesizer.voices?.forEach { voice ->
        if (voice.name == voiceId) {
          synthesizer.setVoice(voice)
          return@forEach
        }
      }
    }
  }

  private fun getValidatedOptions(options: ReadableMap): Map<String, Any> {
    val validated = mutableMapOf<String, Any>()
    if (options.hasKey("voice")) {
      validated["voice"] = options.getString("voice") ?: ""
    }
    if (options.hasKey("language")) {
      validated["language"] = options.getString("language")
        ?: Locale.getDefault().toLanguageTag()
    }
    if (options.hasKey("pitch")) {
      validated["pitch"] = options.getDouble("pitch").toFloat().coerceIn(0.1f, 2.0f)
    }
    if (options.hasKey("volume")) {
      validated["volume"] = options.getDouble("volume").toFloat().coerceIn(0f, 1.0f)
    }
    if (options.hasKey("rate")) {
      validated["rate"] = options.getDouble("rate").toFloat().coerceIn(0.1f, 2.0f)
    }
    return validated
  }

  private fun processNextQueueItem() {
    synchronized(queueLock) {
      if (isPaused) return

      if (currentQueueIndex in 0 until speechQueue.size) {
        val item = speechQueue[currentQueueIndex]
        if (item.status == SpeechStatus.PENDING || item.status == SpeechStatus.PAUSED) {
          applyOptions(item.options)
          val params = getSpeechParams()
          val textToSpeak: String

          if (item.status == SpeechStatus.PAUSED) {
            item.offset = item.position
            textToSpeak = item.text.substring(item.offset)
            isResuming = true
          } else {
            item.offset = 0
            textToSpeak = item.text
          }
          val queueMode = if (isResuming) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
          synthesizer.speak(textToSpeak, queueMode, params, item.utteranceId)

          if (currentQueueIndex == speechQueue.size - 1) {
            applyGlobalOptions()
          }
        } else {
          currentQueueIndex++
          processNextQueueItem()
        }
      } else {
        currentQueueIndex = -1
        applyGlobalOptions()
      }
    }
  }

  override fun initialize(options: ReadableMap) {
    val newOptions = globalOptions.toMutableMap()
    newOptions.putAll(getValidatedOptions(options))
    globalOptions = newOptions
    applyGlobalOptions()
  }

  override fun reset() {
    globalOptions = defaultOptions.toMutableMap()
    applyGlobalOptions()
  }

  override fun getAvailableVoices(language: String?, promise: Promise) {
    ensureInitialized(promise) {
      val voicesArray = Arguments.createArray()
      val voices = synthesizer.voices

      if (voices == null) {
        promise.resolve(voicesArray)
        return@ensureInitialized
      }
      if (language != null) {
        val lowercaseLanguage = language.lowercase()
        voices.forEach { voice ->
          val voiceLanguage = voice.locale.toLanguageTag().lowercase()
          if (voiceLanguage.startsWith(lowercaseLanguage)) {
            voicesArray.pushMap(getVoiceItem(voice))
          }
        }
      } else {
        voices.forEach { voice ->
          voicesArray.pushMap(getVoiceItem(voice))
        }
      }
      promise.resolve(voicesArray)
    }
  }

  override fun isSpeaking(promise: Promise) {
    ensureInitialized(promise) {
      promise.resolve(synthesizer.isSpeaking || isPaused)
    }
  }

  override fun stop(promise: Promise) {
    ensureInitialized(promise) {
      if (synthesizer.isSpeaking || isPaused) {
        synthesizer.stop()
        synchronized(queueLock) {
          if (currentQueueIndex in speechQueue.indices) {
            val item = speechQueue[currentQueueIndex]
            emitOnStopped(getEventData(item.utteranceId))
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
        val pausedItemIndex = speechQueue.indexOfFirst { it.status == SpeechStatus.PAUSED }
        if (pausedItemIndex >= 0) {
          currentQueueIndex = pausedItemIndex
          isPaused = false
          processNextQueueItem()
          promise.resolve(true)
        } else {
          isPaused = false
          promise.resolve(false)
        }
      }
    }
  }

  override fun speak(text: String?, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null")
      return
    }
    ensureInitialized(promise) {
      val utteranceId = getUniqueID()
      val queueItem = SpeechQueueItem(text = text, options = emptyMap(), utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(queueItem)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun speakWithOptions(text: String?, options: ReadableMap, promise: Promise) {
    if (text == null) {
      promise.reject("speech_error", "Text cannot be null")
      return
    }
    ensureInitialized(promise) {
      val utteranceId = getUniqueID()
      val validatedOptions = getValidatedOptions(options)
      val queueItem = SpeechQueueItem(text = text, options = validatedOptions, utteranceId = utteranceId)
      synchronized(queueLock) {
        speechQueue.add(queueItem)
        if (!synthesizer.isSpeaking && !isPaused) {
          currentQueueIndex = speechQueue.size - 1
          processNextQueueItem()
        }
      }
      promise.resolve(null)
    }
  }

  override fun invalidate() {
    super.invalidate()
    if (::synthesizer.isInitialized) {
      synthesizer.stop()
      synthesizer.shutdown()
      resetQueueState()
    }
  }
}
