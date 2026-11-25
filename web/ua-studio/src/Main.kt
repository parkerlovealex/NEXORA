@file:Suppress("UnsafeCastFromDynamic", "unused")

import kotlin.js.JSON
import kotlin.random.Random
import kotlin.math.roundToInt

private val doc: dynamic = js("document")
private val win: dynamic = js("window")
private val appRoot: dynamic get() = doc.getElementById("app")

private data class Device(
    val platform: String,
    val id: String,
    val brand: String,
    val model: String,
    val uaModel: String,
    val osVersions: List<String>,
    val markets: List<String>,
    val raw: dynamic
)

private data class BrowserVersion(
    val uaVersion: String,
    val releaseVersion: String,
    val engineVersion: String,
    val channel: String
)

private data class DesktopOs(
    val id: String,
    val name: String,
    val version: String,
    val family: String,
    val baseBuild: String,
    val latestBuild: String,
    val latestSecurityBuild: String,
    val latestVersion: String,
    val latestBuildChannel: String
)

private data class UaResult(
    val id: String,
    val platform: String,
    val deviceId: String,
    val deviceModel: String,
    val modelIdentifier: String,
    val osVersion: String,
    val osReleaseName: String,
    val osBuild: String,
    val osSecurityBuild: String,
    val osBuildChannel: String,
    val browser: String,
    val browserVersion: String,
    val browserVersionToken: String,
    val browserVersionSource: String,
    val appId: String,
    val appVersion: String,
    val appBuild: String,
    val userAgent: String,
    val country: String,
    val language: String,
    val acceptLanguage: String,
    val sourceType: String,
    val note: String
)

private data class Country(val code: String, val name: String, val language: String)

private val countries = listOf(
    Country("", "Automatic / all markets", ""),
    Country("US", "United States", "en-US"), Country("GB", "United Kingdom", "en-GB"),
    Country("BD", "Bangladesh", "bn-BD"), Country("IN", "India", "en-IN"), Country("PK", "Pakistan", "ur-PK"),
    Country("CA", "Canada", "en-CA"), Country("AU", "Australia", "en-AU"), Country("DE", "Germany", "de-DE"),
    Country("FR", "France", "fr-FR"), Country("ES", "Spain", "es-ES"), Country("IT", "Italy", "it-IT"),
    Country("BR", "Brazil", "pt-BR"), Country("MX", "Mexico", "es-MX"), Country("JP", "Japan", "ja-JP"),
    Country("KR", "South Korea", "ko-KR"), Country("CN", "China", "zh-CN"), Country("TW", "Taiwan", "zh-TW"),
    Country("HK", "Hong Kong", "zh-HK"), Country("NL", "Netherlands", "nl-NL"), Country("SE", "Sweden", "sv-SE"),
    Country("NO", "Norway", "nb-NO"), Country("DK", "Denmark", "da-DK"), Country("FI", "Finland", "fi-FI"),
    Country("PL", "Poland", "pl-PL"), Country("GR", "Greece", "el-GR"), Country("TR", "Türkiye", "tr-TR"),
    Country("SA", "Saudi Arabia", "ar-SA"), Country("AE", "United Arab Emirates", "ar-AE"), Country("EG", "Egypt", "ar-EG"),
    Country("NG", "Nigeria", "en-NG"), Country("ZA", "South Africa", "en-ZA"), Country("ID", "Indonesia", "id-ID"),
    Country("MY", "Malaysia", "ms-MY"), Country("SG", "Singapore", "en-SG"), Country("PH", "Philippines", "en-PH"),
    Country("VN", "Vietnam", "vi-VN"), Country("TH", "Thailand", "th-TH"), Country("LK", "Sri Lanka", "si-LK"),
    Country("NP", "Nepal", "ne-NP")
)

private val platformLabels = mapOf(
    "android" to "Android", "ios" to "iOS", "windows" to "Windows", "macos" to "macOS"
)
private val browserLabels = mapOf(
    "chrome" to "Google Chrome", "safari" to "Safari", "firefox" to "Firefox",
    "edge" to "Microsoft Edge", "opera" to "Opera", "webview" to "Android WebView"
)
private val browserChoices = mapOf(
    "android" to listOf("chrome", "firefox", "edge", "opera", "webview"),
    "ios" to listOf("safari", "chrome", "firefox", "edge"),
    "windows" to listOf("chrome", "firefox", "edge", "opera"),
    "macos" to listOf("chrome", "firefox", "edge", "opera", "safari")
)
private val browserVersionKeys = mapOf(
    "android:chrome" to "chromeAndroid", "android:firefox" to "firefoxAndroid",
    "android:edge" to "edgeAndroid", "android:opera" to "operaAndroid",
    "ios:safari" to "safariIos", "ios:chrome" to "chromeIos",
    "ios:firefox" to "firefoxIos", "ios:edge" to "edgeIos"
)
private val appLabels = mapOf("facebook" to "Facebook", "instagram" to "Instagram")

private object Studio {
    private val devices = mutableListOf<Device>()
    private val desktopOs = mutableMapOf<String, List<DesktopOs>>()
    private val desktopBrowsers = mutableMapOf<String, Map<String, List<BrowserVersion>>>()
    private val mobileBrowsers = mutableMapOf<String, List<String>>()
    private val appVersions = mutableMapOf<String, Pair<String, String>>()
    private var meta: dynamic = null
    private var templateData: dynamic = null
    private var deviceVersion = "LOCAL"
    private var templateVersion = "—"
    private var chromeEngineVersion = ""

    private val activePlatforms = linkedSetOf("android")
    private val quantities = mutableMapOf("android" to 10, "ios" to 0, "windows" to 0, "macos" to 0)
    private var distribution = "random"
    private val selectedDevices = mutableSetOf<String>()
    private val customCounts = mutableMapOf<String, Int>()
    private var deviceSearch = ""
    private var marketCountry = ""
    private val osChoice = mutableMapOf("android" to "latest", "ios" to "latest", "windows" to "latest", "macos" to "latest")
    private val selectedBrowsers = mutableMapOf(
        "android" to linkedSetOf("chrome"), "ios" to linkedSetOf("safari"),
        "windows" to linkedSetOf("chrome"), "macos" to linkedSetOf("safari")
    )
    private var versionMode = "latest"
    private val catalogVersions = mutableMapOf<String, String>()
    private val manualVersions = mutableMapOf<String, String>()
    private var engineOverride = ""
    private var localeCountry = "US"
    private var languageOverride = ""
    private var deduplicate = true
    private var includeApps = false
    private val selectedApps = linkedSetOf("facebook", "instagram")
    private var resultFilter = "all"
    private var resultSearch = ""
    private var showAllResults = false
    private val results = mutableListOf<UaResult>()
    private var duplicatesBlocked = 0
    private var lastError = ""
    private var isReady = false
    private var eventBound = false

    fun start() {
        renderBoot("Loading device, browser and desktop catalogs…")
        fetchText("./data/devices.json", { text ->
            try {
                val data = JSON.parse<dynamic>(text)
                meta = data.meta
                deviceVersion = str(meta.dataVersion, "LOCAL")
                chromeEngineVersion = str(meta.versions.chrome)
                parseDevices(data)
                parseMobileBrowsers()
                parseApps()
                fetchText("./data/desktop-catalog.json", { desktopText ->
                    try {
                        parseDesktopCatalog(JSON.parse<dynamic>(desktopText))
                        fetchText("./data/ua_templates.json", { templateText ->
                            try {
                                templateData = JSON.parse<dynamic>(templateText)
                                templateVersion = str(templateData.dataVersion, "—")
                            } catch (_: Throwable) { templateVersion = "Unavailable" }
                            finishLoad()
                        }, { _ -> finishLoad() })
                    } catch (error: Throwable) {
                        renderBootError("Desktop catalog could not be read: ${error.message ?: "unknown error"}")
                    }
                }, { error -> renderBootError(error) })
            } catch (error: Throwable) {
                renderBootError("Device catalog could not be read: ${error.message ?: "unknown error"}")
            }
        }, { error -> renderBootError(error) })
    }

    private fun finishLoad() {
        isReady = true
        render()
        bindEvents()
        toast("Catalogs loaded · $deviceVersion", "success")
    }

    private fun fetchText(url: String, success: (String) -> Unit, failure: (String) -> Unit) {
        val fetchFn: dynamic = js("fetch")
        val promise: dynamic = fetchFn(url, js("({cache:'no-store'})"))
        promise.then({ response: dynamic ->
            if (response.ok as Boolean) {
                response.text().then({ text: String -> success(text); null })
            } else {
                failure("Could not load $url (HTTP ${str(response.status, "?")}).")
                null
            }
        }).catch({ error: dynamic ->
            failure(str(error.message, "Could not load $url."))
            null
        })
    }

