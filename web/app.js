"use strict";

/* NEXORA Next public workspace + private admin control plane. */
const API_BASE = String(window.NEXORA_CONFIG?.apiBase || "").replace(/\/$/, "");
const APP_NAMES = {
  facebook: "Facebook", fblite: "Facebook Lite", instagram: "Instagram", tiktok: "TikTok",
  x: "X", threads: "Threads", linkedin: "LinkedIn", pinterest: "Pinterest", reddit: "Reddit",
  snapchat: "Snapchat", weibo: "Weibo", telegram: "Telegram", messenger: "Messenger",
  wechat: "WeChat", line: "LINE", kik: "Kik", discord: "Discord", viber: "Viber"
};
const state = {
  data: null, devices: [], apiOnline: false, apiError: "", token: sessionStorage.getItem("nexora_admin_token") || "",
  isAdmin: false, dataset: [], appFilter: "all", uaTemplates: null, uaTemplateError: "",
  uaResults: [], selectedDevices: { android: [], ios: [] }, customQuantities: {}, selectedApps: [],
  deviceTab: "android", adminOverview: null, adminSources: [], adminRuns: [], adminAudit: [],
  theme: localStorage.getItem("nexora_theme") || (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark")
};
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dateText = (value) => value ? new Date(value).toLocaleString() : "Not checked";
const numberText = (value) => Number(value || 0).toLocaleString();

async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  let body = {};
  const text = await response.text();
  try { body = text ? JSON.parse(text) : {}; } catch { body = { detail: text || response.statusText }; }
  if (!response.ok) {
    if (response.status === 401 && state.token) clearAdmin();
    const detail = body.detail;
    throw new Error(typeof detail === "string" ? detail : detail?.message || JSON.stringify(detail) || `Request failed (${response.status})`);
  }
  return body;
}

async function loadData() {
  try {
    state.data = await api("/api/v1/data");
    state.apiOnline = true; state.apiError = "";
  } catch (error) {
    state.apiOnline = false; state.apiError = error.message;
    const response = await fetch("./devices.json", { cache: "no-store" });
    if (!response.ok) throw new Error("Could not load the local catalog or connect to the NEXORA API.");
    state.data = await response.json();
  }
  state.devices = [
    ...(state.data.android || []).map(record => ({ platform: "android", record, sourceStatus: record.sourceStatus || "bundled" })),
    ...(state.data.ios_emulation || []).map(record => ({ platform: "ios_emulation", record, sourceStatus: record.sourceStatus || "bundled" }))
  ];
  state.uaTemplates = null; state.uaTemplateError = "";
  try {
    const templateResponse = await fetch("./ua_templates.json", { cache: "no-store" });
    if (templateResponse.ok) state.uaTemplates = await templateResponse.json();
    else state.uaTemplateError = templateResponse.status === 404 ? "UA template catalog has not been published." : `Template catalog unavailable (${templateResponse.status}).`;
  } catch (error) { state.uaTemplateError = error.message || "Template catalog unavailable."; }
  renderAll();
  updateHealth();
}

function allRecords() { return state.devices.map(item => ({ ...item.record, platform: item.platform, sourceStatus: item.sourceStatus })); }
function meta() { return state.data?.meta || {}; }
function versions() { return meta().versions || {}; }
function apps() { return meta().apps || {}; }

function updateHealth() {
  const dot = $("healthDot"), topDot = $("topStatusDot");
  if (state.apiOnline) {
    dot?.classList.add("good"); topDot?.classList.add("good");
    $("healthText").textContent = "API connected"; $("healthSub").textContent = "Live central database";
    $("topStatusText").textContent = "LIVE DATA";
  } else {
    dot?.classList.remove("good"); topDot?.classList.remove("good");
    $("healthText").textContent = "Offline fallback"; $("healthSub").textContent = "Using bundled read-only data";
    $("topStatusText").textContent = "BUNDLED DATA";
  }
  $("activityHealth").textContent = state.apiOnline ? "Central data service connected" : "Local catalog loaded";
  $("activityHealthDetail").textContent = state.apiOnline ? "API is responding normally" : "Admin editing and source automation require the API";
}

function renderAll() {
  renderOverview(); renderDeviceFilters(); renderDevices(); renderApplications(); renderCompatibility(); renderGeneratorFilters();
  $("dataVersionTag").textContent = meta().dataVersion || "Unversioned";
  $("activityVersion").textContent = meta().dataVersion || "—";
  $("activityDate").textContent = meta().updatedAt ? `Updated ${meta().updatedAt}` : "No update date";
  $("activitySource").textContent = meta().source || "NEXORA catalog";
  if (state.isAdmin) refreshAdminViews();
}

function renderOverview() {
  const list = allRecords();
  const appList = Object.entries(apps());
  const known = appList.filter(([, value]) => Boolean(value)).length;
  $("statDevices").textContent = numberText(list.length);
  $("statAndroid").textContent = numberText(list.filter(x => x.platform === "android").length);
  $("statIos").textContent = numberText(list.filter(x => x.platform === "ios_emulation").length);
  $("statCoverage").textContent = `${known}/${appList.length}`;
  $("statDeviceFoot").textContent = `${list.length} records in current catalog`;
  $("statCoverageFoot").textContent = `${appList.length - known} version fields need verification`;
}

function renderDeviceFilters() {
  const brands = [...new Set(allRecords().map(d => d.brand).filter(Boolean))].sort();
  const options = '<option value="all">All manufacturers</option>' + brands.map(x => `<option value="${esc(x)}">${esc(x)}</option>`).join("");
  const deviceBrand = $("deviceBrand");
  if (deviceBrand) { const old = deviceBrand.value; deviceBrand.innerHTML = options; if (brands.includes(old)) deviceBrand.value = old; }
}

function filteredDevices() {
  const query = ($("deviceSearch")?.value || "").trim().toLowerCase();
  const platform = $("devicePlatform")?.value || "all";
  const brand = $("deviceBrand")?.value || "all";
  return state.devices.filter(item => {
    const d = item.record;
    if (platform !== "all" && item.platform !== platform) return false;
    if (brand !== "all" && d.brand !== brand) return false;
    if (query && ![d.id, d.brand, d.model, d.uaModel, d.soc].join(" ").toLowerCase().includes(query)) return false;
    return true;
  });
}

function renderDevices() {
  const rows = filteredDevices();
  $("deviceCount").textContent = `${rows.length} records`;
  $("deviceTableFoot").textContent = `Showing ${rows.length} of ${state.devices.length} catalog records`;
  const tbody = $("deviceTable");
  if (!rows.length) { tbody.innerHTML = '<tr><td colspan="7" class="empty-cell">No matching device records.</td></tr>'; return; }
  tbody.innerHTML = rows.map(item => {
    const d = item.record, display = d.display || {};
    const status = item.sourceStatus === "admin_verified" ? '<span class="tag">ADMIN VERIFIED</span>' : item.sourceStatus === "ai_verified" ? '<span class="tag">AI REVIEWED</span>' : '<span class="tag neutral">LEGACY RECORD</span>';
    const action = state.isAdmin ? `<td class="admin-column"><button class="action-link" data-edit-device="${esc(d.id)}">Edit</button> <button class="action-link" data-delete-device="${esc(d.id)}">Delete</button></td>` : "";
    return `<tr><td><div class="device-cell"><span class="device-glyph">▯</span><span><strong>${esc(d.brand)} ${esc(d.model)}</strong><small>${esc(d.id)}</small></span></div></td><td><span class="tag neutral">${item.platform === "android" ? "ANDROID" : "IOS"}</span></td><td>${esc(d.uaModel || "—")}</td><td>${esc((d.osVersions || []).join(", "))}</td><td>${numberText(display.w)} × ${numberText(display.h)} · ${esc(display.dpr || "—")}x</td><td>${status}</td>${action}</tr>`;
  }).join("");
  tbody.querySelectorAll("[data-edit-device]").forEach(button => button.addEventListener("click", () => openDeviceEditor(button.dataset.editDevice)));
  tbody.querySelectorAll("[data-delete-device]").forEach(button => button.addEventListener("click", () => deleteDevice(button.dataset.deleteDevice)));
}

