// Wave 4 regression test for B06: (1) a record with no markets array
// must NOT pass a specific-market filter (it still appears under "all
// markets"); before the fix it passed every market. (2) The market
// selector options must cover every market code the catalog uses, not
// only the built-in country list; unknown codes are labelled with the
// code itself. Run: node tests/market-filter.test.js
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.join(__dirname, "..");
const app = fs.readFileSync(path.join(root, "web", "app.js"), "utf8");
const catalog = JSON.parse(fs.readFileSync(path.join(root, "web", "devices.json"), "utf8"));
const assert = (condition, message) => { if (!condition) throw new Error(message); };

assert(app.includes("marketOptions().map("), "selector does not build options from marketOptions()");

const slice = (startMarker, endMarker) => {
  const start = app.indexOf(startMarker);
  assert(start >= 0, `missing: ${startMarker}`);
  const end = app.indexOf(endMarker, start);
  assert(end > start, `missing end marker after: ${startMarker}`);
  return app.slice(start, end);
};
const source = [
  slice("const UA_COUNTRIES", "const UA_APP_NAMES"),
  slice("function platformDevices(", "function marketOptions("),
  slice("function marketOptions(", "function numericVersionSort("),
].join("\n");

const records = [
  { platform: "android", markets: ["US", "AM"] },
  { platform: "android" },                    // no markets array at all
  { platform: "android", markets: [] },       // empty markets
  { platform: "android", markets: ["DE"] },
  { platform: "ios_emulation", markets: ["US"] },
];
let country = "";
const context = {
  allRecords: () => records,
  $: (id) => (id === "uaCountry" ? { value: country } : null),
};
vm.createContext(context);
vm.runInContext(`${source}\nthis.platformDevices = platformDevices; this.marketOptions = marketOptions; this.UA_COUNTRIES = UA_COUNTRIES;`, context);

// (1) Filter behaviour.
country = "";
assert(context.platformDevices("android").length === 4, "all-markets view must include every record");
country = "US";
assert(context.platformDevices("android").length === 1, "US filter must keep only records whose markets include US");
country = "AM";
assert(context.platformDevices("android").length === 1, "AM filter must keep the AM record");
country = "DE";
assert(context.platformDevices("android").length === 1, "DE filter must keep the DE record");
country = "FR";
assert(context.platformDevices("android").length === 0, "FR filter must keep nothing here");

// (2) Selector coverage against the real published catalog.
context.allRecords = () => [...(catalog.android || []), ...(catalog.ios_emulation || [])];
const options = context.marketOptions();
const optionCodes = new Set(options.map(([code]) => code));
const catalogCodes = new Set(context.allRecords().flatMap(d => Array.isArray(d.markets) ? d.markets : []));
assert(catalogCodes.size === 100, `expected 100 catalog market codes, got ${catalogCodes.size}`);
for (const code of catalogCodes) assert(optionCodes.has(code), `selector is missing catalog market ${code}`);
const us = options.find(([code]) => code === "US");
assert(us && us[1] === "United States", "known codes keep their country names");
const am = options.find(([code]) => code === "AM");
assert(am && am[1] === "AM", "codes outside the built-in list are labelled with the code itself");
assert(options.length === context.UA_COUNTRIES.length + (catalogCodes.size - [...catalogCodes].filter(c => context.UA_COUNTRIES.some(([k]) => k === c)).length),
  "option count must be the built-in list plus the catalog-only codes");

console.log("market-filter.test.js: all assertions passed");