    private fun parseDevices(data: dynamic) {
        devices.clear()
        parseDeviceList(data.android, "android")
        parseDeviceList(data.ios_emulation, "ios")
    }

    private fun parseDeviceList(rows: dynamic, platform: String) {
        for (row in dynList(rows)) {
            devices += Device(
                platform = platform,
                id = str(row.id), brand = str(row.brand), model = str(row.model),
                uaModel = str(row.uaModel, str(row.id)), osVersions = stringList(row.osVersions),
                markets = stringList(row.markets), raw = row
            )
        }
    }

    private fun parseMobileBrowsers() {
        val source = meta.browserVersions
        for ((key, value) in browserVersionKeys) mobileBrowsers[key] = stringList(source[value])
    }

    private fun parseApps() {
        val apps = meta.apps
        for (app in listOf("facebook", "instagram")) {
            val row = apps[app]
            appVersions[app] = Pair(str(row.version), str(row.build))
        }
    }

    private fun parseDesktopCatalog(data: dynamic) {
        for (platform in listOf("windows", "macos")) {
            val p = data.platforms[platform]
            val osRows = dynList(p.osVersions).map { row ->
                DesktopOs(
                    id = str(row.id), name = str(row.name), version = str(row.version), family = str(row.family),
                    baseBuild = str(row.baseBuild), latestBuild = str(row.latestBuild),
                    latestSecurityBuild = str(row.latestSecurityBuild), latestVersion = str(row.latestVersion),
                    latestBuildChannel = str(row.latestBuildChannel)
                )
            }
            desktopOs[platform] = osRows
            val browserMap = mutableMapOf<String, List<BrowserVersion>>()
            val browserObject = p.browsers
            for (browser in browserChoices[platform].orEmpty()) {
                val rows = dynList(browserObject[browser]?.versions)
                browserMap[browser] = rows.map { row ->
                    BrowserVersion(str(row.uaVersion), str(row.releaseVersion, str(row.uaVersion)), str(row.engineVersion, str(row.uaVersion)), str(row.channel))
                }
            }
            desktopBrowsers[platform] = browserMap
        }
    }

    private fun renderBoot(message: String) {
        appRoot.innerHTML = """
            <div class="boot-screen"><div class="boot-mark">N</div><strong>NEXORA</strong><span>${esc(message)}</span><div class="boot-bar"><i></i></div></div>
        """.trimIndent()
    }

    private fun renderBootError(message: String) {
        appRoot.innerHTML = """
            <main class="fatal-screen"><div class="boot-mark">N</div><p class="eyebrow">NEXORA / CATALOG ERROR</p><h1>Studio could not start.</h1><p>${esc(message)}</p><a class="button primary" href="../">Return to website</a><p class="muted">Check that the three JSON files exist inside <code>ua-studio/data/</code>.</p></main>
        """.trimIndent()
    }

    private fun render() {
        appRoot.innerHTML = pageHtml()
        updateBatchSummary()
        renderDeviceChooser()
        renderResults()
    }

