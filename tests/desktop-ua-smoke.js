const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.resolve(__dirname, "..");
const appPath = path.join(root, "web", "app.js");
const htmlPath = path.join(root, "web", "index.html");
const catalogPath = path.join(root, "data", "desktop-platforms-2026-11-25.json");

const app = fs.readFileSync(appPath, "utf8");
const html = fs.readFileSync(htmlPath, "utf8");
const catalog = JSON.parse(fs.readFileSync(catalogPath, "utf8"));

const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

const windows = catalog.platforms.windows.osVersions;
const macos = catalog.platforms.macos.osVersions;
assert(windows.filter(row => row.family === "Windows 11").length === 5, "Expected five Windows 11 releases");
assert(windows.filter(row => row.family === "Windows 10").length === 3, "Expected three Windows 10 releases");
assert(macos.length === 10, "Expected ten macOS major releases");

for (const platform of ["windows", "macos"]) {
  const browsers = catalog.platforms[platform].browsers;
  for (const [browser, config] of Object.entries(browsers)) {
    assert(config.versions.length === 5, `${platform}/${browser} must have five versions`);
  }
}
assert(!catalog.platforms.windows.browsers.safari, "Safari must not be offered on Windows");
assert(/data-platform-mode="desktop"/.test(html), "Desktop batch mode is missing");
assert(/data-platform-mode="all"/.test(html), "All-platform batch mode is missing");

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
assert(ids.length === new Set(ids).size, "HTML contains duplicate element IDs");
for (const id of [
  "uaWindowsQty", "uaMacosQty", "uaWindowsRange", "uaMacosRange",
  "uaWindowsOs", "uaMacosOs", "uaDesktopOsCard",
  "uaChromeWindowsVersion", "uaFirefoxWindowsVersion", "uaEdgeWindowsVersion", "uaOperaWindowsVersion",
  "uaChromeMacosVersion", "uaFirefoxMacosVersion", "uaEdgeMacosVersion", "uaOperaMacosVersion", "uaSafariMacosVersion",
  "uaResultWindows", "uaResultMacos"
]) assert(ids.includes(id), `Missing desktop UI id: ${id}`);

const start = app.indexOf("function buildBrowserUa(");
const end = app.indexOf("function buildVerifiedAppUa", start);
assert(start >= 0 && end > start, "Could not locate buildBrowserUa()");
const builderSource = app.slice(start, end);
const context = {
  BROWSER_LABELS: { chrome:"Chrome", firefox:"Firefox", edge:"Edge", opera:"Opera", safari:"Safari" },
  browserVersion: (platform, browser) => catalog.platforms[platform].browsers[browser].versions[0].uaVersion,
  desktopBrowserEntry: (platform, browser, uaVersion) =>
    catalog.platforms[platform].browsers[browser].versions.find(entry => entry.uaVersion === uaVersion),
  $: () => ({ value: "" }),
  versions: () => ({ chrome: "159.0.8154.33" })
};
vm.createContext(context);
vm.runInContext(`${builderSource}\nthis.buildBrowserUa = buildBrowserUa;`, context);

const build = (platform, browser, osVersion) =>
  context.buildBrowserUa(platform, {}, osVersion, browser);

const winChrome = build("windows", "chrome", "26H2");
const winFirefox = build("windows", "firefox", "26H2");
const winEdge = build("windows", "edge", "26H2");
const macChrome = build("macos", "chrome", "27");
const macFirefox = build("macos", "firefox", "27");
const macOpera = build("macos", "opera", "27");
const macSafari = build("macos", "safari", "27");

assert(winChrome.includes("(Windows NT 10.0; Win64; x64)") && winChrome.includes("Chrome/158.0.0.0"), "Windows Chrome UA incorrect");
assert(winFirefox.includes("rv:161.0") && winFirefox.includes("Firefox/161.0"), "Windows Firefox UA incorrect");
assert(winEdge.endsWith("Edg/158.0.0.0"), "Windows Edge UA incorrect");
assert(macChrome.includes("(Macintosh; Intel Mac OS X 10_15_7)") && macChrome.includes("Chrome/158.0.0.0"), "macOS Chrome UA incorrect");
assert(macFirefox.includes("Mac OS X 10.15") && macFirefox.includes("Firefox/161.0"), "macOS Firefox UA incorrect");
assert(macOpera.includes("Chrome/157.0.0.0") && macOpera.endsWith("OPR/140.0.0.0"), "macOS Opera engine/browser tokens incorrect");
assert(macSafari.endsWith("Version/27.2 Safari/605.1.15"), "macOS Safari UA incorrect");

