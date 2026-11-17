package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class AndroidAudioBridge(private val context: Context) {
  companion object {
    private const val TAG = "AndroidAudioBridge"
    private const val SAMPLE_RATE = 16000.0f
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val audioExecutor = Executors.newSingleThreadExecutor()

  @Volatile var webView: WebView? = null

  @Volatile private var recognizer: Recognizer? = null

  @Volatile private var isSpeechActive = false

  @Volatile private var siteFeedsPcmDirectly = false

  @Volatile private var siteSetsVocabularyDirectly = false

  @Volatile private var currentGrammarJson: String? = null

  private var lastPartialSent = ""
  private var candidatePartial = ""
  private var candidatePartialStreak = 0
  private var lastStopTimestamp = 0L

  // Состояние фильтра верхних частот (~150 Гц) для ослабления басовых струн гитары
  private var hpPrevIn = 0f
  private var hpPrevOut = 0f
  private var smoothAgcGain = 1.0f

  private val delayedStopRunnable = Runnable {
    isSpeechActive = false
    lastPartialSent = ""
    candidatePartial = ""
    candidatePartialStreak = 0
  }

  init {
    VoskModelManager.ensureModelLoaded(context) { loadedModel ->
      audioExecutor.execute {
        rebuildRecognizer(loadedModel, currentGrammarJson)
      }
    }
  }

  @Synchronized
  private fun rebuildRecognizer(loadedModel: Model, grammarJson: String?) {
    try {
      recognizer?.close()
      val rec =
        if (!grammarJson.isNullOrBlank()) {
          try {
            Recognizer(loadedModel, SAMPLE_RATE, grammarJson)
          } catch (e: Exception) {
            Log.w(TAG, "Fallback to full-vocabulary Recognizer", e)
            Recognizer(loadedModel, SAMPLE_RATE)
          }
        } else {
          Recognizer(loadedModel, SAMPLE_RATE)
        }
      runCatching { rec.setMaxAlternatives(3) }
      recognizer = rec
    } catch (e: Exception) {
      Log.e(TAG, "Error creating Vosk Recognizer", e)
    }
  }

  @JavascriptInterface
  fun setSongVocabulary(wordsJson: String, lang: String?) {
    siteSetsVocabularyDirectly = true
    applyVocabularyInternal(wordsJson)
  }

  @JavascriptInterface
  fun setPolyfillSongVocabulary(wordsJson: String, lang: String?) {
    if (siteSetsVocabularyDirectly) return
    applyVocabularyInternal(wordsJson)
  }

  private fun applyVocabularyInternal(wordsJson: String) {
    audioExecutor.execute {
      try {
        val inputArr = JSONArray(wordsJson)
        val grammarArr = JSONArray()
        val seen = HashSet<String>()

        fun addWord(word: String) {
          if (word.isNotEmpty() && seen.add(word)) {
            grammarArr.put(word)
          }
        }

        for (i in 0 until inputArr.length()) {
          val rawWord = inputArr.optString(i).trim().lowercase()
          if (rawWord.length < 3) continue

          val baseWord = rawWord.replace('ё', 'е')
          addWord(rawWord)
          addWord(baseWord)

          for (idx in baseWord.indices) {
            if (baseWord[idx] == 'е') {
              val yoVariant = baseWord.substring(0, idx) + 'ё' + baseWord.substring(idx + 1)
              addWord(yoVariant)
            }
          }
        }

        if (grammarArr.length() == 0) return@execute

        addWord("[unk]")

        val newGrammar = grammarArr.toString()
        if (newGrammar == currentGrammarJson && recognizer != null) {
          return@execute
        }
        currentGrammarJson = newGrammar
        VoskModelManager.model?.let { loadedModel ->
          rebuildRecognizer(loadedModel, currentGrammarJson)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse song vocabulary", e)
      }
    }
  }

  @JavascriptInterface
  fun startNativeSpeech(lang: String?) {
    mainHandler.removeCallbacks(delayedStopRunnable)
    val now = SystemClock.elapsedRealtime()
    val wasQuickRecycle = (now - lastStopTimestamp) < 800L
    isSpeechActive = true

    VoskModelManager.ensureModelLoaded(context) { loadedModel ->
      audioExecutor.execute {
        if (recognizer == null) {
          rebuildRecognizer(loadedModel, currentGrammarJson)
        } else if (!wasQuickRecycle) {
          lastPartialSent = ""
          candidatePartial = ""
          candidatePartialStreak = 0
          hpPrevIn = 0f
          hpPrevOut = 0f
          runCatching { recognizer?.reset() }
        }
      }
    }
  }

  @JavascriptInterface
  fun stopNativeSpeech() {
    lastStopTimestamp = SystemClock.elapsedRealtime()
    mainHandler.removeCallbacks(delayedStopRunnable)
    mainHandler.postDelayed(delayedStopRunnable, 600L)
  }

  @JavascriptInterface
  fun feedPcm16Base64(base64Pcm: String) {
    siteFeedsPcmDirectly = true
    processPcmBase64(base64Pcm)
  }

  @JavascriptInterface
  fun feedPolyfillPcm16Base64(base64Pcm: String) {
    if (siteFeedsPcmDirectly) return
    processPcmBase64(base64Pcm)
  }

  private fun preprocessVocalPcm(pcmBytes: ByteArray) {
    val sampleCount = pcmBytes.size / 2
    if (sampleCount <= 0) return

    val shortBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val filtered = FloatArray(sampleCount)
    var sumSquares = 0.0
    var prevIn = hpPrevIn
    var prevOut = hpPrevOut

    val alpha = 0.944f
    for (i in 0 until sampleCount) {
      val x = shortBuf.get(i).toFloat()
      val y = alpha * (prevOut + x - prevIn)
      prevIn = x
      prevOut = y
      filtered[i] = y
      sumSquares += (y * y).toDouble()
    }
    hpPrevIn = prevIn
    hpPrevOut = prevOut

    val rms = sqrt(sumSquares / sampleCount).toFloat()
    val targetRms = 5200f
    val desiredGain =
      if (rms > 120f) {
        (targetRms / rms).coerceIn(0.6f, 3.2f)
      } else {
        1.0f
      }
    smoothAgcGain = smoothAgcGain * 0.75f + desiredGain * 0.25f

    shortBuf.rewind()
    val gain = smoothAgcGain
    for (i in 0 until sampleCount) {
      var v = filtered[i] * gain
      val absV = abs(v)
      if (absV > 24000f) {
        val sign = if (v >= 0f) 1f else -1f
        val excess = (absV - 24000f) / 8767f
        v = sign * (24000f + 8767f * (excess / (1f + excess)))
      }
      val clamped = v.toInt().coerceIn(-32768, 32767).toShort()
      shortBuf.put(i, clamped)
    }
  }

  private fun cleanRecognizedString(raw: String): String {
    return raw
      .replace("[unk]", "")
      .replace('ё', 'е')
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun processPcmBase64(base64Pcm: String) {
    if (!isSpeechActive || base64Pcm.isEmpty()) return

    audioExecutor.execute {
      val rec = recognizer ?: return@execute
      try {
        val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
        if (pcmBytes.size < 2) return@execute

        preprocessVocalPcm(pcmBytes)

        val isFinal = rec.acceptWaveForm(pcmBytes, pcmBytes.size)
        if (isFinal) {
          val resJson = JSONObject(rec.result)
          val candidates = ArrayList<String>()

          val alts = resJson.optJSONArray("alternatives")
          if (alts != null) {
            for (i in 0 until alts.length()) {
              val altText = cleanRecognizedString(alts.optJSONObject(i)?.optString("text", "") ?: "")
              if (altText.isNotEmpty() && !candidates.contains(altText)) {
                candidates.add(altText)
              }
            }
          }

          val singleText = cleanRecognizedString(resJson.optString("text", ""))
          if (singleText.isNotEmpty() && !candidates.contains(singleText)) {
            candidates.add(singleText)
          }

          lastPartialSent = ""
          candidatePartial = ""
          candidatePartialStreak = 0
          if (candidates.isNotEmpty()) {
            sendCandidatesToWeb(candidates, true)
          }
        } else {
          val partialJson = JSONObject(rec.partialResult)
          val partial = cleanRecognizedString(partialJson.optString("partial", ""))
          if (partial.isNotEmpty()) {
            if (partial == candidatePartial) {
              candidatePartialStreak++
            } else {
              candidatePartial = partial
              candidatePartialStreak = 1
            }
            // Отправляем partial только когда гипотеза подтвердилась на 2 подряд фреймах (~170 мс),
            // чтобы не дергать прокрутку на недопетом первом слоге слова
            if (candidatePartialStreak >= 2 && partial != lastPartialSent) {
              lastPartialSent = partial
              sendCandidatesToWeb(listOf(partial), false)
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "PCM processing error", e)
      }
    }
  }

  private fun sendCandidatesToWeb(candidates: List<String>, isFinal: Boolean) {
    val wv = webView ?: return
    val jsonArrayStr = JSONArray(candidates).toString()
    val primaryEscaped = JSONObject.quote(candidates.first())
    mainHandler.post {
      wv.evaluateJavascript(
        """
        (function() {
          var candidates = $jsonArrayStr;
          var primary = $primaryEscaped;
          var isFinal = $isFinal;
          if (typeof window.__onAndroidSpeechResult === 'function') {
            window.__onAndroidSpeechResult(candidates.length > 1 ? { alternatives: candidates.map(function(t) { return { text: t }; }) } : primary, isFinal);
          }
          if (typeof window.__dispatchPolyfillSpeechResult === 'function') {
            window.__dispatchPolyfillSpeechResult(candidates, isFinal);
          }
        })();
        """.trimIndent(),
        null
      )
    }
  }

  fun release() {
    mainHandler.removeCallbacksAndMessages(null)
    isSpeechActive = false
    lastPartialSent = ""
    candidatePartial = ""
    candidatePartialStreak = 0
    audioExecutor.execute {
      runCatching {
        recognizer?.close()
        recognizer = null
      }
    }
  }
}