    private fun pageHtml(): String = """
        <div class="studio-shell">
          <aside class="sidebar">
            <a class="brand" href="../"><span class="brand-mark">N</span><span><strong>NEXORA</strong><small>USER-AGENT STUDIO</small></span></a>
            <div class="workspace"><span class="workspace-icon">NX</span><span><b>Public workspace</b><small>Local catalog · read only</small></span><span class="workspace-dot"></span></div>
            <nav class="side-nav" aria-label="Studio navigation">
              <span class="nav-label">WORKSPACE</span>
              <a class="nav-link active" href="#builder"><span>✳</span>Generator</a>
              <a class="nav-link" href="#results"><span>▤</span>Results & exports</a>
              <a class="nav-link" href="#templates"><span>◈</span>App formats</a>
              <a class="nav-link" href="#data"><span>◉</span>Data & notes</a>
            </nav>
            <div class="sidebar-bottom">
              <div class="data-card"><div class="data-card-top"><span class="live-dot"></span> LOCAL DATA READY</div><b>$deviceVersion</b><small>$templateVersion app-template catalog</small><div class="data-card-line"><span>Mobile devices</span><strong>${devices.size}</strong></div><div class="data-card-line"><span>Desktop OS releases</span><strong>${desktopOs.values.sumOf { it.size }}</strong></div></div>
              <a class="back-link" href="../">← Back to NEXORA website</a>
              <div class="sidebar-foot">NEXORA NEXT <span>KOTLIN / JS</span></div>
            </div>
          </aside>
          <main class="main-area">
            <header class="topbar"><div class="crumbs"><span>WORKSPACE</span><b>/</b><strong>User-Agent Studio</strong></div><div class="top-actions"><span class="catalog-pill"><i></i> Catalog ${esc(deviceVersion)}</span><span class="engine-pill">KOTLIN / JS</span><a class="top-back" href="../">Website ↗</a></div></header>
            <div class="page-wrap">
              <section class="hero" id="builder"><div class="hero-copy"><div class="eyebrow"><span class="eyebrow-mark">✳</span> ADVANCED GENERATION WORKSPACE</div><h1>Build precise<br><em>User-Agent</em> datasets.</h1><p>Compose browser, operating-system and device profiles with version-aware controls. Generate up to 500 records in one batch — directly in your browser.</p><div class="hero-meta"><span><i class="live-dot"></i> LOCAL GENERATION</span><span>NO SERVER REQUIRED</span><span>FOUR PLATFORMS</span></div></div><div class="hero-art"><div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="hero-core"><span>NX</span><small>UA / 01</small></div><div class="float-chip chip-browser">CHROME <b>158</b></div><div class="float-chip chip-os">WINDOWS <b>11</b></div><div class="float-chip chip-mobile">ANDROID <b>17</b></div></div></section>
              <div class="metric-strip"><div><span>DEVICE RECORDS</span><strong>${devices.size}</strong><small>Android + iOS catalog</small></div><div><span>DESKTOP RELEASES</span><strong>${desktopOs.values.sumOf { it.size }}</strong><small>Windows + macOS</small></div><div><span>BROWSER FAMILIES</span><strong>5</strong><small>Chrome · Firefox · Edge · Opera · Safari</small></div><div><span>BATCH CAPACITY</span><strong>500</strong><small>Records per generation</small></div></div>
              <div class="workspace-grid">
                <section class="config-column">
                  <section class="panel section-panel"><div class="section-heading"><div><div class="step-label">01 / TARGET PLATFORMS</div><h2>Choose your platforms</h2><p>Combine mobile and desktop output in one batch.</p></div><span class="section-index">01</span></div>
                    <div class="preset-row"><button class="preset" data-action="preset-mobile">Mobile</button><button class="preset" data-action="preset-desktop">Desktop</button><button class="preset" data-action="preset-all">All platforms</button></div>
                    <div class="platform-grid">${platformCard("android")} ${platformCard("ios")} ${platformCard("windows")} ${platformCard("macos")}</div>
                    <div class="batch-presets"><span>QUICK BATCH</span>${listOf(10, 25, 50, 100, 250, 500).joinToString("") { n -> "<button data-action=\"batch-preset\" data-total=\"$n\">$n</button>" }}</div>
                  </section>
                  <section class="panel section-panel"><div class="section-heading"><div><div class="step-label">02 / DEVICE DISTRIBUTION</div><h2>Control the device mix</h2><p>Choose how mobile device records are sampled.</p></div><span class="section-index">02</span></div>
                    <div class="field-grid two"><label class="field"><span>DISTRIBUTION MODE</span><select id="distribution"><option value="random" ${sel(distribution,"random")}>Random across catalog</option><option value="balanced" ${sel(distribution,"balanced")}>Balanced across selected</option><option value="selected" ${sel(distribution,"selected")}>Selected devices only</option><option value="custom" ${sel(distribution,"custom")}>Custom per-device counts</option></select></label><label class="field"><span>DEVICE MARKET FILTER</span><select id="market-country">${countryOptions(marketCountry, true)}</select></label></div>
                    <div id="device-chooser" class="device-chooser"></div>
                  </section>
                  <section class="panel section-panel"><div class="section-heading"><div><div class="step-label">03 / OPERATING SYSTEM</div><h2>Set OS versions</h2><p>Latest supported, random supported or a specific release.</p></div><span class="section-index">03</span></div>
                    <div class="os-grid">${activePlatforms.joinToString("") { platform -> osField(platform) }}</div>
                    <div class="info-note"><span>i</span><p>Desktop browser User-Agent strings intentionally reduce OS detail. Selected Windows/macOS releases remain available in output metadata rather than being inserted into frozen browser tokens.</p></div>
                  </section>
                  <section class="panel section-panel"><div class="section-heading"><div><div class="step-label">04 / BROWSER MATRIX</div><h2>Select browser families</h2><p>Mix multiple browsers per platform. Versions come from the bundled catalog.</p></div><span class="section-index">04</span></div>
                    <label class="field version-mode"><span>BROWSER VERSION STRATEGY</span><select id="version-mode"><option value="latest" ${sel(versionMode,"latest")}>Latest catalog version</option><option value="random5" ${sel(versionMode,"random5")}>Random · latest five versions</option><option value="catalog" ${sel(versionMode,"catalog")}>Choose from version catalog</option><option value="manual" ${sel(versionMode,"manual")}>Manual version entry</option></select></label>
                    ${activePlatforms.joinToString("") { browserGroup(it) }}
                    <div class="field engine-field"><label for="engine-version">ANDROID CHROMIUM ENGINE VERSION</label><input id="engine-version" type="text" value="${esc(engineOverride.ifBlank { chromeEngineVersion })}" placeholder="159.0.8154.33"><small>Used by Android WebView, Edge and Opera. Chrome uses its own selected browser version.</small></div>
                  </section>
                  <section class="panel section-panel"><div class="section-heading"><div><div class="step-label">05 / LOCALE & OUTPUT</div><h2>Regional profile</h2><p>Attach country and language metadata to each generated record.</p></div><span class="section-index">05</span></div>
                    <div class="field-grid two"><label class="field"><span>LOCALE PRESET</span><select id="locale-country">${countryOptions(localeCountry, false)}</select></label><label class="field"><span>LANGUAGE TAG</span><input id="language-override" type="text" value="${esc(languageOverride)}" placeholder="Auto from locale · e.g. en-US"></label></div>
                    <div class="advanced-grid"><label class="toggle-row"><span><b>Deduplicate identical User-Agents</b><small>Skip repeated strings within the generated batch.</small></span><input id="deduplicate" type="checkbox" ${checked(deduplicate)}><i></i></label><label class="toggle-row"><span><b>Include Android in-app formats</b><small>Facebook / Instagram script candidates, clearly marked unverified.</small></span><input id="include-apps" type="checkbox" ${checked(includeApps)}><i></i></label></div>
                    ${if (includeApps && activePlatforms.contains("android")) appPickerHtml() else ""}
                    <div class="small-warning"><span>!</span><p>In-app formats are script-derived candidates, not independently verified observed traffic. They are labelled as such in results.</p></div>
                  </section>
                </section>
                <aside class="preview-column"><div class="panel sticky-panel"><div class="preview-head"><div><div class="step-label">LIVE CONFIGURATION</div><h2>Batch overview</h2></div><span class="ready-badge"><i></i> READY</span></div>
                  <div class="total-display"><strong id="total-count">${totalQuantity()}</strong><span> / 500</span><small>USER-AGENT RECORDS</small></div><div class="capacity-track"><i id="capacity-fill" style="width:${(totalQuantity().coerceIn(0,500) / 500.0 * 100)}%"></i></div>
                  <div id="platform-summary" class="platform-summary">${summaryRows()}</div>
                  <div class="preview-divider"></div><div class="preview-subhead">GENERATION CHECKLIST</div><ul class="check-list"><li><span>✓</span> Browser versions loaded</li><li><span>✓</span> Device and OS metadata ready</li><li><span>✓</span> Runs locally in your browser</li><li><span>✓</span> JSON / CSV / TXT export</li></ul>
                  <button class="button generate-button" data-action="generate"><span>Generate User-Agents</span><b>↗</b></button><p class="button-hint">Maximum 500 records per batch · no API required</p>
                  <div class="preview-foot"><span>CATALOG</span><b>${esc(deviceVersion)}</b><span>BUILD</span><b>KOTLIN / JS</b></div>
                </div><div class="panel helper-panel"><div class="helper-icon">⌘</div><div><b>Need a clean start?</b><p>Reset the batch configuration without removing generated results.</p><button data-action="reset-config" class="text-action">Reset configuration ↗</button></div></div></aside>
              </div>
              <section class="panel results-panel" id="results"><div class="results-head"><div><div class="step-label">06 / GENERATED OUTPUT</div><h2>Your User-Agent dataset</h2><p id="results-subtitle">Generate a batch to populate results and export files.</p></div><div class="result-actions"><button class="button subtle" data-action="copy-all" ${if (results.isEmpty()) "disabled" else ""}>Copy all</button><button class="button subtle" data-action="export-json" ${if (results.isEmpty()) "disabled" else ""}>JSON ↓</button><button class="button subtle" data-action="export-csv" ${if (results.isEmpty()) "disabled" else ""}>CSV ↓</button><button class="button subtle" data-action="export-txt" ${if (results.isEmpty()) "disabled" else ""}>TXT ↓</button><button class="button danger-quiet" data-action="clear-results" ${if (results.isEmpty()) "disabled" else ""}>Clear</button></div></div>
                <div class="result-toolbar"><div class="result-filters"><button data-action="filter-results" data-filter="all" class="filter-chip ${if (resultFilter=="all") "active" else ""}">All <b>${results.size}</b></button>${listOf("android","ios","windows","macos").map { p -> "<button data-action=\"filter-results\" data-filter=\"$p\" class=\"filter-chip ${if (resultFilter==p) "active" else ""}\">${platformLabels[p]} <b>${results.count { it.platform==platformLabels[p] }}</b></button>" }.joinToString("")}</div><input id="results-search" class="search-input" value="${esc(resultSearch)}" placeholder="Search generated records…"></div>
                <div id="results-list" class="results-list"></div><div id="results-more" class="results-more"></div>
              </section>
              <section class="panel templates-panel" id="templates"><div class="section-heading"><div><div class="step-label">REFERENCE / APP FORMATS</div><h2>In-app template inventory</h2><p>App-specific patterns are separated from ordinary browser User-Agent strings.</p></div><span class="section-index">A / B</span></div><div class="template-grid">${templateInventory()}</div><div class="info-note"><span>i</span><p>Candidate formats must not be represented as verified observed traffic. The current mobile app-template catalog records candidate evidence and unresolved slots; this studio only generates the Facebook and Instagram Android script-candidate formats that the existing website generator supports. Other catalog rows remain reference-only.</p></div></section>
              <section class="panel data-panel" id="data"><div class="section-heading"><div><div class="step-label">REFERENCE / DATA GOVERNANCE</div><h2>Catalog & generation notes</h2><p>Source metadata stays visible so exported strings are not mistaken for live device fingerprints.</p></div><span class="section-index">DATA</span></div><div class="data-grid"><div><span>DEVICE CATALOG</span><b>${esc(deviceVersion)}</b><small>${devices.count { it.platform=="android" }} Android · ${devices.count { it.platform=="ios" }} iOS records</small></div><div><span>DESKTOP CATALOG</span><b>2026-11-25</b><small>Windows + macOS release metadata</small></div><div><span>APP TEMPLATE CATALOG</span><b>${esc(templateVersion)}</b><small>Candidate / verified status preserved</small></div><div><span>EXECUTION</span><b>Browser-local</b><small>Pure Kotlin source compiled to JavaScript</small></div></div><p class="data-disclaimer">These outputs are compatibility templates for QA, analytics testing and development. A selected OS release is metadata when browser UA reduction freezes the platform token. The output does not change the actual browser, device, network identity or runtime environment.</p></section>
              <footer class="page-footer"><span>© NEXORA NEXT · USER-AGENT STUDIO</span><span>BUILT WITH KOTLIN / JS · NO BACKEND REQUIRED</span><a href="../">RETURN TO WEBSITE ↗</a></footer>
            </div>
          </main>
        </div>
        <div id="toast" class="toast" role="status" aria-live="polite"></div>
    """.trimIndent()

    private fun platformCard(platform: String): String {
        val label = platformLabels[platform] ?: platform
        val selected = activePlatforms.contains(platform)
        val icon = when (platform) { "android" -> "◉"; "ios" -> "●"; "windows" -> "▦"; else -> "◌" }
        return """
          <label class="platform-card ${if (selected) "selected" else ""}">
            <input type="checkbox" data-platform="$platform" ${checked(selected)}>
            <span class="platform-icon $platform">$icon</span><span class="platform-copy"><b>$label</b><small>${if (platform=="android") "44 device models" else if (platform=="ios") "15 iPhone profiles" else if (platform=="windows") "8 Windows releases" else "10 macOS releases"}</small></span>
            <span class="qty-wrap"><input type="number" data-quantity="$platform" min="0" max="500" value="${quantities[platform] ?: 0}" aria-label="$label quantity" ${if(selected) "" else "disabled"}><small>QTY</small></span>
          </label>
        """.trimIndent()
    }

