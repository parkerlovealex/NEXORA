"use strict";
/* NEXORA UA Generator v2 - chip/card UX, multiple platform + browser support.
   Mobile templates mirror the Android app (AppUa.kt / UaBuilder.kt).
   Desktop templates are the standard frozen formats each browser ships.
   Data: devices.json fetched live from the NEXORA GitHub repo (bundled fallback). */

const DATA_URL =
  "https://raw.githubusercontent.com/parkerlovealex/NEXORA/main/app/src/main/assets/devices.json";
const HISTORY_URL =
  "https://raw.githubusercontent.com/parkerlovealex/NEXORA/main/data/history/index.json";
const FALLBACK_CHROME = "126.0.6478.122";
/* Seeded from the vendors' current stable releases (Nov 2026); editable in the UI. */
const DEFAULT_VERSIONS = { firefox: "160.0", edge: "158.0.4370.33", opera: "140.0.6074.18", safari: "27.2" };

const APPS = [
  ["facebook", "Facebook"], ["fblite", "FB Lite"], ["instagram", "Instagram"],
  ["tiktok", "TikTok"], ["x", "X (Twitter)"], ["threads", "Threads"],
  ["linkedin", "LinkedIn"], ["pinterest", "Pinterest"], ["reddit", "Reddit"],
  ["snapchat", "Snapchat"], ["weibo", "Weibo"], ["telegram", "Telegram"],
  ["messenger", "Messenger"], ["wechat", "WeChat"], ["line", "LINE"],
  ["kik", "Kik"], ["discord", "Discord"], ["viber", "Viber"],
  ["webview", "WebView"]
];

const COUNTRIES = [
  ["", "All Countries", ""], ["US", "United States", "en_US"], ["GB", "United Kingdom", "en_GB"],
  ["BD", "Bangladesh", "bn_BD"], ["IN", "India", "en_IN"], ["PK", "Pakistan", "ur_PK"],
  ["CA", "Canada", "en_CA"], ["AU", "Australia", "en_AU"], ["DE", "Germany", "de_DE"],
  ["FR", "France", "fr_FR"], ["ES", "Spain", "es_ES"], ["IT", "Italy", "it_IT"],
  ["PT", "Portugal", "pt_PT"], ["BR", "Brazil", "pt_BR"], ["MX", "Mexico", "es_MX"],
  ["AR", "Argentina", "es_AR"], ["RU", "Russia", "ru_RU"], ["UA", "Ukraine", "uk_UA"],
  ["TR", "Turkey", "tr_TR"], ["SA", "Saudi Arabia", "ar_SA"], ["AE", "UAE", "ar_AE"],
  ["EG", "Egypt", "ar_EG"], ["NG", "Nigeria", "en_NG"], ["ZA", "South Africa", "en_ZA"],
  ["KE", "Kenya", "en_KE"], ["ID", "Indonesia", "id_ID"], ["MY", "Malaysia", "ms_MY"],
  ["SG", "Singapore", "en_SG"], ["PH", "Philippines", "en_PH"], ["VN", "Vietnam", "vi_VN"],
  ["TH", "Thailand", "th_TH"], ["JP", "Japan", "ja_JP"], ["KR", "South Korea", "ko_KR"],
  ["CN", "China", "zh_CN"], ["TW", "Taiwan", "zh_TW"], ["HK", "Hong Kong", "zh_HK"],
  ["NL", "Netherlands", "nl_NL"], ["SE", "Sweden", "sv_SE"], ["NO", "Norway", "nb_NO"],
  ["DK", "Denmark", "da_DK"], ["FI", "Finland", "fi_FI"], ["PL", "Poland", "pl_PL"],
  ["GR", "Greece", "el_GR"], ["IR", "Iran", "fa_IR"], ["IQ", "Iraq", "ar_IQ"],
  ["LK", "Sri Lanka", "si_LK"], ["NP", "Nepal", "ne_NP"], ["MM", "Myanmar", "my_MM"]
];

let DB = null;

/* ---------------- mobile templates (mirror the app) ---------------- */

function sdkFor(os) {
  return { "15": 35, "14": 34, "13": 33, "12": 31, "11": 30, "10": 29 }[String(os).split(".")[0]] || 34;
}

function safariUa(p, os) {
  return "Mozilla/5.0 (iPhone; CPU iPhone OS " + String(os).replace(/\./g, "_") +
    " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
    "Version/17.5 Mobile/15E148 Safari/604.1";
}