const win10Chrome = build("windows", "chrome", "22H2");
assert(win10Chrome === winChrome, "Windows 10 and 11 should share the reduced Windows NT 10.0 UA platform token");
const alternateChrome = context.buildBrowserUa("windows", {}, "25H2", "chrome", "157.0.0.0");
assert(alternateChrome.includes("Chrome/157.0.0.0"), "Explicit/random browser version override must drive the UA token");


// Exercise the four-platform quantity modes without needing a full browser DOM.
const quantityStart = app.indexOf("function currentPlatformQuantities()");
const quantityEnd = app.indexOf("function localeMeta()", quantityStart);
assert(quantityStart >= 0 && quantityEnd > quantityStart, "Could not locate platform quantity helpers");
const quantitySource = app.slice(quantityStart, quantityEnd);
const elementMap = new Map();
const makeElement = (value = "") => ({
  value, textContent: "", disabled: false, checked: false, dataset: {},
  classList: { toggle() {}, add() {}, remove() {} },
  setAttribute() {}
});
for (const id of [
  "uaAndroidQty","uaIosQty","uaWindowsQty","uaMacosQty",
  "uaAndroidRange","uaIosRange","uaWindowsRange","uaMacosRange",
  "uaTotal","uaReadySummary","uaBatchSummary","generateButtonCount",
  "generateButton","batchLimitNote","uaMobileDeviceCard","uaIncludeAppsToggle",
  "uaInAppPanel","uaDesktopOsCard","uaWindowsOsField","uaMacosOsField","uaIncludeApps"
]) elementMap.set(id, makeElement(id === "uaAndroidQty" ? 10 : 0));
const modeButtons = ["android","ios","mixed","windows","macos","desktop","all"].map(mode => {
  const el = makeElement(); el.dataset.platformMode = mode; return el;
});
const cards = ["android","ios","windows","macos"].map(platform => {
  const el = makeElement(); el.dataset.platformCard = platform; return el;
});
const desktopBlocks = ["windows","macos"].map(platform => {
  const el = makeElement(); el.dataset.desktopBrowserPlatform = platform; return el;
});
const presetButtons = [10,25,50,100,250,500].map(total => {
  const el = makeElement(); el.dataset.batchPreset = String(total); return el;
});
const fakeDocument = {
  querySelectorAll(selector) {
    if (selector === "[data-platform-mode]") return modeButtons;
    if (selector === "[data-platform-card]") return cards;
    if (selector === "[data-desktop-browser-platform]") return desktopBlocks;
    if (selector === "[data-batch-preset]") return presetButtons;
    return [];
  }
};
const quantityContext = {
  state: { platformMode: "android" },
  $: id => elementMap.get(id) || null,
  document: fakeDocument,
  playUiSound: () => {},
  Math
};
vm.createContext(quantityContext);
vm.runInContext(`${quantitySource}\nthis.setPlatformMode=setPlatformMode;this.applyBatchPreset=applyBatchPreset;this.syncPlatformModeFromQuantities=syncPlatformModeFromQuantities;this.currentPlatformQuantities=currentPlatformQuantities;`, quantityContext);
quantityContext.setPlatformMode("desktop");
let q = quantityContext.currentPlatformQuantities();
assert(q.android === 0 && q.ios === 0 && q.windows === 5 && q.macos === 5, "Desktop mode should split the batch between Windows and macOS");
quantityContext.applyBatchPreset(100);
q = quantityContext.currentPlatformQuantities();
assert(q.windows === 50 && q.macos === 50 && q.android === 0 && q.ios === 0, "Desktop batch preset should split 50/50");
quantityContext.setPlatformMode("all");
q = quantityContext.currentPlatformQuantities();
assert(q.android === 25 && q.ios === 25 && q.windows === 25 && q.macos === 25, "All mode should distribute evenly across four platforms");
quantityContext.setPlatformMode("mixed");
q = quantityContext.currentPlatformQuantities();
assert(q.android === 25 && q.ios === 25 && q.windows === 0 && q.macos === 0, "Both mode should remain Android+iOS only");

console.log("Desktop UA smoke tests passed.");
console.log(`OS catalog: ${windows.length} Windows releases (5 Windows 11 + 3 Windows 10), ${macos.length} macOS releases.`);
console.log("Browser catalog: five versions per supported browser.");
console.log("UA samples: Windows Chrome/Firefox/Edge; macOS Chrome/Firefox/Opera/Safari.");