function renderApplications() {
  const entries = Object.entries(apps());
  const filtered = entries.filter(([id, value]) => state.appFilter === "all" || (state.appFilter === "known" ? Boolean(value) : !value));
  $("applicationGrid").innerHTML = filtered.map(([id, raw]) => {
    const version = typeof raw === "string" ? raw : raw?.version || "";
    const known = Boolean(version);
    const label = APP_NAMES[id] || id;
    const token = typeof raw === "object" && raw ? raw.token : "";
    const sourceUrl = typeof raw === "object" && raw ? raw.sourceUrl || "" : "";
    return `<article class="application-card"><div class="app-card-top"><div class="app-logo">${esc(label.slice(0, 2).toUpperCase())}</div><div><strong>${esc(label)}</strong><small>${esc(id)}</small></div></div><div class="app-card-release"><span>Version metadata</span><strong>${known ? esc(version) : "Not verified"}</strong></div><div class="app-card-link">${known ? "Version value stored" : "No verified version stored"}${token ? ` · token: ${esc(token)}` : ""}${sourceUrl ? `<br><a href="${esc(sourceUrl)}" target="_blank" rel="noopener noreferrer">Open official source ↗</a>` : ""}</div>${state.isAdmin ? `<div style="margin-top:auto;padding-top:12px"><button class="action-link" data-edit-app="${esc(id)}">Edit metadata</button></div>` : ""}</article>`;
  }).join("") || '<div class="panel output-empty"><strong>No applications match this filter.</strong></div>';
  $("applicationGrid").querySelectorAll("[data-edit-app]").forEach(button => button.addEventListener("click", () => openAppEditor(button.dataset.editApp)));
}

function renderCompatibility() {
  const rows = allRecords();
  const android = new Set(rows.filter(x => x.platform === "android").flatMap(x => x.osVersions || []));
  const ios = new Set(rows.filter(x => x.platform === "ios_emulation").flatMap(x => x.osVersions || []));
  const withBuild = rows.filter(x => Object.keys(x.builds || {}).length).length;
  $("compatAndroid").textContent = android.size; $("compatIos").textContent = ios.size;
  $("compatBuilds").textContent = withBuild; $("compatMissing").textContent = rows.length - withBuild;
  $("compatTable").innerHTML = rows.map(d => `<tr><td><div class="device-cell"><span class="device-glyph">▯</span><span><strong>${esc(d.brand)} ${esc(d.model)}</strong><small>${esc(d.id)}</small></span></div></td><td>${esc(d.platform === "android" ? "Android" : "iOS")}</td><td>${esc((d.osVersions || []).join(", "))}</td><td>${Object.keys(d.builds || {}).length ? esc(Object.keys(d.builds).join(", ")) : '<span class="tag warn">Not supplied</span>'}</td><td>${esc((d.ramGb || []).join(", "))} GB</td><td>${esc((d.storageGb || []).join(", "))} GB</td></tr>`).join("");
}


