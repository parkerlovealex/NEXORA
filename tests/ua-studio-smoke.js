const fs = require("fs");
const path = require("path");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

const root = path.resolve(__dirname, "..");
const studio = path.join(root, "web", "ua-studio");
const devices = JSON.parse(fs.readFileSync(path.join(studio, "data", "devices.json"), "utf8"));
const desktop = JSON.parse(fs.readFileSync(path.join(studio, "data", "desktop-catalog.json"), "utf8"));
const templates = JSON.parse(fs.readFileSync(path.join(studio, "data", "ua_templates.json"), "utf8"));
const source = fs.readFileSync(path.join(studio, "src", "Main.kt"), "utf8");
const bundle = fs.readFileSync(path.join(studio, "ua-studio.js"), "utf8");
const page = fs.readFileSync(path.join(studio, "index.html"), "utf8");
const home = fs.readFileSync(path.join(root, "web", "index.html"), "utf8");
const styles = fs.readFileSync(path.join(studio, "styles.css"), "utf8");

assert(devices.android.length === 44, "Expected 44 Android device records");
assert(devices.ios_emulation.length === 15, "Expected 15 iOS device records");
assert(desktop.platforms.windows.osVersions.length === 8, "Expected eight Windows OS releases");
assert(desktop.platforms.macos.osVersions.length === 10, "Expected ten macOS releases");
for (const platform of ["windows", "macos"]) {
  for (const [browser, config] of Object.entries(desktop.platforms[platform].browsers)) {
    assert(config.versions.length === 5, `${platform}/${browser} should have five browser versions`);
  }
}
assert(!desktop.platforms.windows.browsers.safari, "Safari must not be offered on Windows");
for (const key of ["chromeAndroid", "chromeIos", "firefoxAndroid", "firefoxIos", "edgeAndroid", "edgeIos", "operaAndroid", "safariIos"]) {
  assert(devices.meta.browserVersions[key].length >= 5, `Missing mobile version list ${key}`);
}
assert(templates.records.some(row => row.app === "facebook" && row.platform === "android" && row.status === "candidate"), "Facebook Android candidate record missing");
assert(templates.records.every(row => row.status !== "verified" || row.template), "Verified template rows must include a template");

for (const feature of [
  "buildMobileUa", "buildDesktopUa", "buildCandidateAppUa", "scheduleDevices",
  "resolveBrowserVersion", "exportJson", "exportCsv", "copyAll", "deduplicate",
  "marketCountry", "customCounts", "localeProfile"
]) assert(source.includes(feature), `Kotlin source is missing feature marker: ${feature}`);
assert(bundle.length > 300000, "Compiled Kotlin/JS bundle appears incomplete");
assert(page.includes("ua-studio.js"), "Dedicated page must load the compiled Kotlin/JS bundle");
assert(home.includes('href="./ua-studio/"'), "Main website must link to the dedicated UA Studio route");
assert(!styles.includes("fonts.googleapis.com"), "Studio must not require an external font network request");

console.log("NEXORA Kotlin UA Studio smoke checks passed.");
console.log(`Mobile catalog: ${devices.android.length} Android + ${devices.ios_emulation.length} iOS devices.`);
console.log(`Desktop catalog: ${desktop.platforms.windows.osVersions.length} Windows + ${desktop.platforms.macos.osVersions.length} macOS releases.`);
console.log("Desktop browser version lists: five per supported browser.");
console.log(`App-template inventory: ${templates.records.length} source-classified records.`);
console.log("Kotlin/JS bundle, dedicated page, main-site route and core feature markers verified.");
