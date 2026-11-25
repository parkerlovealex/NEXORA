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
  uaResults: [], selectedDevices: { android: [], ios: [] }, customQuantities: {}, selectedApps: [], platformMode: "android", appMode: false, resultFilter: "all", showAllResults: false, duplicatesBlocked: 0,
  deviceTab: "android", adminOverview: null, adminSources: [], adminRuns: [], adminAudit: []
};
const $ = (id) => document.getElementById(id);
const setText = (id, value) => { const el = $(id); if (el) el.textContent = value; };
const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

const BRAND_ICON_MAP = {
  android:["android","3DDC84"], apple:["apple","FFFFFF"],
  chrome:["googlechrome","4285F4"], webview:["googlechrome","4285F4"],
  safari:["safari","0A84FF"], firefox:["firefoxbrowser","FF7139"],
  edge:["microsoftedge","0A84FF"], opera:["opera","FF1B2D"],
  facebook:["facebook","0866FF"], fb_lite:["facebook","0866FF"],
  instagram:["instagram","E4405F"], tiktok:["tiktok","FFFFFF"], x:["x","FFFFFF"],
  threads:["threads","FFFFFF"], linkedin:["linkedin","0A66C2"], pinterest:["pinterest","E60023"],
  reddit:["reddit","FF4500"], snapchat:["snapchat","FFFC00"], weibo:["sinaweibo","E6162D"],
  telegram:["telegram","26A5E4"], messenger:["messenger","0866FF"],
  wechat:["wechat","07C160"], line:["line","06C755"], kik:["kik","82BC23"],
  discord:["discord","5865F2"], viber:["viber","7360F2"],
  google:["google","4285F4"], xiaomi:["xiaomi","FF6900"],
  motorola:["motorola","E60000"], oneplus:["oneplus","F5010C"], oppo:["oppo","00A862"],
  vivo:["vivo","415FFF"], 
  honor:["honor","FFFFFF"], huawei:["huawei","FF0000"], sony:["sony","FFFFFF"], asus:["asus","00539B"]
};
const BRAND_ALIASES = {
  "google pixel":"google", "samsung":"samsung", "apple":"apple", "xiaomi":"xiaomi",
  "redmi":"xiaomi", "poco":"xiaomi", "motorola":"motorola", "oneplus":"oneplus",
  "oppo":"oppo", "vivo":"vivo", "iqoo":"vivo", "realme":"realme", "nothing":"nothing",
  "honor":"honor", "huawei":"huawei", "sony":"sony", "asus":"asus", "hmd":"google"
};
function brandKey(value) {
  const key = String(value || "").toLowerCase();
  return BRAND_ALIASES[key] || key.replace(/[^a-z0-9_]/g, "");
}
const LOCAL_ICONS = { microsoftedge: "./icons/microsoftedge.svg", linkedin: "./icons/linkedin.svg" };
function brandLogo(name, label, extraClass = "") {
  const key = String(name || "").toLowerCase();
  const brand = BRAND_ICON_MAP[key] || BRAND_ICON_MAP[brandKey(label)] || null;
  const fallback = String(label || name || "N").trim().slice(0,1).toUpperCase();
  if (!brand) return `<span class="brand-logo ${extraClass}"><span>${esc(fallback)}</span></span>`;
  const src = LOCAL_ICONS[brand[0]] || `https://cdn.simpleicons.org/${brand[0]}/${brand[1]}`;
  return `<span class="brand-logo ${extraClass}"><img src="${src}" alt="" loading="lazy" decoding="async" onerror="this.hidden=true;this.nextElementSibling.hidden=false"><span hidden>${esc(fallback)}</span></span>`;
}
const UA_CANDIDATE_FORMATS = {
  facebook: {
    android: "Mozilla/5.0 (Linux; Android {osVersion}; {model} Build/{buildId}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/{chromeVersion} Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/{appVersion};IABMV/1;]",
    ios: "Mozilla/5.0 (iPhone; CPU iPhone OS {iosVersion} like Mac OS X) AppleWebKit/{webkitVersion} (KHTML, like Gecko) Mobile/{iosBuild} Safari/604.1 [FBAN/FBIOS;FBAV/{appVersion};FBBV/{appBuild};FBDV/{deviceModel};FBMD/iPhone;FBSN/iOS;FBSV/{iosVersion};FBSS/{scale};FBID/phone;FBLC/{locale};FBOP/5;FBRV/{runtimeVersion};IABMV/1]"
  },
  instagram: {
    androidSamsung: "Mozilla/5.0 (Linux; {androidFull}; {model} Build/{buildId}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/{chromeVersion} Mobile Safari/537.36 Instagram {appVersion} Android ({sdkVersion}/{osVersion}; {density}; {resolution}; {brand}; {model}; {codename}; {chipset}; {locale}; {appBuildId}; IABMV/1)",
    androidMotorola: "Mozilla/5.0 (Linux; Android {osVersion}; {model} Build/{buildId}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/{chromeVersion} Mobile Safari/537.36 Instagram {appVersion} Android ({sdkVersion}/{osVersion}; {density}; {resolution}; motorola; {model}; {chipset}; {locale}; {appBuildId}; IABMV/1)",
    ios: "Mozilla/5.0 (iPhone; CPU iPhone OS {iosVersion} like Mac OS X) AppleWebKit/{webkitVersion} (KHTML, like Gecko) Mobile/{iosBuild} Instagram {appVersion} ({deviceModel}; iOS {iosVersion}; {locale}; scale={scale}; {resolution}; IABMV/1 {appBuildId})"
  }
};

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
  $("catalogStatusDot")?.classList.toggle("good", Boolean(state.data));
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
function hasCandidate(appId, platform) {
  if (platform !== "android") return false;
  const formats = UA_CANDIDATE_FORMATS[appId];
  return Boolean(formats && (formats.android || formats.androidSamsung || formats.androidMotorola));
}
function candidateFormatFor(appId, platform, device) {
  if (!hasCandidate(appId, platform)) return null;
  const formats = UA_CANDIDATE_FORMATS[appId];
  if (appId === "instagram") {
    const brand = String(device?.brand || "").toLowerCase();
    if (brand.includes("motorola")) return formats.androidMotorola || formats.androidSamsung || null;
    return formats.androidSamsung || formats.android || null;
  }
  return formats.android || null;
}
function uaSlotContext(device, osVersion, appId) {
  const app = apps()[appId] || {};
  const appVersion = typeof app === "string" ? app : app.version || "";
  const appBuild = typeof app === "object" ? app.build || "" : "";
  const display = device.display || {};
  const loc = localeMeta();
  return {
    osVersion, model: device.uaModel || device.id,
    buildId: device.builds?.[osVersion] || device.buildsRecent?.find(x => String(x.version) === String(osVersion))?.build || "",
    chromeVersion: versions().chrome || "",
    appVersion, appBuild, appBuildId: appBuild,
    density: display.dpr ? `${Math.round(display.dpr * 160)}dpi` : "",
    resolution: display.w && display.h ? `${display.w}x${display.h}` : "",
    locale: (loc.language || "en-US").replace("-", "_"),
    brand: device.brand || "", width: display.w || "", height: display.h || "",
    codename: device.codename || "", chipset: device.soc || "",
    androidFull: `Android ${osVersion}`,
    sdkVersion: ({ "10":29,"11":30,"12":31,"13":33,"14":34,"15":35,"16":36,"17":37 })[String(osVersion).split(".")[0]] || ""
  };
}
function fillUaTemplate(template, context, appId, platform) {
  let ua = template || "";
  const placeholders = [...ua.matchAll(/\{([A-Za-z0-9_]+)\}/g)].map(m => m[1]);
  for (const key of placeholders) {
    if (!(key in context) || context[key] === "") throw new Error(`Template ${appId}/${platform} has unresolved slot: ${key}`);
    ua = ua.replaceAll(`{${key}}`, String(context[key]));
  }
  if (/\{[^}]+\}/.test(ua)) throw new Error(`Template ${appId}/${platform} contains unresolved placeholders.`);
  return ua;
}
function buildCandidateAppUa(appId, platform, device, osVersion) {
  const template = candidateFormatFor(appId, platform, device);
  if (!template) throw new Error(`${UA_APP_NAMES[appId] || appId} has no usable script-candidate format for ${platform}.`);
  const context = uaSlotContext(device, osVersion, appId);
  const ua = fillUaTemplate(template, context, appId, platform);
  return { ua, appVersion: context.appVersion, appBuild: context.appBuild, templateId: `${appId}-${platform}-script-candidate`, evidence: [] };
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
  const browserVersionLists = (meta().browserVersions) || {};
  const datalistFor = { uaChromeVersion:"chromeAndroid", uaSafariVersion:"safariIos", uaChromeIosVersion:"chromeIos", uaFirefoxAndroidVersion:"firefoxAndroid", uaFirefoxIosVersion:"firefoxIos", uaEdgeAndroidVersion:"edgeAndroid", uaEdgeIosVersion:"edgeIos", uaOperaVersion:"operaAndroid" };
  for (const [inputId, key] of Object.entries(datalistFor)) {
    const input = $(inputId), list = browserVersionLists[key] || [];
    if (!input || !list.length) continue;
    let dl = document.getElementById(`${inputId}List`);
    if (!dl) { dl = document.createElement("datalist"); dl.id = `${inputId}List`; input.after(dl); }
    input.setAttribute("list", dl.id);
    dl.innerHTML = list.map(v => `<option value="${esc(v)}"></option>`).join("");
    if (!input.value) input.value = list[0];
  }
  if ($("uaChromiumEngineVersion") && !$("uaChromiumEngineVersion").value) $("uaChromiumEngineVersion").value = chromeVersion;
  $("chromeCatalogVersion").textContent = chromeVersion ? `Catalog · ${chromeVersion}` : "Version not in catalog";
  $("uaCatalogVersion").textContent = `DATA ${meta().dataVersion || "LOCAL"}`;
  $("uaDeviceMeta").textContent = `${platformDevices("android").length} Android · ${platformDevices("ios").length} iOS records`;
  setText("uaDataVersion", meta().dataVersion || "Unversioned");
  setText("uaDataDevices", allRecords().length);
  setText("uaDataApps", Object.keys(apps()).length);
  setText("uaDataSource", state.apiOnline ? "Live API" : "Bundled fallback");
  const verified = templateRecords().filter(x => x.status === "verified").length;
  setText("uaDataTemplates", verified);
  renderUaDevicePicker();
  renderUaApps();
  updateUaTotals();
  renderSavedBatches();
}
function renderUaDevicePicker() {
  const mode = document.querySelector('input[name="uaDistribution"]:checked')?.value || "random";
  $("uaDevicePicker").classList.toggle("hidden", mode === "random");
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
      const checkId = `ua-device-${platform}-${d.id}`;
      const custom = mode === "custom" && checked
        ? `<input class="device-quantity" type="number" min="0" max="500" value="${esc(state.customQuantities[d.id] ?? 1)}" data-custom-qty="${esc(d.id)}" aria-label="Quantity for ${esc(d.model)}">`
        : "";
      return `<div class="device-option">
        <input id="${esc(checkId)}" type="checkbox" data-device-id="${esc(d.id)}" data-device-platform="${platform}" ${checked ? "checked" : ""}>
        <label for="${esc(checkId)}" class="device-option-label">
          ${brandLogo(brandKey(d.brand), d.brand, "device-logo")}
          <span class="device-option-copy"><b>${esc(d.brand)} ${esc(d.model)}</b><small>${esc(d.uaModel || d.id)} · ${(d.osVersions || []).length} OS versions</small></span>
        </label>${custom}
      </div>`;
    }).join("") || '<div class="device-list-empty">No matching devices.</div>';
  };
  renderList("android"); renderList("ios");
  $("uaAndroidDeviceList").classList.toggle("hidden", state.deviceTab !== "android");
  $("uaIosDeviceList").classList.toggle("hidden", state.deviceTab !== "ios");
  document.querySelectorAll("[data-device-tab]").forEach(button => button.classList.toggle("active", button.dataset.deviceTab === state.deviceTab));
  $("uaAndroidDeviceCount").textContent = platformDevices("android").length;
  $("uaIosDeviceCount").textContent = platformDevices("ios").length;
  const totalSelected = state.selectedDevices.android.length + state.selectedDevices.ios.length;
  $("uaSelectedCount").textContent = `${totalSelected} selected`;
  $("uaDeviceMeta").textContent = `${platformDevices("android").length} Android · ${platformDevices("ios").length} iOS`;
  $("uaDistributionHint").textContent = mode === "custom"
    ? "Set quantities beside selected models. Their sum must equal that platform's batch quantity."
    : mode === "balanced"
      ? "Selected models receive an even distribution. Any remainder starts from the first selected model."
      : "Only selected models are used. Standard iPhone User-Agent strings do not expose the exact iPhone model.";
}

