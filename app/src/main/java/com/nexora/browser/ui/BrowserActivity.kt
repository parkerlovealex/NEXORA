package com.nexora.browser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.CheckBox
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexora.browser.NexoraApp
import com.nexora.browser.R
import com.nexora.browser.content.ContentBlockerDiagnosticsStore
import com.nexora.browser.content.ContentBlockerEngine
import com.nexora.browser.content.ContentBlockerStore
import com.nexora.browser.data.BrowserDataStore
import com.nexora.browser.data.BrowserPageRecord
import com.nexora.browser.data.BrowserSessionTab
import com.nexora.browser.data.BrowserSessionPolicy
import com.nexora.browser.data.SessionTabState
import com.nexora.browser.data.BrowserTabGroup
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.data.ProxyLibraryStore
import com.nexora.browser.databinding.ActivityBrowserBinding
import com.nexora.browser.fp.FingerprintScript
import com.nexora.browser.security.PrivacyPolicy
import com.nexora.browser.util.UaBuilder
import com.nexora.browser.util.UiFeedback
import com.nexora.browser.util.BrowserNotifications
import com.nexora.browser.util.ProxyRelayConfig
import com.nexora.browser.util.ProxyRelayServer
import com.nexora.browser.util.ProxySessionCoordinator
import com.nexora.browser.util.ProxyOverrideClear
import com.nexora.browser.util.ProxyTester
import com.nexora.browser.util.ProfileDownloadClient
import com.nexora.browser.util.ProfileIsolationGuard
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Date
import java.text.DateFormat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

