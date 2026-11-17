package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

private const val TARGET_URL = "https://chords.m43st.ru"

fun isNetworkAvailable(context: Context): Boolean {
  return runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }.getOrDefault(true)
}

class ChordFlowJsBridge(context: Context) {
  private val prefs = context.getSharedPreferences("chordflow_offline", Context.MODE_PRIVATE)

  @JavascriptInterface
  fun onFavoritesUpdated(favoritesJson: String) {
    // Сохраняем полный JSON избранных песен (уже содержит rawText, preferredTranspose, preferredTempoBpm)
    prefs.edit().putString("favorites_backup_json", favoritesJson).apply()
  }

  @JavascriptInterface
  fun getSavedFavorites(): String {
    return prefs.getString("favorites_backup_json", "[]") ?: "[]"
  }

  fun getBackupJsonOrNull(): String? {
    return prefs.getString("favorites_backup_json", null)
  }
}

/**
 * Injected at DOCUMENT_START:
 * 1. Polyfills `SpeechRecognition` and `webkitSpeechRecognition` before React mounts.
 * 2. Upgrades `LyricSpeechTracker.processCandidatePhrases` on the page so that:
 *    - It never jumps across verses or instrumental sections on random/single fuzzy words.
 *    - Already-matched words in an utterance are never re-processed to cause multi-line chain jumps.
 *    - It remembers the previous 2 lyric lines (`s - 1`, `s - 2`, and last 2 visited lines) without
 *      any 14-second cooldown lockout, and returns back to them when the user sings exact matching
 *      words from those lines.
 */