function browserUa(p, os, chrome) {
  if (p.isIos) return safariUa(p, os);
  return "Mozilla/5.0 (Linux; Android " + os + "; " + p.uaModel + ") " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + (chrome || FALLBACK_CHROME) +
    " Mobile Safari/537.36";
}

function webViewBase(p, os, chrome) {
  const build = (p.builds || {})[os];
  const buildPart = build ? " Build/" + build : "";
  return "Mozilla/5.0 (Linux; Android " + os + "; " + p.uaModel + buildPart + "; wv) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/" +
    (chrome || FALLBACK_CHROME) + " Mobile Safari/537.36";
}

function appUa(appId, p, os, chrome, appVersion, appToken, locale) {
  const loc = locale || "en_US";
  if (p.isIos) {
    const base = safariUa(p, os);
    if (appId === "facebook" || appId === "fblite") {
      return base + (appVersion
        ? " [FBAN/FBIOS;FBAV/" + appVersion + ";FBDV/" + p.uaModel + ";FBMD/iPhone;]"
        : " [FBAN/FBIOS;FBDV/" + p.uaModel + ";FBMD/iPhone;]");
    }
    if (appId === "instagram" && appVersion) {
      const scale = (p.dpr % 1 === 0) ? p.dpr.toFixed(2) : String(p.dpr);
      return base + " Instagram " + appVersion + " (" + p.uaModel + "; iOS " +
        String(os).replace(/\./g, "_") + "; " + loc + "; scale=" + scale + "; " +
        p.w + "x" + p.h + "; IABMV/1)";
    }
    return base;
  }
  const base = webViewBase(p, os, chrome);
  if (appId === "facebook" || appId === "fblite") {
    return base + (appVersion
      ? " [FB_IAB/FB4A;FBAV/" + appVersion + ";IABMV/1;]"
      : " [FB_IAB/FB4A;IABMV/1;]");
  }
  if (appId === "messenger") {
    return base + (appVersion
      ? " [FB_IAB/MESSENGER;FBAV/" + appVersion + ";IABMV/1;]"
      : " [FB_IAB/MESSENGER;IABMV/1;]");
  }
  if (appId === "instagram") {
    if (!appVersion) return base;
    const dpi = Math.round(p.dpr * 160);
    return "Instagram " + appVersion + " Android (" + sdkFor(os) + "/" + os + "; " +
      dpi + "dpi; " + p.w + "x" + p.h + "; " + String(p.brand).toLowerCase() + "; " +
      p.uaModel + "; " + loc + ")";
  }
  if (appId === "webview") return base;
  return appToken ? base + " " + appToken : base;
}

/* ---------------- extra mobile browsers ---------------- */

function mobileBrowserUa(browser, p, os, v) {
  if (p.isIos) {
    const ios = String(os).replace(/\./g, "_");
    const head = "Mozilla/5.0 (iPhone; CPU iPhone OS " + ios + " like Mac OS X) " +
      "AppleWebKit/605.1.15 (KHTML, like Gecko) ";
    if (browser === "chrome") return head + "CriOS/" + v.chrome + " Mobile/15E148 Safari/604.1";
    if (browser === "firefox") return head + "FxiOS/" + v.firefox + " Mobile/15E148 Safari/605.1.15";
    if (browser === "edge") return head + "EdgiOS/" + v.edge + " Mobile/15E148 Safari/605.1.15";
    return safariUa(p, os);
  }
  if (browser === "firefox") {
    return "Mozilla/5.0 (Android " + os + "; Mobile; rv:" + v.firefox + ") Gecko/" +
      v.firefox + " Firefox/" + v.firefox;
  }
  const base = browserUa(p, os, v.chrome);
  if (browser === "edge") return base + " EdgA/" + v.edge;
  if (browser === "opera") return base + " OPR/" + v.opera;
  return base;
}

/* ---------------- desktop + iPad templates ---------------- */

function desktopUa(platform, browser, v) {
  const win = platform === "windows";
  const osPart = win ? "Windows NT 10.0; Win64; x64" : "Macintosh; Intel Mac OS X 10_15_7";
  if (browser === "firefox") {
    const ffOs = win ? "Windows NT 10.0; Win64; x64" : "Macintosh; Intel Mac OS X 10.15";
    return "Mozilla/5.0 (" + ffOs + "; rv:" + v.firefox + ") Gecko/20100101 Firefox/" + v.firefox;
  }
  if (browser === "safari") {
    return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
      "(KHTML, like Gecko) Version/" + v.safari + " Safari/605.1.15";
  }
  let ua = "Mozilla/5.0 (" + osPart + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
    v.chrome + " Safari/537.36";
  if (browser === "edge") ua += " Edg/" + v.edge;
  if (browser === "opera") ua += " OPR/" + v.opera;
  return ua;
}