private data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val webView: WebView,
    var title: String = "New tab",
    @Volatile var url: String = "",
    var desktopMode: Boolean = false,
    var groupId: String? = null,
    var documentStartInstalled: Boolean = false
)

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast("Download alerts are disabled. Downloads will continue normally.")
        }
    private lateinit var store: ProfileStore
    private lateinit var profile: BrowserProfile
    private lateinit var dataStore: BrowserDataStore
    private lateinit var contentBlockerStore: ContentBlockerStore
    private lateinit var contentBlockerDiagnosticsStore: ContentBlockerDiagnosticsStore
    private val contentBlockerEngine = ContentBlockerEngine()
    private val blockedResourcesThisSession = AtomicInteger(0)
    private var preset: DevicePreset? = null
    private var fpScript: String = ""
    private var docStartWarningShown = false
    private var proxyReady = false
    private var proxyRoutingActive = false
    private var proxyRelay: ProxyRelayServer? = null
    private var proxyLease: ProxySessionCoordinator.Lease? = null
    private var networkReleaseRequested = false
    private var routeChangePending = false
    private var isClosing = false
    private var clearSessionOnExit = false
    private var activityResumed = false
    private val sessionPersistHandler = Handler(Looper.getMainLooper())
    private val sessionPersistRunnable = Runnable { persistSessionNow() }

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: String? = null
    private val redirectChain = mutableSetOf<String>()

    private val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiFeedback.install(binding.root)

        store = ProfileStore(this)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val profiles = readLocalStore { store.loadAll() }.getOrElse { finish(); return }
        val loaded = profiles.firstOrNull { it.id == profileId }
        if (loaded == null) {
            toast("Profile not found")
            finish()
            return
        }
        if (loaded.proxyCredentialsLocked) {
            toast(com.nexora.browser.data.ProfileCredentialCodec.LOCKED_MESSAGE)
            finish()
            return
        }
        profile = loaded
        val storageKeyOwners = ProfileIsolationGuard.collidingProfiles(profile, profiles)
        if (storageKeyOwners.size > 1) {
            AlertDialog.Builder(this)
                .setTitle("Profile storage collision detected")
                .setMessage(
                    "These profiles resolve to the same Android WebView storage key: " +
                        storageKeyOwners.joinToString { it.name } +
                        ". Opening them could share cookies and website data. NEXORA has blocked this session. " +
                        "Export any profile settings you need, then recreate one of the colliding profiles."
                )
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        preset = DeviceDb.find(this, profile.deviceId)
        if (profile.userAgentMode == "AUTO" && (profile.targetOs == "IOS" || preset?.isIos == true)) {
            val device = preset
            if (device?.isIos != true || !UaBuilder.isVersion(profile.osVersion) || UaBuilder.safariVersion(device, profile.manualSafariVersion) == null) {
                AlertDialog.Builder(this).setTitle("Safari template needs a version")
                    .setMessage("Edit this profile to choose a catalog iOS version and enter any missing Safari version manually, or supply a custom User-Agent.")
                    .setPositiveButton("Edit profile") { _, _ ->
                        startActivity(Intent(this, EditProfileActivity::class.java).putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id))
                        finish()
                    }
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .setOnCancelListener { finish() }.show()
                return
            }
        }
        dataStore = BrowserDataStore(this, profile.id)
        contentBlockerStore = ContentBlockerStore(this, profile.id)
        contentBlockerDiagnosticsStore = ContentBlockerDiagnosticsStore(this, profile.id)
        contentBlockerEngine.update(contentBlockerStore.snapshot())
        binding.tvProfileTitle.text = profile.name

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            AlertDialog.Builder(this)
                .setTitle("WebView update required")
                .setMessage(
                    "This device's Android System WebView does not support isolated browser profiles. " +
                        "NEXORA stopped this session to avoid silently sharing cookies and site data between profiles. " +
                        "Update Android System WebView or Chrome, then try again."
                )
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        fpScript = preset?.let { FingerprintScript.build(profile, it) }.orEmpty()
        setupControls()
        applyProxy {
            if (!isFinishing && !isDestroyed) {
                proxyReady = true
                restoreSessionOrStart()
            }
        }
    }

    private fun setupControls() {
        // The browser chrome is deliberately brand-free. Profile management
        // remains available from the overflow menu, not in the address bar.
        binding.btnExitProfile.setOnClickListener {
            returnToProfiles()
        }
        binding.btnSiteInfo.setOnClickListener {
            showSiteInfoDialog()
        }
        binding.btnBack.setOnClickListener {
            activeTab?.webView?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.btnForward.setOnClickListener {
            activeTab?.webView?.let { if (it.canGoForward()) it.goForward() }
        }
        binding.btnReload.setOnClickListener {
            activeTab?.webView?.reload() ?: createTab(null)
        }
        binding.btnNewTab.setOnClickListener {
            createTab(null)
        }
        binding.btnHome.setOnClickListener {
            loadHome()
        }
        binding.btnBookmarks.setOnClickListener {
            showBookmarksDialog()
        }
        binding.btnTabs.setOnClickListener {
            showTabsDialog()
        }
        binding.btnDownloads.setOnClickListener {
            showDownloadsDialog()
        }
        binding.btnOverflow.setOnClickListener {
            showOverflowMenu()
        }

        binding.etOmnibox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                navigateFromAddress()
                true
            } else {
                false
            }
        }
        updateToolbar()
    }

    // ---------------------------------------------------------------------
    // WebView and tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(
        initialUrl: String?,
        groupId: String? = activeTab?.groupId,
        desktopMode: Boolean = false,
        restoredTitle: String? = null
    ) {
        if (tabs.size >= MAX_LIVE_TABS) {
            toast("NEXORA supports up to $MAX_LIVE_TABS open tabs in one profile")
            return
        }
        if (!proxyReady) return
        val webView = WebView(this)
        try {
            androidx.webkit.ProfileStore.getInstance()
                .getOrCreateProfile(profile.webViewProfileKey)
            WebViewCompat.setProfile(webView, profile.webViewProfileKey)
        } catch (e: Exception) {
            toast("Could not create isolated profile storage: ${e.message ?: "unknown error"}")
            webView.destroy()
            return
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = !profile.blockImages
            blockNetworkImage = profile.blockImages
            mediaPlaybackRequiresUserGesture = PrivacyPolicy.MEDIA_PLAYBACK_REQUIRES_GESTURE
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = PrivacyPolicy.FILE_ACCESS_ALLOWED
            allowContentAccess = PrivacyPolicy.CONTENT_ACCESS_ALLOWED
            mixedContentMode = if (PrivacyPolicy.BLOCK_MIXED_CONTENT) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            javaScriptCanOpenWindowsAutomatically = PrivacyPolicy.JAVASCRIPT_POPUPS_ALLOWED
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = PrivacyPolicy.SAFE_BROWSING_ENABLED
            userAgentString = resolvedProfileUserAgent()
        }
        // Android exposes this as a per-WebView policy. Keep first-party cookies
        // available while blocking third-party cookie access for this profile view.
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView,
            PrivacyPolicy.THIRD_PARTY_COOKIES_ALLOWED
        )

        webView.setLayerType(
            if (profile.hardwareAcceleration) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
            null
        )
        val tab = BrowserTab(
            webView = webView,
            title = restoredTitle?.takeIf { it.isNotBlank() } ?: "New tab",
            desktopMode = desktopMode,
            groupId = groupId?.takeIf { id -> dataStore.tabGroups().any { it.id == id } }
        )
        tabs.add(tab)
        configureWebView(tab)
        applyTextZoom(webView, dataStore.textZoom())
        if (desktopMode) {
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        }
        switchToTab(tab.id)

        val target = initialUrl?.let { normalizeInput(it) } ?: HOME_URL
        loadIntoTab(tab, target)
        updateToolbar()
        persistSession()
    }

    private fun configureWebView(tab: BrowserTab) {
        val webView = tab.webView
        tab.documentStartInstalled = false
        if (fpScript.isNotBlank() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            try {
                // Register before the WebView loads its first document. AndroidX runs
                // this in matching frames before page JavaScript begins.
                WebViewCompat.addDocumentStartJavaScript(webView, fpScript, setOf("*"))
                tab.documentStartInstalled = true
            } catch (_: Exception) {
                tab.documentStartInstalled = false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                if (!request.isForMainFrame) return false
                when (uri.scheme?.lowercase()) {
                    "mailto", "tel" -> {
                        openExternal(uri)
                        return true
                    }
                    "http", "https" -> Unit
                    else -> return true
                }
                val url = uri.toString()
                if (!redirectChain.add(url)) {
                    // A repeated URL indicates a redirect loop; stop applying rules for this chain.
                    redirectChain.clear()
                    return false
                }
                val redirected = resolveRedirect(url)
                if (redirected != null && redirected != url) {
                    if (redirected in redirectChain || redirectChain.size >= MAX_REDIRECT_HOPS) {
                        redirectChain.clear()
                        return false
                    }
                    redirectChain.add(redirected)
                    tabFor(view)?.let { applyUserAgent(it, redirected) }
                    applySiteSettings(view, redirected)
                    view?.loadUrl(redirected)
                    return true
                }
                tabFor(view)?.let { applyUserAgent(it, url) }
                applySiteSettings(view, url)
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Keep top-level navigation intact. This engine filters subresources only.
                if (request == null || request.isForMainFrame || !contentBlockerEngine.isEnabled()) {
                    return null
                }
                // Use this WebView's top-level URL for the site exception. A Referer
                // header can describe an embedded iframe rather than the top-level page.
                val pageUrl = tab.url
                val decision = contentBlockerEngine.evaluate(request.url.toString(), pageUrl)
                if (!decision.blocked) return null

                blockedResourcesThisSession.incrementAndGet()
                decision.matchedRule?.let { rule ->
                    contentBlockerDiagnosticsStore.recordBlocked(
                        resourceUrl = request.url.toString(),
                        pageUrl = pageUrl,
                        ruleHost = rule.host,
                        method = request.method
                    )
                }
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    204,
                    "No Content",
                    emptyMap(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val current = tabFor(view) ?: return
                if (!url.isNullOrBlank()) current.url = url
                persistSession()
                if (!current.documentStartInstalled && fpScript.isNotBlank()) {
                    // Best-effort fallback only: onPageStarted is not equivalent
                    // to a guaranteed document-start injection before page JS.
                    if (!docStartWarningShown) {
                        toast("Document-start protection is unavailable; fingerprint overrides are best-effort on this WebView.")
                        docStartWarningShown = true
                    }
                    view?.evaluateJavascript(fpScript, null)
                }
                if (current.id == activeTabId) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = 0
                    if (!binding.etOmnibox.hasFocus()) binding.etOmnibox.setText(current.url)
                    updateToolbar()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val current = tabFor(view) ?: return
                if (!url.isNullOrBlank()) current.url = url
                current.title = view?.title?.takeIf { it.isNotBlank() } ?: current.title
                if (current.url != HOME_URL &&
                    (current.url.startsWith("http://") || current.url.startsWith("https://"))
                ) {
                    dataStore.addHistory(current.url, current.title)
                }
                if (current.id == activeTabId) {
                    binding.progressBar.visibility = View.GONE
                    binding.etOmnibox.setText(current.url)
                    updateToolbar()
                }
                persistSession()
                redirectChain.clear()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && tab.id == activeTabId) {
                    binding.progressBar.visibility = View.GONE
                    val code = error?.errorCode?.toString() ?: "unknown"
                    val description = error?.description?.toString()?.take(90) ?: "Network error"
                    toast("Page failed ($code): $description")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && tab.id == activeTabId) {
                    binding.progressBar.visibility = View.GONE
                    toast("Server returned HTTP ${errorResponse?.statusCode ?: "error"}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                // No web-origin camera, microphone, MIDI or other WebView permissions
                // are granted until a complete in-app + Android runtime permission flow exists.
                val pending = request ?: return
                if (PrivacyPolicy.grantWebContentPermission(pending.resources.toList())) {
                    pending.grant(pending.resources)
                } else {
                    pending.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin.orEmpty(), PrivacyPolicy.grantGeolocation(origin), false)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab.id == activeTabId) {
                    binding.progressBar.progress = newProgress
                    binding.progressBar.visibility =
                        if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title.take(80)
                    updateToolbar()
                    persistSession()
                }
            }
        }

        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                downloadUrl(url, userAgent, contentDisposition, mimeType)
            }
        )

        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val target = hit.extra
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    if (!target.isNullOrBlank()) showLinkContextMenu(target)
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    if (!target.isNullOrBlank()) showImageContextMenu(target)
                    true
                }
                else -> false
            }
        }
    }

    private fun tabFor(view: WebView?): BrowserTab? =
        tabs.firstOrNull { it.webView === view }

    private fun switchToTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        val previous = activeTab
        if (previous != null && previous.id != id) previous.webView.onPause()
        activeTabId = id
        if (activityResumed) tab.webView.onResume() else tab.webView.onPause()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        binding.webContainer.removeAllViews()
        binding.webContainer.addView(
            tab.webView,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.etOmnibox.setText(tab.url)
        updateToolbar()
        persistSession()
    }

    private fun closeTab(id: String, remember: Boolean = true) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs[index]
        if (remember && tab.url.isNotBlank() && tab.url != HOME_URL) {
            dataStore.addRecentlyClosed(tab.url, tab.title)
        }
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            activeTabId = null
            createTab(null)
            return
        }
        if (activeTabId == id) {
            val next = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex)) ?: tabs.last()
            switchToTab(next.id)
        } else {
            updateToolbar()
        }
        persistSession()
    }

    private fun loadIntoTab(tab: BrowserTab, requestedUrl: String) {
        if (isFinishing || isDestroyed) return
        redirectChain.clear()
        val url = if (requestedUrl == HOME_URL) HOME_URL else (resolveRedirect(requestedUrl) ?: requestedUrl)
        applyUserAgent(tab, url)
        applySiteSettings(tab.webView, url)
        tab.url = url
        persistSession()
        if (url == HOME_URL) {
            tab.title = "New tab"
            loadNewTabPage(tab)
        } else {
            tab.webView.loadUrl(url)
        }
        if (tab.id == activeTabId) {
            binding.etOmnibox.setText(if (url == HOME_URL) "" else url)
            updateToolbar()
        }
    }

    /**
     * Loads the bundled NEXORA start page under a stable HTTPS origin so that
     * browser APIs which require a secure context behave consistently.
     *
     * The page has no JavaScript bridge into Android. Its optional network
     * check contacts only the disclosed public-IP endpoint after user consent.
     */
    private fun loadNewTabPage(tab: BrowserTab) {
        val contextJson = preset?.let {
            FingerprintScript.diagnosticsContextJson(
                profile, it, tab.webView.settings.userAgentString, tab.documentStartInstalled
            )
        } ?: "{}"
        // This context is inserted only into the bundled local start page. It is
        // never added to remote navigations or exposed through a JS bridge.
        val safeContextJson = contextJson
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
        val contextTag = "<script id=\"nexoraProfileContext\" type=\"application/json\">$safeContextJson</script>"
        val html = assets.open("newtab.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("<!-- NEXORA_PROFILE_CONTEXT -->", contextTag)
        tab.webView.loadDataWithBaseURL(HOME_BASE, html, "text/html", "UTF-8", null)
    }

    /**
     * Persist this profile's tab session and return to the NEXORA profile workspace.
     * onDestroy then closes WebViews and releases the active proxy relay/lease.
     */
    private fun returnToProfiles() {
        if (isFinishing || isDestroyed) return
        persistSessionNow()
        finish()
    }

    private fun loadHome() {
        activeTab?.let { loadIntoTab(it, HOME_URL) } ?: createTab(null)
    }

    private fun navigateFromAddress() {
        val raw = binding.etOmnibox.text?.toString().orEmpty().trim()
        if (raw.isBlank()) return
        val url = normalizeInput(raw)
        activeTab?.let { loadIntoTab(it, url) } ?: createTab(url)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etOmnibox.windowToken, 0)
        binding.etOmnibox.clearFocus()
    }

    private fun normalizeInput(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        val looksLikeHost = !value.contains(' ') &&
            (value.contains('.') || value.startsWith("localhost", true))
        if (looksLikeHost) return "https://$value"
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val engine = getSharedPreferences(NexoraApp.PREFS_NAME, MODE_PRIVATE)
            .getString(NexoraApp.KEY_SEARCH_ENGINE, NexoraApp.SEARCH_DUCKDUCKGO)
        return when (engine) {
            NexoraApp.SEARCH_GOOGLE -> "https://www.google.com/search?q=$encoded"
            NexoraApp.SEARCH_BING -> "https://www.bing.com/search?q=$encoded"
            NexoraApp.SEARCH_BRAVE -> "https://search.brave.com/search?q=$encoded"
            else -> "https://duckduckgo.com/?q=$encoded"
        }
    }

    private fun applyUserAgent(tab: BrowserTab, url: String) {
        val host = Uri.parse(url).host.orEmpty()
        val custom = dataStore.siteUserAgent(host)
        tab.webView.settings.userAgentString = when {
            custom != null -> custom
            tab.desktopMode -> UaBuilder.desktopUserAgent(WebSettings.getDefaultUserAgent(this))
            else -> resolvedProfileUserAgent()
        }
    }

    private fun resolvedProfileUserAgent(): String {
        val native = WebSettings.getDefaultUserAgent(this)
        if (profile.userAgentMode == "AUTO") {
            val selectedPreset = preset ?: return native
            return runCatching { UaBuilder.build(selectedPreset, profile.osVersion, native, profile.manualSafariVersion) }
                .getOrDefault(native)
        }
        return profile.userAgent.ifBlank { native }
    }

    private fun applySiteSettings(webView: WebView?, url: String) {
        if (webView == null) return
        val host = Uri.parse(url).host.orEmpty()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                    webView.settings,
                    dataStore.isSiteDark(host)
                )
            } catch (_: Exception) {
                // The WebView provider may expose the feature but reject it at runtime.
            }
        }
    }

    private fun applyTextZoom(webView: WebView, value: Int) {
        webView.settings.textZoom = value.coerceIn(50, 200)
    }

    private fun showTextSizeDialog() {
        val current = dataStore.textZoom()
        var selected = current
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
        }
        val valueLabel = TextView(this).apply {
            text = "$current%"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nexora_browser_text))
        }
        val seek = SeekBar(this).apply {
            max = 150
            progress = current - 50
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selected = (progress + 50).coerceIn(50, 200)
                valueLabel.text = "$selected%"
                tabs.forEach { applyTextZoom(it.webView, selected) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        content.addView(valueLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ))
        content.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        var accepted = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Text size")
            .setMessage("Adjust webpage text size for this browser profile.")
            .setView(content)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dataStore.setTextZoom(selected)
                accepted = true
                dialog.dismiss()
                toast("Text size saved")
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (!accepted) tabs.forEach { applyTextZoom(it.webView, current) }
        }
        dialog.show()
    }

    private fun toggleSiteDarkMode() {
        val host = Uri.parse(activeTab?.url.orEmpty()).host.orEmpty()
        if (host.isBlank()) {
            toast("Open a website first")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            toast("Website dark mode is not supported by this WebView")
            return
        }
        val enabled = !dataStore.isSiteDark(host)
        dataStore.setSiteDark(host, enabled)
        activeTab?.let {
            applySiteSettings(it.webView, it.url)
            it.webView.reload()
        }
        toast(if (enabled) "Dark theme enabled for $host" else "Dark theme disabled for $host")
    }

    // ---------------------------------------------------------------------
    // Proxy

    private fun applyProxy(onReady: () -> Unit) {
        // ProxyController is app-wide. Never let two BrowserActivity instances
        // overwrite each other's network route.
        val lease = ProxySessionCoordinator.acquire(profile.id)
        if (lease == null) {
            toast("Another NEXORA profile session is active. Close it before opening another profile.")
            finish()
            return
        }
        proxyLease = lease

        val effectiveProfile = if (profile.proxyEntryId.isNotBlank()) {
            val library = ProxyLibraryStore(this)
            val entry = library.find(profile.proxyEntryId)
            if (entry == null) {
                toast("The saved proxy assigned to this profile no longer exists. Browsing stopped.")
                finish()
                return
            }
            if (!entry.isSupportedByProxyRelay) {
                toast("${entry.type} is stored but not supported by the NEXORA proxy relay. Browsing stopped.")
                finish()
                return
            }
            val credentials = library.credentials(entry)
            if (entry.hasCredentials && credentials.first.isEmpty() && credentials.second.isEmpty()) {
                toast("Could not decrypt the saved proxy credentials. Browsing stopped.")
                finish()
                return
            }
            profile.copy(
                proxyType = entry.type,
                proxyHost = entry.host,
                proxyPort = entry.port,
                proxyUser = credentials.first,
                proxyPass = credentials.second
            )
        } else profile

        if (effectiveProfile.proxyType == "NONE") {
            routeChangePending = true
            clearProxy { result ->
                routeChangePending = false
                if (networkReleaseRequested) {
                    finishRouteRelease(lease, result)
                } else if (!isFinishing && !isDestroyed) {
                    result.fold(
                        onSuccess = { proxyRoutingActive = false; onReady() },
                        onFailure = {
                            toast("Could not clear the previous proxy. Direct browsing stopped.")
                            finish()
                        }
                    )
                }
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            toast("This WebView does not support proxy routing. Browsing stopped.")
            finish()
            return
        }
        if (!effectiveProfile.hasProxy ||
            effectiveProfile.proxyType !in ProxyRelayConfig.SUPPORTED_TYPES
        ) {
            toast("Proxy settings are invalid or unsupported. Browsing stopped.")
            finish()
            return
        }

        val relayConfig = ProxyRelayConfig(
            type = effectiveProfile.proxyType,
            host = effectiveProfile.proxyHost,
            port = effectiveProfile.proxyPort,
            username = effectiveProfile.proxyUser,
            password = effectiveProfile.proxyPass
        )
        relayConfig.validate()?.let {
            toast("Proxy settings are invalid: $it. Browsing stopped.")
            finish()
            return
        }

        toast("Verifying proxy connection before opening this profile…")
        ProxyTester.test(effectiveProfile) { testResult ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!testResult.startsWith("OK — exit IP ")) {
                    toast("Proxy verification failed: $testResult. Browsing stopped; no direct fallback was used.")
                    finish()
                    return@runOnUiThread
                }
                startProxyRelay(relayConfig, lease, testResult, onReady)
            }
        }
    }

    private fun startProxyRelay(
        relayConfig: ProxyRelayConfig,
        lease: ProxySessionCoordinator.Lease,
        verifiedResult: String,
        onReady: () -> Unit
    ) {
        Thread({
            try {
                val relay = ProxyRelayServer(relayConfig)
                val localPort = relay.start()
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        relay.close()
                        releaseNetworkSession()
                        return@runOnUiThread
                    }
                    proxyRelay = relay
                    try {
                        // WebView sees only an unauthenticated loopback HTTP proxy.
                        // The local relay handles upstream protocol/authentication.
                        val localConfig = ProxyConfig.Builder()
                            .addProxyRule("http://127.0.0.1:$localPort")
                            .build()
                        routeChangePending = true
                        ProxyController.getInstance().setProxyOverride(
                            localConfig,
                            ContextCompat.getMainExecutor(this),
                            Runnable {
                                routeChangePending = false
                                if (networkReleaseRequested) {
                                    clearReleasedRoute(lease)
                                } else if (isFinishing || isDestroyed) {
                                    releaseNetworkSession()
                                } else {
                                    proxyRoutingActive = true
                                    val exitIp = verifiedResult
                                        .removePrefix("OK — exit IP ")
                                        .substringBefore(" (")
                                    toast("Proxy verified · exit IP $exitIp")
                                    onReady()
                                }
                            }
                        )
                    } catch (error: Exception) {
                        routeChangePending = false
                        toast("Could not apply the local proxy route: ${error.message ?: "unknown error"}. Browsing stopped.")
                        finish()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    toast("Could not start the proxy relay: ${error.message ?: "unknown error"}. Browsing stopped.")
                    finish()
                }
            }
        }, "NEXORA-ProxyRelay-Start").apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseProxyLease(lease: ProxySessionCoordinator.Lease) {
        ProxySessionCoordinator.release(lease)
        if (proxyLease?.token == lease.token) proxyLease = null
    }

    private fun releaseNetworkSession() {
        if (networkReleaseRequested) return
        networkReleaseRequested = true
        proxyRelay?.close()
        proxyRelay = null
        proxyRoutingActive = false
        val lease = proxyLease
        if (lease != null && !routeChangePending) clearReleasedRoute(lease)
    }

    private fun clearReleasedRoute(lease: ProxySessionCoordinator.Lease) {
        if (proxyLease?.token != lease.token) return
        clearProxy { result -> finishRouteRelease(lease, result) }
    }

    private fun finishRouteRelease(lease: ProxySessionCoordinator.Lease, result: Result<Unit>) {
        if (result.isFailure) android.util.Log.w("NEXORA", "WebView did not confirm proxy clearance during teardown.")
        releaseProxyLease(lease)
    }

    private fun clearProxy(onResult: (Result<Unit>) -> Unit) {
        ProxyOverrideClear.request({ acknowledged ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
            ) {
                acknowledged.run()
            } else {
                ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(this), acknowledged)
            }
        }, onResult)
    }

    // ---------------------------------------------------------------------
    // Menus and context actions

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, binding.btnOverflow)
        val items = listOf(
            "Back to profiles",
            "New tab",
            "Anti-detect Center",
            "Tabs",
            "Recently closed tabs",
            "Bookmarks",
            "History",
            "Downloads",
            "Find in page",
            "Text size",
            "Desktop site",
            "User-Agent for this site",
            "Toggle site dark theme",
            "Redirect rules",
            "Content blocker",
            "Share page",
            "Copy URL",
            "Privacy Center",
            "Privacy diagnostics",
            "Compatibility Lab",
            "Profile info",
            "Clear profile data",
            "Close tab"
        )
        items.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Back to profiles" -> returnToProfiles()
                "New tab" -> createTab(null)
                "Anti-detect Center" -> startActivity(
                    Intent(this, FeatureCenterActivity::class.java)
                        .putExtra(FeatureCenterActivity.EXTRA_PROFILE_ID, profile.id)
                )
                "Tabs" -> showTabsDialog()
                "Recently closed tabs" -> showRecentlyClosedDialog()
                "Bookmarks" -> showBookmarksDialog()
                "History" -> showHistoryDialog()
                "Downloads" -> showDownloadsDialog()
                "Find in page" -> showFindDialog()
                "Text size" -> showTextSizeDialog()
                "Desktop site" -> toggleDesktopSite()
                "User-Agent for this site" -> showSiteUserAgentDialog()
                "Toggle site dark theme" -> toggleSiteDarkMode()
                "Redirect rules" -> showRedirectRulesDialog()
                "Content blocker" -> showContentBlockerDialog()
                "Share page" -> shareUrl(activeTab?.url.orEmpty())
                "Copy URL" -> copyText(activeTab?.url.orEmpty())
                "Privacy Center" -> startActivity(
                    Intent(this, PrivacyCenterActivity::class.java)
                        .putExtra(PrivacyCenterActivity.EXTRA_PROFILE_ID, profile.id)
                )
                "Privacy diagnostics" -> showPrivacyDiagnosticsDialog()
                "Compatibility Lab" -> {
                    val intent = Intent(this, CompatibilityLabActivity::class.java)
                        .putExtra(CompatibilityLabActivity.EXTRA_PROFILE_ID, profile.id)
                        .putExtra(CompatibilityLabActivity.EXTRA_USER_AGENT, activeTab?.webView?.settings?.userAgentString.orEmpty())
                    startActivity(intent)
                }
                "Profile info" -> showProfileInfo()
                "Clear profile data" -> confirmClearData()
                "Close tab" -> activeTab?.let { closeTab(it.id) }
            }
            true
        }
        popup.show()
    }

    private fun showLinkContextMenu(url: String) {
        val options = arrayOf("Open in new tab", "Open here", "Copy link", "Share link", "Download link")
        AlertDialog.Builder(this)
            .setTitle("Link")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createTab(url)
                    1 -> activeTab?.let { loadIntoTab(it, url) }
                    2 -> copyText(url)
                    3 -> shareUrl(url)
                    4 -> downloadUrl(url, null, null, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImageContextMenu(url: String) {
        val options = arrayOf("Open image in new tab", "Copy image URL", "Share image", "Download image")
        AlertDialog.Builder(this)
            .setTitle("Image")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createTab(url)
                    1 -> copyText(url)
                    2 -> shareUrl(url)
                    3 -> downloadUrl(url, null, null, null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleDesktopSite() {
        val tab = activeTab ?: return
        tab.desktopMode = !tab.desktopMode
        applyUserAgent(tab, tab.url)
        tab.webView.settings.useWideViewPort = tab.desktopMode
        tab.webView.settings.loadWithOverviewMode = tab.desktopMode
        tab.webView.reload()
        toast(if (tab.desktopMode) "Desktop site enabled" else "Mobile site enabled")
    }

    private fun showSiteUserAgentDialog() {
        val tab = activeTab ?: return
        val host = Uri.parse(tab.url).host.orEmpty()
        if (host.isBlank()) {
            toast("Open a website first")
            return
        }
        val input = EditText(this).apply {
            setText(dataStore.siteUserAgent(host).orEmpty())
            hint = "Leave empty to use profile User-Agent"
            minLines = 2
            maxLines = 4
            setPadding(32, 20, 32, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("User-Agent · $host")
            .setMessage("This override is saved locally for this profile and website.")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim()
                dataStore.setSiteUserAgent(host, value.takeIf { it.isNotBlank() })
                applyUserAgent(tab, tab.url)
                tab.webView.reload()
            }
            .setNeutralButton("Reset") { _, _ ->
                dataStore.setSiteUserAgent(host, null)
                applyUserAgent(tab, tab.url)
                tab.webView.reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRedirectRulesDialog() {
        val input = EditText(this).apply {
            setText(dataStore.redirectRules())
            hint = "^https://old\\.example/(.*)$ => https://new.example/$1"
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP
            setPadding(28, 22, 28, 22)
        }
        AlertDialog.Builder(this)
            .setTitle("URL redirects")
            .setMessage("One regular-expression rule per line: pattern => https://replacement. HTTPS destinations only. Rules are stored locally in this profile.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString()
                val invalid = value.lineSequence().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .any { line ->
                        val parts = line.split("=>", limit = 2)
                        parts.size != 2 ||
                            runCatching { Regex(parts[0].trim()) }.isFailure ||
                            !parts[1].trim().startsWith("https://")
                    }
                if (invalid) {
                    toast("A redirect rule is invalid. No changes saved.")
                } else {
                    dataStore.setRedirectRules(value)
                    toast("Redirect rules saved")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveRedirect(url: String): String? {
        val rules = dataStore.redirectRules()
        for (line in rules.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = trimmed.split("=>", limit = 2)
            if (parts.size != 2) continue
            val result = runCatching {
                val regex = Regex(parts[0].trim(), RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(url)) regex.replaceFirst(url, parts[1].trim()) else null
            }.getOrNull()
            if (!result.isNullOrBlank() && result != url) return result
        }
        return null
    }

    private fun refreshContentBlockerEngine(reloadPage: Boolean = true) {
        contentBlockerEngine.update(contentBlockerStore.snapshot())
        if (reloadPage) {
            activeTab?.let { tab ->
                if (tab.url.startsWith("http://") || tab.url.startsWith("https://")) {
                    tab.webView.reload()
                }
            }
        }
    }

    private fun showContentBlockerDialog() {
        val host = Uri.parse(activeTab?.url.orEmpty()).host.orEmpty()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val enabled = CheckBox(this).apply {
            text = "Enable content blocking for this profile"
            setTextColor(getColor(R.color.nexora_browser_text))
            isChecked = contentBlockerStore.isEnabled()
        }
        val diagnosticsEnabled = CheckBox(this).apply {
            text = "Save redacted request diagnostics"
            setTextColor(getColor(R.color.nexora_browser_text))
            isChecked = contentBlockerDiagnosticsStore.isEnabled()
        }
        val diagnosticsButton = Button(this).apply {
            text = "View recent blocked requests"
            setOnClickListener { showBlockedRequestDiagnosticsDialog() }
        }
        val summary = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(0, dp(10), 0, dp(8))
        }
        val scope = TextView(this).apply {
            text = "NEXORA checks matching host rules for subresource requests. Main-page navigation is not blocked."
            textSize = 12f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(0, dp(4), 0, dp(8))
        }
        val limitation = TextView(this).apply {
            text = "Supported format: domain rules such as ||ads.example^ and exceptions such as @@||trusted.example^. This WebView-level filter does not cover every request path. Full EasyList syntax, cosmetic rules, service-worker interception and automatic remote updates are not available yet."
            textSize = 12f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(0, dp(4), 0, 0)
        }

        fun renderSummary() {
            summary.text = buildString {
                append("Supported rules: ").append(contentBlockerEngine.supportedRuleCount())
                append("  ·  Ignored lines: ").append(contentBlockerEngine.ignoredRuleCount())
                append("\nBlocked this session: ").append(blockedResourcesThisSession.get())
                append("\nSaved diagnostics: ").append(contentBlockerDiagnosticsStore.records().size)
                append("\nCustom lists: ").append(contentBlockerStore.customLists().size)
                append("\nBuilt-in list: ").append(if (contentBlockerStore.isBuiltInEnabled()) "On" else "Off")
                if (host.isNotBlank()) {
                    append("\nCurrent site: ").append(host)
                    append(if (contentBlockerStore.isSiteAllowed(host)) " · Allowed" else " · Filtering")
                }
            }
        }
        renderSummary()
        enabled.setOnCheckedChangeListener { _, checked ->
            contentBlockerStore.setEnabled(checked)
            refreshContentBlockerEngine()
            renderSummary()
            toast(if (checked) "Content blocking enabled" else "Content blocking disabled")
        }
        diagnosticsEnabled.setOnCheckedChangeListener { _, checked ->
            contentBlockerDiagnosticsStore.setEnabled(checked)
            renderSummary()
            toast(
                if (checked) "Redacted diagnostics enabled for this profile"
                else "Diagnostic capture paused; saved records remain until cleared"
            )
        }

        content.addView(enabled)
        content.addView(diagnosticsEnabled)
        content.addView(diagnosticsButton)
        content.addView(summary)
        content.addView(scope)
        content.addView(limitation)

        AlertDialog.Builder(this)
            .setTitle("NEXORA Content Blocker")
            .setView(content)
            .setPositiveButton("Manage lists") { _, _ -> showFilterListsDialog() }
            .setNeutralButton(
                if (host.isNotBlank() && contentBlockerStore.isSiteAllowed(host)) {
                    "Restore site blocking"
                } else {
                    "Allow this site"
                }
            ) { _, _ ->
                if (host.isBlank()) {
                    toast("Open a website before changing its exception")
                } else {
                    val allow = !contentBlockerStore.isSiteAllowed(host)
                    if (contentBlockerStore.setSiteAllowed(host, allow)) {
                        refreshContentBlockerEngine()
                        toast(if (allow) "Filtering paused for $host" else "Filtering restored for $host")
                    } else {
                        toast("Could not update this site's exception")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBlockedRequestDiagnosticsDialog() {
        val records = contentBlockerDiagnosticsStore.records()
        val body = if (records.isEmpty()) {
            "No blocked-request diagnostics have been saved. Enable redacted diagnostics in the Content Blocker panel to collect future matches."
        } else {
            records.joinToString("\n\n") { record ->
                val time = DateFormat.getDateTimeInstance().format(Date(record.timestamp))
                buildString {
                    append(time)
                    append("\n").append(record.method).append(" ").append(record.requestHost)
                    append("\nPage: ").append(record.pageHost.ifBlank { "Unknown" })
                    append(" · Rule: ").append(record.ruleHost)
                }
            }
        }
        val text = TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(getColor(R.color.nexora_browser_text))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(text)
        }
        AlertDialog.Builder(this)
            .setTitle("Blocked requests · ${records.size}")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear records") { _, _ ->
                contentBlockerDiagnosticsStore.clear()
                toast("Blocked-request diagnostics cleared")
            }
            .show()
    }

    private fun showFilterListsDialog() {
        val customLists = contentBlockerStore.customLists()
        val options = buildList {
            add(
                "NEXORA Starter Ads · " +
                    if (contentBlockerStore.isBuiltInEnabled()) "Enabled" else "Disabled"
            )
            add("Manage allowed sites…")
            add("Import / paste a filter list…")
            customLists.forEach { list ->
                val report = ContentBlockerEngine.parseRules(list.rawRules.lines())
                add(
                    "${if (list.enabled) "ON" else "OFF"} · ${list.name} · " +
                        "${report.supportedLines} supported rules"
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Filter lists")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        contentBlockerStore.setBuiltInEnabled(!contentBlockerStore.isBuiltInEnabled())
                        refreshContentBlockerEngine()
                        showFilterListsDialog()
                    }
                    1 -> showAllowedSitesDialog()
                    2 -> showAddFilterListDialog()
                    else -> customLists.getOrNull(which - 3)?.let { showCustomFilterListActions(it) }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun showAllowedSitesDialog() {
        val sites = contentBlockerStore.allowedSites().sorted()
        if (sites.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Allowed sites")
                .setMessage("No site exceptions are saved for this profile.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allowed sites · ${sites.size}")
            .setItems(sites.toTypedArray()) { _, which ->
                val host = sites.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle("Restore filtering?")
                    .setMessage("Remove the exception for $host?")
                    .setPositiveButton("Restore") { _, _ ->
                        contentBlockerStore.setSiteAllowed(host, false)
                        refreshContentBlockerEngine()
                        showAllowedSitesDialog()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Back") { _, _ -> showFilterListsDialog() }
            .show()
    }

    private fun showAddFilterListDialog(existing: com.nexora.browser.content.ContentFilterList? = null) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val nameInput = EditText(this).apply {
            hint = "List name"
            setSingleLine(true)
            setText(existing?.name.orEmpty())
        }
        val rulesInput = EditText(this).apply {
            hint = "||ads.example^\n@@||allowed.example^\n! Comments start with !"
            setText(existing?.rawRules.orEmpty())
            minLines = 8
            maxLines = 14
            gravity = Gravity.TOP
        }
        val helper = TextView(this).apply {
            text = "Paste a text filter list. NEXORA currently imports supported domain and exception rules; unsupported lines are skipped and counted."
            textSize = 12f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(0, dp(6), 0, dp(8))
        }
        content.addView(nameInput)
        content.addView(helper)
        content.addView(rulesInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Import filter list" else "Edit filter list")
            .setView(content)
            .setPositiveButton(if (existing == null) "Import" else "Save") { _, _ ->
                val name = nameInput.text?.toString().orEmpty()
                val rules = rulesInput.text?.toString().orEmpty()
                val report = ContentBlockerEngine.parseRules(rules.lines())
                if (report.supportedLines == 0) {
                    toast("No supported rules found. Use ||domain^ or @@||domain^.")
                    return@setPositiveButton
                }
                val saved = if (existing == null) {
                    contentBlockerStore.addCustomList(name, rules) != null
                } else {
                    contentBlockerStore.updateCustomList(existing.id, name, rules)
                }
                if (saved) {
                    refreshContentBlockerEngine()
                    toast(
                        "Filter list saved: ${report.supportedLines} supported, " +
                            "${report.ignoredLines} ignored"
                    )
                } else {
                    toast("Could not save list. Check its name, size and duplicate names.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomFilterListActions(list: com.nexora.browser.content.ContentFilterList) {
        val actions = arrayOf(
            if (list.enabled) "Disable list" else "Enable list",
            "Edit list",
            "Copy rules",
            "Remove list"
        )
        AlertDialog.Builder(this)
            .setTitle(list.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        contentBlockerStore.setCustomListEnabled(list.id, !list.enabled)
                        refreshContentBlockerEngine()
                        showFilterListsDialog()
                    }
                    1 -> showAddFilterListDialog(list)
                    2 -> {
                        copyText(list.rawRules)
                        toast("Filter rules copied")
                    }
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Remove filter list?")
                        .setMessage("Remove \"${list.name}\" from this profile?")
                        .setPositiveButton("Remove") { _, _ ->
                            contentBlockerStore.removeCustomList(list.id)
                            refreshContentBlockerEngine()
                            showFilterListsDialog()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Back") { _, _ -> showFilterListsDialog() }
            .show()
    }

    private fun showFindDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val input = EditText(this).apply {
            hint = "Find text on this page"
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val caseSensitive = CheckBox(this).apply {
            text = "Match case"
            setTextColor(getColor(R.color.nexora_browser_text))
        }
        val wholeWords = CheckBox(this).apply {
            text = "Whole words"
            setTextColor(getColor(R.color.nexora_browser_text))
        }
        val status = TextView(this).apply {
            text = "Enter text to search this page."
            textSize = 12f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(input)
        content.addView(caseSensitive)
        content.addView(wholeWords)
        content.addView(status)

        var matchCount = 0
        var currentIndex = 0
        var lastQuery = ""
        var lastCase = false
        var lastWhole = false

        fun search(backwards: Boolean, recount: Boolean) {
            val query = input.text?.toString().orEmpty()
            if (query.isBlank()) {
                status.text = "Enter text to search this page."
                return
            }
            val tab = activeTab
            if (tab == null) {
                status.text = "No active tab."
                return
            }
            val matchCase = caseSensitive.isChecked
            val whole = wholeWords.isChecked
            val changed = query != lastQuery || matchCase != lastCase || whole != lastWhole
            if (recount || changed || matchCount == 0) {
                lastQuery = query
                lastCase = matchCase
                lastWhole = whole
                val countScript = buildFindCountScript(query, matchCase, whole)
                tab.webView.evaluateJavascript(countScript) { raw ->
                    matchCount = raw?.trim()?.trim('"')?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    currentIndex = if (matchCount > 0) 1 else 0
                    if (matchCount == 0) {
                        status.text = "No matches found."
                    } else {
                        runWindowFind(tab.webView, query, matchCase, whole, false) { found ->
                            status.text = if (found) "1 of $matchCount matches" else "Found $matchCount text matches, but the page search API could not select one."
                        }
                    }
                }
            } else {
                val nextIndex = if (backwards) {
                    if (currentIndex <= 1) matchCount else currentIndex - 1
                } else {
                    if (currentIndex >= matchCount) 1 else currentIndex + 1
                }
                runWindowFind(tab.webView, query, matchCase, whole, backwards) { found ->
                    if (found) {
                        currentIndex = nextIndex
                        status.text = "$currentIndex of $matchCount matches"
                    } else {
                        status.text = "No further match found."
                    }
                }
            }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun actionButton(label: String, callback: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nexora_browser_text))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { callback() }
        }
        actions.addView(actionButton("Previous") { search(true, false) })
        actions.addView(actionButton("Find") { search(false, true) })
        actions.addView(actionButton("Next") { search(false, false) })
        content.addView(actions)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            activeTab?.webView?.evaluateJavascript("window.getSelection && window.getSelection().removeAllRanges();", null)
        }
        dialog.show()
    }

    private fun buildFindCountScript(query: String, matchCase: Boolean, wholeWords: Boolean): String {
        val escapedQuery = JSONObject.quote(query)
        val flags = if (matchCase) "g" else "gi"
        val sourceExpression = if (wholeWords) {
            "\"(^|[^A-Za-z0-9_])\" + escaped + \"(?=$|[^A-Za-z0-9_])\""
        } else {
            "escaped"
        }
        return """(function(){
            try {
                const q = $escapedQuery;
                if (!q) return 0;
                let escaped = '';
                const special = '\\^$.*+?()[]{}|';
                for (let i = 0; i < q.length; i++) {
                    const ch = q.charAt(i);
                    if (special.indexOf(ch) >= 0) escaped += '\\';
                    escaped += ch;
                }
                const source = $sourceExpression;
                const re = new RegExp(source, '$flags');
                const root = document.body || document.documentElement;
                if (!root) return 0;
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                let count = 0, node;
                while ((node = walker.nextNode())) {
                    const parent = node.parentElement;
                    if (!parent || /^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT)$/i.test(parent.tagName)) continue;
                    const found = node.nodeValue.match(re);
                    if (found) count += found.length;
                }
                return count;
            } catch (e) { return 0; }
        })();"""
    }

    private fun runWindowFind(
        webView: WebView,
        query: String,
        matchCase: Boolean,
        wholeWords: Boolean,
        backwards: Boolean,
        callback: (Boolean) -> Unit
    ) {
        val q = JSONObject.quote(query)
        val script = """(function(){
            try { return !!window.find($q, $matchCase, $backwards, true, $wholeWords, true, false); }
            catch (e) { return false; }
        })();"""
        webView.evaluateJavascript(script) { raw -> callback(raw == "true") }
    }

    private fun showTabsDialog(groupFilter: String? = null) {
        if (tabs.isEmpty()) {
            createTab(null)
            return
        }
        val groups = dataStore.tabGroups()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val groupHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val groupScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val groupChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addGroupChip(label: String, id: String?, selected: Boolean, group: BrowserTabGroup? = null) {
            val chip = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(getColor(if (selected) R.color.nexora_browser_text else R.color.nexora_browser_muted))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(if (selected) R.drawable.omnibox_bg else android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialogRef?.dismiss()
                    showTabsDialog(id)
                }
                if (group != null) {
                    setOnLongClickListener {
                        showTabGroupActions(group)
                        true
                    }
                }
            }
            groupChips.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply { marginEnd = dp(5) })
        }
        addGroupChip("All", FILTER_ALL, groupFilter == FILTER_ALL || groupFilter == null)
        addGroupChip("Ungrouped", FILTER_UNGROUPED, groupFilter == FILTER_UNGROUPED)
        groups.forEach { group ->
            addGroupChip(group.name, group.id, groupFilter == group.id, group)
        }
        groupScroll.addView(
            groupChips,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        groupHeader.addView(groupScroll, LinearLayout.LayoutParams(0, dp(42), 1f))
        val addGroup = TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nexora_browser_text))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                dialogRef?.dismiss()
                showCreateTabGroupDialog()
            }
        }
        groupHeader.addView(addGroup, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(groupHeader)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copyAll = TextView(this).apply {
            text = "COPY ALL TAB URLS"
            textSize = 10f
            setTextColor(getColor(R.color.nexora_browser_muted))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                val urls = tabs.map { it.url }.filter { it.startsWith("http://") || it.startsWith("https://") }
                copyText(urls.joinToString("\n"))
                dialogRef?.dismiss()
            }
        }
        actionRow.addView(copyAll, LinearLayout.LayoutParams(0, dp(40), 1f))
        val createGroup = TextView(this).apply {
            text = "NEW GROUP"
            textSize = 10f
            setTextColor(getColor(R.color.nexora_accent))
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                dialogRef?.dismiss()
                showCreateTabGroupDialog()
            }
        }
        actionRow.addView(createGroup)
        content.addView(actionRow)

        val visibleTabs = when (groupFilter) {
            FILTER_UNGROUPED -> tabs.filter { it.groupId == null }
            null, FILTER_ALL -> tabs
            else -> tabs.filter { it.groupId == groupFilter }
        }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        visibleTabs.forEachIndexed { index, tab ->
            val groupName = groups.firstOrNull { it.id == tab.groupId }?.name ?: "Ungrouped"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnLongClickListener {
                    val actions = arrayOf("Copy URL", "Share URL", "Move to group", "Close tab")
                    AlertDialog.Builder(this@BrowserActivity)
                        .setTitle(tab.title)
                        .setItems(actions) { _, which ->
                            when (which) {
                                0 -> copyText(tab.url)
                                1 -> shareUrl(tab.url)
                                2 -> {
                                    dialogRef?.dismiss()
                                    showMoveTabToGroupDialog(tab)
                                }
                                3 -> {
                                    closeTab(tab.id)
                                    dialogRef?.dismiss()
                                    showTabsDialog(groupFilter)
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            val label = TextView(this).apply {
                text = "${index + 1}. ${tab.title}\n$groupName · ${tab.url.ifBlank { "New tab" }}"
                textSize = 12f
                setTextColor(getColor(R.color.nexora_browser_text))
                maxLines = 3
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    switchToTab(tab.id)
                    dialogRef?.dismiss()
                }
            }
            val close = TextView(this).apply {
                text = "×"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.nexora_browser_muted))
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener {
                    closeTab(tab.id)
                    dialogRef?.dismiss()
                    showTabsDialog(groupFilter)
                }
            }
            row.addView(label)
            row.addView(close)
            list.addView(row)
        }
        if (visibleTabs.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No tabs in this group yet."
                textSize = 13f
                setTextColor(getColor(R.color.nexora_browser_muted))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(30), dp(12), dp(30))
            }
            list.addView(empty)
        }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(330)
        ))
        val dialog = AlertDialog.Builder(this)
            .setTitle("${visibleTabs.size} of ${tabs.size} tabs")
            .setView(content)
            .setNeutralButton("Recently closed", null)
            .setNegativeButton("Done", null)
            .setPositiveButton("New tab", null)
            .create()
        dialogRef = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                val targetGroup = when (groupFilter) {
                    FILTER_UNGROUPED -> null
                    null, FILTER_ALL -> activeTab?.groupId
                    else -> groupFilter
                }
                createTab(null, targetGroup)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                showRecentlyClosedDialog()
            }
        }
        dialog.show()
    }

    private fun showCreateTabGroupDialog() {
        val input = EditText(this).apply {
            hint = "Group name"
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("New tab group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val group = dataStore.addTabGroup(input.text?.toString().orEmpty())
                if (group == null) toast("Use a unique name (up to 32 characters); maximum 30 groups")
                else showTabsDialog(group.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveTabToGroupDialog(tab: BrowserTab) {
        val groups = dataStore.tabGroups()
        val labels = (listOf("Ungrouped") + groups.map { it.name }).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move tab to group")
            .setItems(labels) { _, which ->
                tab.groupId = if (which == 0) null else groups[which - 1].id
                persistSession()
                showTabsDialog(tab.groupId ?: FILTER_UNGROUPED)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTabGroupActions(group: BrowserTabGroup) {
        dialogRef?.dismiss()
        val actions = arrayOf("Rename group", "Delete group")
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(actions) { _, which ->
                if (which == 0) showRenameTabGroupDialog(group) else {
                    AlertDialog.Builder(this)
                        .setTitle("Delete group?")
                        .setMessage("Tabs in this group will remain open and become ungrouped.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            dataStore.removeTabGroup(group.id)
                            tabs.forEach { if (it.groupId == group.id) it.groupId = null }
                            persistSession()
                            showTabsDialog()
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameTabGroupDialog(group: BrowserTabGroup) {
        val input = EditText(this).apply {
            setText(group.name)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename group")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (!dataStore.renameTabGroup(group.id, input.text?.toString().orEmpty())) {
                    toast("Use a unique name (up to 32 characters); maximum 30 groups")
                }
                showTabsDialog(group.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreSessionOrStart() {
        val snapshot = dataStore.sessionSnapshot()
        if (snapshot.tabs.isEmpty()) {
            val startup = profile.startupUrls.ifEmpty { listOf(profile.startupUrl).filter { it.isNotBlank() } }
                .take(MAX_RESTORED_TABS)
            if (startup.isEmpty()) {
                createTab(null)
            } else {
                startup.forEach { url -> createTab(url) }
            }
            return
        }
        val restored = snapshot.tabs.take(MAX_RESTORED_TABS)
        restored.forEach { saved ->
            val target = saved.url.takeIf { it.isNotBlank() }
            createTab(target, saved.groupId, saved.desktopMode, saved.title)
        }
        val targetTab = tabs.getOrNull(snapshot.activeIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
            ?: tabs.firstOrNull()
        targetTab?.let { switchToTab(it.id) }
        toast("Restored ${tabs.size} tabs")
    }

    /** Coalesce rapid navigation/title/tab events into one metadata write. */
    private fun persistSession() {
        if (!::dataStore.isInitialized || clearSessionOnExit || tabs.isEmpty()) return
        sessionPersistHandler.removeCallbacks(sessionPersistRunnable)
        sessionPersistHandler.postDelayed(sessionPersistRunnable, SESSION_PERSIST_DEBOUNCE_MS)
    }

    /** Flush synchronously at lifecycle boundaries so process recreation keeps the latest session. */
    private fun persistSessionNow() {
        sessionPersistHandler.removeCallbacks(sessionPersistRunnable)
        if (!::dataStore.isInitialized || clearSessionOnExit || tabs.isEmpty()) return
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        val selection = BrowserSessionPolicy.selectForPersistence(
            tabs.map { tab ->
                SessionTabState(
                    url = tab.url.ifBlank { HOME_URL },
                    title = tab.title,
                    desktopMode = tab.desktopMode,
                    groupId = tab.groupId
                )
            },
            activeIndex
        )
        val snapshot = selection.tabs.map { tab ->
            BrowserSessionTab(
                url = tab.url,
                title = tab.title,
                desktopMode = tab.desktopMode,
                groupId = tab.groupId
            )
        }
        dataStore.saveSession(snapshot, selection.activeIndex)
    }

    private var dialogRef: AlertDialog? = null

    private fun showRecentlyClosedDialog() {
        val closed = dataStore.recentlyClosed()
        if (closed.isEmpty()) {
            toast("No recently closed tabs")
            return
        }
        val labels = closed.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recently closed")
            .setItems(labels) { _, which ->
                val page = closed[which]
                dataStore.removeRecentlyClosed(page.url)
                createTab(page.url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBookmarksDialog() {
        val bookmarks = dataStore.bookmarks()
        if (bookmarks.isEmpty()) {
            toast("No bookmarks yet")
            return
        }
        val labels = bookmarks.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which ->
                activeTab?.let { loadIntoTab(it, bookmarks[which].url) } ?: createTab(bookmarks[which].url)
            }
            .setNeutralButton("Manage") { _, _ -> showBookmarkManager() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBookmarkManager() {
        val bookmarks = dataStore.bookmarks()
        if (bookmarks.isEmpty()) {
            toast("No bookmarks to manage")
            return
        }
        val labels = bookmarks.map { "Remove · ${it.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Remove bookmark")
            .setItems(labels) { _, which ->
                dataStore.removeBookmark(bookmarks[which].url)
                toast("Bookmark removed")
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun toggleBookmark() {
        val tab = activeTab ?: return
        if (tab.url.isBlank() || tab.url == HOME_URL) {
            toast("Open a website first")
            return
        }
        dataStore.addBookmark(tab.url, tab.title)
        toast("Bookmark saved")
    }

    private fun showHistoryDialog() {
        val history = dataStore.history()
        if (history.isEmpty()) {
            toast("No browsing history")
            return
        }
        val labels = history.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(labels) { _, which ->
                activeTab?.let { loadIntoTab(it, history[which].url) } ?: createTab(history[which].url)
            }
            .setNeutralButton("Clear history") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Clear history?")
                    .setMessage("This removes the local history for this profile.")
                    .setPositiveButton("Clear") { _, _ -> dataStore.clearHistory(); toast("History cleared") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDownloadsDialog() {
        val downloads = dataStore.downloads()
        if (downloads.isEmpty()) {
            toast("No downloads started in this profile")
            return
        }
        val labels = downloads.map { "${it.fileName} · ${it.status}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Downloads")
            .setItems(labels) { _, which -> openDownloadedFile(downloads[which].id) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Downloading and sharing

    private fun downloadUrl(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            toast("Only HTTP and HTTPS downloads are supported")
            return
        }

        requestDownloadNotificationPermission()

        val profileCookieManager = try {
            androidx.webkit.ProfileStore.getInstance()
                .getProfile(profile.webViewProfileKey)?.cookieManager
        } catch (_: Exception) {
            null
        }
        if (profileCookieManager == null) {
            toast("Could not access this profile's cookie store. Download cancelled to protect profile isolation.")
            return
        }

        val activeRelay = if (proxyRoutingActive) proxyRelay?.takeIf { it.isRunning } else null
        if (proxyRoutingActive && activeRelay == null) {
            toast("The assigned proxy relay is unavailable. Download cancelled; no direct fallback was attempted.")
            return
        }
        val networkProxy = activeRelay?.let {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it.localPort))
        } ?: Proxy.NO_PROXY

        val directory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "downloads")
        if (!directory.exists() && !directory.mkdirs()) {
            toast("Could not create the downloads directory")
            return
        }
        val suggested = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val safeName = File(suggested).name
            .replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(160)
            .trim('.')
            .ifBlank { "download" }
        val destination = uniqueDownloadFile(directory, safeName)
        val id = NEXT_DOWNLOAD_ID.incrementAndGet()
        val ua = userAgent ?: activeTab?.webView?.settings?.userAgentString
        dataStore.addDownload(id, destination.name, status = "QUEUED", mimeType = mimeType.orEmpty())
        toast("Download queued")

        DOWNLOAD_EXECUTOR.execute {
            dataStore.updateDownload(id, status = "DOWNLOADING", mimeType = mimeType.orEmpty())
            var lastProgress = 0L
            try {
                val client = ProfileDownloadClient(
                    proxy = networkProxy,
                    userAgent = ua,
                    cookieForUrl = { requestUrl -> profileCookieManager.getCookie(requestUrl) }
                )
                val result = client.download(url, destination) { downloaded, total ->
                    if (downloaded - lastProgress >= DOWNLOAD_PROGRESS_STEP || downloaded == total) {
                        dataStore.updateDownload(
                            id = id,
                            status = "DOWNLOADING",
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            mimeType = mimeType.orEmpty()
                        )
                        lastProgress = downloaded
                    }
                }
                dataStore.updateDownload(
                    id = id,
                    status = "COMPLETE",
                    localPath = destination.absolutePath,
                    downloadedBytes = result.bytesDownloaded,
                    totalBytes = result.totalBytes,
                    mimeType = result.mimeType.ifBlank { mimeType.orEmpty() }
                )
                runOnUiThread {
                    BrowserNotifications.notifyDownload(
                        applicationContext, profile.id, id, destination.name, success = true
                    )
                    if (!isFinishing && !isDestroyed) toast("Download complete: ${destination.name}")
                }
            } catch (error: Exception) {
                dataStore.updateDownload(
                    id = id,
                    status = "FAILED",
                    downloadedBytes = 0L,
                    totalBytes = -1L,
                    mimeType = mimeType.orEmpty()
                )
                runOnUiThread {
                    BrowserNotifications.notifyDownload(
                        applicationContext,
                        profile.id,
                        id,
                        destination.name,
                        success = false,
                        detail = error.message ?: "Network or storage error"
                    )
                    if (!isFinishing && !isDestroyed) {
                        toast("Download failed: ${error.message ?: "network or storage error"}")
                    }
                }
            }
        }
    }

    private fun requestDownloadNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences(NexoraApp.PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(NexoraApp.KEY_DOWNLOAD_NOTIFICATIONS, true)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        if (!prefs.getBoolean(NexoraApp.KEY_NOTIFICATION_PERMISSION_ASKED, false)) {
            prefs.edit().putBoolean(NexoraApp.KEY_NOTIFICATION_PERMISSION_ASKED, true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun uniqueDownloadFile(directory: File, requestedName: String): File {
        val name = File(requestedName).name.ifBlank { "download" }
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index += 1
        }
        return candidate
    }

    private fun openDownloadedFile(id: Long) {
        val record = dataStore.downloads().firstOrNull { it.id == id }
        if (record != null && record.status != "LEGACY") {
            if (record.status != "COMPLETE" || record.localPath.isBlank()) {
                toast(if (record.status == "FAILED") "This download failed" else "Download is not complete yet")
                return
            }
            val file = File(record.localPath)
            if (!file.exists()) {
                toast("Downloaded file is no longer available")
                return
            }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val mime = record.mimeType.ifBlank {
                    java.net.URLConnection.guessContentTypeFromName(file.name) ?: "*/*"
                }
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (_: Exception) {
                toast("No app can open this download")
            }
            return
        }

        // Alpha18 DownloadManager records remain readable after upgrading.
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id)
            if (uri == null) {
                toast("Download is not complete yet")
                return
            }
            val mime = manager.getMimeTypeForDownloadedFile(id) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            toast("No app can open this download")
        }
    }

    private fun shareUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share page"))
    }

    private fun copyText(value: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NEXORA URL", value))
        toast("Copied")
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("No app can open this link")
        }
    }

    // ---------------------------------------------------------------------
    // Profile and utility actions

    private fun showSiteInfoDialog() {
        val currentUrl = activeTab?.url.orEmpty()
        val isHome = currentUrl == HOME_URL || currentUrl.isBlank()
        val scheme = Uri.parse(currentUrl).scheme?.lowercase().orEmpty()
        val host = Uri.parse(currentUrl).host.orEmpty()
        val connection = when {
            isHome -> "Local browser start page"
            scheme == "https" -> "HTTPS transport"
            scheme == "http" -> "HTTP · not encrypted"
            else -> "Unknown or local content"
        }
        val detail = buildString {
            append("Site: ").append(host.ifBlank { if (isHome) "New tab" else "Unavailable" })
            append("\nConnection: ").append(connection)
            append("\nProfile: ").append(profile.name)
            append("\n\nThis indicator reports the page URL scheme only. It does not verify certificate trust, DNS routing, proxy routing, or whether a site is safe.")
        }
        AlertDialog.Builder(this)
            .setTitle("Connection information")
            .setMessage(detail)
            .setPositiveButton("Privacy Center") { _, _ ->
                startActivity(
                    Intent(this, PrivacyCenterActivity::class.java)
                        .putExtra(PrivacyCenterActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPrivacyDiagnosticsDialog() {
        val tab = activeTab
        val proxyStatus = when {
            proxyRoutingActive && proxyRelay?.isRunning == true -> "Active through loopback relay"
            proxyRoutingActive -> "Configured, but relay unavailable"
            else -> "Direct connection (no proxy assigned)"
        }
        val fingerprintStatus = when {
            tab?.documentStartInstalled == true -> "Document-start script registered"
            fpScript.isNotBlank() -> "Best-effort page-start fallback"
            else -> "No fingerprint override script"
        }
        val blockerEnabled = contentBlockerStore.snapshot().enabled
        val message = buildString {
            append("Profile: ").append(profile.name).append('\n')
            append("Named WebView storage: ").append(profile.webViewProfileKey).append('\n')
            append("Multi-profile API: ").append(
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) "Supported" else "Unavailable"
            ).append('\n')
            append("Network route: ").append(proxyStatus).append('\n')
            append("Safe Browsing: ").append(if (PrivacyPolicy.SAFE_BROWSING_ENABLED) "On" else "Off").append('\n')
            append("Mixed content: ").append(if (PrivacyPolicy.BLOCK_MIXED_CONTENT) "Blocked" else "Compatibility mode").append('\n')
            append("Third-party cookies: ").append(if (PrivacyPolicy.THIRD_PARTY_COOKIES_ALLOWED) "Allowed" else "Blocked").append('\n')
            append("Web permissions: Deny by default\n")
            append("Website geolocation: Deny by default\n")
            append("Fingerprint injection: ").append(fingerprintStatus).append('\n')
            append("Content blocker: ").append(if (blockerEnabled) "Enabled" else "Disabled").append('\n')
            append("Blocked resources this session: ").append(blockedResourcesThisSession.get()).append('\n')
            append("WebRTC setting: ").append(if (profile.webrtcBlock) "JavaScript-level block preference" else "Default WebView behavior").append('\n')
            append("Hardware MAC control: Not available\n\n")
            append("This screen reports local configuration only. It does not perform a live IP, DNS or WebRTC leak test.")
        }
        AlertDialog.Builder(this)
            .setTitle("Privacy diagnostics")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showProfileInfo() {
        val presetName = preset?.displayName ?: profile.deviceId
        val osLabel = if (preset?.isIos == true) "iOS ${profile.osVersion} (emulation)" else "Android ${profile.osVersion}"
        val proxyLabel = if (profile.hasProxy) "${profile.proxyType} ${profile.proxyHost}:${profile.proxyPort}" else "None"
        val message = buildString {
            append("Name: ").append(profile.name).append('\n')
            append("Device: ").append(presetName).append('\n')
            append("OS: ").append(osLabel).append('\n')
            fun capacity(gb: Int) = if (gb > 0) "$gb GB" else "Unknown"
            append("RAM / storage: ").append(capacity(profile.ramGb)).append(" / ").append(capacity(profile.storageGb)).append('\n')
            if (profile.manualSafariVersion.isNotBlank() && preset?.safariVersion.isNullOrBlank()) {
                append("Safari version (manual): ").append(profile.manualSafariVersion).append('\n')
            }
            append("Proxy: ").append(proxyLabel).append('\n')
            append("Timezone: ").append(profile.timezoneId.ifEmpty { "Auto (device)" }).append('\n')
            append("Language: ").append(profile.language).append('\n')
            append("WebRTC: ").append(if (profile.webrtcBlock) "Blocked" else "Default").append('\n')
            append("Fingerprint script: ").append(
                if (activeTab?.documentStartInstalled == true) "Document-start registered"
                else "Best-effort fallback"
            ).append("\n\n")
            append("User-Agent:\n").append(profile.userAgent)
            append("\n\nFingerprint changes are JavaScript-level, not native Chromium modifications.")
        }
        AlertDialog.Builder(this)
            .setTitle("Profile information")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear profile data")
            .setMessage("Remove cookies, site storage and cache for \"${profile.name}\"? Bookmarks and history metadata are separate.")
            .setPositiveButton("Clear") { _, _ ->
                clearProfileData()
                toast("Profile data cleared")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearProfileData() {
        clearSessionOnExit = true
        sessionPersistHandler.removeCallbacks(sessionPersistRunnable)
        dataStore.clearSession()
        activeTab?.webView?.stopLoading()
        try {
            val webProfile = androidx.webkit.ProfileStore.getInstance()
                .getProfile(profile.webViewProfileKey)
            webProfile?.cookieManager?.removeAllCookies(null)
            webProfile?.cookieManager?.flush()
            webProfile?.webStorage?.deleteAllData()
            tabs.forEach { it.webView.clearCache(true); it.webView.clearHistory(); it.webView.clearFormData() }
        } catch (e: Exception) {
            toast("Some website data could not be cleared: ${e.message ?: "unknown error"}")
        }
    }

    private fun updateToolbar() {
        val tab = activeTab
        binding.btnTabs.text = tabs.size.toString()
        binding.btnTabs.contentDescription = "Tabs, ${tabs.size} open"
        val currentUrl = tab?.url.orEmpty()
        val secure = currentUrl.startsWith("https://", true) && currentUrl != HOME_URL
        binding.btnSiteInfo.setImageResource(
            if (secure) R.drawable.ic_lock else R.drawable.ic_info
        )
        binding.btnSiteInfo.contentDescription =
            if (secure) "Secure connection information" else "Connection information"
        binding.btnBack.isEnabled = tab?.webView?.canGoBack() == true
        binding.btnForward.isEnabled = tab?.webView?.canGoForward() == true
        binding.btnBack.alpha = if (binding.btnBack.isEnabled) 1f else 0.38f
        binding.btnForward.alpha = if (binding.btnForward.isEnabled) 1f else 0.38f
        if (tab != null && !binding.etOmnibox.hasFocus()) {
            binding.etOmnibox.setText(if (tab.url == HOME_URL) "" else tab.url)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Uses WebView history before closing the browser tab")
    override fun onBackPressed() {
        val tab = activeTab
        when {
            tab?.webView?.canGoBack() == true -> tab.webView.goBack()
            tabs.size > 1 && tab != null -> closeTab(tab.id)
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::dataStore.isInitialized && dataStore.consumePrivacyResetPending() && tabs.isNotEmpty()) {
            resetLiveTabsAfterPrivacyCleanup()
        }
        activeTab?.webView?.onResume()
        tabs.filterNot { it.id == activeTabId }.forEach { it.webView.onPause() }
    }

    private fun resetLiveTabsAfterPrivacyCleanup() {
        tabs.toList().forEach { tab ->
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            runCatching {
                tab.webView.stopLoading()
                tab.webView.clearHistory()
                tab.webView.clearFormData()
                tab.webView.destroy()
            }
        }
        tabs.clear()
        activeTabId = null
        dataStore.clearSession()
        createTab(null)
        toast("Open tabs and in-memory navigation history were reset")
    }

    override fun onPause() {
        activityResumed = false
        persistSessionNow()
        tabs.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        if (!isClosing) {
            isClosing = true
            persistSessionNow()
            tabs.forEach { tab ->
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
            tabs.clear()
        }
        releaseNetworkSession()
        super.onDestroy()
    }

    private fun resolveRedirectPlaceholder() = Unit

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val HOME_BASE = "https://nexora.invalid/"
        private const val HOME_URL = HOME_BASE
        private const val MAX_RESTORED_TABS = BrowserSessionPolicy.MAX_PERSISTED_TABS
        private const val MAX_LIVE_TABS = 20
        private const val SESSION_PERSIST_DEBOUNCE_MS = 350L
        private const val MAX_REDIRECT_HOPS = 10
        private const val DOWNLOAD_PROGRESS_STEP = 512L * 1024L
        private val NEXT_DOWNLOAD_ID = AtomicLong(System.currentTimeMillis() * 1000L)
        private val DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "NEXORA-Profile-Download").apply { isDaemon = true }
        }
        private const val FILTER_ALL = "__nexora_all_tabs__"
        private const val FILTER_UNGROUPED = "__nexora_ungrouped_tabs__"
    }
}