const UA_COUNTRIES = [
  ["", "All countries / automatic", "auto"],
  ["US","United States","en-US"],["GB","United Kingdom","en-GB"],["BD","Bangladesh","bn-BD"],
  ["IN","India","en-IN"],["PK","Pakistan","ur-PK"],["CA","Canada","en-CA"],["AU","Australia","en-AU"],
  ["DE","Germany","de-DE"],["FR","France","fr-FR"],["ES","Spain","es-ES"],["IT","Italy","it-IT"],
  ["BR","Brazil","pt-BR"],["MX","Mexico","es-MX"],["JP","Japan","ja-JP"],["KR","South Korea","ko-KR"],
  ["CN","China","zh-CN"],["TW","Taiwan","zh-TW"],["HK","Hong Kong","zh-HK"],["NL","Netherlands","nl-NL"],
  ["SE","Sweden","sv-SE"],["NO","Norway","nb-NO"],["DK","Denmark","da-DK"],["FI","Finland","fi-FI"],
  ["PL","Poland","pl-PL"],["GR","Greece","el-GR"],["TR","Türkiye","tr-TR"],["SA","Saudi Arabia","ar-SA"],
  ["AE","United Arab Emirates","ar-AE"],["EG","Egypt","ar-EG"],["NG","Nigeria","en-NG"],["ZA","South Africa","en-ZA"],
  ["ID","Indonesia","id-ID"],["MY","Malaysia","ms-MY"],["SG","Singapore","en-SG"],["PH","Philippines","en-PH"],
  ["VN","Vietnam","vi-VN"],["TH","Thailand","th-TH"],["LK","Sri Lanka","si-LK"],["NP","Nepal","ne-NP"]
];
const UA_APP_NAMES = {
  facebook:"Facebook", fb_lite:"Facebook Lite", instagram:"Instagram", tiktok:"TikTok", x:"X",
  threads:"Threads", linkedin:"LinkedIn", pinterest:"Pinterest", reddit:"Reddit", snapchat:"Snapchat",
  weibo:"Weibo", telegram:"Telegram", messenger:"Messenger", wechat:"WeChat", line:"LINE",
  kik:"Kik", discord:"Discord", viber:"Viber"
};
function templateRecords() {
  const source = state.uaTemplates;
  if (!source) return [];
  if (Array.isArray(source)) return source;
  return source.records || source.templates || source.uaTemplates || [];
}
function verifiedTemplates(appId, platform) {
  const aliases = appId === "fb_lite" ? ["fb_lite", "fblite", "facebook_lite"] : [appId];
  return templateRecords().filter(row => aliases.includes(row.app) && row.platform === platform && row.status === "verified");
}
function platformDevices(platform) {
  const expected = platform === "android" ? "android" : "ios_emulation";
  const country = $("uaCountry")?.value || "";
  return allRecords().filter(d => d.platform === expected && (!country || !Array.isArray(d.markets) || d.markets.includes(country)));
}
function numericVersionSort(values) {
  return [...new Set(values)].sort((a,b) => {
    const aa = String(a).split(/[._-]/).map(x => Number(x) || 0), bb = String(b).split(/[._-]/).map(x => Number(x) || 0);
    for (let i=0;i<Math.max(aa.length,bb.length);i++) { const diff=(aa[i]||0)-(bb[i]||0); if(diff) return diff; }
    return String(a).localeCompare(String(b));
  });
}
function setupUaGenerator() {
  const androidVersions = numericVersionSort(platformDevices("android").flatMap(d => d.osVersions || []));
  const iosVersions = numericVersionSort(platformDevices("ios").flatMap(d => d.osVersions || []));
  const fillOs = (id, values) => {
    const el = $(id); if (!el) return;
    const previous = el.value;
    el.innerHTML = '<option value="latest">Latest supported per device</option><option value="random">Random supported version</option>' +
      values.map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join("");
    if ([...el.options].some(o => o.value === previous)) el.value = previous;
  };
  fillOs("uaAndroidOs", androidVersions); fillOs("uaIosOs", iosVersions);
  const country = $("uaCountry");
  if (country && !country.dataset.ready) {
    country.innerHTML = UA_COUNTRIES.map(([code,name]) => `<option value="${esc(code)}">${esc(name)}</option>`).join("");
    country.dataset.ready = "1";
  }
  const chromeVersion = versions().chrome || "";
  if ($("uaChromeVersion") && !$("uaChromeVersion").value) $("uaChromeVersion").value = chromeVersion;
  if ($("uaChromiumEngineVersion") && !$("uaChromiumEngineVersion").value) $("uaChromiumEngineVersion").value = chromeVersion;
  $("chromeCatalogVersion").textContent = chromeVersion ? `Catalog · ${chromeVersion}` : "Version not in catalog";
  $("uaCatalogVersion").textContent = `DATA ${meta().dataVersion || "LOCAL"}`;
  $("uaDeviceMeta").textContent = `${platformDevices("android").length} Android · ${platformDevices("ios").length} iOS records`;
  $("uaDataVersion").textContent = meta().dataVersion || "Unversioned";
  $("uaDataDevices").textContent = allRecords().length;
  $("uaDataApps").textContent = Object.keys(apps()).length;
  $("uaDataSource").textContent = state.apiOnline ? "Live API" : "Bundled fallback";
  const verified = templateRecords().filter(x => x.status === "verified").length;
  $("uaDataTemplates").textContent = verified;
  renderUaDevicePicker();
  renderUaApps();
  updateUaTotals();
  renderSavedBatches();
}
function renderUaDevicePicker() {
  const mode = document.querySelector('input[name="uaDistribution"]:checked')?.value || "random";
  const picker = $("uaDevicePicker");
  picker.classList.toggle("hidden", mode === "random");
  document.querySelectorAll(".distribution-option").forEach(label => {
    const input = label.querySelector("input");
    label.classList.toggle("selected", Boolean(input?.checked));
  });
  const query = ($("uaDeviceSearch")?.value || "").trim().toLowerCase();
  const renderList = (platform) => {
    const target = $(platform === "android" ? "uaAndroidDeviceList" : "uaIosDeviceList");
    const records = platformDevices(platform).filter(d => !query || [d.brand,d.model,d.id,d.uaModel].join(" ").toLowerCase().includes(query));
    const selected = state.selectedDevices[platform] || [];
    target.innerHTML = records.map(d => {
      const checked = selected.includes(d.id);
      const custom = mode === "custom" && checked
        ? `<input class="device-quantity" type="number" min="0" max="500" value="${esc(state.customQuantities[d.id] ?? 0)}" data-custom-qty="${esc(d.id)}" aria-label="Quantity for ${esc(d.model)}">` : "";
      return `<label class="device-option"><input type="checkbox" data-device-id="${esc(d.id)}" data-device-platform="${platform}" ${checked ? "checked" : ""}><span class="device-option-copy"><b>${esc(d.brand)} ${esc(d.model)}</b><small>${esc(d.uaModel || d.id)} · ${(d.osVersions || []).length} OS versions</small></span>${custom}<span class="device-checkmark">✓</span></label>`;
    }).join("") || '<div class="device-list-empty">No devices match this search.</div>';
  };
  renderList("android"); renderList("ios");
  $("uaAndroidDeviceList").classList.toggle("hidden", state.deviceTab !== "android");
  $("uaIosDeviceList").classList.toggle("hidden", state.deviceTab !== "ios");
  $("uaAndroidDeviceCount").textContent = platformDevices("android").length;
  $("uaIosDeviceCount").textContent = platformDevices("ios").length;
  const totalSelected = state.selectedDevices.android.length + state.selectedDevices.ios.length;
  $("uaSelectedCount").textContent = `${totalSelected} device${totalSelected === 1 ? "" : "s"} selected`;
  $("uaDeviceMeta").textContent = `${platformDevices("android").length} Android · ${platformDevices("ios").length} iOS records in selected market`;
  $("uaDistributionHint").textContent = mode === "custom"
    ? "Enter an exact quantity for each selected device. Per-platform totals must match the requested quantities."
    : mode === "balanced"
      ? "Selected models receive an even distribution. Any remainder is assigned from the start of the selected list."
      : "Only selected models are eligible. iOS model identity is retained as metadata because standard iPhone User-Agent strings do not expose the exact model.";
}
function renderUaApps() {
  const records = Object.entries(apps());
  const verified = templateRecords().filter(x => x.status === "verified");
  $("uaTemplateSummary").textContent = state.uaTemplates
    ? `${verified.length} verified templates · ${records.length} applications`
    : `0 verified templates · ${records.length} apps have version/build data`;
  const q = ($("uaAppSearch")?.value || "").trim().toLowerCase();
  $("uaAppGrid").innerHTML = records.map(([id, raw]) => {
    const label = UA_APP_NAMES[id] || id;
    const version = typeof raw === "string" ? raw : raw?.version || "Version unavailable";
    const readyRows = verifiedTemplates(id, "android").concat(verifiedTemplates(id, "ios"));
    const ready = readyRows.length > 0;
    const checked = state.selectedApps.includes(id);
    const status = ready ? "VERIFIED TEMPLATE" : "TEMPLATE PENDING";
    if (q && !(label + " " + id).toLowerCase().includes(q)) return "";
    return `<label class="app-template-option ${ready ? "ready" : "pending"}"><input type="checkbox" data-app-template="${esc(id)}" ${checked ? "checked" : ""} ${ready ? "" : "disabled"}><span class="app-template-logo">${esc(label.slice(0,2).toUpperCase())}</span><span class="app-template-copy"><b>${esc(label)}</b><small>App ${esc(version)}</small></span><span class="app-template-status">${status}</span></label>`;
  }).join("") || '<div class="device-list-empty">No application matches your search.</div>';
}
function updateUaTotals() {
  const android = Math.max(0, Math.min(500, Number($("uaAndroidQty").value) || 0));
  const ios = Math.max(0, Math.min(500, Number($("uaIosQty").value) || 0));
  $("uaAndroidQty").value = android; $("uaIosQty").value = ios;
  $("uaAndroidRange").value = android; $("uaIosRange").value = ios;
  const total = android + ios;
  $("uaTotal").textContent = total;
  $("uaReadySummary").textContent = `${android} Android + ${ios} iOS · ${total} total`;
  $("uaAndroidRange").setAttribute("aria-valuenow", android);
  $("uaIosRange").setAttribute("aria-valuenow", ios);
  $("generateButton").disabled = total < 1 || total > 500;
}
function localeMeta() {
  const code = $("uaCountry").value;
  const entry = UA_COUNTRIES.find(x => x[0] === code);
  const language = $("uaLanguage").value === "auto" ? (entry?.[2] || "en-US") : $("uaLanguage").value;
  return { country: code || null, language, acceptLanguage: `${language},${language.split("-")[0]};q=0.9` };
}
function renderSavedBatches() {
  let batches = [];
  try { batches = JSON.parse(localStorage.getItem("nexora_ua_batches") || "[]"); } catch {}
  $("savedBatchCount").textContent = `${batches.length} saved`;
  $("uaSavedBatches").innerHTML = '<option value="">Open a saved set…</option>' + batches.map(b => `<option value="${esc(b.id)}">${esc(b.name)} · ${b.records.length} records</option>`).join("");
}
function getSelectedBrowsers(platform) {
  return [...document.querySelectorAll(`[data-browser][data-platform="${platform}"]:checked`)].map(x => x.dataset.browser);
}
function browserVersion(platform, browser) {
  const ids = {
    android: { chrome:"uaChromeVersion", firefox:"uaFirefoxAndroidVersion", edge:"uaEdgeAndroidVersion", opera:"uaOperaVersion" },
    ios: { safari:"uaSafariVersion", chrome:"uaChromeIosVersion", firefox:"uaFirefoxIosVersion", edge:"uaEdgeIosVersion" }
  };
  const id = ids[platform]?.[browser];
  return id ? $(id).value.trim() : "";
}
function buildBrowserUa(platform, device, osVersion, browser) {
  const model = device.uaModel || device.id;
  const build = device.builds?.[osVersion] || device.buildsRecent?.find(x => String(x.version) === String(osVersion))?.build || "";
  const engineVersion = $("uaChromiumEngineVersion").value.trim() || versions().chrome || "";
  const version = browserVersion(platform, browser);
  if (platform === "android") {
    if (browser === "chrome") {
      const v = version || versions().chrome;
      if (!v) throw new Error("Chrome version is missing from the database.");
      return `Mozilla/5.0 (Linux; Android ${osVersion}; ${model}${build ? ` Build/${build}` : ""}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${v} Mobile Safari/537.36`;
    }
    if (!version) throw new Error(`${browser[0].toUpperCase()+browser.slice(1)} Android version is required. Open Advanced browser versions.`);
    if (browser === "firefox") return `Mozilla/5.0 (Android ${osVersion}; Mobile; rv:${version}) Gecko/${version} Firefox/${version}`;
    if (!engineVersion) throw new Error("Chromium engine version is required for Edge and Opera.");
    const base = `Mozilla/5.0 (Linux; Android ${osVersion}; ${model}${build ? ` Build/${build}` : ""}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${engineVersion} Mobile Safari/537.36`;
    if (browser === "edge") return `${base} EdgA/${version}`;
    if (browser === "opera") return `${base} OPR/${version}`;
  }
  const ios = String(osVersion).replace(/\./g, "_");
  const head = `Mozilla/5.0 (iPhone; CPU iPhone OS ${ios} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) `;
  if (!version) throw new Error(`${browser[0].toUpperCase()+browser.slice(1)} iOS version is required. Open Advanced browser versions.`);
  if (browser === "safari") return `${head}Version/${version} Mobile/15E148 Safari/604.1`;
  if (browser === "chrome") return `${head}CriOS/${version} Mobile/15E148 Safari/604.1`;
  if (browser === "firefox") return `${head}FxiOS/${version} Mobile/15E148 Safari/605.1.15`;
  if (browser === "edge") return `${head}EdgiOS/${version} Mobile/15E148 Safari/605.1.15`;
  throw new Error(`Unsupported ${platform} browser: ${browser}`);
}
function buildVerifiedAppUa(appId, platform, device, osVersion) {
  const row = verifiedTemplates(appId, platform)[0];
  if (!row) throw new Error(`${UA_APP_NAMES[appId] || appId} has no verified ${platform} template.`);
  const app = apps()[appId] || {};
  const appVersion = typeof app === "string" ? app : app.version;
  const appBuild = typeof app === "object" ? app.build : "";
  const display = device.display || {};
  const loc = localeMeta();
  const context = {
    osVersion, model: device.uaModel || device.id,
    buildId: device.builds?.[osVersion] || device.buildsRecent?.find(x => String(x.version) === String(osVersion))?.build || "",
    chromeVersion: versions().chrome || "",
    appVersion: appVersion || "", appBuild: appBuild || "",
    density: display.dpr ? `${Math.round(display.dpr * 160)}dpi` : "",
    resolution: display.w && display.h ? `${display.w}x${display.h}` : "",
    locale: (loc.language || "en-US").replace("-", "_"),
    brand: device.brand || "", width: display.w || "", height: display.h || "",
    sdkVersion: ({ "10":29,"11":30,"12":31,"13":33,"14":34,"15":35,"16":36,"17":37 })[String(osVersion).split(".")[0]] || ""
  };
  let ua = row.template || "";
  const slots = row.slots || {};
  const placeholders = [...ua.matchAll(/\{([A-Za-z0-9_]+)\}/g)].map(m => m[1]);
  for (const key of placeholders) {
    if (!(key in context) || context[key] === "") throw new Error(`Template ${appId}/${platform} has unresolved slot: ${key}`);
    ua = ua.replaceAll(`{${key}}`, String(context[key]));
  }
  if (/\{[^}]+\}/.test(ua)) throw new Error(`Template ${appId}/${platform} contains unresolved placeholders.`);
  return { ua, appVersion, appBuild, templateId: row.id || `${appId}-${platform}`, evidence: row.evidence || [] };
}
function chooseOsVersion(device, platform) {
  const select = $(platform === "android" ? "uaAndroidOs" : "uaIosOs");
  const value = select.value;
  const supported = device.osVersions || [];
  if (value === "latest") return numericVersionSort(supported).at(-1);
  if (value === "random") return supported[Math.floor(Math.random() * supported.length)];
  if (!supported.includes(value)) throw new Error(`${device.model} does not support selected OS ${value}.`);
  return value;
}
function deviceSchedule(platform, quantity, mode) {
  const all = platformDevices(platform);
  const chosenIds = state.selectedDevices[platform] || [];
  let pool = mode === "random" ? all : all.filter(d => chosenIds.includes(d.id));
  if (!pool.length && quantity) throw new Error(`Select at least one ${platform} device or choose Random.`);
  const custom = [];
  if (mode === "custom") {
    for (const d of pool) {
      const count = Math.max(0, Number(state.customQuantities[d.id]) || 0);
      for (let i=0;i<count;i++) custom.push(d);
    }
    if (custom.length !== quantity) throw new Error(`${platform.toUpperCase()} custom device quantities total ${custom.length}; expected ${quantity}.`);
    return custom;
  }
  if (mode === "balanced") return Array.from({length:quantity}, (_,i)=>pool[i % pool.length]);
  return Array.from({length:quantity}, () => pool[Math.floor(Math.random()*pool.length)]);
}
function generateUaBatch() {
  const qty = { android: Number($("uaAndroidQty").value)||0, ios: Number($("uaIosQty").value)||0 };
  const total = qty.android + qty.ios;
  if (!total || total > 500) throw new Error("Choose a total between 1 and 500.");
  const mode = document.querySelector('input[name="uaDistribution"]:checked')?.value || "random";
  const locale = localeMeta();
  const dedupe = $("uaDeduplicate").checked;
  const output = [], seen = new Set();
  for (const platform of ["android","ios"]) {
    if (!qty[platform]) continue;
    const browsers = getSelectedBrowsers(platform);
    const selectedAppIds = state.selectedApps.filter(id => verifiedTemplates(id, platform).length);
    if (!browsers.length && !selectedAppIds.length) throw new Error(`Choose at least one compatible ${platform.toUpperCase()} browser or verified in-app template.`);
    const schedule = deviceSchedule(platform, qty[platform], mode);
    for (let i=0;i<schedule.length;i++) {
      const device = schedule[i];
      const osVersion = chooseOsVersion(device, platform);
      const useApp = selectedAppIds.length && (browsers.length === 0 || (i % (browsers.length + selectedAppIds.length)) >= browsers.length);
      let ua, browser, appId = null, appVersion = null, appBuild = null, templateId = null, templateEvidence = [];
      if (useApp) {
        appId = selectedAppIds[i % selectedAppIds.length];
        const built = buildVerifiedAppUa(appId, platform, device, osVersion);
        ua = built.ua; appVersion = built.appVersion; appBuild = built.appBuild; templateId = built.templateId; templateEvidence = built.evidence; browser = "In-app";
      } else {
        browser = browsers[i % browsers.length];
        ua = buildBrowserUa(platform, device, osVersion, browser);
      }
      if (dedupe && seen.has(ua)) continue;
      seen.add(ua);
      const buildId = device.builds?.[osVersion] || device.buildsRecent?.find(x => String(x.version) === String(osVersion))?.build || null;
      output.push({
        id: `nexora-ua-${String(output.length+1).padStart(4,"0")}`,
        platform: platform === "android" ? "Android" : "iOS",
        deviceId: device.id, brand: device.brand, deviceModel: device.model,
        modelIdentifier: device.uaModel || null, osVersion, buildId,
        browser, browserVersion: appId ? null : browserVersion(platform, browser) || (platform === "android" && browser === "chrome" ? versions().chrome : null),
        browserVersionSource: appId ? "verified-template" : (platform === "android" && browser === "chrome" && !$("uaChromeVersion").dataset.manual ? "database" : "manual"),
        appId, appVersion, appBuild, templateId, templateEvidence, userAgent: ua,
        country: locale.country, language: locale.language, acceptLanguage: locale.acceptLanguage,
        catalogDataVersion: meta().dataVersion || null,
        sourceType: appId ? "verified-app-template" : "browser-template",
        note: platform === "ios" ? "iOS device identity is retained as metadata; standard iPhone UAs do not expose the exact model." : "Generated compatibility UA; this does not alter the real runtime device."
      });
    }
  }
  return output;
}
function renderUaResultsView() {
  const rows = state.dataset || [];
  $("uaResultTotal").textContent = rows.length;
  $("uaResultAndroid").textContent = rows.filter(x => x.platform === "Android").length;
  $("uaResultIos").textContent = rows.filter(x => x.platform === "iOS").length;
  $("uaResultSubtitle").textContent = rows.length ? `${rows.length} records · data ${meta().dataVersion || "local"}` : "Your results will appear here.";
  if (!rows.length) {
    $("generatorPreview").classList.remove("hidden");
    $("uaResultsList").classList.add("hidden");
    $("exportActions").classList.add("hidden");
    return;
  }
  $("generatorPreview").classList.add("hidden");
  $("uaResultsList").classList.remove("hidden");
  $("uaResultsList").innerHTML = rows.slice(0,20).map((r,i) =>
    `<article class="ua-result-item"><div class="ua-result-index">${String(i+1).padStart(2,"0")}</div><div class="ua-result-main"><div class="ua-result-tags"><span>${esc(r.platform)}</span><span>${esc(r.browser)}</span><span>${esc(r.osVersion)}</span></div><strong>${esc(r.brand)} ${esc(r.deviceModel)}</strong><code>${esc(r.userAgent)}</code></div><button class="copy-one" type="button" data-copy-ua="${i}" aria-label="Copy User-Agent ${i+1}">Copy</button></article>`
  ).join("") + (rows.length > 20 ? `<div class="more-results">Showing 20 of ${rows.length} results. Export or copy all to access the complete batch.</div>` : "");
  $("exportActions").classList.remove("hidden");
  $("uaDataVersion").textContent = meta().dataVersion || "Unversioned";
}
function csvValue(value) { return `"${String(value ?? "").replaceAll('"','""')}"`; }
function exportDataset() {
  if (!state.dataset.length) return;
  const format = $("uaExportFormat").value;
  if (format === "json") downloadText("nexora-user-agents.json", JSON.stringify({ meta: { dataVersion: meta().dataVersion, generatedAt: new Date().toISOString(), count: state.dataset.length }, records: state.dataset }, null, 2), "application/json");
  else if (format === "csv") {
    const keys = Object.keys(state.dataset[0]);
    const csv = [keys.map(csvValue).join(","), ...state.dataset.map(row => keys.map(key => csvValue(row[key])).join(","))].join("\n");
    downloadText("nexora-user-agents.csv", csv, "text/csv;charset=utf-8");
  } else downloadText("nexora-user-agents.txt", state.dataset.map(row => row.userAgent).join("\n"), "text/plain;charset=utf-8");
}
function downloadText(name, content, type = "text/plain") {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a"); link.href = url; link.download = name; link.click();
  URL.revokeObjectURL(url);
}
function playUiSound(kind = "tap") {
  if (!$("uaSound")?.checked || !window.AudioContext) return;
  try {
    const ctx = new AudioContext(), osc = ctx.createOscillator(), gain = ctx.createGain();
    osc.type = "sine"; osc.frequency.value = kind === "success" ? 660 : 420;
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.035, ctx.currentTime + 0.012);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.12);
    osc.connect(gain); gain.connect(ctx.destination); osc.start(); osc.stop(ctx.currentTime + 0.13);
    osc.onended = () => ctx.close();
  } catch {}
}