function ipadUa(os, v) {
  return "Mozilla/5.0 (iPad; CPU OS " + String(os).replace(/\./g, "_") +
    " like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 " +
    "Mobile/15E148 Safari/604.1";
}

/* ---------------- data ---------------- */

function flatten(data) {
  const rows = [];
  (data.android || []).forEach(d => rows.push(Object.assign({}, d, {
    isIos: false, w: d.display.w, h: d.display.h, dpr: d.display.dpr
  })));
  (data.ios_emulation || []).forEach(d => rows.push(Object.assign({}, d, {
    isIos: true, w: d.display.w, h: d.display.h, dpr: d.display.dpr
  })));
  return rows;
}

function appInfo(id) {
  const raw = (DB.meta && DB.meta.apps && DB.meta.apps[id]) || null;
  if (!raw) return { version: "", token: "" };
  if (typeof raw === "string") return { version: raw, token: "" };
  return { version: raw.version || "", token: raw.token || "" };
}

async function fetchData(live) {
  if (live) {
    try {
      const r = await fetch(DATA_URL, { cache: "no-store" });
      if (r.ok) return { data: await r.json(), live: true };
    } catch (e) { /* fall back */ }
  }
  const r = await fetch("./devices.json", { cache: "no-store" });
  return { data: await r.json(), live: false };
}

if (typeof module !== "undefined") {
  module.exports = { appUa, browserUa, webViewBase, mobileBrowserUa, desktopUa, ipadUa, flatten, COUNTRIES, APPS };
}

/* ---------------- UI ---------------- */

const state = {
  platform: "all",        // all | android | iphone | ipad | windows | macos
  browser: "all",         // all | chrome | firefox | safari | edge | opera
  app: "",                // "" = none, else APPS id
  country: "",            // COUNTRIES code
  deviceIdx: -1,
  deviceKey: "",
  live: true
};

const BROWSERS = [["all", "All"], ["chrome", "Chrome"], ["firefox", "Firefox"],
  ["safari", "Safari"], ["edge", "Edge"], ["opera", "Opera"]];
const PLATFORM_BROWSERS = {
  android: ["all", "chrome", "firefox", "edge", "opera"],
  iphone: ["all", "safari", "chrome", "firefox", "edge"],
  ipad: ["all", "safari", "chrome", "firefox", "edge"],
  windows: ["all", "chrome", "firefox", "edge", "opera"],
  macos: ["all", "safari", "chrome", "firefox", "edge", "opera"]
};

function devices() { return flatten(DB); }
function isApplePlatform() { return state.platform === "iphone" || state.platform === "ipad" || state.platform === "macos"; }
function isDesktop() { return state.platform === "windows" || state.platform === "macos"; }

function platformDevices() {
  const all = devices();
  let list = all;
  if (state.platform === "android") list = all.filter(d => !d.isIos);
  else if (state.platform === "iphone") list = all.filter(d => d.isIos);
  // Country-wise official phones: when a country is picked, only devices
  // officially sold in that country stay in the pool - under EVERY platform
  // choice, including "All devices".
  if (state.country) {
    list = list.filter(d => Array.isArray(d.markets) && d.markets.includes(state.country));
  }
  return list;
}

function versions() {
  return {
    chrome: document.getElementById("chrome").value.trim() || FALLBACK_CHROME,
    firefox: document.getElementById("firefoxVer").value.trim() || DEFAULT_VERSIONS.firefox,
    edge: document.getElementById("edgeVer").value.trim() || DEFAULT_VERSIONS.edge,
    opera: document.getElementById("operaVer").value.trim() || DEFAULT_VERSIONS.opera,
    safari: DEFAULT_VERSIONS.safari
  };
}

function locale() {
  const c = COUNTRIES.find(x => x[0] === state.country);
  return (c && c[2]) || "en_US";
}

function defaultBrowserFor(platform) {
  if (platform === "iphone" || platform === "ipad" || platform === "macos") return "safari";
  return "chrome";
}

