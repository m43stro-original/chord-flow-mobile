package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

private const val TARGET_URL = "https://chords.m43st.ru"

/**
 * Suppresses the system start/stop earcon beep triggered by Android's SpeechRecognizer
 * when WebView's webkitSpeechRecognition continuously restarts in the background.
 */
class SpeechBeepSuppressor(context: Context) {
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val streamsToMute =
    intArrayOf(
      AudioManager.STREAM_MUSIC,
      AudioManager.STREAM_NOTIFICATION,
      AudioManager.STREAM_SYSTEM
    )

  private val mutedByUs = mutableSetOf<Int>()
  private var isSpeechLoopActive = false
  private var isTemporarilyUnmutedForStrum = false

  private val delayedDeactivateRunnable = Runnable {
    isSpeechLoopActive = false
    applyMuteState()
  }

  private val restoreAfterStrumRunnable = Runnable {
    isTemporarilyUnmutedForStrum = false
    applyMuteState()
  }

  @JavascriptInterface
  fun onSpeechStart() {
    mainHandler.removeCallbacks(delayedDeactivateRunnable)
    isSpeechLoopActive = true
    applyMuteState()
  }

  @JavascriptInterface
  fun onSpeechStopOrEnd() {
    // Keep streams muted across the 100ms–400ms auto-restart gap used by chords.m43st.ru.
    // Only unmute if SpeechRecognition stays stopped for more than 1800ms.
    mainHandler.removeCallbacks(delayedDeactivateRunnable)
    mainHandler.postDelayed(delayedDeactivateRunnable, 1800L)
  }

  @JavascriptInterface
  fun onChordStrum(durationMs: Long) {
    mainHandler.removeCallbacks(restoreAfterStrumRunnable)
    isTemporarilyUnmutedForStrum = true
    applyMuteState()
    mainHandler.postDelayed(restoreAfterStrumRunnable, durationMs.coerceIn(500L, 3000L))
  }

  private fun applyMuteState() {
    val shouldMute = isSpeechLoopActive && !isTemporarilyUnmutedForStrum
    if (shouldMute) {
      for (stream in streamsToMute) {
        runCatching {
          if (!audioManager.isStreamMute(stream)) {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            mutedByUs.add(stream)
          }
        }
      }
    } else {
      unmuteAll()
    }
  }

  fun release() {
    mainHandler.removeCallbacksAndMessages(null)
    isSpeechLoopActive = false
    isTemporarilyUnmutedForStrum = false
    unmuteAll()
  }

  private fun unmuteAll() {
    val iterator = mutedByUs.iterator()
    while (iterator.hasNext()) {
      val stream = iterator.next()
      runCatching {
        audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
      }
      iterator.remove()
    }
  }
}

private const val SPEECH_BEEP_SUPPRESSION_JS = """
(function() {
  if (window.__chordFlowBeepPatchInstalled) return;
  window.__chordFlowBeepPatchInstalled = true;

  var bridge = window.AndroidAudioBridge;
  if (!bridge) return;

  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (SR && SR.prototype) {
    var origStart = SR.prototype.start;
    var origStop = SR.prototype.stop;
    var origAbort = SR.prototype.abort;

    SR.prototype.start = function() {
      try { bridge.onSpeechStart(); } catch (e) {}
      this.addEventListener('end', function() {
        try { bridge.onSpeechStopOrEnd(); } catch (e) {}
      });
      this.addEventListener('error', function() {
        try { bridge.onSpeechStopOrEnd(); } catch (e) {}
      });
      return origStart.apply(this, arguments);
    };

    SR.prototype.stop = function() {
      try { bridge.onSpeechStopOrEnd(); } catch (e) {}
      return origStop.apply(this, arguments);
    };

    SR.prototype.abort = function() {
      try { bridge.onSpeechStopOrEnd(); } catch (e) {}
      return origAbort.apply(this, arguments);
    };
  }

  var AC = window.AudioContext || window.webkitAudioContext;
  if (AC && AC.prototype && AC.prototype.createOscillator) {
    var origCreateOscillator = AC.prototype.createOscillator;
    AC.prototype.createOscillator = function() {
      try { bridge.onChordStrum(1500); } catch (e) {}
      return origCreateOscillator.apply(this, arguments);
    };
  }
})();
"""