function renderUaApps() {
  const records = Object.entries(apps()).sort((a,b) => (UA_APP_NAMES[a[0]] || a[0]).localeCompare(UA_APP_NAMES[b[0]] || b[0]));
  const verified = templateRecords().filter(x => x.status === "verified");
  const candidateCount = Object.keys(UA_CANDIDATE_FORMATS).length;
  $("uaTemplateSummary").textContent = `${verified.length} verified · ${candidateCount} script-candidate apps · ${records.length} apps`;
  const q = ($("uaAppSearch")?.value || "").trim().toLowerCase();
  $("uaAppGrid").innerHTML = records.map(([id, raw]) => {
    const label = UA_APP_NAMES[id] || id;
    const version = typeof raw === "string" ? raw : raw?.version || "Version unavailable";
    const build = typeof raw === "object" ? raw?.build : "";
    const readyRows = verifiedTemplates(id, "android").concat(verifiedTemplates(id, "ios"));
    const ready = readyRows.length > 0;
    const candidate = UA_CANDIDATE_FORMATS[id] || null;
    const checked = state.selectedApps.includes(id);
    const status = ready ? "READY" : candidate ? "SCRIPT CANDIDATE" : "TEMPLATE PENDING";
    if (q && !(label + " " + id).toLowerCase().includes(q)) return "";
    const previews = candidate ? Object.entries(candidate).map(([platform, pattern]) =>
      `<div class="candidate-format"><strong>${platform === "androidSamsung" ? "Android · Samsung script" : platform === "androidMotorola" ? "Android · Motorola script" : platform === "android" ? "Android" : "iOS"}</strong><pre>${esc(pattern)}</pre></div>`
    ).join("") : "";
    return `<article class="app-template-option ${ready ? "ready" : "pending"}">
      <label class="app-template-select">
        <input type="checkbox" data-app-template="${esc(id)}" ${checked ? "checked" : ""} ${ready || candidate ? "" : "disabled"}>
        ${brandLogo(id, label, "app-logo")}
        <span class="app-template-copy"><b>${esc(label)}</b><small>${esc(version)}${build ? ` · build ${esc(build)}` : ""}</small></span>
        <span class="app-template-status ${ready ? "ready" : candidate ? "candidate" : "pending"}">${status}</span>
      </label>
      ${candidate ? `<details class="app-format-preview"><summary>View supplied format</summary><div class="candidate-format-list">${previews}</div><p>Candidate format from the supplied scripts. Selectable — generated results are labelled as unverified script candidates until the collection pipeline verifies them.</p></details>` : ""}
    </article>`;
  }).join("") || '<div class="device-list-empty">No matching applications.</div>';
}