function buildOne() {
  const v = versions();
  const appVersion = document.getElementById("appVersion").value.trim();
  if (isDesktop()) {
    const b = state.browser === "all" ? defaultBrowserFor(state.platform) : state.browser;
    return { ua: desktopUa(state.platform, b, v), tag: b.toUpperCase() + " · " + state.platform.toUpperCase() };
  }
  if (state.platform === "ipad") {
    const ios = platformDevices().filter(d => d.isIos);
    const os = ios.length ? ios[0].osVersions[ios[0].osVersions.length - 1] : "18";
    if (state.app) return { ua: ipadUa(os, v), tag: "APP · IPAD" };
    const b = state.browser === "all" ? "safari" : state.browser;
    if (b === "safari") return { ua: ipadUa(os, v), tag: "SAFARI · IPAD" };
    // Other iPad browsers reuse the iPhone CriOS/FxiOS/EdgiOS shapes with iPad head.
    const p = ios[0];
    return { ua: mobileBrowserUa(b, p, os, v).replace("(iPhone;", "(iPad;").replace("CPU iPhone OS", "CPU OS"), tag: b.toUpperCase() + " · IPAD" };
  }
  const list = platformDevices();
  if (!list.length) return null;
  // Random mode (deviceIdx -1): pick a random device from the current pool
  // (already filtered by platform + country) and a random OS for it.
  const randomMode = state.deviceIdx === -1;
  const d = randomMode
    ? list[Math.floor(Math.random() * list.length)]
    : (list[state.deviceIdx % list.length] || list[0]);
  const os = randomMode
    ? d.osVersions[Math.floor(Math.random() * d.osVersions.length)]
    : (document.getElementById("os").value || d.osVersions[d.osVersions.length - 1]);
  if (state.app) {
    const info = appInfo(state.app);
    const label = (APPS.find(a => a[0] === state.app) || ["", state.app])[1];
    return { ua: appUa(state.app, d, os, v.chrome, appVersion || info.version, info.token, locale()), tag: label.toUpperCase() + " · " + (d.isIos ? "IOS" : "ANDROID") };
  }
  const b = state.browser === "all" ? defaultBrowserFor(d.isIos ? "iphone" : "android") : state.browser;
  const ua = (b === "chrome" && !d.isIos) || (b === "safari" && d.isIos)
    ? browserUa(d, os, v.chrome)
    : mobileBrowserUa(b, d, os, v);
  return { ua, tag: b.toUpperCase() + " · " + (d.isIos ? "IOS" : "ANDROID") };
}

function generate() {
  const qty = Number(document.getElementById("qty").value) || 10;
  const list = platformDevices();
  const results = [];
  const seen = new Set();
  const startIdx = state.deviceIdx;
  // Walk devices x OS versions for variety until qty unique strings or combos run out.
  const combos = [];
  if (isDesktop() || state.platform === "ipad") {
    combos.push(0);
  } else if (state.deviceIdx >= 0 && list[state.deviceIdx]) {
    // A specific phone is chosen: vary only its OS versions.
    list[state.deviceIdx].osVersions.forEach(() => combos.push(state.deviceIdx));
  } else {
    list.forEach((d, di) => d.osVersions.forEach(() => combos.push(di)));
  }
  let ptr = 0;
  const osSel = document.getElementById("os");
  const osBackup = osSel.value;
  if (state.deviceIdx === -1 && !isDesktop() && state.platform !== "ipad") {
    // Bulk random: every UA rolls a fresh random device (+OS) from the pool.
    let attempts = 0;
    while (results.length < qty && attempts < qty * 40 + 120) {
      const one = buildOne();
      if (one && !seen.has(one.ua)) { seen.add(one.ua); results.push(one); }
      attempts++;
    }
  } else {
    while (results.length < qty && ptr < combos.length * 2) {
      const di = combos[ptr % combos.length];
      state.deviceIdx = di;
      const d = list[di];
      if (d) {
        const singleDevice = combos.every(x => x === combos[0]);
        const osIdx = singleDevice
          ? ptr % d.osVersions.length
          : Math.floor(ptr / Math.max(1, list.length)) % d.osVersions.length;
        osSel.value = d.osVersions[d.osVersions.length - 1 - osIdx];
      }
      const one = buildOne();
      if (one && !seen.has(one.ua)) { seen.add(one.ua); results.push(one); }
      ptr++;
      if (ptr > qty * 4 + 40) break;
    }
    state.deviceIdx = startIdx;
    osSel.value = osBackup;
  }
  const out = document.getElementById("output");
  if (!isDesktop() && state.platform !== "ipad" && !list.length) {
    document.getElementById("resCount").textContent = "Results (0)";
    out.innerHTML = '<p class="muted">No phones in the database are officially sold in ' +
      'the selected country yet. Pick "All Countries" or another country.</p>';
    window._lastResults = [];
    return;
  }
  document.getElementById("resCount").textContent = "Results (" + results.length + ")";
  out.innerHTML = results.map((r, i) =>
    '<div class="ua"><div class="ua-num">' + (i + 1) + '.</div><div class="ua-body">' +
    '<div class="ua-text">' + r.ua.replace(/</g, "&lt;") + '</div>' +
    '<div class="ua-tags"><span class="tag">' + r.tag + "</span>" +
    (state.country ? '<span class="tag tag-loc">' + state.country + " OFFICIAL · " + locale() + "</span>" : "") +
    "</div></div></div>").join("");
  window._lastResults = results.map(r => r.ua);
}

