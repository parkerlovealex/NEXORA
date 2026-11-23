"use strict";
/* NEXORA UA Generator - same templates as the Android app (AppUa.kt / UaBuilder.kt).
   Data: devices.json - fetched live from the NEXORA GitHub repo first
   (the exact file the app auto-updates from), bundled copy as fallback. */

const DATA_URL =
  "https://raw.githubusercontent.com/parkerlovealex/NEXORA/main/app/src/main/assets/devices.json";
const FALLBACK_CHROME = "126.0.6478.122";
const APPS = [
  ["facebook", "Facebook"], ["instagram", "Instagram"], ["tiktok", "TikTok"],
  ["x", "X (Twitter)"], ["threads", "Threads"], ["linkedin", "LinkedIn"],
  ["pinterest", "Pinterest"], ["reddit", "Reddit"], ["snapchat", "Snapchat"],
  ["weibo", "Weibo"], ["telegram", "Telegram"], ["messenger", "Messenger"],
  ["wechat", "WeChat"], ["line", "LINE"], ["kik", "Kik"],
  ["discord", "Discord"], ["viber", "Viber"]
];

let DB = null;

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

function appUa(appId, p, os, chrome, appVersion, appToken) {
  if (p.isIos) {
    const base = safariUa(p, os);
    if (appId === "facebook") {
      return base + (appVersion
        ? " [FBAN/FBIOS;FBAV/" + appVersion + ";FBDV/" + p.uaModel + ";FBMD/iPhone;]"
        : " [FBAN/FBIOS;FBDV/" + p.uaModel + ";FBMD/iPhone;]");
    }
    if (appId === "instagram" && appVersion) {
      const scale = (p.dpr % 1 === 0) ? p.dpr.toFixed(2) : String(p.dpr);
      return base + " Instagram " + appVersion + " (" + p.uaModel + "; iOS " +
        String(os).replace(/\./g, "_") + "; en_US; scale=" + scale + "; " +
        p.w + "x" + p.h + "; IABMV/1)";
    }
    return base;
  }
  const base = webViewBase(p, os, chrome);
  if (appId === "facebook") {
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
      p.uaModel + "; en_US)";
  }
  return appToken ? base + " " + appToken : base;
}

function buildUa(mode, p, os, chrome, appVersion, appToken) {
  return mode === "browser"
    ? browserUa(p, os, chrome)
    : appUa(mode, p, os, chrome, appVersion, appToken);
}

/* ---------------- UI (browser only) ---------------- */

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

async function loadData() {
  try {
    const r = await fetch(DATA_URL, { cache: "no-store" });
    if (!r.ok) throw new Error("live fetch failed");
    return { data: await r.json(), live: true };
  } catch (e) {
    const r = await fetch("./devices.json", { cache: "no-store" });
    return { data: await r.json(), live: false };
  }
}

function init() {
  const deviceSel = document.getElementById("device");
  const osSel = document.getElementById("os");
  const modeSel = document.getElementById("mode");
  const chromeIn = document.getElementById("chrome");
  const appVerIn = document.getElementById("appVersion");
  const qtyIn = document.getElementById("qty");
  const out = document.getElementById("output");
  const meta = document.getElementById("metaLine");

  modeSel.innerHTML = '<option value="browser">Browser</option>' +
    APPS.map(a => '<option value="' + a[0] + '">' + a[1] + "</option>").join("");

  const devices = flatten(DB);
  deviceSel.innerHTML = devices.map((d, i) =>
    '<option value="' + i + '">' + d.brand + " " + d.model + (d.isIos ? " (iOS)" : "") + "</option>"
  ).join("");

  function currentDevice() { return devices[Number(deviceSel.value)] || devices[0]; }

  function syncOs() {
    const d = currentDevice();
    osSel.innerHTML = d.osVersions.map(v => "<option>" + v + "</option>").join("");
    osSel.value = d.osVersions[d.osVersions.length - 1];
  }
  function syncAppVersion() {
    const info = appInfo(modeSel.value);
    appVerIn.value = info.version || "";
    appVerIn.placeholder = info.version ? "" : "Not in data yet - leave blank or type one";
  }

  deviceSel.addEventListener("change", () => { syncOs(); generate(); });
  osSel.addEventListener("change", generate);
  modeSel.addEventListener("change", () => { syncAppVersion(); generate(); });
  chromeIn.addEventListener("input", generate);
  appVerIn.addEventListener("input", generate);
  qtyIn.addEventListener("input", generate);
  document.getElementById("generate").addEventListener("click", generate);
  document.getElementById("copyAll").addEventListener("click", () => {
    const text = Array.from(out.querySelectorAll(".ua")).map(e => e.textContent).join("\n");
    if (text) navigator.clipboard.writeText(text);
  });

  function generate() {
    const d = currentDevice();
    if (!d) return;
    const mode = modeSel.value;
    const chrome = chromeIn.value.trim();
    const info = appInfo(mode);
    const appVersion = appVerIn.value.trim();
    const token = info.token;
    const qty = Math.max(1, Math.min(100, Number(qtyIn.value) || 1));
    const seen = new Set();
    const results = [];
    // Vary across the device's OS versions (each with its own build ID) for bulk output.
    const osList = d.osVersions.slice().reverse();
    for (let i = 0; results.length < qty; i++) {
      const os = osList[i % osList.length];
      const ua = buildUa(mode, d, os, chrome, appVersion, token);
      if (!seen.has(ua)) { seen.add(ua); results.push(ua); }
      if (i > qty * 3) break;
    }
    const first = buildUa(mode, d, osSel.value, chrome, appVersion, token);
    if (results[0] !== first) results.unshift(first);
    out.innerHTML = results.slice(0, qty).map(u =>
      '<div class="ua">' + u.replace(/</g, "&lt;") + "</div>").join("");
  }

  chromeIn.value = (DB.meta && DB.meta.versions && DB.meta.versions.chrome) || FALLBACK_CHROME;
  meta.textContent = "Data version " + ((DB.meta && DB.meta.dataVersion) || "?") +
    " · Updated " + ((DB.meta && DB.meta.updatedAt) || "?") +
    " · " + devices.length + " devices · Same devices.json the NEXORA app uses";
  syncOs();
  syncAppVersion();
  generate();
}

if (typeof document !== "undefined") {
  loadData().then(({ data, live }) => {
    DB = data;
    init();
    if (!live) {
      document.getElementById("metaLine").textContent += " · (bundled copy - live data unreachable)";
    }
  }).catch(() => {
    document.getElementById("metaLine").textContent = "Could not load device data.";
  });
}

if (typeof module !== "undefined") {
  module.exports = { buildUa, browserUa, webViewBase, appUa, flatten, sdkFor };
}
