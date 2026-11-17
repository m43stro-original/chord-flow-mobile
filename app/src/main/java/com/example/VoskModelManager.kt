package com.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import org.vosk.Model

object VoskModelManager {
  private const val TAG = "VoskModelManager"
  private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
  private const val MODEL_DIR_NAME = "vosk-model-small-ru-0.22"

  /**
   * Конфигурация декодера Kaldi с расширенным лучом поиска (beam=13.0, lattice-beam=6.0,
   * max-active=7000) для повышения точности распознавания пения на фоне гитары.
   */
  private val TUNED_MODEL_CONF =
    """
    --min-active=200
    --max-active=7000
    --beam=13.0
    --lattice-beam=6.0
    --acoustic-scale=1.0
    --frame-subsampling-factor=3
    --endpoint.silence-phones=1:2:3:4:5:6:7:8:9:10
    --endpoint.rule2.min-trailing-silence=0.75
    --endpoint.rule3.min-trailing-silence=1.2
    --endpoint.rule4.min-trailing-silence=2.0
    """.trimIndent() + "\n"

  private val mainHandler = Handler(Looper.getMainLooper())
  private val loaderExecutor = Executors.newSingleThreadExecutor()
  private val pendingCallbacks = CopyOnWriteArrayList<(Model) -> Unit>()

  @Volatile
  var model: Model? = null
    private set

  @Volatile
  var isLoading = false
    private set

  fun ensureModelLoaded(context: Context, onReady: (Model) -> Unit) {
    model?.let {
      onReady(it)
      return
    }

    pendingCallbacks.add(onReady)
    synchronized(this) {
      if (isLoading) return
      isLoading = true
    }

    val appContext = context.applicationContext
    loaderExecutor.execute {
      try {
        val baseDir = File(appContext.filesDir, "vosk_models")
        val targetModelDir = File(baseDir, MODEL_DIR_NAME)
        val modelConfFile = File(targetModelDir, "conf/model.conf")
        val needDownload = !modelConfFile.exists()

        if (needDownload) {
          Log.d(TAG, "Downloading Vosk RU model from $MODEL_URL...")
          mainHandler.post {
            Toast.makeText(
              appContext,
              "Загрузка офлайн-модели голоса (45 МБ)...",
              Toast.LENGTH_LONG
            ).show()
          }
          baseDir.mkdirs()
          val zipFile = File(baseDir, "model_download.zip")
          if (zipFile.exists()) {
            zipFile.delete()
          }

          val connection =
            (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
              connectTimeout = 30_000
              readTimeout = 90_000
              instanceFollowRedirects = true
            }

          connection.inputStream.use { input ->
            FileOutputStream(zipFile).use { output ->
              input.copyTo(output)
            }
          }

          ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
              val outFile = File(baseDir, entry.name)
              val canonicalBase = baseDir.canonicalPath
              val canonicalOut = outFile.canonicalPath
              if (!canonicalOut.startsWith(canonicalBase)) {
                throw SecurityException("Zip entry outside target dir: ${entry.name}")
              }
              if (entry.isDirectory) {
                outFile.mkdirs()
              } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                  zis.copyTo(fos)
                }
              }
              zis.closeEntry()
              entry = zis.nextEntry
            }
          }
          zipFile.delete()
        }

        // Обновляем параметры луча декодера Kaldi (beam=13.0, max-active=7000) для лучшей точности
        runCatching {
          modelConfFile.parentFile?.mkdirs()
          modelConfFile.writeText(TUNED_MODEL_CONF)
        }

        val loadedModel = Model(targetModelDir.absolutePath)
        model = loadedModel
        isLoading = false
        Log.d(TAG, "Vosk model loaded with tuned beam configuration!")

        if (needDownload) {
          mainHandler.post {
            Toast.makeText(
              appContext,
              "Голосовая модель готова к работе!",
              Toast.LENGTH_SHORT
            ).show()
          }
        }

        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        for (cb in callbacks) {
          runCatching { cb(loadedModel) }
        }
      } catch (e: Exception) {
        isLoading = false
        Log.e(TAG, "Failed to load Vosk model", e)
        mainHandler.post {
          Toast.makeText(
            appContext,
            "Ошибка загрузки голосовой модели: ${e.localizedMessage ?: "проверьте интернет"}",
            Toast.LENGTH_LONG
          ).show()
        }
      }
    }
  }
}