function renderGeneratorFilters() { setupUaGenerator(); }

function makeDataset() { return generateUaBatch(); }

function renderDatasetPreview() { renderUaResultsView(); }

function downloadText(filename, text, mime) {
  const blob = new Blob([text], { type: mime || "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob); const a = document.createElement("a");
  a.href = url; a.download = filename; a.click(); URL.revokeObjectURL(url);
}
function csvValue(value) { const text = typeof value === "object" ? JSON.stringify(value) : String(value ?? ""); return `"${text.replace(/"/g, '""')}"`; }
function exportDataset() { if (!state.dataset.length) return; const format = $("uaExportFormat").value; if (format === "json") downloadText("nexora-user-agents.json", JSON.stringify({meta:{dataVersion:meta().dataVersion,generatedAt:new Date().toISOString(),count:state.dataset.length},records:state.dataset},null,2), "application/json"); else if (format === "csv") { const keys=Object.keys(state.dataset[0]); const csv=[keys.map(csvValue).join(","),...state.dataset.map(row=>keys.map(key=>csvValue(row[key])).join(","))].join("\n"); downloadText("nexora-user-agents.csv",csv,"text/csv;charset=utf-8"); } else downloadText("nexora-user-agents.txt",state.dataset.map(row=>row.userAgent).join("\n"),"text/plain;charset=utf-8"); }

function showToast(message, type = "") {
  const toast = document.createElement("div"); toast.className = `toast ${type}`; toast.textContent = message;
  $("toastRegion").appendChild(toast); setTimeout(() => toast.remove(), 3800);
}
function go(view) {
  const adminViews = ["overview","compatibility","automation","review","assistant","audit","settings"];
  if (adminViews.includes(view) && !state.isAdmin) { showToast("Sign in to access the system control center.", "error"); openModal("loginModal"); return; }
  document.querySelectorAll(".view").forEach(el => el.classList.toggle("active", el.id === `view-${view}`));
  document.querySelectorAll(".nav-item[data-view]").forEach(el => el.classList.toggle("active", el.dataset.view === view));
  const active = document.querySelector(`.nav-item[data-view="${view}"]`);
  $("breadcrumbCurrent").textContent = active ? active.textContent.trim().replace(/\s+\d+$/, "") : view[0].toUpperCase() + view.slice(1);
  $("sidebar").classList.remove("open");
  if (view === "automation" && state.isAdmin) loadAutomation();
  if (view === "review" && state.isAdmin) loadReview();
  if (view === "audit" && state.isAdmin) loadAudit();
  if (view === "overview" && state.isAdmin) refreshAdminViews();
}

function setAdmin(token) {
  state.token = token; state.isAdmin = true; sessionStorage.setItem("nexora_admin_token", token);
  document.body.classList.add("is-admin");
  document.querySelectorAll(".admin-only").forEach(el => el.classList.remove("hidden"));
  $("adminLabel").textContent = "Administrator"; $("adminAvatar").textContent = "A"; $("authButton").textContent = "Sign out";
  renderDevices(); renderApplications(); renderOverview(); refreshAdminViews();
}

function clearAdmin() {
  state.token = ""; state.isAdmin = false; sessionStorage.removeItem("nexora_admin_token");
  document.body.classList.remove("is-admin"); document.querySelectorAll(".admin-only").forEach(el => el.classList.add("hidden"));
  $("adminLabel").textContent = "Guest access"; $("adminAvatar").textContent = "G"; $("authButton").textContent = "Sign in";
  if (["overview","compatibility","automation","review","assistant","audit","settings"].includes(currentView())) go("generator");
  renderDevices(); renderApplications(); renderOverview();
}

function currentView() { return document.querySelector(".view.active")?.id.replace("view-", "") || "overview"; }
async function verifyAdmin() {
  if (!state.token) return;
  try { await api("/api/v1/admin/overview"); setAdmin(state.token); }
  catch { clearAdmin(); }
}

function openModal(id) { $(id).classList.remove("hidden"); }
function closeModal(id) { $(id).classList.add("hidden"); }
function findDevice(id) { return state.devices.find(item => item.record.id === id); }
function openDeviceEditor(id = "") {
  if (!state.isAdmin) return;
  const item = id ? findDevice(id) : null, d = item?.record || {};
  $("deviceModalTitle").textContent = id ? "Edit device record" : "Add device record";
  $("editOriginalId").value = id;
  $("editPlatform").value = item?.platform || "android";
  $("editId").value = d.id || ""; $("editBrand").value = d.brand || ""; $("editModel").value = d.model || "";
  $("editUaModel").value = d.uaModel || ""; $("editSoc").value = d.soc || ""; $("editGpu").value = d.gpu || "";
  $("editCores").value = d.cores || ""; $("editWidth").value = d.display?.w || ""; $("editHeight").value = d.display?.h || ""; $("editDpr").value = d.display?.dpr || "";
  $("editRam").value = (d.ramGb || []).join(", "); $("editStorage").value = (d.storageGb || []).join(", "); $("editOs").value = (d.osVersions || []).join(", ");
  $("editBuilds").value = JSON.stringify(d.builds || {}, null, 2); $("editMarkets").value = (d.markets || []).join(", "); $("editSourceUrl").value = d.sourceUrl || d.source_url || ""; $("deviceError").classList.add("hidden"); openModal("deviceModal");
}
function parseNumbers(value, allowFloat = false) {
  return value.split(",").map(x => allowFloat ? Number(x.trim()) : parseInt(x.trim(), 10)).filter(x => Number.isFinite(x) && x > 0);
}
async function saveDevice(event) {
  event.preventDefault();
  const originalId = $("editOriginalId").value;
  let builds;
  try { builds = JSON.parse($("editBuilds").value || "{}"); } catch { $("deviceError").textContent = "Build IDs must be valid JSON."; $("deviceError").classList.remove("hidden"); return; }
  const record = {
    id: $("editId").value.trim(), brand: $("editBrand").value.trim(), model: $("editModel").value.trim(),
    uaModel: $("editUaModel").value.trim(), soc: $("editSoc").value.trim(), gpu: $("editGpu").value.trim(), cores: Number($("editCores").value),
    display: { w: Number($("editWidth").value), h: Number($("editHeight").value), dpr: Number($("editDpr").value) },
    ramGb: parseNumbers($("editRam").value), storageGb: parseNumbers($("editStorage").value),
    osVersions: $("editOs").value.split(",").map(x => x.trim()).filter(Boolean), builds,
    markets: $("editMarkets").value.split(",").map(x => x.trim().toUpperCase()).filter(Boolean),
    source_url: $("editSourceUrl").value.trim()
  };
  const errors = [];
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(record.id)) errors.push("Record ID must use lowercase kebab-case.");
  ["brand", "model", "uaModel", "soc", "gpu"].forEach(k => { if (!record[k]) errors.push(`${k} is required.`); });
  if (!record.cores || record.cores < 1) errors.push("CPU cores must be positive.");
  if (!record.display.w || !record.display.h || !record.display.dpr) errors.push("Display width, height and DPR must be positive.");
  if (!record.ramGb.length || !record.storageGb.length || !record.osVersions.length) errors.push("RAM, storage and OS versions are required.");
  if (!originalId && !record.source_url) errors.push("An official source URL is required for new records.");
  if (record.source_url && !/^https:\/\//i.test(record.source_url)) errors.push("Source URL must use HTTPS.");
  if (Object.keys(builds).some(k => !record.osVersions.includes(k))) errors.push("Every build ID key must be a supported OS version.");
  if (errors.length) { $("deviceError").textContent = errors.join(" "); $("deviceError").classList.remove("hidden"); return; }
  try {
    const body = JSON.stringify({ platform: $("editPlatform").value, record });
    if (originalId) await api(`/api/v1/admin/devices/${encodeURIComponent(originalId)}`, { method: "PUT", body });
    else await api("/api/v1/admin/devices", { method: "POST", body });
    closeModal("deviceModal"); showToast(originalId ? "Device updated." : "Device added."); await loadData();
  } catch (error) { $("deviceError").textContent = error.message; $("deviceError").classList.remove("hidden"); }
}
async function deleteDevice(id) {
  if (!state.isAdmin || !confirm(`Delete ${id} from the shared catalog? This action will be recorded.`)) return;
  try { await api(`/api/v1/admin/devices/${encodeURIComponent(id)}`, { method: "DELETE" }); showToast("Device deleted."); await loadData(); }
  catch (error) { showToast(error.message, "error"); }
}

function openAppEditor(id = "") {
  if (!state.isAdmin) return;
  const select = $("editAppId"); select.innerHTML = Object.keys(apps()).map(key => `<option value="${esc(key)}">${esc(APP_NAMES[key] || key)}</option>`).join("");
  if (id) select.value = id;
  const value = apps()[select.value]; $("editAppVersion").value = typeof value === "string" ? value : value?.version || "";
  $("editAppToken").value = typeof value === "object" && value ? value.token || "" : "";
  $("editAppSourceUrl").value = typeof value === "object" && value ? value.sourceUrl || "" : "";
  $("appVersionError").classList.add("hidden"); openModal("appVersionModal");
}
async function saveAppVersion(event) {
  event.preventDefault();
  try {
    await api("/api/v1/admin/app-versions", { method: "POST", body: JSON.stringify({ app_id: $("editAppId").value, version: $("editAppVersion").value.trim(), token: $("editAppToken").value.trim(), source_url: $("editAppSourceUrl").value.trim() }) });
    closeModal("appVersionModal"); showToast("Application version metadata saved."); await loadData();
  } catch (error) { $("appVersionError").textContent = error.message; $("appVersionError").classList.remove("hidden"); }
}


function renderSystemOverview(overview = state.adminOverview) {
  const stats = overview?.stats || {};
  const appRows = Object.values(apps());
  const knownApps = appRows.filter(x => Boolean(typeof x === "string" ? x : x?.version)).length;
  const verified = templateRecords().filter(x => x.status === "verified").length;
  const pendingTemplates = Math.max(0, Object.keys(apps()).length - new Set(templateRecords().filter(x => x.status === "verified").map(x => x.app)).size);
  $("systemApiStatus").textContent = state.apiOnline ? "Online" : "Fallback";
  $("systemApiDetail").textContent = state.apiOnline ? "Live API responded" : "Bundled catalog in use";
  $("systemDatabaseVersion").textContent = meta().dataVersion || "—";
  $("systemDatabaseDate").textContent = meta().updatedAt || "No timestamp";
  $("systemDeviceCount").textContent = stats.devices ?? allRecords().length;
  $("systemPlatformCounts").textContent = `${stats.android ?? platformDevices("android").length} Android / ${stats.ios ?? platformDevices("ios").length} iOS`;
  $("systemAppCount").textContent = stats.applications ?? Object.keys(apps()).length;
  $("systemAppCoverage").textContent = `${stats.verifiedAppVersions ?? knownApps} app versions available`;
  $("systemUaCatalog").textContent = state.uaTemplates ? (state.uaTemplates.dataVersion || "Published") : "Not published";
  $("systemUaCoverage").textContent = `${verified} verified · ${pendingTemplates} pending`;
  $("systemPendingCount").textContent = overview?.pendingProposals ?? "—";
  const sourceCount = state.adminSources.length;
  const healthySources = state.adminSources.filter(s => s.status === "healthy").length;
  const lastRun = state.adminRuns[0];
  $("systemPipelineRows").innerHTML = [
    ["Device catalog", `${allRecords().length} records · ${meta().dataVersion || "unversioned"}`, "good"],
    ["UA template catalog", state.uaTemplates ? `${verified} verified templates` : "Not published · collection pending", state.uaTemplates ? "good" : "warn"],
    ["Source connectors", state.adminSources.length ? `${healthySources}/${sourceCount} healthy` : "Not loaded", state.adminSources.length && healthySources === sourceCount ? "good" : "warn"],
    ["Latest automation", lastRun ? `${lastRun.status} · ${dateText(lastRun.startedAt)}` : "No runs recorded", lastRun?.status === "completed" ? "good" : "warn"]
  ].map(([label,value,status]) => `<div class="system-status-row"><span>${esc(label)}</span><strong class="${status}">${esc(value)}</strong></div>`).join("");
  const events = state.adminAudit.slice(0,5);
  $("systemRecentEvents").innerHTML = events.map(e => `<div class="system-status-row"><span>${esc(e.action)} · ${esc(e.target)}</span><strong>${esc(dateText(e.createdAt))}</strong></div>`).join("") || '<div class="system-status-row"><span>No recent audit events</span><strong>—</strong></div>';
}

async function refreshAdminViews() {
  if (!state.isAdmin) return;
  try {
    const [overview, sources, runs, audit] = await Promise.all([
      api("/api/v1/admin/overview"), api("/api/v1/admin/sources"), api("/api/v1/admin/runs"), api("/api/v1/admin/audit?limit=8")
    ]);
    state.adminOverview = overview; state.adminSources = sources; state.adminRuns = runs; state.adminAudit = audit;
    $("reviewCount").textContent = overview.pendingProposals;
    $("reviewCount").classList.toggle("hidden", !overview.pendingProposals);
    renderSystemOverview(overview);
  } catch (error) {
    $("systemApiStatus").textContent = "Unavailable";
    $("systemApiDetail").textContent = error.message || "Admin API unavailable";
  }
}

async function loadAutomation() {
  try {
    const [sources, runs] = await Promise.all([api("/api/v1/admin/sources"), api("/api/v1/admin/runs")]);
    state.adminSources = sources; state.adminRuns = runs; renderSystemOverview();
    $("sourceCount").textContent = `${sources.length} sources`;
    $("sourceTable").innerHTML = sources.map(s => `<tr><td><strong>${esc(s.name)}</strong><br><small class="muted-cell">${esc(s.url)}</small></td><td>${esc(s.category.replaceAll("_", " "))}</td><td>${s.trusted ? '<span class="tag">OFFICIAL</span>' : '<span class="tag neutral">UNVERIFIED</span>'}</td><td><span class="tag ${s.status === "healthy" ? "" : s.status === "error" ? "bad" : "warn"}">${esc(s.status.toUpperCase())}</span></td><td>${esc(dateText(s.lastChecked))}</td><td>${esc(s.details?.latestVersion || s.details?.error || s.details?.note || (s.details?.aiExtraction === "not_configured" ? "AI key not configured" : s.details?.lastObservation || "—"))}</td><td><button class="action-link" data-toggle-source="${esc(s.id)}" data-enabled="${s.enabled ? "true" : "false"}">${s.enabled ? "Pause" : "Enable"}</button></td></tr>`).join("") || '<tr><td colspan="7" class="empty-cell">No sources configured.</td></tr>';
    $("sourceTable").querySelectorAll("[data-toggle-source]").forEach(button => button.addEventListener("click", () => toggleSource(button.dataset.toggleSource, button.dataset.enabled !== "true")));
    renderRuns(runs);
  } catch (error) { $("sourceTable").innerHTML = `<tr><td colspan="6" class="empty-cell">${esc(error.message)}</td></tr>`; }
}
function renderRuns(runs) {
  $("runsTable").innerHTML = runs.map(r => `<tr><td>#${r.id}</td><td>${esc(r.source)}</td><td><span class="tag ${r.status === "completed" ? "" : r.status === "failed" ? "bad" : "warn"}">${esc(r.status.toUpperCase())}</span></td><td>${esc(dateText(r.startedAt))}</td><td>${esc(r.summary)}</td></tr>`).join("") || '<tr><td colspan="5" class="empty-cell">No automation runs yet.</td></tr>';
}
async function toggleSource(id, enabled) {
  try { await api(`/api/v1/admin/sources/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ enabled }) }); showToast(enabled ? "Source connector enabled." : "Source connector paused."); await loadAutomation(); }
  catch (error) { showToast(error.message, "error"); }
}

async function runRefresh() {
  try { const result = await api("/api/v1/admin/refresh", { method: "POST" }); showToast(result.message || "Source check queued."); await loadAutomation(); }
  catch (error) { showToast(error.message, "error"); }
}
async function reloadRuns() { try { renderRuns(await api("/api/v1/admin/runs")); } catch (error) { showToast(error.message, "error"); } }

async function loadReview() {
  try {
    const [pending, approved, rejected] = await Promise.all(["pending", "approved", "rejected"].map(s => api(`/api/v1/admin/proposals?status=${s}`)));
    $("pendingTotal").textContent = pending.length; $("approvedTotal").textContent = approved.length; $("rejectedTotal").textContent = rejected.length;
    $("reviewCount").textContent = pending.length; $("reviewCount").classList.toggle("hidden", !pending.length);
    $("reviewList").innerHTML = pending.map(p => `<article class="proposal-card"><div class="proposal-top"><div><h3>${esc(p.title)}</h3><p>Proposal #${p.id} · ${esc(p.kind)} · ${esc(dateText(p.createdAt))}</p></div><span class="tag warn">PENDING</span></div><div class="proposal-meta"><span class="tag neutral">Confidence ${Math.round(p.confidence * 100)}%</span><span class="tag neutral">${p.evidence.length} evidence item(s)</span></div><pre class="proposal-payload">${esc(JSON.stringify(p.payload, null, 2))}</pre><details class="proposal-evidence"><summary>View source evidence</summary><pre class="proposal-payload">${esc(JSON.stringify(p.evidence, null, 2))}</pre></details><div class="proposal-actions"><button class="button button-secondary button-small" data-reject-proposal="${p.id}">Reject</button><button class="button button-primary button-small" data-approve-proposal="${p.id}">Approve and publish</button></div></article>`).join("") || '<div class="panel output-empty"><div class="empty-art">✓</div><strong>No pending changes</strong><span>New source findings and AI proposals will appear here.</span></div>';
    $("reviewList").querySelectorAll("[data-approve-proposal]").forEach(b => b.addEventListener("click", () => decideProposal(b.dataset.approveProposal, "approve")));
    $("reviewList").querySelectorAll("[data-reject-proposal]").forEach(b => b.addEventListener("click", () => decideProposal(b.dataset.rejectProposal, "reject")));
  } catch (error) { $("reviewList").innerHTML = `<div class="panel output-empty">${esc(error.message)}</div>`; }
}
async function decideProposal(id, decision) {
  const message = decision === "approve" ? "Approve this change and publish it to the public catalog?" : "Reject this proposal?";
  if (!confirm(message)) return;
  try { await api(`/api/v1/admin/proposals/${id}/decision?decision=${decision}`, { method: "POST" }); showToast(`Proposal ${decision}d.`); await loadReview(); await loadData(); }
  catch (error) { showToast(error.message, "error"); }
}
async function loadAudit() {
  try {
    const rows = await api("/api/v1/admin/audit");
    state.adminAudit = rows; renderSystemOverview();
    $("auditTable").innerHTML = rows.map(r => `<tr><td>${esc(dateText(r.createdAt))}</td><td>${esc(r.actor)}</td><td>${esc(r.action)}</td><td>${esc(r.target)}</td><td>${esc(JSON.stringify(r.details))}</td></tr>`).join("") || '<tr><td colspan="5" class="empty-cell">No administrative changes recorded yet.</td></tr>';
  } catch (error) { $("auditTable").innerHTML = `<tr><td colspan="5" class="empty-cell">${esc(error.message)}</td></tr>`; }
}

function appendAssistantMessage(text, who = "ai") {
  const row = document.createElement("div"); row.className = `assistant-message ${who === "user" ? "assistant-message-user" : ""}`;
  const avatar = document.createElement("div"); avatar.className = "message-avatar"; avatar.textContent = who === "user" ? "YOU" : "NX";
  const body = document.createElement("div"); const strong = document.createElement("strong"); strong.textContent = who === "user" ? "YOU" : "NEXORA AI";
  const p = document.createElement("p"); p.textContent = text; body.append(strong, p); row.append(avatar, body); $("assistantMessages").appendChild(row); $("assistantMessages").scrollTop = $("assistantMessages").scrollHeight;
}
async function askAssistant(event) {
  event.preventDefault(); const prompt = $("assistantPrompt").value.trim(); if (!prompt) return;
  appendAssistantMessage(prompt, "user"); $("assistantPrompt").value = "";
  try { const result = await api("/api/v1/admin/ai/assist", { method: "POST", body: JSON.stringify({ prompt }) }); appendAssistantMessage(result.answer); $("aiModelLabel").textContent = result.model.toUpperCase(); }
  catch (error) { appendAssistantMessage(`I could not process that request: ${error.message}. Configure AI_API_KEY on the backend and try again.`); }
}

function bindEvents() {
  document.querySelectorAll("[data-view]").forEach(button => button.addEventListener("click", () => go(button.dataset.view)));
  document.querySelectorAll("[data-go]").forEach(button => button.addEventListener("click", () => go(button.dataset.go)));
  document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => closeModal(button.dataset.close)));
  $("mobileMenu").addEventListener("click", () => $("sidebar").classList.toggle("open"));
  $("authButton").addEventListener("click", () => state.isAdmin ? (clearAdmin(), showToast("Signed out.")) : openModal("loginModal"));
  $("loginForm").addEventListener("submit", async event => {
    event.preventDefault(); $("loginError").classList.add("hidden");
    try {
      const result = await api("/api/v1/admin/login", { method: "POST", body: JSON.stringify({ password: $("adminPassword").value }) });
      setAdmin(result.accessToken); closeModal("loginModal"); $("adminPassword").value = "";
      go("overview"); showToast("Administrator session started.");
    } catch (error) { $("loginError").textContent = error.message; $("loginError").classList.remove("hidden"); }
  });
  $("deviceSearch").addEventListener("input", renderDevices); $("devicePlatform").addEventListener("change", renderDevices); $("deviceBrand").addEventListener("change", renderDevices);
  $("addDevice").addEventListener("click", () => openDeviceEditor()); $("deviceForm").addEventListener("submit", saveDevice);
  $("exportDevices").addEventListener("click", () => downloadText("nexora-device-catalog.json", JSON.stringify(state.data, null, 2), "application/json"));
  $("appFilters").querySelectorAll("[data-app-filter]").forEach(button => button.addEventListener("click", () => { state.appFilter = button.dataset.appFilter; $("appFilters").querySelectorAll(".filter-pill").forEach(b => b.classList.toggle("active", b === button)); renderApplications(); }));
  $("editAppVersions").addEventListener("click", () => openAppEditor()); $("appVersionForm").addEventListener("submit", saveAppVersion);

  const uaRoot = $("uaGenerator");
  uaRoot.addEventListener("input", event => {
    const t = event.target;
    if (t.id === "uaAndroidQty" || t.id === "uaIosQty") updateUaTotals();
    if (t.id === "uaAndroidRange") { $("uaAndroidQty").value = t.value; updateUaTotals(); }
    if (t.id === "uaIosRange") { $("uaIosQty").value = t.value; updateUaTotals(); }
    if (t.id === "uaDeviceSearch") renderUaDevicePicker();
    if (t.id === "uaAppSearch") renderUaApps();
    if (t.id === "uaChromeVersion") t.dataset.manual = "1";
    if (t.hasAttribute("data-custom-qty")) state.customQuantities[t.dataset.customQty] = Math.max(0, Math.min(500, Number(t.value) || 0));
  });
  uaRoot.addEventListener("change", event => {
    const t = event.target;
    if (t.name === "uaDistribution") renderUaDevicePicker();
    if (t.hasAttribute("data-device-id")) {
      const platform = t.dataset.devicePlatform, id = t.dataset.deviceId;
      const list = new Set(state.selectedDevices[platform] || []);
      if (t.checked) list.add(id); else list.delete(id);
      state.selectedDevices[platform] = [...list];
      renderUaDevicePicker();
    }
    if (t.hasAttribute("data-app-template")) {
      const id = t.dataset.appTemplate;
      const list = new Set(state.selectedApps);
      if (t.checked) list.add(id); else list.delete(id);
      state.selectedApps = [...list];
      renderUaApps();
    }
    if (t.id === "uaCountry") {
      $("uaLanguage").value = "auto";
      renderGeneratorFilters();
    }
    if (t.id === "uaSound") playUiSound("tap");
  });
  uaRoot.addEventListener("click", event => {
    const qtyButton = event.target.closest("[data-qty-step]");
    if (qtyButton) {
      const platform = qtyButton.dataset.qtyStep, input = $(platform === "android" ? "uaAndroidQty" : "uaIosQty");
      input.value = Math.max(0, Math.min(500, Number(input.value || 0) + Number(qtyButton.dataset.step)));
      updateUaTotals(); playUiSound("tap");
    }
    const tab = event.target.closest("[data-device-tab]");
    if (tab) {
      state.deviceTab = tab.dataset.deviceTab;
      document.querySelectorAll("[data-device-tab]").forEach(x => x.classList.toggle("active", x === tab));
      renderUaDevicePicker();
    }
    const copy = event.target.closest("[data-copy-ua]");
    if (copy) {
      const record = state.dataset[Number(copy.dataset.copyUa)];
      if (record) navigator.clipboard.writeText(record.userAgent).then(() => showToast("User-Agent copied."), () => showToast("Clipboard unavailable.", "error"));
    }
  });
  $("generateButton").addEventListener("click", () => {
    try {
      state.dataset = generateUaBatch();
      renderDatasetPreview();
      if (!state.dataset.length) showToast("No unique User-Agent strings matched this configuration.", "error");
      else { showToast(`Generated ${state.dataset.length} User-Agent records.`); playUiSound("success"); }
    } catch (error) { showToast(error.message, "error"); }
  });
  $("downloadDataset").addEventListener("click", exportDataset);
  $("copyDataset").addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(state.dataset.map(x => x.userAgent).join("\n")); showToast("All User-Agent strings copied."); }
    catch { showToast("Clipboard access is unavailable in this browser.", "error"); }
  });
  $("saveUaBatch").addEventListener("click", () => {
    if (!state.dataset.length) return;
    let batches = []; try { batches = JSON.parse(localStorage.getItem("nexora_ua_batches") || "[]"); } catch {}
    const id = `ua-${Date.now()}`;
    batches.unshift({ id, name: `UA set ${new Date().toLocaleString()}`, records: state.dataset, savedAt: new Date().toISOString() });
    localStorage.setItem("nexora_ua_batches", JSON.stringify(batches.slice(0,20)));
    renderSavedBatches(); $("uaSavedBatches").value = id; showToast("User-Agent set saved on this device.");
  });
  $("uaSavedBatches").addEventListener("change", () => {
    const id = $("uaSavedBatches").value; if (!id) return;
    let batches = []; try { batches = JSON.parse(localStorage.getItem("nexora_ua_batches") || "[]"); } catch {}
    const batch = batches.find(x => x.id === id);
    if (batch) { state.dataset = batch.records || []; renderDatasetPreview(); showToast("Saved User-Agent set loaded."); }
  });
  $("runRefresh").addEventListener("click", runRefresh); $("reloadRuns").addEventListener("click", reloadRuns); $("reloadAudit").addEventListener("click", loadAudit);
  $("assistantForm").addEventListener("submit", askAssistant);
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => { $("assistantPrompt").value = button.dataset.prompt; $("assistantPrompt").focus(); }));
  $("themeToggle").addEventListener("click", () => { state.theme = state.theme === "dark" ? "light" : "dark"; document.body.classList.toggle("light", state.theme === "light"); localStorage.setItem("nexora_theme", state.theme); });
}

async function init() {
  document.body.classList.toggle("light", state.theme === "light");
  bindEvents();
  await loadData();
  await verifyAdmin();
}

document.addEventListener("DOMContentLoaded", init);
