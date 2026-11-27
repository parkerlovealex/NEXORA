package com.nexora.browser.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nexora.browser.R
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.fp.FingerprintScript
import com.nexora.browser.util.UiFeedback
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * Runs a local-only compatibility probe inside the selected profile's Android WebView.
 *
 * This screen never navigates to a public diagnostic site and does not transmit
 * fingerprint measurements. The same-origin iframe is served locally; the worker
 * source is bundled into a Blob and does not require a network request.
 */
class CompatibilityLabActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var probeWebView: WebView? = null
    private var runStartedAt = 0L
    private var activeRun = 0
    private var pollRunnable: Runnable? = null

    private lateinit var profile: BrowserProfile
    private var preset: DevicePreset? = null
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button
    private lateinit var webViewHost: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val loadedProfile = readLocalStore { ProfileStore(this).getById(profileId) }.getOrElse {
            setContentView(buildMessageView(it.message.orEmpty()))
            return
        }
        if (loadedProfile == null) {
            setContentView(buildMessageView("Profile not found. Return to NEXORA and open Compatibility Lab from an active profile."))
            return
        }
        profile = loadedProfile
        preset = DeviceDb.find(this, profile.deviceId)

        setContentView(buildLayout())
        UiFeedback.install(window.decorView)
        showDeviceSummary()
        runDiagnostics()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_bg))
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(RED)
            setPadding(dp(4), 0, dp(16), 0)
            setOnClickListener { finish() }
            contentDescription = "Back"
        }
        val title = TextView(this).apply {
            text = "Compatibility Lab"
            textSize = 21f
            setTextColor(DARK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val subtitle = TextView(this).apply {
            text = "Local Android WebView checks · no external diagnostic sites"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(subtitle)

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(DARK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_surface))
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        runButton = Button(this).apply {
            text = "Run device checks again"
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(RED)
            setOnClickListener { runDiagnostics() }
        }
        root.addView(runButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(10) })

        val scroll = ScrollView(this)
        resultText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(DARK)
            setPadding(dp(14), dp(14), dp(14), dp(18))
            setBackgroundColor(ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_surface))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Preparing local checks…"
        }
        scroll.addView(resultText)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Keep the probe WebView attached and laid out so its document and workers run.
        webViewHost = FrameLayout(this)
        root.addView(webViewHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
        ))
        return root
    }

    private fun buildMessageView(message: String): View =
        TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(DARK)
            setPadding(dp(24), dp(32), dp(24), dp(32))
            setBackgroundColor(ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_surface))
        }

    private fun showDeviceSummary() {
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }.getOrNull()
        val providerName = provider?.packageName ?: "Unavailable"
        val providerVersion = provider?.versionName ?: "Unknown"
        val feature = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        val multiProfile = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        }.getOrDefault(false)
        val proxyOverride = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        }.getOrDefault(false)
        statusText.text = buildString {
            append("PROFILE  ").append(profile.name).append('\n')
            append("DEVICE   ").append(preset?.let { "${it.brand} ${it.model}" } ?: "Preset unavailable").append('\n')
            append("ANDROID  ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("WEBVIEW  ").append(providerName).append(" · ").append(providerVersion).append('\n')
            append("DOC START FEATURE  ").append(if (feature) "SUPPORTED" else "NOT SUPPORTED").append('\n')
            append("MULTI-PROFILE FEATURE  ").append(if (multiProfile) "SUPPORTED" else "NOT SUPPORTED").append('\n')
            append("PROXY OVERRIDE FEATURE  ").append(if (proxyOverride) "SUPPORTED" else "NOT SUPPORTED")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runDiagnostics() {
        activeRun += 1
        val runId = activeRun
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        destroyProbeWebView()

        runButton.isEnabled = false
        runButton.text = "Running local checks…"
        resultText.text = "Creating an isolated probe WebView…"

        val webView = WebView(this)
        probeWebView = webView

        var profileAttached = false
        try {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(profile.webViewProfileKey)
            WebViewCompat.setProfile(webView, profile.webViewProfileKey)
            profileAttached = true
        } catch (error: Exception) {
            resultText.text = "PROFILE CONTEXT FAILED\n${error.message ?: error.javaClass.simpleName}\n\nNo fallback to shared/default storage was attempted."
        }

        if (!profileAttached) {
            finishRun()
            return
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = intent.getStringExtra(EXTRA_USER_AGENT)
                ?.takeIf { it.isNotBlank() }
                ?: profile.userAgent.ifBlank { WebSettings.getDefaultUserAgent(this@CompatibilityLabActivity) }
        }

        val documentStartFeature = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        var documentStartInstalled = false
        var injectionError = ""
        val expectedJson = preset?.let {
            FingerprintScript.diagnosticsContextJson(
                profile, it, webView.settings.userAgentString, documentStartFeature
            )
        } ?: JSONObject().put("expectedUserAgent", webView.settings.userAgentString).toString()

        val profileScript = preset?.let { FingerprintScript.build(profile, it) }.orEmpty()
        val markerScript = """
            window.__nexoraAlpha10DocumentStart = true;
            window.__nexoraAlpha10Expected = $expectedJson;
        """.trimIndent()
        if (documentStartFeature) {
            try {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    profileScript + "\n" + markerScript,
                    setOf("*")
                )
                documentStartInstalled = true
            } catch (error: Exception) {
                injectionError = error.message ?: error.javaClass.simpleName
            }
        }

        val expectedForPage = expectedJson
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
        val expectedTag = "<script>window.__nexoraAlpha10Expected = $expectedForPage;</script>"
        val workerSource = assets.open("alpha10-worker.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val workerTag = "<script>window.__nexoraAlpha10WorkerSource = ${JSONObject.quote(workerSource)};</script>"
        val html = assets.open("alpha10-probe.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("<!-- NEXORA_ALPHA10_WORKER_SOURCE -->", workerTag)
            .replace("<!-- NEXORA_ALPHA10_EXPECTED -->", expectedTag)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                return when {
                    uri.host == PROBE_HOST && uri.path == "/alpha10-frame.html" ->
                        localResponse("text/html", "alpha10-frame.html")
                    uri.scheme == "https" || uri.scheme == "http" ->
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    else -> null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (runId != activeRun) return
                runStartedAt = SystemClock.elapsedRealtime()
                pollForReport(runId, documentStartFeature, documentStartInstalled, injectionError)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true && runId == activeRun) {
                    resultText.text = "LOCAL PROBE LOAD FAILED\n${error?.description ?: "Unknown WebView error"}"
                    finishRun()
                }
            }
        }

        webViewHost.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
        ))
        webView.loadDataWithBaseURL(PROBE_BASE, html, "text/html", "UTF-8", null)

        // Record feature/injection state before page callbacks arrive.
        resultText.text = buildString {
            append("PROFILE STORAGE  PASS\n")
            append("DOCUMENT START FEATURE  ").append(if (documentStartFeature) "SUPPORTED" else "UNSUPPORTED").append('\n')
            append("DOCUMENT START REGISTRATION  ")
                .append(if (documentStartInstalled) "REGISTERED" else "NOT REGISTERED")
            if (injectionError.isNotBlank()) append("\nREGISTRATION ERROR  ").append(injectionError)
            append("\n\nRunning page, iframe, worker, canvas, WebGL, audio and UA-CH probes…")
        }
    }

    private fun localResponse(mimeType: String, assetName: String): WebResourceResponse {
        val bytes = assets.open(assetName).use { it.readBytes() }
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(bytes)
        )
    }

    private fun pollForReport(
        runId: Int,
        documentStartFeature: Boolean,
        documentStartInstalled: Boolean,
        injectionError: String
    ) {
        val webView = probeWebView ?: return
        if (runId != activeRun || isFinishing || isDestroyed) return
        webView.evaluateJavascript(
            "window.__nexoraAlpha10Ready === true ? JSON.stringify(window.__nexoraAlpha10Report || {}) : null"
        ) { value ->
            if (runId != activeRun) return@evaluateJavascript
            if (value != null && value != "null") {
                val report = runCatching {
                    val decoded = JSONTokener(value).nextValue()
                    JSONObject(decoded as? String ?: decoded.toString())
                }.getOrNull()
                if (report != null) {
                    renderReport(report, documentStartFeature, documentStartInstalled, injectionError)
                    finishRun()
                    return@evaluateJavascript
                }
            }
            if (SystemClock.elapsedRealtime() - runStartedAt > REPORT_TIMEOUT_MS) {
                resultText.text = "PROBE TIMEOUT\nThe local page did not finish its worker/frame checks within ${REPORT_TIMEOUT_MS / 1000} seconds."
                finishRun()
            } else {
                val next = Runnable {
                    pollForReport(runId, documentStartFeature, documentStartInstalled, injectionError)
                }
                pollRunnable = next
                mainHandler.postDelayed(next, POLL_INTERVAL_MS)
            }
        }
    }

    private fun renderReport(
        report: JSONObject,
        documentStartFeature: Boolean,
        documentStartInstalled: Boolean,
        injectionError: String
    ) {
        val consistency = report.optJSONObject("profileConsistency") ?: JSONObject()
        val canvas = report.optJSONObject("canvas") ?: JSONObject()
        val webgl = report.optJSONObject("webgl") ?: JSONObject()
        val audio = report.optJSONObject("audio") ?: JSONObject()
        val uaHints = report.optJSONObject("uaHints") ?: JSONObject()
        val iframe = report.optJSONObject("iframe") ?: JSONObject()
        val worker = report.optJSONObject("worker") ?: JSONObject()

        val lines = mutableListOf<String>()
        lines += "NEXORA ALPHA10 · DEVICE REPORT"
        lines += "PROFILE  ${profile.name}"
        lines += "WEBVIEW  ${WebViewCompat.getCurrentWebViewPackage(this)?.packageName ?: "Unknown"}"
        lines += "DOCUMENT START FEATURE  ${if (documentStartFeature) "SUPPORTED" else "UNSUPPORTED"}"
        lines += "DOCUMENT START REGISTRATION  ${if (documentStartInstalled) "REGISTERED" else "NOT REGISTERED"}"
        val pageMarkerStatus = when {
            !documentStartFeature || !documentStartInstalled -> "NOT VERIFIED"
            report.optBoolean("pageDocumentStart") -> "PASS"
            else -> "FAIL"
        }
        lines += "PAGE FIRST-SCRIPT MARKER  $pageMarkerStatus"
        if (injectionError.isNotBlank()) lines += "INJECTION ERROR  $injectionError"
        lines += ""
        lines += "PROFILE CONSISTENCY"
        listOf(
            "userAgent" to "User-Agent",
            "platform" to "Platform",
            "language" to "Language",
            "cores" to "CPU cores",
            "memory" to "Approx. RAM bucket",
            "screen" to "Screen size",
            "dpr" to "Device pixel ratio"
        ).forEach { (key, label) ->
            val result = when {
                !consistency.has(key) || consistency.isNull(key) -> "NOT MEASURED"
                consistency.optBoolean(key) -> "PASS"
                else -> "MISMATCH"
            }
            lines += "$label  $result"
        }
        lines += ""
        lines += "FRAME / WORKER"
        val iframeMarkerStatus = when {
            !documentStartFeature || !documentStartInstalled -> "NOT VERIFIED"
            iframe.optBoolean("documentStart") -> "PASS"
            else -> "FAIL"
        }
        lines += "Same-origin iframe marker  $iframeMarkerStatus"
        lines += "Dedicated worker marker  ${if (worker.optBoolean("documentStart")) "PRESENT" else "NOT INJECTED"}"
        lines += "Worker note  ${worker.optString("note", "Worker has a separate global context; document-start frame injection does not automatically cover workers.")}"
        lines += ""
        lines += "CANVAS"
        lines += "2D context  ${if (canvas.optBoolean("canvas2d")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += "getImageData perturbation  ${report.optString("canvasNoiseCheck", "NOT MEASURED")}"
        lines += "toDataURL  ${if (canvas.optBoolean("dataUrlAvailable")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += "toBlob  ${if (canvas.optBoolean("blobAvailable")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += "OffscreenCanvas  ${if (canvas.optBoolean("offscreenCanvas")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += ""
        lines += "WEBGL"
        lines += "Context  ${if (webgl.optBoolean("available")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += "Vendor  ${webgl.optString("vendor", "Not exposed")}"
        lines += "Renderer  ${webgl.optString("renderer", "Not exposed")}"
        lines += "Profile target  ${report.optString("webglSpoofCheck", "NOT MEASURED")}"
        lines += ""
        lines += "AUDIO"
        lines += "OfflineAudioContext  ${if (audio.optBoolean("available")) "AVAILABLE" else "UNAVAILABLE"}"
        lines += "Profile audio-noise flag  ${if (report.optJSONObject("expected")?.optBoolean("audioNoiseEnabled") == true) "ON" else "OFF / UNKNOWN"}"
        lines += "Offline render  ${if (audio.optBoolean("rendered")) "COMPLETED" else "NOT COMPLETED"}"
        lines += "Audio note  ${audio.optString("note", "No result")}"
        lines += ""
        lines += "USER-AGENT CLIENT HINTS"
        lines += "API  ${if (uaHints.optBoolean("available")) "AVAILABLE" else "NOT EXPOSED"}"
        lines += "Platform  ${uaHints.optString("platform", "Not exposed")}"
        lines += "High-entropy hints  ${if (uaHints.optBoolean("highEntropyAvailable")) "AVAILABLE" else "NOT AVAILABLE"}"
        lines += "Model hint  ${uaHints.optString("model", "Not exposed")}"
        lines += ""
        lines += "MEASURED USER-AGENT"
        lines += report.optString("userAgent", "Not exposed")
        lines += ""
        lines += "LIMITATIONS"
        lines += "• Worker scripts have a separate global; document-start frame scripts do not automatically inject into workers."
        lines += "• API availability is not proof of protection effectiveness."
        lines += "• Audio render success is not proof that audio noise is effective."
        lines += "• These results are local to this device and current WebView provider."
        resultText.text = lines.joinToString("\n")
    }

    private fun finishRun() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        destroyProbeWebView()
        runButton.isEnabled = true
        runButton.text = "Run device checks again"
    }

    private fun destroyProbeWebView() {
        probeWebView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        probeWebView = null
        if (::webViewHost.isInitialized) webViewHost.removeAllViews()
    }

    override fun onDestroy() {
        activeRun += 1
        pollRunnable?.let(mainHandler::removeCallbacks)
        destroyProbeWebView()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROFILE_ID = "nexora_compatibility_profile_id"
        const val EXTRA_USER_AGENT = "nexora_compatibility_user_agent"
        private const val PROBE_BASE = "https://nexora-alpha10.invalid/"
        private const val PROBE_HOST = "nexora-alpha10.invalid"
        private const val POLL_INTERVAL_MS = 250L
        private const val REPORT_TIMEOUT_MS = 15_000L
    }

    private val RED: Int get() = ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_accent)
    private val DARK: Int get() = ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_text)
    private val MUTED: Int get() = ContextCompat.getColor(this@CompatibilityLabActivity, R.color.nexora_muted)
}