private const val VOSK_WEBVIEW_POLYFILL_JS = """
(function() {
  if (window.__chordFlowVoskPolyfillInstalled) return;
  window.__chordFlowVoskPolyfillInstalled = true;

  window.__chordFlowActiveNodes = window.__chordFlowActiveNodes || [];
  var activeInstance = null;
  var polyfillSpeechEnabled = false;
  var lastVocabSignature = '';

  var STOPWORDS = new Set(
    'и.в.во.не.что.он.на.я.с.со.как.а.то.все.она.так.его.но.да.ты.к.у.же.вы.за.бы.по.только.ее.мне.было.вот.от.меня.еще.нет.о.из.ему.теперь.когда.даже.ну.вдруг.ли.если.уже.или.ни.быть.был.него.до.вас.нибудь.опять.уж.вам.ведь.там.потом.себя.ничего.ей.может.они.тут.где.есть.надо.ней.для.мы.тебя.их.чем.была.сам.чтоб.без.будто.чего.раз.тоже.себе.под.будет.ж.тогда.кто.этот.того.потому.этого.какой.совсем.ним.здесь.этом.один.почти.мой.тем.чтобы.нее.сейчас.были.куда.зачем.всех.никогда.можно.the.and.to.of.in.is.it.you.that.he.was.for.on.are.with.as.his.they.be.at.one.have.this.from.or.had.by.not.but.what.all.were.we.when.your.can.said.there.use.an.each.which.she.do.how.their.if.will.up.other.about.out.many.then.them.these.so.some.her.would.make.like.him.into.my.me.oh.yeah.ah.ooh.na.la.its.im'.split('.')
  );

  function normalizeText(str) {
    return String(str || '')
      .toLowerCase()
      .replace(/ё/g, 'е')
      .replace(/[^a-zа-я0-9\s]/gi, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  function tokenize(str) {
    var clean = normalizeText(str);
    return clean ? clean.split(' ').filter(function(w) { return w.length >= 2; }) : [];
  }

  function wordSimilarity(a, b) {
    if (a === b) return 1.0;
    if (a.length < 3 || b.length < 3) return 0;
    var minLen = Math.min(a.length, b.length);
    var maxLen = Math.max(a.length, b.length);
    if (Math.abs(a.length - b.length) > 2) return 0;
    if (minLen >= 4 && a.slice(0, 4) === b.slice(0, 4)) {
      var ratio = minLen / maxLen;
      if (ratio >= 0.72) return 0.84 + ratio * 0.12;
    }
    var rows = a.length + 1;
    var cols = b.length + 1;
    var dp = [];
    for (var i = 0; i < rows; i++) {
      dp[i] = [i];
    }
    for (var j = 0; j < cols; j++) {
      dp[0][j] = j;
    }
    for (var r = 1; r < rows; r++) {
      for (var c = 1; c < cols; c++) {
        var cost = a[r - 1] === b[c - 1] ? 0 : 1;
        dp[r][c] = Math.min(dp[r - 1][c] + 1, dp[r][c - 1] + 1, dp[r - 1][c - 1] + cost);
      }
    }
    return 1.0 - dp[a.length][b.length] / maxLen;
  }

  function findLyricTrackerOnFiber() {
    try {
      var rootEl = document.getElementById('root');
      if (!rootEl) return null;
      var keys = Object.keys(rootEl);
      var containerKey = null;
      for (var i = 0; i < keys.length; i++) {
        if (keys[i].indexOf('__reactContainer') === 0 || keys[i].indexOf('_reactRootContainer') === 0) {
          containerKey = keys[i];
          break;
        }
      }
      if (!containerKey || !rootEl[containerKey]) return null;
      var rootFiber = rootEl[containerKey].current || rootEl[containerKey];
      var queue = [rootFiber];
      var visited = new Set();
      while (queue.length > 0 && visited.size < 80) {
        var fiber = queue.shift();
        if (!fiber || visited.has(fiber)) continue;
        visited.add(fiber);

        for (var hook = fiber.memoizedState; hook; hook = hook.next) {
          var ms = hook.memoizedState;
          if (ms && typeof ms === 'object' && ms.current && typeof ms.current === 'object') {
            var refObj = ms.current;
            if (Array.isArray(refObj.indexedLines) && typeof refObj.processCandidatePhrases === 'function') {
              return refObj;
            }
          }
        }
        if (fiber.child) queue.push(fiber.child);
        if (fiber.sibling) queue.push(fiber.sibling);
        if (fiber.alternate) queue.push(fiber.alternate);
      }
    } catch (e) {}
    return null;
  }

  function patchTrackerInstance(tracker) {
    if (!tracker || tracker.__chordFlowAccuratePatchInstalled) return;
    tracker.__chordFlowAccuratePatchInstalled = true;

    tracker.__spokenHistory = [];
    tracker.__recentLinesHistory = [];
    tracker.__lastPartialTokens = [];

    var origReset = tracker.resetHistory;
    if (typeof origReset === 'function') {
      tracker.resetHistory = function() {
        this.__spokenHistory = [];
        this.__recentLinesHistory = [];
        this.__lastPartialTokens = [];
        return origReset.apply(this, arguments);
      };
    }

    tracker.processCandidatePhrases = function(phrases, isFinalFlag) {
      if (!this.getCurrentChordIndex || !this.indexedLines || this.indexedLines.length === 0) return;
      var now = performance.now();
      var currentChordIdx = this.getCurrentChordIndex();
      var currentLinePos = this.findLinePosForChord(currentChordIdx);

      if (!this.__recentLinesHistory) this.__recentLinesHistory = [];
      if (this.__recentLinesHistory[this.__recentLinesHistory.length - 1] !== currentLinePos) {
        this.__recentLinesHistory = this.__recentLinesHistory.filter(function(lp) { return lp !== currentLinePos; });
        this.__recentLinesHistory.push(currentLinePos);
        if (this.__recentLinesHistory.length > 4) {
          this.__recentLinesHistory.shift();
        }
      }

      var primaryTokens = tokenize(phrases[0]);
      if (primaryTokens.length === 0) return;

      var prevTokens = this.__lastPartialTokens || [];
      var commonPrefix = 0;
      while (
        commonPrefix < prevTokens.length &&
        commonPrefix < primaryTokens.length &&
        prevTokens[commonPrefix] === primaryTokens[commonPrefix]
      ) {
        commonPrefix++;
      }

      var newTokens = [];
      if (commonPrefix >= prevTokens.length - 1) {
        newTokens = primaryTokens.slice(commonPrefix);
      } else {
        newTokens = primaryTokens.slice(-3);
      }
      this.__lastPartialTokens = isFinalFlag ? [] : primaryTokens.slice();

      if (!this.__spokenHistory) this.__spokenHistory = [];
      for (var ti = 0; ti < newTokens.length; ti++) {
        this.__spokenHistory.push({ word: newTokens[ti], time: now, consumed: false });
      }
      this.__spokenHistory = this.__spokenHistory.filter(function(item) {
        return now - item.time <= 4500;
      });

      var unconsumed = this.__spokenHistory
        .filter(function(item) { return !item.consumed; })
        .slice(-5)
        .map(function(item) { return item.word; });

      if (unconsumed.length > 0 && this.onTranscriptCallback) {
        this.onTranscriptCallback(unconsumed.slice(-4).join(' '));
      }

      if (unconsumed.length === 0 || (now - this.lastMatchTime) < 650) return;

      var isOnInstrumental = this.instrumentalChordIndices && this.instrumentalChordIndices.has(currentChordIdx);
      var currentLine = this.indexedLines[currentLinePos];

      if (isOnInstrumental && currentLine && currentLine.firstChordIndex > currentChordIdx) {
        var bestInstSim = 0;
        var bestInstWord = '';
        var instExactCount = 0;
        var targetLineTokens = currentLine.fullTokens.slice(0, 6);
        for (var uIdx = 0; uIdx < unconsumed.length; uIdx++) {
          var uw = unconsumed[uIdx];
          if (uw.length < 3) continue;
          for (var ltIdx = 0; ltIdx < targetLineTokens.length; ltIdx++) {
            var sim = wordSimilarity(uw, targetLineTokens[ltIdx]);
            if (sim > bestInstSim) {
              bestInstSim = sim;
              bestInstWord = targetLineTokens[ltIdx];
            }
            if (sim >= 0.88 && !STOPWORDS.has(targetLineTokens[ltIdx])) {
              instExactCount++;
            }
          }
        }
        if (instExactCount >= 2 || (instExactCount >= 1 && bestInstSim >= 0.92 && bestInstWord.length >= 4)) {
          for (var k = 0; k < this.__spokenHistory.length; k++) {
            this.__spokenHistory[k].consumed = true;
          }
          this.triggerMatch(
            now,
            currentChordIdx,
            currentLine.firstChordIndex,
            currentLine.lineId,
            unconsumed.join(' '),
            bestInstWord,
            0.90,
            true
          );
        }
        return;
      }

      var currentAndNextWords = new Set();
      if (currentLine) {
        currentLine.fullTokens.forEach(function(w) { currentAndNextWords.add(w); });
      }
      var nextLine = (currentLinePos + 1 < this.indexedLines.length) ? this.indexedLines[currentLinePos + 1] : null;
      if (nextLine) {
        nextLine.fullTokens.forEach(function(w) { currentAndNextWords.add(w); });
      }

      var currentSegmentWords = new Set();
      if (currentLine) {
        for (var si = 0; si < currentLine.segments.length; si++) {
          if (currentLine.segments[si].chordIndex === currentChordIdx) {
            currentLine.segments[si].tokens.forEach(function(w) { currentSegmentWords.add(w); });
          }
        }
      }

      var forwardLines = [];
      if (currentLine) forwardLines.push(currentLine);
      if (nextLine) forwardLines.push(nextLine);

      var bestForwardScore = 0;
      var bestForwardChord = -1;
      var bestForwardLineId = '';
      var bestForwardWord = '';

      for (var fl = 0; fl < forwardLines.length; fl++) {
        var fLine = forwardLines[fl];
        var isNextLine = fLine.lineIndex > currentLinePos;

        var hasInstBetween = false;
        if (isNextLine && this.instrumentalChordIndices) {
          for (var ci = currentChordIdx + 1; ci < fLine.firstChordIndex; ci++) {
            if (this.instrumentalChordIndices.has(ci)) {
              hasInstBetween = true;
              break;
            }
          }
        }

        for (var segIdx = 0; segIdx < fLine.segments.length; segIdx++) {
          var seg = fLine.segments[segIdx];
          if (seg.chordIndex <= currentChordIdx) continue;
          var chordDelta = seg.chordIndex - currentChordIdx;
          if (!hasInstBetween && chordDelta > 4) continue;
          if (hasInstBetween && chordDelta > 6) continue;

          var matchedTokensCount = 0;
          var exactContentCount = 0;
          var maxWeightedSim = 0;
          var matchedWord = '';

          for (var st = 0; st < seg.tokens.length; st++) {
            var segToken = seg.tokens[st];
            if (segToken.length < 3 || currentSegmentWords.has(segToken)) continue;

            var bestSim = 0;
            for (var ui = 0; ui < unconsumed.length; ui++) {
              var sVal = wordSimilarity(unconsumed[ui], segToken);
              if (sVal > bestSim) bestSim = sVal;
            }

            if (bestSim >= 0.84) {
              matchedTokensCount++;
              matchedWord = segToken;
              if (!STOPWORDS.has(segToken)) {
                if (bestSim >= 0.90) exactContentCount++;
                var weight = segToken.length >= 5 ? 1.12 : 1.02;
                maxWeightedSim = Math.max(maxWeightedSim, bestSim * weight);
              }
            }
          }

          var score = 0;
          if (chordDelta === 1 && !hasInstBetween) {
            if (matchedTokensCount >= 2 && maxWeightedSim >= 0.86) {
              score = 0.92;
            } else if (exactContentCount >= 1 && maxWeightedSim >= 0.92 && matchedWord.length >= 4) {
              score = 0.86;
            }
          } else {
            if (matchedTokensCount >= 2 && exactContentCount >= 1 && maxWeightedSim >= 0.90) {
              score = 0.89;
            } else if (exactContentCount >= 2 && maxWeightedSim >= 0.94) {
              score = 0.93;
            }
          }

          if (score > bestForwardScore) {
            bestForwardScore = score;
            bestForwardChord = seg.chordIndex;
            bestForwardLineId = fLine.lineId;
            bestForwardWord = matchedWord;
          }
        }
      }

      if (bestForwardScore >= 0.85 && bestForwardChord > currentChordIdx) {
        for (var m = 0; m < this.__spokenHistory.length; m++) {
          this.__spokenHistory[m].consumed = true;
        }
        this.triggerMatch(
          now,
          currentChordIdx,
          bestForwardChord,
          bestForwardLineId,
          unconsumed.join(' '),
          bestForwardWord,
          bestForwardScore,
          false
        );
        return;
      }

      var backwardLineIndices = [];
      if (currentLinePos - 1 >= 0) backwardLineIndices.push(currentLinePos - 1);
      if (currentLinePos - 2 >= 0) backwardLineIndices.push(currentLinePos - 2);
      for (var rh = this.__recentLinesHistory.length - 1; rh >= 0; rh--) {
        var rLinePos = this.__recentLinesHistory[rh];
        if (rLinePos !== currentLinePos && backwardLineIndices.indexOf(rLinePos) === -1 && Math.abs(rLinePos - currentLinePos) <= 3) {
          backwardLineIndices.push(rLinePos);
        }
      }

      var bestBackChord = -1;
      var bestBackLineId = '';
      var bestBackWord = '';
      var bestBackExactCount = 0;

      for (var bl = 0; bl < backwardLineIndices.length; bl++) {
        var bLine = this.indexedLines[backwardLineIndices[bl]];
        if (!bLine) continue;

        var lineExactMatches = [];
        for (var u2 = 0; u2 < unconsumed.length; u2++) {
          var spokenW = unconsumed[u2];
          if (spokenW.length < 3 || STOPWORDS.has(spokenW)) continue;
          if (bLine.fullTokens.indexOf(spokenW) !== -1 && !currentSegmentWords.has(spokenW)) {
            if (lineExactMatches.indexOf(spokenW) === -1) {
              lineExactMatches.push(spokenW);
            }
          }
        }

        var isStrongUniqueSingle =
          lineExactMatches.length === 1 &&
          lineExactMatches[0].length >= 5 &&
          !currentAndNextWords.has(lineExactMatches[0]);

        if (lineExactMatches.length >= 2 || isStrongUniqueSingle) {
          var targetWord = lineExactMatches[lineExactMatches.length - 1];
          var targetChord = bLine.firstChordIndex;
          for (var bs = 0; bs < bLine.segments.length; bs++) {
            if (bLine.segments[bs].tokens.indexOf(targetWord) !== -1) {
              targetChord = bLine.segments[bs].chordIndex;
              break;
            }
          }
          if (targetChord !== currentChordIdx && lineExactMatches.length > bestBackExactCount) {
            bestBackExactCount = lineExactMatches.length;
            bestBackChord = targetChord;
            bestBackLineId = bLine.lineId;
            bestBackWord = targetWord;
          }
        }
      }

      if (bestBackChord >= 0 && bestBackChord !== currentChordIdx) {
        for (var bIdx = 0; bIdx < this.__spokenHistory.length; bIdx++) {
          this.__spokenHistory[bIdx].consumed = true;
        }
        this.triggerMatch(
          now,
          currentChordIdx,
          bestBackChord,
          bestBackLineId,
          unconsumed.join(' '),
          bestBackWord,
          0.92,
          false
        );
      }
    };
  }

  var currentSpeechHandler = null;
  try {
    Object.defineProperty(window, '__onAndroidSpeechResult', {
      configurable: true,
      enumerable: true,
      get: function() {
        return currentSpeechHandler;
      },
      set: function(fn) {
        if (typeof fn !== 'function') {
          currentSpeechHandler = fn;
          return;
        }
        currentSpeechHandler = function(data, isFinal) {
          var tracker = findLyricTrackerOnFiber();
          if (tracker) {
            patchTrackerInstance(tracker);
            if (!tracker.shouldStayActive) return;
            tracker.lastResultTime = performance.now();
            var phrases = [];
            if (typeof data === 'string') {
              phrases = [data];
            } else if (Array.isArray(data)) {
              phrases = data;
            } else if (data && Array.isArray(data.alternatives)) {
              phrases = data.alternatives.map(function(a) { return a.text || ''; }).filter(Boolean);
            }
            if (phrases.length > 0) {
              tracker.processCandidatePhrases(phrases, !!isFinal);
            }
            return;
          }
          return fn(data, isFinal);
        };
      }
    });
  } catch (e) {}

  function extractAndSyncSongVocabulary(lang) {
    try {
      var bridge = window.AndroidAudioBridge;
      if (!bridge || typeof bridge.setPolyfillSongVocabulary !== 'function') return;

      var raw = '';
      try {
        var histStr = window.localStorage && window.localStorage.getItem('chordflow_history_v1');
        if (histStr) {
          var hist = JSON.parse(histStr);
          if (Array.isArray(hist) && hist.length > 0 && hist[0] && hist[0].rawText) {
            raw += ' ' + hist[0].rawText;
          }
        }
      } catch (e) {}

      var mainEl = document.querySelector('main');
      if (mainEl && mainEl.innerText) {
        raw += ' ' + mainEl.innerText;
      }

      if (!raw.trim()) return;
      var tokens = tokenize(raw);
      var unique = [];
      var seen = {};
      for (var i = 0; i < tokens.length; i++) {
        var w = tokens[i];
        if (w.length >= 3 && /[а-яa-z]/i.test(w) && !seen[w]) {
          seen[w] = true;
          unique.push(w);
        }
      }
      if (unique.length > 5) {
        var sig = unique.slice(0, 30).join(',') + ':' + unique.length;
        if (sig !== lastVocabSignature) {
          lastVocabSignature = sig;
          bridge.setPolyfillSongVocabulary(JSON.stringify(unique), lang || 'ru-RU');
        }
      }
    } catch (err) {}
  }

  function AndroidSpeechRecognition() {
    this.lang = 'ru-RU';
    this.continuous = true;
    this.interimResults = true;
    this.maxAlternatives = 3;
    this.onstart = null;
    this.onresult = null;
    this.onerror = null;
    this.onend = null;
  }

  AndroidSpeechRecognition.prototype.start = function() {
    activeInstance = this;
    polyfillSpeechEnabled = true;
    var lang = this.lang || 'ru-RU';
    extractAndSyncSongVocabulary(lang);
    setTimeout(function() { extractAndSyncSongVocabulary(lang); }, 500);
    try {
      if (window.AndroidAudioBridge) {
        window.AndroidAudioBridge.startNativeSpeech(lang);
      }
    } catch (e) {}
    var self = this;
    setTimeout(function() {
      if (typeof self.onstart === 'function') self.onstart();
    }, 15);
  };

  AndroidSpeechRecognition.prototype.stop = function() {
    if (activeInstance === this) activeInstance = null;
    polyfillSpeechEnabled = false;
    try {
      if (window.AndroidAudioBridge) {
        window.AndroidAudioBridge.stopNativeSpeech();
      }
    } catch (e) {}
    if (typeof this.onend === 'function') this.onend();
  };

  AndroidSpeechRecognition.prototype.abort = function() {
    if (activeInstance === this) activeInstance = null;
    polyfillSpeechEnabled = false;
    try {
      if (window.AndroidAudioBridge) {
        window.AndroidAudioBridge.stopNativeSpeech();
      }
    } catch (e) {}
  };

  window.__dispatchPolyfillSpeechResult = function(textOrPhrases, isFinal) {
    var tracker = findLyricTrackerOnFiber();
    if (tracker) {
      patchTrackerInstance(tracker);
    }
    if (!activeInstance || typeof activeInstance.onresult !== 'function') return;
    var arr = Array.isArray(textOrPhrases) ? textOrPhrases : [String(textOrPhrases || '')];
    var item = arr.map(function(p) { return { transcript: p, confidence: 0.95 }; });
    item.isFinal = !!isFinal;
    var fakeEvent = {
      resultIndex: 0,
      results: [item]
    };
    activeInstance.onresult(fakeEvent);
  };

  window.SpeechRecognition = AndroidSpeechRecognition;
  window.webkitSpeechRecognition = AndroidSpeechRecognition;
})();
"""

