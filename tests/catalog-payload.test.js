// Wave 5 regression test for C17: the website must not accept a
// non-catalog 200 response (e.g. an HTML page from a misrouted
// deployment, parsed as a detail object) as its data — doing so showed
// "API connected" with an empty catalog and disabled mobile
// generation. loadData now accepts the API result only when it has the
// catalog shape and otherwise falls back to the bundled file.
// Run: node tests/catalog-payload.test.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const app = fs.readFileSync(path.join(__dirname, "..", "web", "app.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

const start = app.indexOf("function isCatalogPayload(");
assert(start >= 0, "isCatalogPayload() missing");
const end = app.indexOf("async function loadData()", start);
const source = app.slice(start, end);
const context = {};
vm.createContext(context);
vm.runInContext(`${source}\nthis.isCatalogPayload = isCatalogPayload;`, context);
const ok = context.isCatalogPayload;

assert(ok({ android: [], ios_emulation: [], meta: {} }) === true, "minimal valid catalog rejected");
assert(ok({ android: [{ id: "a" }], ios_emulation: [{ id: "b" }], meta: { dataVersion: "1" } }) === true, "populated catalog rejected");
assert(ok({ detail: "<html><body>Not Found</body></html>" }) === false, "HTML detail object accepted as catalog");
assert(ok({}) === false, "empty object accepted as catalog");
assert(ok(null) === false, "null accepted as catalog");
assert(ok("catalog") === false, "string accepted as catalog");
assert(ok({ android: [], ios_emulation: [] }) === false, "payload without meta accepted");
assert(ok({ android: {}, ios_emulation: [], meta: {} }) === false, "non-array android accepted");

// loadData must gate the API result on the shape check.
const loadStart = app.indexOf("async function loadData()");
const loadEnd = app.indexOf("function allRecords()", loadStart);
const loadSource = app.slice(loadStart, loadEnd);
assert(loadSource.includes("if (!isCatalogPayload(payload)) throw"), "loadData does not reject non-catalog API payloads");
assert(loadSource.includes('fetch("./devices.json"'), "bundled fallback missing from loadData");

console.log("catalog-payload.test.js: all assertions passed");
