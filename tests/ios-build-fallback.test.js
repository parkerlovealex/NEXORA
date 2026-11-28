// Wave 5 regression test for C16: when the catalog records no iOS
// build for the chosen OS version, the UA still needs a Mobile/<build>
// token — but the generated record must label it as the generic
// fallback instead of presenting it as catalog data. Before the fix,
// the literal 15E148 was inserted with buildId null and no label.
// Run: node tests/ios-build-fallback.test.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const app = fs.readFileSync(path.join(__dirname, "..", "web", "app.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

// Record-level labelling must exist in the generator output.
assert(app.includes('buildSource: buildId ? "database" : (platform === "ios" && !appId ? "fallback" : null)'),
  "generated records do not carry buildSource");
assert(app.includes("The Mobile build token is the generic fallback; no build is recorded for this OS version."),
  "fallback disclosure missing from the record note");

// Behavioural check on the real builder: extract buildBrowserUa and
// drive its iOS branch with stubbed inputs.
const start = app.indexOf("function buildBrowserUa(");
const end = app.indexOf("function buildVerifiedAppUa", start);
assert(start >= 0 && end > start, "could not locate buildBrowserUa()");
const builderSource = app.slice(start, end);
const context = {
  BROWSER_LABELS: { safari: "Safari", chrome: "Chrome", firefox: "Firefox", edge: "Edge" },
  browserVersion: () => "26.0",
  desktopBrowserEntry: () => null,
  desktopBrowserEntries: () => [],
  $: () => ({ value: "" }),
  versions: () => ({ chrome: "140.0.0.0" }),
};
vm.createContext(context);
vm.runInContext(`${builderSource}\nthis.buildBrowserUa = buildBrowserUa;`, context);

const noBuild = context.buildBrowserUa("ios", { uaModel: "iPhone16,2", builds: {} }, "26", "safari");
assert(noBuild.includes("Mobile/15E148"), "fallback token missing when no build is recorded");
const withBuild = context.buildBrowserUa("ios", { uaModel: "iPhone16,2", builds: { "26": "23A341" } }, "26", "safari");
assert(withBuild.includes("Mobile/23A341"), "recorded build not used in the UA");
assert(!withBuild.includes("15E148"), "fallback token leaked into a UA with a recorded build");

console.log("ios-build-fallback.test.js: all assertions passed");