class MainActivity : ComponentActivity() {
  private var audioBridge: AndroidAudioBridge? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // Гарантированно включаем звук, если прошлая сборка оставила потоки в состоянии mute
    runCatching {
      val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
      intArrayOf(
        android.media.AudioManager.STREAM_MUSIC,
        android.media.AudioManager.STREAM_NOTIFICATION,
        android.media.AudioManager.STREAM_SYSTEM
      ).forEach { stream ->
        audioManager.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0)
      }
    }
    val bridge = AndroidAudioBridge(applicationContext)
    val offlineBridge = ChordFlowJsBridge(applicationContext)
    audioBridge = bridge
    enableEdgeToEdge()
    setContent {
      ChordFlowWebView(bridge = bridge, offlineBridge = offlineBridge)
    }
  }

  override fun onDestroy() {
    audioBridge?.release()
    audioBridge = null
    super.onDestroy()
  }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "RequiresFeature")
@Composable
fun ChordFlowWebView(bridge: AndroidAudioBridge, offlineBridge: ChordFlowJsBridge) {
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
      val online = isNetworkAvailable(ctx)

      runCatching {
        val swSettings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
        swSettings.allowContentAccess = true
        swSettings.cacheMode =
          if (online) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK
      }

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
          cacheMode =
            if (online) {
              WebSettings.LOAD_DEFAULT
            } else {
              WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
          setSupportZoom(false)
        }

        bridge.webView = this
        addJavascriptInterface(bridge, "AndroidAudioBridge")
        addJavascriptInterface(offlineBridge, "AndroidBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
          runCatching {
            WebViewCompat.addDocumentStartJavaScript(
              this,
              VOSK_WEBVIEW_POLYFILL_JS,
              setOf("*")
            )
          }
        }

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
              view?.evaluateJavascript(VOSK_WEBVIEW_POLYFILL_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              view?.evaluateJavascript(VOSK_WEBVIEW_POLYFILL_JS, null)
              val backupJson = offlineBridge.getBackupJsonOrNull()
              if (!backupJson.isNullOrBlank() && backupJson != "[]") {
                val escaped = JSONObject.quote(backupJson)
                view?.evaluateJavascript(
                  "window.ChordFlowOffline && window.ChordFlowOffline.importFavoritesJson($escaped);",
                  null
                )
              }
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
              if (newProgress >= 15) {
                view?.evaluateJavascript(VOSK_WEBVIEW_POLYFILL_JS, null)
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
      bridge.webView = webView
      webViewRef = webView
    }
  )

  DisposableEffect(Unit) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val networkCallback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          webViewRef?.post {
            webViewRef?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
            runCatching {
              ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode =
                WebSettings.LOAD_DEFAULT
            }
          }
        }

        override fun onLost(network: Network) {
          webViewRef?.post {
            webViewRef?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            runCatching {
              ServiceWorkerController.getInstance().serviceWorkerWebSettings.cacheMode =
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
          }
        }
      }
    runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }

    onDispose {
      runCatching { cm?.unregisterNetworkCallback(networkCallback) }
      bridge.release()
      bridge.webView = null
      webViewRef?.destroy()
      webViewRef = null
    }
  }
}
