// Wave 5 regression test for C15: the legacy generator silently
// substituted a hardcoded Chrome version when the fetched catalog had
// none, while still labelling the source "Live database". The status
// line must now disclose the fallback whenever it is used.
// Run: node tests/legacy-chrome-fallback.test.js
const fs = require("fs");
const path = require("path");

const source = fs.readFileSync(path.join(__dirname, "..", "web", "generator-legacy.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

const bootStart = source.indexOf("async function boot()");
assert(bootStart >= 0, "boot() not found");
const boot = source.slice(bootStart, source.indexOf("async function loadHistory()", bootStart));

assert(boot.includes("const chromeFromDb = Boolean(v.chrome);"), "boot does not track whether Chrome came from the catalog");
assert(boot.includes('document.getElementById("chrome").value = v.chrome || FALLBACK_CHROME;'), "Chrome field no longer prefers the catalog value");
assert(boot.includes('(chromeFromDb ? "" : " · Chrome version not in database — built-in fallback in use")'),
  "status line does not disclose the Chrome fallback");
assert(boot.includes('(live ? "Live database" : "Bundled copy")'), "live/bundled source label changed");

console.log("legacy-chrome-fallback.test.js: all assertions passed");