function wireLogoFallbacks() {
  document.querySelectorAll('img[src*="cdn.simpleicons.org"]:not([onerror])').forEach(img => {
    const showFallback = () => {
      if (img.dataset.fallbackDone) return;
      img.dataset.fallbackDone = "1";
      img.hidden = true;
      const fallback = document.createElement("span");
      fallback.className = "logo-fallback";
      fallback.textContent = (img.alt || "•").trim().slice(0,1).toUpperCase();
      img.parentElement?.appendChild(fallback);
    };
    img.addEventListener("error", showFallback, { once: true });
    if (img.complete && img.naturalWidth === 0) showFallback();
  });
}

function updatePlatformCards() {
  document.querySelectorAll("[data-platform-mode]").forEach(button => {
    button.classList.toggle("active", button.dataset.platformMode === state.platformMode);
    button.setAttribute("aria-pressed", button.dataset.platformMode === state.platformMode ? "true" : "false");
  });
  document.querySelectorAll("[data-platform-card]").forEach(card => {
    const show = state.platformMode === "mixed" || state.platformMode === card.dataset.platformCard;
    card.classList.toggle("hidden", !show);
  });
}
function setPlatformMode(mode) {
  const old = state.platformMode;
  let android = Number($("uaAndroidQty").value) || 0;
  let ios = Number($("uaIosQty").value) || 0;
  if (mode === "android") { if (!android) android = 10; ios = 0; }
  else if (mode === "ios") { if (!ios) ios = 10; android = 0; }
  else {
    if (old === "android" && android && !ios) ios = android;
    else if (old === "ios" && ios && !android) android = ios;
    else if (!android && !ios) { android = 5; ios = 5; }
  }
  state.platformMode = mode;
  $("uaAndroidQty").value = android; $("uaIosQty").value = ios;
  updateUaTotals(); playUiSound("tap");
}
function applyBatchPreset(total) {
  if (state.platformMode === "android") { $("uaAndroidQty").value = total; $("uaIosQty").value = 0; }
  else if (state.platformMode === "ios") { $("uaAndroidQty").value = 0; $("uaIosQty").value = total; }
  else {
    $("uaAndroidQty").value = Math.ceil(total / 2);
    $("uaIosQty").value = Math.floor(total / 2);
  }
  document.querySelectorAll("[data-batch-preset]").forEach(button => button.classList.toggle("active", Number(button.dataset.batchPreset) === total));
  updateUaTotals(); playUiSound("tap");
}
function syncPlatformModeFromQuantities() {
  const android = Number($("uaAndroidQty").value) || 0;
  const ios = Number($("uaIosQty").value) || 0;
  if (android > 0 && ios > 0) state.platformMode = "mixed";
  else if (android > 0) state.platformMode = "android";
  else if (ios > 0) state.platformMode = "ios";
  updatePlatformCards();
}