function chipRow(elId, items, current, onPick, disabledIds) {
  const el = document.getElementById(elId);
  el.innerHTML = "";
  items.forEach(([id, label]) => {
    const b = document.createElement("button");
    b.className = "chip" + (current === id ? " chip-on" : "") +
      (disabledIds && disabledIds.includes(id) ? " chip-off" : "");
    b.textContent = label;
    b.addEventListener("click", () => { if (!(disabledIds && disabledIds.includes(id))) onPick(id); });
    el.appendChild(b);
  });
}

function renderControls() {
  // Browsers (disabled per platform)
  const allowed = state.platform === "all"
    ? ["all", "chrome", "firefox", "safari", "edge", "opera"]
    : (PLATFORM_BROWSERS[state.platform] || BROWSERS.map(b => b[0]));
  const disabled = BROWSERS.map(b => b[0]).filter(id => !allowed.includes(id));
  chipRow("browserChips", BROWSERS, state.browser, id => { state.browser = id; state.app = ""; renderControls(); generate(); }, disabled);
  // Apps (mobile only)
  const appItems = [["", "Browser"], ...APPS];
  chipRow("appChips", appItems, state.app, id => { state.app = id; syncAppVersion(); renderControls(); generate(); },
    (isDesktop()) ? appItems.map(a => a[0]).filter(x => x !== "") : []);
  // Countries
  const q = (document.getElementById("countrySearch").value || "").toLowerCase();
  const list = COUNTRIES.filter(c => !q || c[1].toLowerCase().includes(q));
  document.getElementById("countryTotal").textContent = "Total: " + COUNTRIES.length;
  const grid = document.getElementById("countryGrid");
  grid.innerHTML = "";
  list.slice(0, 60).forEach(([code, name, loc]) => {
    const b = document.createElement("button");
    b.className = "country" + (state.country === code ? " country-on" : "");
    b.innerHTML = "<strong>" + (code || "ALL") + "</strong> " + name + (loc ? ' <span>' + loc + "</span>" : "");
    b.addEventListener("click", () => { state.country = code; syncDeviceSelect(); renderControls(); generate(); });
    grid.appendChild(b);
  });
  // Device row visibility
  const showDevice = !isDesktop() && state.platform !== "ipad";
  document.getElementById("deviceRow").style.display = showDevice ? "" : "none";
  document.querySelectorAll(".platform-card").forEach(c =>
    c.classList.toggle("platform-on", c.dataset.platform === state.platform));
}

function syncDeviceSelect() {
  const sel = document.getElementById("device");
  const list = platformDevices();
  sel.innerHTML = '<option value="-1">Random device (from selected country)</option>' +
    list.map((d, i) =>
      '<option value="' + i + '">' + d.brand + " " + d.model + (d.isIos ? " (iOS)" : "") + "</option>").join("");
  // Keep the previously picked phone if it still exists in the new pool
  // (e.g. after a country change); otherwise fall back to Random.
  const keep = state.deviceKey ? list.findIndex(d => d.id === state.deviceKey) : -1;
  if (keep >= 0) {
    state.deviceIdx = keep;
    sel.value = String(keep);
  } else {
    state.deviceIdx = -1;
    state.deviceKey = "";
    sel.value = "-1";
  }
  syncOs();
}