    private fun osField(platform: String): String {
        val label = platformLabels[platform] ?: platform
        val selected = osChoice[platform] ?: "latest"
        val options = if (platform == "android" || platform == "ios") {
            val all = devices.filter { it.platform==platform }.flatMap { it.osVersions }.distinct().sortedWith(::compareVersions).reversed()
            "<option value=\"latest\" ${sel(selected,"latest")}>Latest supported per device</option><option value=\"random\" ${sel(selected,"random")}>Random supported version</option>" + all.joinToString("") { "<option value=\"${esc(it)}\" ${sel(selected,it)}>${esc(it)}</option>" }
        } else {
            val rows = desktopOs[platform].orEmpty()
            "<option value=\"latest\" ${sel(selected,"latest")}>Latest release</option><option value=\"random\" ${sel(selected,"random")}>Random release</option>" + rows.joinToString("") { row ->
                val detail = if (platform=="windows") "${row.name} · ${row.latestBuild.ifBlank { row.baseBuild }}" else "${row.name} · ${row.latestVersion.ifBlank { row.version }}"
                "<option value=\"${esc(row.id)}\" ${sel(selected,row.id)}>${esc(detail)}</option>"
            }
        }
        return "<label class=\"field os-field\"><span>$label OS RELEASE</span><select data-os=\"$platform\">$options</select></label>"
    }

    private fun browserGroup(platform: String): String {
        val label = platformLabels[platform] ?: platform
        val selected = selectedBrowsers[platform].orEmpty()
        return """
          <div class="browser-group"><div class="browser-group-head"><b>$label browsers</b><span>${selected.size} selected</span></div><div class="browser-grid">${browserChoices[platform].orEmpty().joinToString("") { browserCard(platform,it,selected.contains(it)) }}</div></div>
        """.trimIndent()
    }

    private fun browserCard(platform: String, browser: String, selected: Boolean): String {
        val key = "$platform:$browser"
        val entries = browserVersionEntries(platform,browser)
        val latest = entries.firstOrNull()?.uaVersion ?: if (platform=="android" && browser=="chrome") chromeEngineVersion else ""
        val current = catalogVersions[key] ?: latest
        val manual = manualVersions[key] ?: latest
        val versionSelect = if (browser=="webview") "<div class=\"version-static\">Chromium engine · set below</div>" else {
            val options = entries.joinToString("") { entry ->
                val suffix = if (entry.releaseVersion.isNotBlank() && entry.releaseVersion != entry.uaVersion) " · ${entry.releaseVersion}" else ""
                "<option value=\"${esc(entry.uaVersion)}\" ${sel(current,entry.uaVersion)}>${esc(entry.uaVersion + suffix)}</option>"
            }
            "<select data-version-select=\"$key\" aria-label=\"$browser version\">$options</select>"
        }
        return """
          <label class="browser-card ${if (selected) "selected" else ""}"><span class="browser-card-top"><input type="checkbox" data-browser="$key" ${checked(selected)}><span class="browser-mark ${esc(browser)}">${browserInitial(browser)}</span><span class="browser-name">${browserLabels[browser]}</span><span class="browser-check">✓</span></span><span class="browser-version">$versionSelect</span><input class="manual-version" data-browser-manual="$key" value="${esc(manual)}" placeholder="Manual version" ${if(versionMode=="manual" && browser!="webview") "" else "disabled"}></label>
        """.trimIndent()
    }

    private fun appPickerHtml(): String = """
        <div class="app-picker"><div class="app-picker-title">ANDROID APP CANDIDATES <span>NOT VERIFIED</span></div>${appLabels.map { (id,label) -> "<label class=\"app-option\"><input type=\"checkbox\" data-app=\"$id\" ${checked(selectedApps.contains(id))}><span class=\"app-mark $id\">${label.take(1)}</span><span><b>$label</b><small>Script candidate · Android</small></span></label>" }.joinToString("")}</div>
    """.trimIndent()

    private fun templateInventory(): String {
        val grouped = linkedMapOf<Pair<String,String>, MutableList<String>>()
        for(row in dynList(templateData?.records)) {
            val app=str(row.app).ifBlank { "Unknown app" }
            val platform=str(row.platform).ifBlank { "unknown" }
            grouped.getOrPut(Pair(app,platform)) { mutableListOf() }.add(str(row.status,"unclassified"))
        }
        if(grouped.isEmpty()) return "<div class=\"template-item\"><span><b>Template catalog unavailable</b><small>Could not load app format records.</small></span></div>"
        return grouped.entries.joinToString("") { (key,statuses) ->
            val app=appLabels[key.first] ?: key.first.replaceFirstChar { it.uppercase() }
            val platform=if(key.second=="android") "Android" else if(key.second=="ios") "iOS" else key.second
            val status=statuses.groupingBy { it }.eachCount().entries.joinToString(" · ") { "${it.value} ${it.key}" }
            val generated=key.first in listOf("facebook","instagram") && key.second=="android"
            "<div class=\"template-item\"><span class=\"template-app\">${app.take(1)}</span><span><b>${esc(app)}</b><small>$platform · ${statuses.size} records</small></span><span class=\"template-status ${if(generated) "candidate" else "neutral"}\">${if(generated) "Generator candidate" else esc(status)}</span></div>"
        }
    }

    private fun countryOptions(selected: String, includeAll: Boolean): String = countries.filter { includeAll || it.code.isNotBlank() }.joinToString("") { c ->
        val label = if (c.code.isBlank()) "All countries / no market filter" else "${c.name} · ${c.code}"
        "<option value=\"${esc(c.code)}\" ${sel(selected,c.code)}>${esc(label)}</option>"
    }

    private fun renderDeviceChooser() {
        val host = doc.getElementById("device-chooser") ?: return
        if (distribution == "random") {
            host.innerHTML = "<div class=\"device-empty\"><span>↝</span><div><b>Random device pool enabled</b><small>All market-eligible Android / iOS devices will be sampled automatically.</small></div></div>"
            return
        }
        val activeMobile = activePlatforms.filter { it=="android" || it=="ios" }
        if (activeMobile.isEmpty()) {
            host.innerHTML = "<div class=\"device-empty\"><span>⌁</span><div><b>Desktop output needs no physical device selection</b><small>Windows and macOS use virtual desktop profiles.</small></div></div>"
            return
        }
        host.innerHTML = """
          <div class="device-chooser-head"><div><b>Select device models</b><small>Selected ${selectedDevices.size} models · ${if(distribution=="custom") "set exact record counts" else "balanced / selected pool"}</small></div><div class="device-chooser-actions"><button data-action="select-visible">Select visible</button><button data-action="clear-devices">Clear</button></div></div>
          <input class="search-input device-search" id="device-search" value="${esc(deviceSearch)}" placeholder="Search brand, model or model ID…">
          ${activeMobile.joinToString("") { platform ->
            val label = platformLabels[platform] ?: platform
            val filtered = eligibleDevices(platform).filter { deviceSearch.isBlank() || "${it.brand} ${it.model} ${it.uaModel}".contains(deviceSearch, true) }
            "<div class=\"device-platform-list\"><div class=\"device-platform-title\">$label <span>${filtered.size} available</span></div>${filtered.joinToString("") { d ->
                val isSelected = selectedDevices.contains(d.id)
                "<label class=\"device-row\"><input type=\"checkbox\" data-device=\"${esc(d.id)}\" ${checked(isSelected)}><span class=\"device-row-copy\"><b>${esc(d.brand)} ${esc(d.model)}</b><small>${esc(d.uaModel)} · ${d.osVersions.size} OS versions</small></span>${if(distribution=="custom") "<input class=\"custom-count\" type=\"number\" min=\"0\" max=\"500\" data-custom-count=\"${esc(d.id)}\" value=\"${customCounts[d.id] ?: 0}\" aria-label=\"${esc(d.model)} count\">" else "<span class=\"device-check\">✓</span>"}</label>"
            }.ifEmpty { "<div class=\"empty-small\">No devices match this filter.</div>" }}</div>"
          }}
          ${if(distribution=="custom") "<div class=\"custom-total\">Custom device total <b>${customCounts.values.sum()}</b> · must equal each mobile platform quantity</div>" else ""}
        """.trimIndent()
    }

    private fun customCountsFor(platform: String): Int = eligibleDevices(platform).sumOf { customCounts[it.id] ?: 0 }

    private fun updateCustomTotal() {
        val node=doc.querySelector(".custom-total") ?: return
        val platforms=activePlatforms.filter { it=="android" || it=="ios" }
        node.innerHTML=platforms.joinToString(" · ") { p -> "${platformLabels[p]} <b>${customCountsFor(p)}</b> / ${quantities[p] ?: 0}" }
    }

    private fun eligibleDevices(platform: String): List<Device> = devices.filter { d ->
        d.platform==platform && (marketCountry.isBlank() || d.markets.isEmpty() || d.markets.contains(marketCountry))
    }