function updateUaTotals() {
  const android = Math.max(0, Math.min(500, Number($("uaAndroidQty").value) || 0));
  const ios = Math.max(0, Math.min(500, Number($("uaIosQty").value) || 0));
  $("uaAndroidQty").value = android; $("uaIosQty").value = ios;
  $("uaAndroidRange").value = android; $("uaIosRange").value = ios;
  const total = android + ios;
  $("uaTotal").textContent = total;
  $("uaReadySummary").textContent = `${android} Android · ${ios} iOS`;
  if ($("uaBatchSummary")) $("uaBatchSummary").textContent = `${android} Android · ${ios} iOS`;
  $("generateButtonCount").textContent = total;
  $("generateButton").disabled = total < 1 || total > 500;
  $("batchLimitNote").textContent = total > 500 ? "Batch limit is 500. Reduce one platform quantity." : `${500 - total} slots remaining in this batch.`;
  document.querySelectorAll("[data-batch-preset]").forEach(button => button.classList.toggle("active", Number(button.dataset.batchPreset) === total));
  updatePlatformCards();
}

function localeMeta() {
  const code = $("uaCountry").value;
  const entry = UA_COUNTRIES.find(x => x[0] === code);
  const automaticLanguage = entry?.[2] && entry[2] !== "auto" ? entry[2] : "en-US";
  const language = $("uaLanguage").value === "auto" ? automaticLanguage : $("uaLanguage").value;
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
const BROWSER_LABELS = { chrome:"Chrome", safari:"Safari", firefox:"Firefox", edge:"Edge", opera:"Opera", webview:"Android WebView" };
function browserUsable(platform, browser) {
  if (browserVersion(platform, browser)) return true;
  if (platform === "android" && (browser === "chrome" || browser === "webview")) return Boolean(versions().chrome);
  return false;
}
function browserVersion(platform, browser) {
  if (browser !== "webview" && $("uaVersionMode")?.value === "random3") {
    const key = { android: { chrome: "chromeAndroid", firefox: "firefoxAndroid", edge: "edgeAndroid", opera: "operaAndroid" }, ios: { safari: "safariIos", chrome: "chromeIos", firefox: "firefoxIos", edge: "edgeIos" } }[platform]?.[browser];
    const list = key ? (meta().browserVersions || {})[key] || [] : [];
    if (list.length) return list[Math.floor(Math.random() * Math.min(3, list.length))];
  }
  const ids = {
    android: { chrome:"uaChromeVersion", firefox:"uaFirefoxAndroidVersion", edge:"uaEdgeAndroidVersion", opera:"uaOperaVersion", webview:"uaChromiumEngineVersion" },
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
    if (browser === "webview") {
      if (!engineVersion) throw new Error("Chromium engine version is missing from the database.");
      return `Mozilla/5.0 (Linux; Android ${osVersion}; ${model}${build ? ` Build/${build}` : ""}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${engineVersion} Mobile Safari/537.36`;
    }
    if (!version) throw new Error(`${browser[0].toUpperCase()+browser.slice(1)} Android version is required. Open Advanced options.`);
    if (browser === "firefox") return `Mozilla/5.0 (Android ${osVersion}; Mobile; rv:${version}) Gecko/${version} Firefox/${version}`;
    if (!engineVersion) throw new Error("Chromium engine version is required for Edge and Opera.");
    const base = `Mozilla/5.0 (Linux; Android ${osVersion}; ${model}${build ? ` Build/${build}` : ""}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${engineVersion} Mobile Safari/537.36`;
    if (browser === "edge") return `${base} EdgA/${version}`;
    if (browser === "opera") return `${base} OPR/${version}`;
  }
  const ios = String(osVersion).replace(/\./g, "_");
  const mobileBuild = build || "15E148";
  const head = `Mozilla/5.0 (iPhone; CPU iPhone OS ${ios} like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) `;
  if (!version) throw new Error(`${browser[0].toUpperCase()+browser.slice(1)} iOS version is required. Open Advanced options.`);
  if (browser === "safari") return `${head}Version/${version} Mobile/${mobileBuild} Safari/604.1`;
  if (browser === "chrome") return `${head}CriOS/${version} Mobile/${mobileBuild} Safari/604.1`;
  if (browser === "firefox") return `${head}FxiOS/${version} Mobile/${mobileBuild} Safari/605.1.15`;
  if (browser === "edge") return `${head}EdgiOS/${version} Mobile/${mobileBuild} Safari/605.1.15`;
  throw new Error(`Unsupported ${platform} browser: ${browser}`);
}

function buildVerifiedAppUa(appId, platform, device, osVersion) {
  const row = verifiedTemplates(appId, platform)[0];
  if (!row) throw new Error(`${UA_APP_NAMES[appId] || appId} has no verified ${platform} template.`);
  const context = uaSlotContext(device, osVersion, appId);
  const ua = fillUaTemplate(row.template || "", context, appId, platform);
  return { ua, appVersion: context.appVersion, appBuild: context.appBuild, templateId: row.id || `${appId}-${platform}`, evidence: row.evidence || [] };
}
function chooseOsVersion(device, platform) {
  const select = $(platform === "android" ? "uaAndroidOs" : "uaIosOs");
  const value = select.value;
  const supported = device.osVersions || [];
  if (!supported.length) throw new Error(`${device.model} has no supported OS versions in the catalog.`);
  if (value === "latest") return numericVersionSort(supported).at(-1);
  if (value === "random") return supported[Math.floor(Math.random() * supported.length)];
  if (!supported.includes(value)) throw new Error(`${device.model} does not support selected OS ${value}.`);
  return value;
}

function deviceSchedule(platform, quantity, mode) {
  const all = platformDevices(platform);
  const chosenIds = state.selectedDevices[platform] || [];
  let pool = mode === "random" ? all : all.filter(d => chosenIds.includes(d.id));
  const osSelect = $(platform === "android" ? "uaAndroidOs" : "uaIosOs");
  const requestedOs = osSelect?.value || "latest";
  if (requestedOs !== "latest" && requestedOs !== "random") {
    if (mode === "custom") {
      const invalid = pool.filter(d => Number(state.customQuantities[d.id] || 0) > 0 && !(d.osVersions || []).includes(requestedOs));
      if (invalid.length) throw new Error(`${invalid.map(d => d.model).join(", ")} do not support OS ${requestedOs}.`);
    } else {
      pool = pool.filter(d => (d.osVersions || []).includes(requestedOs));
    }
  }
  if (!pool.length && quantity) throw new Error(`No eligible ${platform} devices match this selection and OS version.`);
  if (mode === "custom") {
    const custom = [];
    for (const d of pool) {
      const count = Math.max(0, Number(state.customQuantities[d.id]) || 0);
      for (let i=0;i<count;i++) custom.push(d);
    }
    if (custom.length !== quantity) throw new Error(`${platform.toUpperCase()} custom quantities total ${custom.length}; expected ${quantity}.`);
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
  const warnings = [];
  let duplicatesBlocked = 0;
  for (const platform of ["android","ios"]) {
    if (!qty[platform]) continue;
    const selectedBrowsers = getSelectedBrowsers(platform);
    const browsers = selectedBrowsers.filter(b => browserUsable(platform, b));
    const skippedBrowsers = selectedBrowsers.filter(b => !browserUsable(platform, b));
    const selectedAppIds = $("uaIncludeApps").checked
      ? state.selectedApps.filter(id => verifiedTemplates(id, platform).length || hasCandidate(id, platform))
      : [];
    if (!browsers.length && !selectedAppIds.length) {
      if (skippedBrowsers.length) warnings.push(...skippedBrowsers.map(b => `${BROWSER_LABELS[b] || b} ${platform === "ios" ? "iOS" : "Android"} version is required. Open Advanced options.`));
      else warnings.push(`Choose a compatible ${platform.toUpperCase()} browser or an in-app app.`);
      continue;
    }
    if (skippedBrowsers.length) warnings.push(...skippedBrowsers.map(b => `${BROWSER_LABELS[b] || b} skipped — no version set in Advanced options.`));
    const schedule = deviceSchedule(platform, qty[platform], mode);
    for (let i=0;i<schedule.length;i++) {
      const device = schedule[i];
      const osVersion = chooseOsVersion(device, platform);
      const useApp = selectedAppIds.length && (browsers.length === 0 || (i % (browsers.length + selectedAppIds.length)) >= browsers.length);
      let ua, browser, appId = null, appVersion = null, appBuild = null, templateId = null, templateEvidence = [], appSource = null;
      if (useApp) {
        appId = selectedAppIds[i % selectedAppIds.length];
        const isVerified = verifiedTemplates(appId, platform).length > 0;
        appSource = isVerified ? "verified-app-template" : "script-candidate-app";
        const built = isVerified ? buildVerifiedAppUa(appId, platform, device, osVersion) : buildCandidateAppUa(appId, platform, device, osVersion);
        ua = built.ua; appVersion = built.appVersion; appBuild = built.appBuild; templateId = built.templateId; templateEvidence = built.evidence; browser = "In-app";
      } else {
        browser = browsers[i % browsers.length];
        ua = buildBrowserUa(platform, device, osVersion, browser);
      }
      if (dedupe && seen.has(ua)) { duplicatesBlocked++; continue; }
      seen.add(ua);
      const buildId = device.builds?.[osVersion] || device.buildsRecent?.find(x => String(x.version) === String(osVersion))?.build || null;
      const browserVersionValue = appId ? null : browserVersion(platform, browser) || (platform === "android" && browser === "chrome" ? versions().chrome : browser === "webview" ? $("uaChromiumEngineVersion").value.trim() || versions().chrome : null);
      const versionInputId = browser === "webview" ? "uaChromiumEngineVersion" : "uaChromeVersion";
      output.push({
        id: `nexora-ua-${String(output.length+1).padStart(4,"0")}`,
        platform: platform === "android" ? "Android" : "iOS",
        deviceId: device.id, brand: device.brand, deviceModel: device.model,
        modelIdentifier: device.uaModel || null, osVersion, buildId,
        browser, browserVersion: browserVersionValue,
        browserVersionSource: appId ? (appSource === "script-candidate-app" ? "script-candidate" : "verified-template") : (platform === "android" && ["chrome","webview"].includes(browser) && !$(versionInputId).dataset.manual ? "database" : "manual"),
        appId, appVersion, appBuild, templateId, templateEvidence, userAgent: ua,
        country: locale.country, language: locale.language, acceptLanguage: locale.acceptLanguage,
        catalogDataVersion: meta().dataVersion || null,
        sourceType: appId ? appSource : "browser-template",
        note: appSource === "script-candidate-app" ? "Script-candidate in-app UA — format supplied by the owner's scripts, not yet verified by the collection pipeline." : platform === "iOS" ? "iOS device identity is retained as metadata; standard iPhone UAs do not expose the exact model." : "Generated compatibility UA; this does not alter the real runtime device."
      });
    }
  }
  state.duplicatesBlocked = duplicatesBlocked;
  state.uaWarnings = warnings;
  if (!output.length && warnings.length) throw new Error(warnings.join(" "));
  return output;
}

function renderUaResultsView() {
  const rows = state.dataset || [];
  $("uaResultTotal").textContent = rows.length;
  $("uaResultAndroid").textContent = rows.filter(x => x.platform === "Android").length;
  $("uaResultIos").textContent = rows.filter(x => x.platform === "iOS").length;
  $("uaResultSubtitle").textContent = rows.length
    ? `${rows.length} generated · ${meta().dataVersion || "local catalog"}${state.duplicatesBlocked ? ` · ${state.duplicatesBlocked} duplicates skipped` : ""}`
    : "Your batch will appear here.";
  if (!rows.length) {
    $("generatorPreview").classList.remove("hidden");
    $("uaResultsList").classList.add("hidden");
    $("exportActions").classList.add("hidden");
    return;
  }
  $("generatorPreview").classList.add("hidden");
  $("uaResultsList").classList.remove("hidden");
  const filtered = rows.map((record,index)=>({record,index})).filter(({record}) => {
    if (state.resultFilter === "android") return record.platform === "Android";
    if (state.resultFilter === "ios") return record.platform === "iOS";
    if (state.resultFilter === "apps") return Boolean(record.appId);
    return true;
  });
  const visible = state.showAllResults ? filtered : filtered.slice(0,30);
  $("uaResultsList").innerHTML = visible.map(({record:r,index:i}) => {
    const title = r.appId ? (UA_APP_NAMES[r.appId] || r.appId) : ({chrome:"Chrome",safari:"Safari",firefox:"Firefox",edge:"Edge",opera:"Opera",webview:"Android WebView"}[r.browser] || r.browser);
    const iconKey = r.appId || r.browser;
    const platformLogo = r.platform === "Android" ? brandLogo("android","Android","result-platform-logo") : brandLogo("apple","Apple","result-platform-logo");
    const versionLine = r.appId
      ? `App ${esc(r.appVersion || "—")}${r.appBuild ? ` · build ${esc(r.appBuild)}` : ""}`
      : `${esc(r.browserVersion || "Version not recorded")}`;
    const sourceLabel = r.sourceType === "script-candidate-app" ? "Script candidate · unverified" : r.sourceType === "verified-app-template" ? "Verified template" : "Browser template";
    return `<article class="ua-result-item">
      <div class="ua-result-head">
        ${brandLogo(brandKey(r.brand), r.brand || title, "result-brand-logo")}
        <div class="ua-result-title"><strong>${esc(r.brand || "")} ${esc(r.deviceModel)}</strong><small>${platformLogo}<span>${esc(r.platform)} · OS ${esc(r.osVersion)}${r.buildId ? ` · Build ${esc(r.buildId)}` : ""}</span></small></div>
        <button class="copy-one" type="button" data-copy-ua="${i}" aria-label="Copy User-Agent ${i+1}">Copy</button>
      </div>
      <div class="ua-result-meta">${brandLogo(iconKey, title, "result-sub-logo")}<span><b>${esc(title)}</b> · ${esc(versionLine)}</span></div>
      <pre class="ua-result-string">${esc(r.userAgent)}</pre>
      <div class="ua-result-foot"><span>${esc(r.modelIdentifier || "")}</span><span>${esc(sourceLabel)}</span></div>
    </article>`;
  }).join("") || '<div class="device-list-empty">No results match this filter.</div>';
  if (filtered.length > visible.length) {
    $("uaResultsList").insertAdjacentHTML("beforeend", `<button class="show-more-results" type="button" data-show-all-results>Show all ${filtered.length} results</button>`);
  } else if (state.showAllResults && filtered.length > 30) {
    $("uaResultsList").insertAdjacentHTML("beforeend", `<button class="show-more-results" type="button" data-show-all-results>Show first 30</button>`);
  }
  document.querySelectorAll("[data-result-filter]").forEach(button => button.classList.toggle("active", button.dataset.resultFilter === state.resultFilter));
  $("exportActions").classList.remove("hidden");
  setText("uaDataVersion", meta().dataVersion || "Unversioned");
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
  if (!$("uaSound")?.checked) return;
  const AudioCtx = window.AudioContext || window.webkitAudioContext;
  if (!AudioCtx) return;
  try {
    const ctx = new AudioCtx();
    const osc = ctx.createOscillator(), gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.value = kind === "success" ? 740 : kind === "error" ? 240 : 480;
    gain.gain.setValueAtTime(0.0001, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.025, ctx.currentTime + 0.012);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.14);
    osc.connect(gain); gain.connect(ctx.destination);
    ctx.resume().catch(()=>{});
    osc.start(); osc.stop(ctx.currentTime + 0.15);
    osc.onended = () => ctx.close().catch(()=>{});
  } catch {}
}

function renderGeneratorFilters() { setupUaGenerator(); }

function makeDataset() { return generateUaBatch(); }

function renderDatasetPreview() { renderUaResultsView(); }

function downloadText(name, content, type = "text/plain;charset=utf-8") {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url; link.download = name; link.style.display = "none";
  document.body.appendChild(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1200);
}

function csvValue(value) { const text = typeof value === "object" ? JSON.stringify(value) : String(value ?? ""); return `"${text.replace(/"/g, '""')}"`; }
function exportDataset() { if (!state.dataset.length) return; const format = $("uaExportFormat").value; if (format === "json") downloadText("nexora-user-agents.json", JSON.stringify({meta:{dataVersion:meta().dataVersion,generatedAt:new Date().toISOString(),count:state.dataset.length},records:state.dataset},null,2), "application/json"); else if (format === "csv") { const keys=Object.keys(state.dataset[0]); const csv=[keys.map(csvValue).join(","),...state.dataset.map(row=>keys.map(key=>csvValue(row[key])).join(","))].join("\n"); downloadText("nexora-user-agents.csv",csv,"text/csv;charset=utf-8"); } else downloadText("nexora-user-agents.txt",state.dataset.map(row=>row.userAgent).join("\n"),"text/plain;charset=utf-8"); }

function showToast(message, type = "") {
  const toast = document.createElement("div"); toast.className = `toast ${type}`; toast.textContent = message;
  $("toastRegion").appendChild(toast); setTimeout(() => toast.remove(), 3800);
}
function go(view) {
  const adminViews = ["devices","applications","overview","compatibility","automation","review","assistant","audit","settings","about"];
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
  if (["devices","applications","overview","compatibility","automation","review","assistant","audit","settings","about"].includes(currentView())) go("generator");
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
  $("mobileMenu")?.addEventListener("click", () => $("sidebar")?.classList.toggle("open"));
  $("authButton")?.addEventListener("click", () => state.isAdmin ? (clearAdmin(), showToast("Signed out.")) : openModal("loginModal"));
  $("loginForm")?.addEventListener("submit", async event => {
    event.preventDefault(); $("loginError").classList.add("hidden");
    try {
      const result = await api("/api/v1/admin/login", { method: "POST", body: JSON.stringify({ password: $("adminPassword").value }) });
      setAdmin(result.accessToken); closeModal("loginModal"); $("adminPassword").value = "";
      go("overview"); showToast("Administrator session started.");
    } catch (error) { $("loginError").textContent = error.message; $("loginError").classList.remove("hidden"); }
  });
  $("deviceSearch")?.addEventListener("input", renderDevices);
  $("devicePlatform")?.addEventListener("change", renderDevices);
  $("deviceBrand")?.addEventListener("change", renderDevices);
  $("addDevice")?.addEventListener("click", () => openDeviceEditor());
  $("deviceForm")?.addEventListener("submit", saveDevice);
  $("exportDevices")?.addEventListener("click", () => downloadText("nexora-device-catalog.json", JSON.stringify(state.data, null, 2), "application/json"));
  $("appFilters")?.querySelectorAll("[data-app-filter]").forEach(button => button.addEventListener("click", () => { state.appFilter = button.dataset.appFilter; $("appFilters").querySelectorAll(".filter-pill").forEach(b => b.classList.toggle("active", b === button)); renderApplications(); }));
  $("editAppVersions")?.addEventListener("click", () => openAppEditor());
  $("appVersionForm")?.addEventListener("submit", saveAppVersion);

  const uaRoot = $("uaGenerator");
  if (uaRoot) {
    uaRoot.addEventListener("input", event => {
      const t = event.target;
      if (t.id === "uaAndroidQty" || t.id === "uaIosQty") { syncPlatformModeFromQuantities(); updateUaTotals(); }
      if (t.id === "uaAndroidRange") { $("uaAndroidQty").value = t.value; syncPlatformModeFromQuantities(); updateUaTotals(); }
      if (t.id === "uaIosRange") { $("uaIosQty").value = t.value; syncPlatformModeFromQuantities(); updateUaTotals(); }
      if (t.id === "uaDeviceSearch") renderUaDevicePicker();
      if (t.id === "uaAppSearch") renderUaApps();
      if (t.id === "uaChromeVersion" || t.id === "uaChromiumEngineVersion") t.dataset.manual = "1";
      if (t.hasAttribute("data-custom-qty")) state.customQuantities[t.dataset.customQty] = Math.max(0, Math.min(500, Number(t.value) || 0));
    });
    uaRoot.addEventListener("change", event => {
      const t = event.target;
      if (t.name === "uaDistribution") {
        if (t.value === "custom") {
          for (const platform of ["android","ios"]) for (const id of state.selectedDevices[platform]) {
            if (!state.customQuantities[id]) state.customQuantities[id] = 1;
          }
        }
        renderUaDevicePicker();
      }
      if (t.hasAttribute("data-device-id")) {
        const platform = t.dataset.devicePlatform, id = t.dataset.deviceId;
        const list = new Set(state.selectedDevices[platform] || []);
        if (t.checked) { list.add(id); if (!state.customQuantities[id]) state.customQuantities[id] = 1; }
        else list.delete(id);
        state.selectedDevices[platform] = [...list];
        renderUaDevicePicker();
      }
      if (t.hasAttribute("data-app-template")) {
        const id = t.dataset.appTemplate, list = new Set(state.selectedApps);
        if (t.checked) list.add(id); else list.delete(id);
        state.selectedApps = [...list]; renderUaApps();
      }
      if (t.id === "uaIncludeApps") $("uaInAppPanel").classList.toggle("hidden", !t.checked);
      if (t.id === "uaCountry") { $("uaLanguage").value = "auto"; renderGeneratorFilters(); }
      if (t.id === "uaSound") playUiSound("tap");
    });
    uaRoot.addEventListener("click", event => {
      const platformButton = event.target.closest("[data-platform-mode]");
      if (platformButton) { setPlatformMode(platformButton.dataset.platformMode); return; }
      const preset = event.target.closest("[data-batch-preset]");
      if (preset) { applyBatchPreset(Number(preset.dataset.batchPreset)); return; }
      const qtyButton = event.target.closest("[data-qty-step]");
      if (qtyButton) {
        const input = $(qtyButton.dataset.qtyStep === "android" ? "uaAndroidQty" : "uaIosQty");
        input.value = Math.max(0, Math.min(500, Number(input.value || 0) + Number(qtyButton.dataset.step)));
        syncPlatformModeFromQuantities(); updateUaTotals(); playUiSound("tap"); return;
      }
      const deviceAction = event.target.closest("[data-device-action]");
      if (deviceAction) {
        const platform = state.deviceTab, query = ($("uaDeviceSearch").value || "").trim().toLowerCase();
        const visible = platformDevices(platform).filter(d => !query || [d.brand,d.model,d.id,d.uaModel].join(" ").toLowerCase().includes(query)).map(d => d.id);
        const selected = new Set(state.selectedDevices[platform] || []);
        if (deviceAction.dataset.deviceAction === "select-visible") visible.forEach(id => selected.add(id));
        else visible.forEach(id => selected.delete(id));
        state.selectedDevices[platform] = [...selected];
        renderUaDevicePicker(); playUiSound("tap"); return;
      }
      const tab = event.target.closest("[data-device-tab]");
      if (tab) { state.deviceTab = tab.dataset.deviceTab; renderUaDevicePicker(); playUiSound("tap"); return; }
      const filter = event.target.closest("[data-result-filter]");
      if (filter) { state.resultFilter = filter.dataset.resultFilter; state.showAllResults = false; renderUaResultsView(); return; }
      if (event.target.closest("[data-show-all-results]")) { state.showAllResults = !state.showAllResults; renderUaResultsView(); return; }
      const copy = event.target.closest("[data-copy-ua]");
      if (copy) {
        const record = state.dataset[Number(copy.dataset.copyUa)];
        if (record) navigator.clipboard.writeText(record.userAgent).then(() => { showToast("User-Agent copied."); playUiSound("success"); }, () => showToast("Clipboard unavailable.", "error"));
      }
    });
  }

  $("generateButton")?.addEventListener("click", async () => {
    const button = $("generateButton");
    button.disabled = true; button.classList.add("is-loading");
    const original = button.innerHTML;
    button.innerHTML = '<span class="button-spinner"></span><span>Generating…</span>';
    try {
      await new Promise(resolve => requestAnimationFrame(() => resolve()));
      state.dataset = generateUaBatch();
      state.resultFilter = "all"; state.showAllResults = false;
      renderDatasetPreview();
      if (!state.dataset.length) showToast("No unique User-Agent strings matched this configuration.", "error");
      else {
        const requested = Number($("uaAndroidQty").value) + Number($("uaIosQty").value);
        showToast((state.duplicatesBlocked
          ? `Generated ${state.dataset.length} of ${requested}; ${state.duplicatesBlocked} duplicates skipped.`
          : `Generated ${state.dataset.length} User-Agents.`) + (state.uaWarnings?.length ? " " + state.uaWarnings.join(" ") : ""));
        playUiSound("success");
      }
    } catch (error) { showToast(error.message, "error"); playUiSound("error"); }
    finally { button.innerHTML = original; button.classList.remove("is-loading"); updateUaTotals(); }
  });
  $("downloadDataset")?.addEventListener("click", exportDataset);
  $("copyDataset")?.addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(state.dataset.map(x => x.userAgent).join("\n")); showToast("All User-Agents copied."); playUiSound("success"); }
    catch { showToast("Clipboard access is unavailable in this browser.", "error"); }
  });
  $("saveUaBatch")?.addEventListener("click", () => {
    if (!state.dataset.length) return;
    let batches = []; try { batches = JSON.parse(localStorage.getItem("nexora_ua_batches") || "[]"); } catch {}
    const id = `ua-${Date.now()}`;
    batches.unshift({ id, name: `UA set ${new Date().toLocaleString()}`, records: state.dataset, savedAt: new Date().toISOString() });
    localStorage.setItem("nexora_ua_batches", JSON.stringify(batches.slice(0,20)));
    renderSavedBatches(); $("uaSavedBatches").value = id; showToast("User-Agent set saved on this device."); playUiSound("success");
  });
  $("uaSavedBatches")?.addEventListener("change", () => {
    const id = $("uaSavedBatches").value; if (!id) return;
    let batches = []; try { batches = JSON.parse(localStorage.getItem("nexora_ua_batches") || "[]"); } catch {}
    const batch = batches.find(x => x.id === id);
    if (batch) { state.dataset = batch.records || []; state.duplicatesBlocked = 0; state.resultFilter = "all"; renderDatasetPreview(); showToast("Saved User-Agent set loaded."); }
  });
  $("runRefresh")?.addEventListener("click", runRefresh);
  $("reloadRuns")?.addEventListener("click", reloadRuns);
  $("reloadAudit")?.addEventListener("click", loadAudit);
  $("assistantForm")?.addEventListener("submit", askAssistant);
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => { $("assistantPrompt").value = button.dataset.prompt; $("assistantPrompt").focus(); }));
}

async function init() {
  bindEvents();
  wireLogoFallbacks();
  await loadData();
  await verifyAdmin();
}


init();

// Admin entry: the sign-in chip stays hidden on public pages; it appears on
// /admin (and for a browser that already holds an admin session).
const adminPath = /\/admin\/?$/.test(location.pathname);
if (adminPath || sessionStorage.getItem("nexora_admin_token")) document.body.classList.add("show-admin-chip");
if (adminPath) setTimeout(() => { if (!state.isAdmin) openModal("loginModal"); }, 900);
