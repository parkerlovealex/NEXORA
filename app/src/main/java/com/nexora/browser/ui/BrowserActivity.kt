package com.nexora.browser.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexora.browser.NexoraApp
import com.nexora.browser.R
import com.nexora.browser.data.BrowserDataStore
import com.nexora.browser.data.BrowserPageRecord
import com.nexora.browser.data.BrowserSessionTab
import com.nexora.browser.data.BrowserTabGroup
import com.nexora.browser.data.BrowserProfile
import com.nexora.browser.data.DeviceDb
import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.ProfileStore
import com.nexora.browser.databinding.ActivityBrowserBinding
import com.nexora.browser.fp.FingerprintScript
import com.nexora.browser.util.UaBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

private data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val webView: WebView,
    var title: String = "New tab",
    var url: String = "",
    var desktopMode: Boolean = false,
    var groupId: String? = null
)

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: BrowserProfile
    private lateinit var dataStore: BrowserDataStore
    private var preset: DevicePreset? = null
    private var fpScript: String = ""
    private var docStartSupported = false
    private var proxyReady = false
    private var isClosing = false
    private var clearSessionOnExit = false

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: String? = null
    private val redirectChain = mutableSetOf<String>()

    private val activeTab: BrowserTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ProfileStore(this)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val loaded = store.getById(profileId)
        if (loaded == null) {
            toast("Profile not found")
            finish()
            return
        }
        profile = loaded
        preset = DeviceDb.find(this, profile.deviceId)
        dataStore = BrowserDataStore(this, profile.id)
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

        resolveIpGeoIfNeeded {
            if (isFinishing || isDestroyed) return@resolveIpGeoIfNeeded
            fpScript = preset?.let { FingerprintScript.build(profile, it) }.orEmpty()
            setupControls()
            applyProxy {
                if (!isFinishing && !isDestroyed) {
                    proxyReady = true
                    restoreSessionOrStart()
                }
            }
        }
    }

    /**
     * IP-based timezone/language: when a profile's modes are "IP" and the
     * stored values were not resolved for the current connection (proxy or
     * direct), fetch them through that connection before the fingerprint
     * script is built. On failure the session falls back to device values.
     */
    private fun resolveIpGeoIfNeeded(done: () -> Unit) {
        val wantsIp = profile.timezoneMode == "IP" || profile.languageMode == "IP"
        val key = com.nexora.browser.util.IpGeoResolver.connectionKey(profile)
        if (!wantsIp || profile.ipGeoKey == key) {
            done()
            return
        }
        toast("Resolving timezone and language from IP...")
        kotlin.concurrent.thread(name = "ip-geo-resolve") {
            val geo = com.nexora.browser.util.IpGeoResolver.resolve(profile)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (geo != null) {
                    if (profile.timezoneMode == "IP") profile.timezoneId = geo.timezone
                    if (profile.languageMode == "IP") profile.language = geo.language
                    profile.ipGeoKey = key
                    store.update(profile)
                } else {
                    toast("Could not fetch IP location. Using device timezone and language this session.")
                }
                done()
            }
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            activeTab?.webView?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.btnForward.setOnClickListener {
            activeTab?.webView?.let { if (it.canGoForward()) it.goForward() }
        }
        binding.btnReload.setOnClickListener {
            activeTab?.webView?.reload() ?: createTab(null)
        }
        binding.btnNewTab.setOnClickListener { createTab(null) }
        binding.btnHome.setOnClickListener { loadHome() }
        binding.btnBookmarks.setOnClickListener { showBookmarksDialog() }
        binding.btnTabs.setOnClickListener { showTabsDialog() }
        binding.btnDownloads.setOnClickListener { showDownloadsDialog() }
        binding.btnOverflow.setOnClickListener { showOverflowMenu() }

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
        if (!proxyReady && profile.proxyType != "NONE") return
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
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            userAgentString = profile.userAgent.ifBlank { WebSettings.getDefaultUserAgent(this@BrowserActivity) }
        }

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
        if (fpScript.isNotBlank() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, fpScript, setOf("*"))
                docStartSupported = true
            } catch (_: Exception) {
                docStartSupported = false
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

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val current = tabFor(view) ?: return
                if (!url.isNullOrBlank()) current.url = url
                persistSession()
                if (!docStartSupported && fpScript.isNotBlank()) {
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
                    toast("Page failed to load")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
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
        activeTabId = id
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
            tab.webView.loadDataWithBaseURL(HOME_BASE, NEW_TAB_HTML, "text/html", "UTF-8", null)
        } else {
            tab.webView.loadUrl(url)
        }
        if (tab.id == activeTabId) {
            binding.etOmnibox.setText(if (url == HOME_URL) "" else url)
            updateToolbar()
        }
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
            tab.desktopMode -> UaBuilder.DESKTOP_UA
            else -> profile.userAgent.ifBlank { WebSettings.getDefaultUserAgent(this) }
        }
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
            setTextColor(getColor(R.color.nexora_text))
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
        if (profile.proxyType == "NONE") {
            clearProxy(onReady)
            return
        }
        if (!profile.hasProxy || profile.proxyType !in setOf("HTTP", "SOCKS5")) {
            toast("Proxy settings are invalid. Browsing stopped.")
            finish()
            return
        }
        if (profile.proxyUser.isNotEmpty() || profile.proxyPass.isNotEmpty()) {
            toast("This WebView cannot authenticate to the configured proxy. Browsing stopped.")
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            toast("Proxy override is unavailable in this WebView. Browsing stopped.")
            finish()
            return
        }
        try {
            val scheme = if (profile.proxyType == "SOCKS5") "socks://" else "http://"
            val rule = scheme + profile.proxyHost + ":" + profile.proxyPort
            val config = ProxyConfig.Builder().addProxyRule(rule).build()
            ProxyController.getInstance().setProxyOverride(
                config,
                ContextCompat.getMainExecutor(this),
                Runnable { onReady() }
            )
        } catch (e: Exception) {
            toast("Could not apply proxy: ${e.message ?: "unknown error"}")
            finish()
        }
    }

    private fun clearProxy(onCleared: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            onCleared?.invoke()
            return
        }
        try {
            ProxyController.getInstance().clearProxyOverride(
                ContextCompat.getMainExecutor(this),
                Runnable { onCleared?.invoke() }
            )
        } catch (_: Exception) {
            onCleared?.invoke()
        }
    }

    // ---------------------------------------------------------------------
    // Menus and context actions

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, binding.btnOverflow)
        val items = listOf(
            "New tab",
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
            "Share page",
            "Copy URL",
            "Check fingerprint",
            "Profile info",
            "Clear profile data",
            "Close tab"
        )
        items.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "New tab" -> createTab(null)
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
                "Share page" -> shareUrl(activeTab?.url.orEmpty())
                "Copy URL" -> copyText(activeTab?.url.orEmpty())
                "Check fingerprint" -> activeTab?.webView?.loadUrl("file:///android_asset/fp-test.html")
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
            setTextColor(getColor(R.color.nexora_text))
        }
        val wholeWords = CheckBox(this).apply {
            text = "Whole words"
            setTextColor(getColor(R.color.nexora_text))
        }
        val status = TextView(this).apply {
            text = "Enter text to search this page."
            textSize = 12f
            setTextColor(getColor(R.color.nexora_muted))
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
            setTextColor(getColor(R.color.nexora_text))
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
                setTextColor(getColor(if (selected) R.color.nexora_text else R.color.nexora_muted))
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
            setTextColor(getColor(R.color.nexora_text))
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
            setTextColor(getColor(R.color.nexora_muted))
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
                setTextColor(getColor(R.color.nexora_text))
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
                setTextColor(getColor(R.color.nexora_muted))
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
                setTextColor(getColor(R.color.nexora_muted))
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

    private fun persistSession() {
        if (!::dataStore.isInitialized || clearSessionOnExit) return
        if (tabs.isEmpty()) return
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
        val selectedTabs = if (tabs.size <= MAX_RESTORED_TABS || activeIndex < MAX_RESTORED_TABS) {
            tabs.take(MAX_RESTORED_TABS)
        } else {
            tabs.take(MAX_RESTORED_TABS - 1) + tabs[activeIndex]
        }
        val savedActiveIndex = if (activeIndex < MAX_RESTORED_TABS) {
            activeIndex.coerceAtMost(selectedTabs.lastIndex)
        } else {
            MAX_RESTORED_TABS - 1
        }
        val snapshot = selectedTabs.map { tab ->
            BrowserSessionTab(
                url = tab.url.ifBlank { HOME_URL },
                title = tab.title,
                desktopMode = tab.desktopMode,
                groupId = tab.groupId
            )
        }
        dataStore.saveSession(snapshot, savedActiveIndex)
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
        val labels = downloads.map { it.fileName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Downloads")
            .setItems(labels) { _, which -> openDownloadedFile(downloads[which].id) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // Downloading and sharing

    private fun downloadUrl(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("This download type is not supported by the current WebView download handler")
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            request.setTitle(fileName)
            request.setDescription(Uri.parse(url).host ?: "NEXORA download")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(false)
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            val ua = userAgent ?: activeTab?.webView?.settings?.userAgentString
            if (!ua.isNullOrBlank()) request.addRequestHeader("User-Agent", ua)
            val cookie = try {
                androidx.webkit.ProfileStore.getInstance()
                    .getProfile(profile.webViewProfileKey)?.cookieManager?.getCookie(url)
            } catch (_: Exception) {
                CookieManager.getInstance().getCookie(url)
            }
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            dataStore.addDownload(id, fileName)
            toast("Download started")
        } catch (e: Exception) {
            toast("Download failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun openDownloadedFile(id: Long) {
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

    private fun showProfileInfo() {
        val presetName = preset?.displayName ?: profile.deviceId
        val osLabel = if (preset?.isIos == true) "iOS ${profile.osVersion} (emulation)" else "Android ${profile.osVersion}"
        val proxyLabel = if (profile.hasProxy) "${profile.proxyType} ${profile.proxyHost}:${profile.proxyPort}" else "None"
        val message = buildString {
            append("Name: ").append(profile.name).append('\n')
            append("Device: ").append(presetName).append('\n')
            append("OS: ").append(osLabel).append('\n')
            append("RAM / storage: ").append(profile.ramGb).append(" GB / ").append(profile.storageGb).append(" GB\n")
            append("Proxy: ").append(proxyLabel).append('\n')
            append("Timezone: ").append(profile.timezoneId.ifEmpty { "Auto (device)" }).append('\n')
            append("Language: ").append(profile.language).append('\n')
            append("WebRTC: ").append(if (profile.webrtcBlock) "Blocked" else "Default").append('\n')
            append("Fingerprint script: ").append(if (docStartSupported) "Document start" else "Fallback").append("\n\n")
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
        binding.btnTabs.text = "▢ ${tabs.size}"
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
        tabs.forEach { it.webView.onResume() }
    }

    override fun onPause() {
        persistSession()
        tabs.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        if (!isClosing) {
            isClosing = true
            persistSession()
            tabs.forEach { tab ->
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.stopLoading()
                tab.webView.destroy()
            }
            tabs.clear()
            clearProxy()
        }
        super.onDestroy()
    }

    private fun resolveRedirectPlaceholder() = Unit

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val HOME_BASE = "https://nexora.invalid/"
        private const val HOME_URL = HOME_BASE
        private const val MAX_RESTORED_TABS = 20
        private const val MAX_REDIRECT_HOPS = 10
        private const val FILTER_ALL = "__nexora_all_tabs__"
        private const val FILTER_UNGROUPED = "__nexora_ungrouped_tabs__"
        private const val NEW_TAB_HTML = """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
            *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#fff;color:#202027;
            font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;
            text-align:center;padding:28px}main{max-width:420px}.mark{width:56px;height:56px;
            margin:0 auto 22px;border-radius:16px;background:#202027;color:#fff;display:grid;
            place-items:center;font-size:27px;font-weight:700}h1{font-size:25px;margin:0 0 10px}
            p{color:#777;font-size:14px;line-height:1.6}small{color:#999;letter-spacing:1px}
            </style></head><body><main><div class="mark">N</div><h1>Browse with focus.</h1>
            <p>Search or enter a website in the address bar.</p><small>NEXORA · PRIVATE WORKSPACE</small>
            </main></body></html>
        """
    }
}