    private fun renderResults() {
        val list = doc.getElementById("results-list") ?: return
        val filtered = results.withIndex().filter { (_, r) ->
            val platformOk = resultFilter=="all" || r.platform.lowercase()==resultFilter
            val q = resultSearch.trim()
            platformOk && (q.isBlank() || "${r.platform} ${r.deviceModel} ${r.browser} ${r.osVersion} ${r.userAgent}".contains(q,true))
        }
        val visible = if(showAllResults) filtered else filtered.take(30)
        val searchInput = doc.getElementById("results-search")
        if (searchInput != null && str(searchInput.value)!=resultSearch) searchInput.value=resultSearch
        val summary = doc.getElementById("results-subtitle")
        if (summary != null) summary.textContent = if (results.isEmpty()) "Generate a batch to populate results and export files." else "${results.size} generated · $duplicatesBlocked duplicates skipped · ${filtered.size} visible after filters."
        list.innerHTML = if (visible.isEmpty()) {
            if (results.isEmpty()) "<div class=\"results-empty\"><span>▤</span><b>Your output will appear here</b><small>Choose platforms and settings, then generate a batch.</small></div>" else "<div class=\"results-empty compact\"><b>No records match this filter.</b><small>Try a different platform or search term.</small></div>"
        } else visible.joinToString("") { indexed -> resultCard(indexed.index,indexed.value) }
        val more = doc.getElementById("results-more")
        if (more != null) more.innerHTML = if (filtered.size>visible.size) "<button class=\"button subtle\" data-action=\"show-all\">Show all ${filtered.size} records</button>" else if (showAllResults && filtered.size>30) "<button class=\"button subtle\" data-action=\"show-first\">Show first 30</button>" else ""
        val actionNodes = doc.querySelectorAll("[data-action='copy-all'],[data-action^='export-'],[data-action='clear-results']")
        val actionLength = str(actionNodes.length).toIntOrNull() ?: 0
        for (i in 0 until actionLength) actionNodes[i].disabled = results.isEmpty()
        val countNodes = doc.querySelectorAll(".filter-chip[data-filter]")
        val countLength = str(countNodes.length).toIntOrNull() ?: 0
        for (i in 0 until countLength) {
            val node = countNodes[i]
            val f = str(node.getAttribute("data-filter"))
            node.classList.toggle("active", f==resultFilter)
        }
    }

    private fun resultCard(index: Int, r: UaResult): String {
        val title = if (r.appId.isNotBlank()) appLabels[r.appId] ?: r.appId else r.browser
        val metaLine = if (r.appId.isNotBlank()) "App ${r.appVersion} · build ${r.appBuild}" else "${r.browserVersion}${if(r.browserVersionToken.isNotBlank() && r.browserVersionToken!=r.browserVersion) " · UA token ${r.browserVersionToken}" else ""}"
        val osName = r.osReleaseName.ifBlank { r.osVersion }
        return """
          <article class="result-card"><div class="result-card-head"><div class="result-card-id"><span class="result-index">${(index+1).toString().padStart(3,'0')}</span><span class="result-platform ${r.platform.lowercase()}">${esc(r.platform)}</span><b>${esc(r.deviceModel)}</b></div><button class="copy-one" data-action="copy-row" data-index="$index">Copy UA</button></div>
            <div class="result-meta"><span>${esc(title)}</span><span>OS ${esc(osName)}${if(r.osBuild.isNotBlank()) " · build ${esc(r.osBuild)}" else ""}</span><span>${esc(metaLine)}</span></div>
            <pre class="ua-string">${esc(r.userAgent)}</pre><div class="result-card-foot"><span>${esc(r.modelIdentifier.ifBlank { r.osBuildChannel })}</span><span>${esc(if(r.sourceType=="script-candidate-app") "SCRIPT CANDIDATE · UNVERIFIED" else if(r.browserVersionSource=="manual") "MANUAL VERSION" else "BROWSER TEMPLATE")}</span><span>${esc(r.language)}</span></div>
          </article>
        """.trimIndent()
    }

    private fun summaryRows(): String = listOf("android","ios","windows","macos").joinToString("") { p ->
        "<div class=\"summary-row\"><span class=\"summary-dot $p\"></span><span>${platformLabels[p]}</span><b>${quantities[p] ?: 0}</b></div>"
    }

    private fun updateBatchSummary() {
        val total = totalQuantity()
        val count = doc.getElementById("total-count")
        if (count != null) count.textContent = total.toString()
        val fill = doc.getElementById("capacity-fill")
        if (fill != null) fill.style.width = "${(total.coerceIn(0,500) / 500.0 * 100)}%"
        val summary = doc.getElementById("platform-summary")
        if (summary != null) summary.innerHTML = summaryRows()
        val button = doc.querySelector("[data-action='generate']")
        if (button != null) button.disabled = total !in 1..500
    }

    private fun bindEvents() {
        if (eventBound) return
        eventBound = true
        appRoot.addEventListener("click", { event: dynamic ->
            val target = event.target
            val node = target.closest("[data-action]")
            if (node != null) {
                event.preventDefault()
                handleAction(str(node.getAttribute("data-action")), node)
            }
        })
        appRoot.addEventListener("change", { event: dynamic -> handleChange(event.target) })
        appRoot.addEventListener("input", { event: dynamic -> handleInput(event.target) })
    }

    private fun handleAction(action: String, node: dynamic) {
        when (action) {
            "preset-mobile" -> applyPlatformPreset(listOf("android","ios"))
            "preset-desktop" -> applyPlatformPreset(listOf("windows","macos"))
            "preset-all" -> applyPlatformPreset(listOf("android","ios","windows","macos"))
            "batch-preset" -> applyBatchPreset(str(node.getAttribute("data-total")).toIntOrNull() ?: 10)
            "generate" -> generate()
            "copy-all" -> copyAll()
            "export-json" -> export("json")
            "export-csv" -> export("csv")
            "export-txt" -> export("txt")
            "clear-results" -> { results.clear(); duplicatesBlocked=0; showAllResults=false; render(); toast("Results cleared", "success") }
            "copy-row" -> {
                val index = str(node.getAttribute("data-index")).toIntOrNull() ?: -1
                val row = results.getOrNull(index)
                if (row != null) copyText(row.userAgent, "User-Agent copied")
            }
            "filter-results" -> { resultFilter = str(node.getAttribute("data-filter"),"all"); renderResults() }
            "show-all" -> { showAllResults=true; renderResults() }
            "show-first" -> { showAllResults=false; renderResults() }
            "select-visible" -> {
                for (p in activePlatforms.filter { it=="android" || it=="ios" }) eligibleDevices(p).filter { deviceSearch.isBlank() || "${it.brand} ${it.model} ${it.uaModel}".contains(deviceSearch,true) }.forEach { selectedDevices.add(it.id) }
                renderDeviceChooser()
            }
            "clear-devices" -> { selectedDevices.clear(); customCounts.clear(); renderDeviceChooser() }
            "reset-config" -> resetConfig()
        }
    }

    private fun handleChange(node: dynamic) {
        if (node == null) return
        when {
            hasAttr(node,"data-platform") -> {
                val p = attr(node,"data-platform")
                if (node.checked as Boolean) { activePlatforms.add(p); if ((quantities[p] ?: 0)==0) quantities[p]=10 } else { activePlatforms.remove(p); quantities[p]=0 }
                render()
            }
            hasAttr(node,"data-quantity") -> {
                val p=attr(node,"data-quantity"); quantities[p]=str(node.value).toIntOrNull()?.coerceIn(0,500) ?: 0; render();
            }
            hasAttr(node,"data-device") -> {
                val id=attr(node,"data-device"); if(node.checked as Boolean) selectedDevices.add(id) else { selectedDevices.remove(id); customCounts.remove(id) }; renderDeviceChooser()
            }
            hasAttr(node,"data-custom-count") -> { val id=attr(node,"data-custom-count"); val count=str(node.value).toIntOrNull()?.coerceIn(0,500) ?: 0; customCounts[id]=count; if(count>0) selectedDevices.add(id) else selectedDevices.remove(id); renderDeviceChooser() }
            hasAttr(node,"data-browser") -> {
                val key=attr(node,"data-browser"); val parts=key.split(":"); val set=selectedBrowsers.getOrPut(parts[0]) { linkedSetOf() }
                if(node.checked as Boolean) set.add(parts[1]) else set.remove(parts[1]); render()
            }
            hasAttr(node,"data-version-select") -> { catalogVersions[attr(node,"data-version-select")]=str(node.value); render() }
            hasAttr(node,"data-os") -> { osChoice[attr(node,"data-os")]=str(node.value); render() }
            hasAttr(node,"data-app") -> { val id=attr(node,"data-app"); if(node.checked as Boolean) selectedApps.add(id) else selectedApps.remove(id) }
            node.id=="distribution" -> { distribution=str(node.value); render() }
            node.id=="market-country" -> { marketCountry=str(node.value); renderDeviceChooser() }
            node.id=="version-mode" -> { versionMode=str(node.value); render() }
            node.id=="locale-country" -> { localeCountry=str(node.value); render() }
            node.id=="language-override" -> { languageOverride=str(node.value) }
            node.id=="engine-version" -> { engineOverride=str(node.value) }
            node.id=="deduplicate" -> { deduplicate=node.checked as Boolean }
            node.id=="include-apps" -> { includeApps=node.checked as Boolean; render() }
        }
        updateBatchSummary()
    }