class MainActivity : ComponentActivity() {
  private var beepSuppressor: SpeechBeepSuppressor? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val suppressor = SpeechBeepSuppressor(applicationContext)
    beepSuppressor = suppressor
    enableEdgeToEdge()
    setContent {
      ChordFlowWebView(beepSuppressor = suppressor)
    }
  }

  override fun onPause() {
    super.onPause()
    beepSuppressor?.release()
  }

  override fun onDestroy() {
    beepSuppressor?.release()
    beepSuppressor = null
    super.onDestroy()
  }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun ChordFlowWebView(beepSuppressor: SpeechBeepSuppressor) {
  val context = LocalContext.current
  var pendingWebPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
  var canGoBack by remember { mutableStateOf(false) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
      pendingWebPermissionRequest?.let { request ->
        if (isGranted) {
          request.grant(request.resources)
        } else {
          request.deny()
        }
        pendingWebPermissionRequest = null
      }
    }

  LaunchedEffect(Unit) {
    val hasRecordAudio =
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
      ) == PackageManager.PERMISSION_GRANTED
    if (!hasRecordAudio) {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  BackHandler(enabled = canGoBack) {
    webViewRef?.let { webView ->
      if (webView.canGoBack()) {
        webView.goBack()
      }
    }
  }

  AndroidView(
    modifier =
      Modifier.fillMaxSize()
        .background(Color(0xFF09090B))
        .windowInsetsPadding(WindowInsets.systemBars)
        .testTag("chordflow_webview"),
    factory = { ctx ->
      WebView(ctx).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        setBackgroundColor(android.graphics.Color.parseColor("#09090B"))

        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          databaseEnabled = true
          mediaPlaybackRequiresUserGesture = false
          allowContentAccess = true
          loadsImagesAutomatically = true
          mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
          cacheMode = WebSettings.LOAD_DEFAULT
          setSupportZoom(false)
        }

        addJavascriptInterface(beepSuppressor, "AndroidAudioBridge")

        webViewClient =
          object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
            ): Boolean {
              return false
            }

            override fun onPageStarted(
              view: WebView?,
              url: String?,
              favicon: android.graphics.Bitmap?
            ) {
              super.onPageStarted(view, url, favicon)
              view?.evaluateJavascript(SPEECH_BEEP_SUPPRESSION_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              view?.evaluateJavascript(SPEECH_BEEP_SUPPRESSION_JS, null)
            }

            override fun doUpdateVisitedHistory(
              view: WebView?,
              url: String?,
              isReload: Boolean
            ) {
              super.doUpdateVisitedHistory(view, url, isReload)
              canGoBack = view?.canGoBack() == true
            }
          }

        webChromeClient =
          object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
              super.onProgressChanged(view, newProgress)
              if (newProgress >= 20) {
                view?.evaluateJavascript(SPEECH_BEEP_SUPPRESSION_JS, null)
              }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              if (request == null) return
              val wantsAudio =
                request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
              if (wantsAudio) {
                val granted =
                  ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.RECORD_AUDIO
                  ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                  request.grant(request.resources)
                } else {
                  pendingWebPermissionRequest = request
                  permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
              } else {
                request.grant(request.resources)
              }
            }
          }

        loadUrl(TARGET_URL)
        webViewRef = this
      }
    },
    update = { webView ->
      webViewRef = webView
    }
  )

  DisposableEffect(Unit) {
    onDispose {
      beepSuppressor.release()
      webViewRef?.destroy()
      webViewRef = null
    }
  }
}
