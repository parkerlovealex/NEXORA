// Wave 4 regression test for B05: a mobile browser version taken from
// the catalog (prefill or random pick) must be labelled "database", not
// "manual"; only a version the user actually edited is "manual". Before
// the fix, every iOS browser and every non-Chrome/non-WebView Android
// browser was labelled "manual" regardless of where the version came
// from. Run: node tests/ua-version-source.test.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const app = fs.readFileSync(path.join(__dirname, "..", "web", "app.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

// The generator must route mobile provenance through the helper, and the
// old android-only inline condition must be gone.
assert(app.includes("mobileVersionSource(platform, browser)"), "generator does not use mobileVersionSource()");
assert(!app.includes('["chrome","webview"].includes(browser)'), "old android-only source condition still present");
assert(app.includes('"Browser version catalog"'), "result cards do not label the database source");

const slice = (startMarker, endMarker) => {
  const start = app.indexOf(startMarker);
  assert(start >= 0, `missing: ${startMarker}`);
  const end = app.indexOf(endMarker, start);
  assert(end > start, `missing end marker after: ${startMarker}`);
  return app.slice(start, end);
};
const source = [
  slice("const MOBILE_VERSION_INPUT_IDS", "function mobileVersionSource("),
  slice("function browserVersionInputId(", "const MOBILE_VERSION_INPUT_IDS"),
  slice("function mobileVersionSource(", "function browserUsable("),
].join("\n");

const elements = new Map();
const el = (id) => {
  if (!elements.has(id)) elements.set(id, { value: "", dataset: {} });
  return elements.get(id);
};
el("uaVersionMode").value = "latest";
const context = {
  $: (id) => el(id),
  meta: () => ({ browserVersions: { safariIos: ["26.0"], chromeAndroid: ["140.0.0.0"] } }),
};
vm.createContext(context);
vm.runInContext(`${source}\nthis.mobileVersionSource = mobileVersionSource; this.MOBILE_VERSION_INPUT_IDS = MOBILE_VERSION_INPUT_IDS;`, context);
const src = context.mobileVersionSource;

// Untouched catalog prefills: previously mislabelled "manual".
assert(src("ios", "safari") === "database", "iOS Safari prefill must be database");
assert(src("ios", "chrome") === "database", "iOS Chrome prefill must be database");
assert(src("android", "firefox") === "database", "Android Firefox prefill must be database");
assert(src("android", "opera") === "database", "Android Opera prefill must be database");
assert(src("android", "webview") === "database", "Android WebView prefill must be database");

// A version the user edited is manual, on every mobile browser.
el("uaSafariVersion").dataset.manual = "1";
assert(src("ios", "safari") === "manual", "edited iOS Safari version must be manual");
el("uaFirefoxAndroidVersion").dataset.manual = "1";
assert(src("android", "firefox") === "manual", "edited Android Firefox version must be manual");

// Random mode: the pick comes from the catalog list when one exists,
// even if the (ignored) input field was edited earlier.
el("uaVersionMode").value = "random5";
assert(src("ios", "safari") === "database", "random iOS Safari pick must be database");
assert(src("android", "chrome") === "database", "random Android Chrome pick must be database");
// WebView has no random-list key, so the input rule still applies.
assert(src("android", "webview") === "database", "random WebView falls back to the prefill rule");
el("uaChromiumEngineVersion").dataset.manual = "1";
assert(src("android", "webview") === "manual", "random WebView with an edited engine field must be manual");

// Every mobile version input participates in the manual flag.
for (const id of ["uaChromeVersion", "uaChromiumEngineVersion", "uaSafariVersion", "uaChromeIosVersion",
  "uaFirefoxAndroidVersion", "uaFirefoxIosVersion", "uaEdgeAndroidVersion", "uaEdgeIosVersion", "uaOperaVersion"]) {
  assert(context.MOBILE_VERSION_INPUT_IDS.has(id), `manual-flag set is missing ${id}`);
}

console.log("ua-version-source.test.js: all assertions passed");