    private fun handleInput(node: dynamic) {
        if (node == null) return
        when {
            node.id=="device-search" -> { deviceSearch=str(node.value); val caret=str(node.selectionStart).toIntOrNull() ?: deviceSearch.length; renderDeviceChooser(); val replacement=doc.getElementById("device-search"); if(replacement!=null){replacement.focus();replacement.setSelectionRange(caret,caret)} }
            node.id=="results-search" -> { resultSearch=str(node.value); renderResults() }
            hasAttr(node,"data-browser-manual") -> { manualVersions[attr(node,"data-browser-manual")]=str(node.value) }
            hasAttr(node,"data-custom-count") -> { val id=attr(node,"data-custom-count"); val count=str(node.value).toIntOrNull()?.coerceIn(0,500) ?: 0; customCounts[id]=count; if(count>0) selectedDevices.add(id) else selectedDevices.remove(id); val row=doc.querySelector("[data-device=\"$id\"]"); if(row!=null) row.checked=count>0; updateCustomTotal() }
            hasAttr(node,"data-quantity") -> { quantities[attr(node,"data-quantity")]=str(node.value).toIntOrNull()?.coerceIn(0,500) ?: 0; updateBatchSummary() }
        }
    }

    private fun applyPlatformPreset(platforms: List<String>) {
        activePlatforms.clear(); activePlatforms.addAll(platforms)
        val total = totalQuantity().takeIf { it>0 } ?: 10
        quantities.keys.toList().forEach { quantities[it]=0 }
        when (platforms.size) {
            2 -> { quantities[platforms[0]]=kotlin.math.ceil(total/2.0).toInt(); quantities[platforms[1]]=total/2 }
            4 -> { quantities["android"]=(total+3)/4; quantities["ios"]=total/4; quantities["windows"]=total/4; quantities["macos"]=total-quantities.values.sum() }
        }
        render()
    }

    private fun applyBatchPreset(total: Int) {
        val platforms=activePlatforms.toList().ifEmpty { activePlatforms.add("android"); listOf("android") }
        quantities.keys.toList().forEach { quantities[it]=0 }
        val each=total/platforms.size; var remainder=total%platforms.size
        platforms.forEach { p -> quantities[p]=each+(if(remainder>0){remainder--;1}else 0) }
        render()
    }

    private fun resetConfig() {
        activePlatforms.clear(); activePlatforms.add("android")
        quantities.keys.toList().forEach { quantities[it]=0 }; quantities["android"]=10
        distribution="random"; selectedDevices.clear(); customCounts.clear(); deviceSearch=""; marketCountry=""
        osChoice.keys.toList().forEach { osChoice[it]="latest" }
        selectedBrowsers["android"] = linkedSetOf("chrome"); selectedBrowsers["ios"] = linkedSetOf("safari")
        selectedBrowsers["windows"] = linkedSetOf("chrome"); selectedBrowsers["macos"] = linkedSetOf("safari")
        versionMode="latest"; catalogVersions.clear(); manualVersions.clear(); engineOverride=""
        localeCountry="US"; languageOverride=""; deduplicate=true; includeApps=false; selectedApps.clear(); selectedApps.addAll(listOf("facebook","instagram"))
        render(); toast("Configuration reset", "success")
    }

    private fun readControls() {
        val platformNodes = doc.querySelectorAll("[data-platform]")
        val pLen = str(platformNodes.length).toIntOrNull() ?: 0
        for(i in 0 until pLen) { val node=platformNodes[i]; val p=attr(node,"data-platform"); if(node.checked as Boolean) activePlatforms.add(p) else activePlatforms.remove(p) }
        val qtyNodes = doc.querySelectorAll("[data-quantity]")
        val qLen = str(qtyNodes.length).toIntOrNull() ?: 0
        for(i in 0 until qLen) { val node=qtyNodes[i]; quantities[attr(node,"data-quantity")]=str(node.value).toIntOrNull()?.coerceIn(0,500) ?: 0 }
        if (totalQuantity()>500) lastError="Batch limit is 500. Reduce platform quantities."
    }

    private fun generate() {
        readControls()
        try {
            lastError=""
            val qtyTotal=totalQuantity()
            if(qtyTotal !in 1..500) throw IllegalArgumentException("Choose a total between 1 and 500.")
            val locale=localeProfile()
            val output=mutableListOf<UaResult>()
            val seen=mutableSetOf<String>()
            var duplicates=0
            for(platform in listOf("android","ios","windows","macos")) {
                val quantity=quantities[platform] ?: 0
                if(quantity<=0 || !activePlatforms.contains(platform)) continue
                val isDesktop=platform=="windows" || platform=="macos"
                val browsers=selectedBrowsers[platform].orEmpty().toList()
                val apps=if(platform=="android" && includeApps) selectedApps.toList() else emptyList()
                if(browsers.isEmpty() && apps.isEmpty()) throw IllegalArgumentException("Select at least one browser or supported app format for ${platformLabels[platform]}.")
                val schedule=if(isDesktop) emptyList() else scheduleDevices(platform,quantity)
                val desktopChoice=if(isDesktop) chooseDesktopOs(platform) else null
                for(i in 0 until quantity) {
                    val device=if(isDesktop) null else schedule[i]
                    val osVersion=if(isDesktop) desktopChoice!!.version else chooseMobileOs(device!!,platform)
                    val useApp=apps.isNotEmpty() && (browsers.isEmpty() || (i % (browsers.size+apps.size))>=browsers.size)
                    val appId=if(useApp) apps[i%apps.size] else ""
                    val browser=if(useApp) "In-app" else browsers[i%browsers.size]
                    var ua=""; var appVersion=""; var appBuild=""; var browserVersion=""; var browserToken=""; var versionSource="catalog"
                    var model=""; var modelId=""; var osName=""; var osBuild=""; var osSecurityBuild=""; var osChannel=""; var sourceType="browser-template"; var note=""
                    if(isDesktop) {
                        osName=desktopChoice!!.name; osBuild=desktopChoice.latestBuild.ifBlank { desktopChoice.baseBuild }; osSecurityBuild=desktopChoice.latestSecurityBuild; osChannel=desktopChoice.latestBuildChannel
                        model=if(platform=="windows") "Windows Desktop" else "Mac Desktop"; modelId=""
                        val version=resolveBrowserVersion(platform,browser)
                        val entry=browserVersionEntry(platform,browser,version)
                        browserToken=version; browserVersion=entry?.releaseVersion?.ifBlank { version } ?: version
                        versionSource=if(versionMode=="manual") "manual" else "desktop-catalog"
                        ua=buildDesktopUa(platform,browser,version,entry)
                        note=if(platform=="windows") "Reduced desktop UA reports Windows NT 10.0 for Windows 10 and 11; selected release is metadata." else "Modern macOS browser UAs use reduced platform tokens; selected release is metadata."
                    } else {
                        model="${device!!.brand} ${device.model}"; modelId=device.uaModel
                        if(useApp) {
                            val built=buildCandidateAppUa(appId,device,osVersion,locale.first)
                            ua=built.first; appVersion=built.second.first; appBuild=built.second.second; sourceType="script-candidate-app"; versionSource="candidate"
                            note="Script-derived app UA candidate; not independently verified observed traffic."
                        } else {
                            val version=resolveBrowserVersion(platform,browser)
                            browserToken=version; browserVersion=version; versionSource=if(versionMode=="manual") "manual" else "mobile-catalog"
                            ua=buildMobileUa(platform,browser,device,osVersion,version)
                            note=if(platform=="ios") "iOS device identity is retained as metadata; standard iPhone UAs do not expose the exact iPhone model." else "Compatibility UA template; generation does not change the real runtime device."
                        }
                    }
                    if(deduplicate && seen.contains(ua)) { duplicates++; continue }
                    seen.add(ua)
                    output += UaResult(
                        id="nexora-ua-${(output.size+1).toString().padStart(4,'0')}", platform=platformLabels[platform] ?: platform,
                        deviceId=if(isDesktop) "" else device!!.id, deviceModel=model, modelIdentifier=modelId, osVersion=osVersion,
                        osReleaseName=osName, osBuild=osBuild, osSecurityBuild=osSecurityBuild, osBuildChannel=osChannel,
                        browser=browserLabels[browser] ?: browser, browserVersion=browserVersion, browserVersionToken=browserToken,
                        browserVersionSource=versionSource, appId=appId, appVersion=appVersion, appBuild=appBuild, userAgent=ua,
                        country=locale.first, language=locale.second, acceptLanguage=locale.third, sourceType=sourceType, note=note
                    )
                }
            }
            if(output.isEmpty()) throw IllegalArgumentException("No unique User-Agent strings were generated. Check browser versions, device selections and duplicate settings.")
            results.clear(); results.addAll(output); duplicatesBlocked=duplicates; resultFilter="all"; resultSearch=""; showAllResults=false
            renderResults(); toast("Generated ${output.size} User-Agent records${if(duplicates>0) " · $duplicates duplicates skipped" else ""}","success")
            val resultsAnchor=doc.getElementById("results"); resultsAnchor?.scrollIntoView(js("({behavior:'smooth',block:'start'})"))
        } catch(error: Throwable) {
            lastError=error.message ?: "Could not generate this batch."
            toast(lastError,"error")
        }
    }