function syncOs() {
  const osSel = document.getElementById("os");
  if (state.deviceIdx === -1) {
    osSel.innerHTML = "<option>Random</option>";
    osSel.disabled = true;
    return;
  }
  osSel.disabled = false;
  const list = platformDevices();
  const d = list[state.deviceIdx] || list[0];
  if (!d) { osSel.innerHTML = ""; return; }
  osSel.innerHTML = d.osVersions.map(v => "<option>" + v + "</option>").join("");
  osSel.value = d.osVersions[d.osVersions.length - 1];
}

function syncAppVersion() {
  const info = appInfo(state.app);
  const input = document.getElementById("appVersion");
  input.value = info.version || "";
  input.placeholder = info.version ? "" : "App version (optional)";
}

async function boot() {
  const toggle = document.getElementById("liveToggle");
  state.live = toggle.checked;
  const { data, live } = await fetchData(state.live);
  DB = data;
  const v = (DB.meta && DB.meta.versions) || {};
  document.getElementById("chrome").value = v.chrome || FALLBACK_CHROME;
  document.getElementById("metaLine").textContent =
    "Data version " + ((DB.meta && DB.meta.dataVersion) || "?") +
    " · Updated " + ((DB.meta && DB.meta.updatedAt) || "?") +
    " · " + devices().length + " devices · " +
    (live ? "Live database" : "Bundled copy") +
    " · Same devices.json the NEXORA app uses";
  syncDeviceSelect();
  syncAppVersion();
  renderControls();
  generate();
}

async function loadHistory() {
  let entries = null;
  try {
    const r = await fetch(HISTORY_URL, { cache: "no-store" });
    if (r.ok) entries = await r.json();
  } catch (e) { /* fallback below */ }
  if (!entries) {
    try {
      const r = await fetch("./history.json", { cache: "no-store" });
      if (r.ok) entries = await r.json();
    } catch (e) { /* none */ }
  }
  const box = document.getElementById("history");
  if (!entries || !entries.length) {
    box.innerHTML = '<p class="muted">No archived versions yet - history starts with the next refresh.</p>';
    return;
  }
  box.innerHTML = entries.map(e =>
    '<div class="ua hist" data-chrome="' + e.chrome + '"><div class="ua-body">' +
    "<strong>Data " + e.dataVersion + "</strong> · " + e.updatedAt + " · Chrome " + e.chrome +
    "</div></div>").join("");
  box.querySelectorAll(".hist").forEach(el => {
    el.addEventListener("click", () => {
      const c = document.getElementById("chrome");
      c.value = el.dataset.chrome;
      generate();
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
  });
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    document.querySelectorAll(".platform-card").forEach(card => {
      card.addEventListener("click", () => {
        state.platform = card.dataset.platform;
        if (state.platform !== "all" && state.app && isDesktop()) state.app = "";
        syncDeviceSelect();
        renderControls();
        generate();
      });
    });
    document.getElementById("device").addEventListener("change", e => {
      state.deviceIdx = Number(e.target.value);
      const d = platformDevices()[state.deviceIdx];
      state.deviceKey = d ? d.id : "";
      syncOs();
      generate();
    });
    document.getElementById("os").addEventListener("change", generate);
    ["chrome", "firefoxVer", "edgeVer", "operaVer", "appVersion"].forEach(id =>
      document.getElementById(id).addEventListener("input", generate));
    const qty = document.getElementById("qty");
    qty.addEventListener("input", () => {
      document.getElementById("qtyVal").textContent = qty.value;
      generate();
    });
    document.getElementById("countrySearch").addEventListener("input", renderControls);
    document.getElementById("getBtn").addEventListener("click", generate);
    document.getElementById("copyAll").addEventListener("click", () => {
      if (window._lastResults && window._lastResults.length)
        navigator.clipboard.writeText(window._lastResults.join("\n"));
    });
    document.getElementById("download").addEventListener("click", () => {
      if (!window._lastResults || !window._lastResults.length) return;
      const blob = new Blob([window._lastResults.join("\n")], { type: "text/plain" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "nexora-user-agents.txt";
      a.click();
      URL.revokeObjectURL(a.href);
    });
    document.getElementById("liveToggle").addEventListener("change", boot);
    document.getElementById("firefoxVer").value = DEFAULT_VERSIONS.firefox;
    document.getElementById("edgeVer").value = DEFAULT_VERSIONS.edge;
    document.getElementById("operaVer").value = DEFAULT_VERSIONS.opera;
    boot();
    loadHistory();
  });
}