    private fun scheduleDevices(platform: String, quantity: Int): List<Device> {
        val all=eligibleDevices(platform)
        val pool=if(distribution=="random") all else all.filter { selectedDevices.contains(it.id) }
        if(pool.isEmpty()) throw IllegalArgumentException("Choose one or more ${platformLabels[platform]} devices or switch distribution to Random.")
        val requested=osChoice[platform] ?: "latest"
        val compatible=if(requested=="latest" || requested=="random") pool else pool.filter { it.osVersions.contains(requested) }
        if(compatible.isEmpty()) throw IllegalArgumentException("No selected ${platformLabels[platform]} devices support OS $requested.")
        if(distribution=="custom") {
            val custom=mutableListOf<Device>()
            compatible.forEach { d -> repeat((customCounts[d.id] ?: 0).coerceAtLeast(0)) { custom.add(d) } }
            if(custom.size!=quantity) throw IllegalArgumentException("${platformLabels[platform]} custom device counts total ${custom.size}; expected $quantity.")
            return custom
        }
        if(distribution=="balanced") return List(quantity) { i -> compatible[i%compatible.size] }
        return List(quantity) { compatible[Random.nextInt(compatible.size)] }
    }

    private fun chooseMobileOs(device: Device, platform: String): String {
        val requested=osChoice[platform] ?: "latest"
        val supported=device.osVersions
        if(supported.isEmpty()) throw IllegalArgumentException("${device.model} has no supported OS versions in the catalog.")
        return when(requested) {
            "latest" -> supported.sortedWith(::compareVersions).last()
            "random" -> supported[Random.nextInt(supported.size)]
            else -> if(supported.contains(requested)) requested else throw IllegalArgumentException("${device.model} does not support selected OS $requested.")
        }
    }

    private fun chooseDesktopOs(platform: String): DesktopOs {
        val rows=desktopOs[platform].orEmpty()
        if(rows.isEmpty()) throw IllegalArgumentException("${platformLabels[platform]} OS catalog is unavailable.")
        return when(val requested=osChoice[platform] ?: "latest") {
            "latest" -> rows.first()
            "random" -> rows[Random.nextInt(rows.size)]
            else -> rows.firstOrNull { it.id==requested } ?: throw IllegalArgumentException("Selected ${platformLabels[platform]} release is not in the catalog.")
        }
    }

    private fun browserVersionEntries(platform: String, browser: String): List<BrowserVersion> {
        if(platform=="windows" || platform=="macos") return desktopBrowsers[platform]?.get(browser).orEmpty()
        val list=mobileBrowsers["$platform:$browser"].orEmpty()
        return list.map { BrowserVersion(it,it,it,"") }
    }

    private fun browserVersionEntry(platform: String, browser: String, version: String): BrowserVersion? = browserVersionEntries(platform,browser).firstOrNull { it.uaVersion==version }

    private fun resolveBrowserVersion(platform: String, browser: String): String {
        if(browser=="webview") return engineVersion()
        val key="$platform:$browser"
        val list=browserVersionEntries(platform,browser)
        return when(versionMode) {
            "random5" -> list.take(5).randomOrNull()?.uaVersion ?: manualVersions[key].orEmpty().ifBlank { list.firstOrNull()?.uaVersion.orEmpty() }
            "catalog" -> catalogVersions[key] ?: list.firstOrNull()?.uaVersion ?: manualVersions[key].orEmpty()
            "manual" -> manualVersions[key].orEmpty().ifBlank { catalogVersions[key] ?: list.firstOrNull()?.uaVersion.orEmpty() }
            else -> list.firstOrNull()?.uaVersion ?: manualVersions[key].orEmpty()
        }.ifBlank { throw IllegalArgumentException("${browserLabels[browser]} ${platformLabels[platform]} version is required.") }
    }

    private fun engineVersion(): String = engineOverride.trim().ifBlank { chromeEngineVersion }.ifBlank { throw IllegalArgumentException("Android Chromium engine version is missing.") }

    private fun buildDesktopUa(platform: String, browser: String, version: String, entry: BrowserVersion?): String {
        val engine=if(browser=="opera") entry?.engineVersion?.ifBlank { operaEngine(version) } ?: operaEngine(version) else entry?.engineVersion?.ifBlank { version } ?: version
        val winToken="Windows NT 10.0; Win64; x64"
        val macToken="Macintosh; Intel Mac OS X 10_15_7"
        if(browser=="firefox") {
            val token=if(platform=="windows") winToken else "Macintosh; Intel Mac OS X 10.15"
            return "Mozilla/5.0 ($token; rv:$version) Gecko/20100101 Firefox/$version"
        }
        if(browser=="safari") {
            if(platform!="macos") throw IllegalArgumentException("Safari is only available on macOS.")
            return "Mozilla/5.0 ($macToken) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$version Safari/605.1.15"
        }
        val token=if(platform=="windows") winToken else macToken
        val base="Mozilla/5.0 ($token) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$engine Safari/537.36"
        return when(browser) { "chrome" -> base; "edge" -> "$base Edg/$version"; "opera" -> "$base OPR/$version"; else -> throw IllegalArgumentException("Unsupported desktop browser $browser.") }
    }

    private fun operaEngine(version: String): String = when(version.substringBefore('.')) {
        "140" -> "157.0.0.0"; "139" -> "155.0.0.0"; "138" -> "154.0.0.0"; "137" -> "153.0.0.0"; "136" -> "152.0.0.0"; else -> ""
    }.ifBlank { throw IllegalArgumentException("Opera Chromium engine is unknown for this version. Select a catalog version.") }

    private fun buildMobileUa(platform: String, browser: String, device: Device, osVersion: String, version: String): String {
        val model=device.uaModel.ifBlank { device.id }
        val build=deviceBuild(device,osVersion)
        if(platform=="android") {
            val engine=engineVersion()
            if(browser=="chrome") return "Mozilla/5.0 (Linux; Android $osVersion; $model${if(build.isNotBlank()) " Build/$build" else ""}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
            if(browser=="webview") return "Mozilla/5.0 (Linux; Android $osVersion; $model${if(build.isNotBlank()) " Build/$build" else ""}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$engine Mobile Safari/537.36"
            if(browser=="firefox") return "Mozilla/5.0 (Android $osVersion; Mobile; rv:$version) Gecko/$version Firefox/$version"
            val base="Mozilla/5.0 (Linux; Android $osVersion; $model${if(build.isNotBlank()) " Build/$build" else ""}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$engine Mobile Safari/537.36"
            return when(browser) { "edge" -> "$base EdgA/$version"; "opera" -> "$base OPR/$version"; else -> throw IllegalArgumentException("Unsupported Android browser $browser.") }
        }
        val ios=osVersion.replace(".","_")
        val mobileBuild=build.ifBlank { "15E148" }
        val head="Mozilla/5.0 (iPhone; CPU iPhone OS $ios like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
        return when(browser) {
            "safari" -> "${head}Version/$version Mobile/$mobileBuild Safari/604.1"
            "chrome" -> "${head}CriOS/$version Mobile/$mobileBuild Safari/604.1"
            "firefox" -> "${head}FxiOS/$version Mobile/$mobileBuild Safari/605.1.15"
            "edge" -> "${head}EdgiOS/$version Mobile/$mobileBuild Safari/605.1.15"
            else -> throw IllegalArgumentException("Unsupported iOS browser $browser.")
        }
    }

    private fun buildCandidateAppUa(app: String, device: Device, osVersion: String, language: String): Pair<String, Pair<String,String>> {
        val appMeta=meta.apps[app]
        val appVersion=str(appMeta.version)
        val appBuild=str(appMeta.build)
        if(appVersion.isBlank() || appBuild.isBlank()) throw IllegalArgumentException("${appLabels[app]} app version metadata is incomplete.")
        val model=device.uaModel.ifBlank { device.id }
        val build=deviceBuild(device,osVersion)
        if(build.isBlank()) throw IllegalArgumentException("${device.model} has no build ID for Android $osVersion; this app candidate needs a device build.")
        val chrome=chromeEngineVersion.ifBlank { throw IllegalArgumentException("Chrome engine version is missing for in-app candidates.") }
        val display=device.raw.display
        val width=str(display.w); val height=str(display.h); val dpr=number(display.dpr)
        val density=if(dpr>0) "${(dpr*160).roundToInt()}dpi" else ""
        val resolution=if(width.isNotBlank() && height.isNotBlank()) "${width}x$height" else ""
        val locale=language.replace("-","_")
        val brand=device.brand
        val chipset=str(device.raw.soc)
        val codename=str(device.raw.codename)
        val sdk=mapOf("10" to "29","11" to "30","12" to "31","13" to "33","14" to "34","15" to "35","16" to "36","17" to "37")[osVersion.substringBefore('.')] ?: ""
        val ua=if(app=="facebook") {
            "Mozilla/5.0 (Linux; Android $osVersion; $model Build/$build; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$chrome Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/$appVersion;IABMV/1;]"
        } else if(brand.lowercase().contains("motorola")) {
            "Mozilla/5.0 (Linux; Android $osVersion; $model Build/$build; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$chrome Mobile Safari/537.36 Instagram $appVersion Android ($sdk/$osVersion; $density; $resolution; motorola; $model; $chipset; $locale; $appBuild; IABMV/1)"
        } else {
            "Mozilla/5.0 (Linux; Android $osVersion; $model Build/$build; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$chrome Mobile Safari/537.36 Instagram $appVersion Android ($sdk/$osVersion; $density; $resolution; $brand; $model; $codename; $chipset; $locale; $appBuild; IABMV/1)"
        }
        if(app=="instagram" && (density.isBlank() || resolution.isBlank() || chipset.isBlank())) throw IllegalArgumentException("${device.model} is missing display or chipset metadata required by the Instagram candidate format.")
        return Pair(ua,Pair(appVersion,appBuild))
    }

    private fun deviceBuild(device: Device, osVersion: String): String {
        val direct=str(device.raw.builds[osVersion])
        if(direct.isNotBlank()) return direct
        for(row in dynList(device.raw.buildsRecent)) if(str(row.version)==osVersion && str(row.build).isNotBlank()) return str(row.build)
        return ""
    }

    private fun localeProfile(): Triple<String,String,String> {
        val selected=countries.firstOrNull { it.code==localeCountry }
        val language=languageOverride.trim().ifBlank { selected?.language?.ifBlank { "" } ?: "" }.ifBlank { str(win.navigator.language,"en-US") }
        val country=localeCountry.ifBlank { "AUTO" }
        val primary=language.substringBefore('-').ifBlank { "en" }
        val accept="$language, $primary;q=0.9, en;q=0.8"
        return Triple(country,language,accept)
    }

    private fun copyAll() {
        if(results.isEmpty()) return
        copyText(results.joinToString("\n") { it.userAgent },"${results.size} User-Agent strings copied")
    }

    private fun copyText(text: String, message: String) {
        try {
            val clipboard=win.navigator.clipboard
            if(clipboard != null) {
                clipboard.writeText(text).then({ _: dynamic -> toast(message,"success"); null }).catch({ _: dynamic -> fallbackCopy(text,message); null })
            } else fallbackCopy(text,message)
        } catch (_: Throwable) { fallbackCopy(text,message) }
    }

    private fun fallbackCopy(text: String, message: String) {
        try {
            val area=doc.createElement("textarea")
            area.value=text; area.style.position="fixed"; area.style.opacity="0"; doc.body.appendChild(area); area.select(); doc.execCommand("copy"); area.remove(); toast(message,"success")
        } catch (_: Throwable) { toast("Clipboard access is unavailable in this browser.","error") }
    }

    private fun export(kind: String) {
        if(results.isEmpty()) return
        val content=when(kind) {
            "json" -> exportJson()
            "csv" -> exportCsv()
            else -> results.joinToString("\n\n") { "${it.platform} · ${it.deviceModel} · ${it.browser}\n${it.userAgent}" }
        }
        val mime=when(kind) { "json" -> "application/json;charset=utf-8"; "csv" -> "text/csv;charset=utf-8"; else -> "text/plain;charset=utf-8" }
        val parts: dynamic=js("[]"); parts.push(content)
        val opts: dynamic=js("({})"); opts.type=mime
        val blobCtor: dynamic=js("Blob"); val args: dynamic=js("[]"); args.push(parts); args.push(opts)
        val reflect: dynamic=js("Reflect"); val blob=reflect.construct(blobCtor,args)
        val url=win.URL.createObjectURL(blob)
        val anchor=doc.createElement("a"); anchor.href=url; anchor.download="nexora-user-agents-${kind}."+(if(kind=="txt") "txt" else kind); doc.body.appendChild(anchor); anchor.click(); anchor.remove(); win.URL.revokeObjectURL(url)
        toast("Exported ${results.size} records as ${kind.uppercase()}","success")
    }

    private fun exportJson(): String = results.joinToString(",\n", "[\n", "\n]") { r ->
        "  {\n" + listOf(
            "id" to r.id, "platform" to r.platform, "deviceId" to r.deviceId, "deviceModel" to r.deviceModel,
            "modelIdentifier" to r.modelIdentifier, "osVersion" to r.osVersion, "osReleaseName" to r.osReleaseName,
            "osBuild" to r.osBuild, "osSecurityBuild" to r.osSecurityBuild, "osBuildChannel" to r.osBuildChannel,
            "browser" to r.browser, "browserVersion" to r.browserVersion, "browserVersionToken" to r.browserVersionToken,
            "browserVersionSource" to r.browserVersionSource, "appId" to r.appId, "appVersion" to r.appVersion,
            "appBuild" to r.appBuild, "userAgent" to r.userAgent, "country" to r.country, "language" to r.language,
            "acceptLanguage" to r.acceptLanguage, "sourceType" to r.sourceType, "note" to r.note
        ).joinToString(",\n") { (k,v) -> "    \"$k\": ${jsonString(v)}" } + "\n  }"
    }

    private fun exportCsv(): String {
        val headers=listOf("id","platform","deviceId","deviceModel","modelIdentifier","osVersion","osReleaseName","osBuild","browser","browserVersion","browserVersionToken","appId","appVersion","appBuild","userAgent","country","language","acceptLanguage","sourceType","note")
        val rows=results.joinToString("\n") { r -> listOf(r.id,r.platform,r.deviceId,r.deviceModel,r.modelIdentifier,r.osVersion,r.osReleaseName,r.osBuild,r.browser,r.browserVersion,r.browserVersionToken,r.appId,r.appVersion,r.appBuild,r.userAgent,r.country,r.language,r.acceptLanguage,r.sourceType,r.note).joinToString(",") { csv(it) } }
        return headers.joinToString(",")+"\n"+rows
    }

    private fun toast(message: String, type: String) {
        val node=doc.getElementById("toast") ?: return
        node.textContent=message; node.className="toast visible $type"
        win.clearTimeout(node._timer)
        node._timer=win.setTimeout({ node.className="toast"; null }, 3200)
    }

    private fun totalQuantity(): Int = quantities.values.sum()
    private fun browserInitial(browser: String): String = when(browser) { "chrome" -> "C"; "safari" -> "S"; "firefox" -> "F"; "edge" -> "E"; "opera" -> "O"; else -> "W" }
    private fun compareVersions(a: String, b: String): Int {
        val aa=a.split(Regex("[._-]")); val bb=b.split(Regex("[._-]"))
        for(i in 0 until maxOf(aa.size,bb.size)) { val x=aa.getOrNull(i)?.toIntOrNull() ?: 0; val y=bb.getOrNull(i)?.toIntOrNull() ?: 0; if(x!=y) return x.compareTo(y) }
        return a.compareTo(b)
    }
    private fun str(value: dynamic, fallback: String = ""): String {
        if(value==null) return fallback
        val text=value.toString()
        return if(text=="undefined" || text=="null") fallback else text
    }
    private fun number(value: dynamic): Double = str(value).toDoubleOrNull() ?: 0.0
    private fun dynList(value: dynamic): List<dynamic> {
        if(value==null) return emptyList()
        val length=str(value.length).toIntOrNull() ?: 0
        return (0 until length).map { value[it] }
    }
    private fun stringList(value: dynamic): List<String> = dynList(value).map { str(it) }.filter { it.isNotBlank() }
    private fun esc(value: String): String = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun checked(value: Boolean): String = if(value) "checked" else ""
    private fun sel(a: String,b: String): String = if(a==b) "selected" else ""
    private fun jsonString(value: String): String = "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""
    private fun csv(value: String): String = "\""+value.replace("\"","\"\"")+"\""
    private fun attr(node: dynamic, name: String): String = str(node.getAttribute(name))
    private fun hasAttr(node: dynamic, name: String): Boolean = node.hasAttribute(name) as Boolean
}

fun main() {
    Studio.start()
}
