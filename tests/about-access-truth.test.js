// Wave 5 regression test for B08: the website About page described
// public catalog browsing, but every catalog view is behind
// administrator sign-in — only the generator is public. The About
// text must match the actual access model enforced by go().
// Run: node tests/about-access-truth.test.js
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "web", "index.html"), "utf8");
const app = fs.readFileSync(path.join(__dirname, "..", "web", "app.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

// Ground truth: the gate itself.
assert(app.includes('const adminViews = ["devices","applications","overview","compatibility","automation","review","assistant","audit","settings","about"];'),
  "admin view gate not found — recheck the access model before trusting this test");

// The About page must not promise public browsing anymore.
assert(!html.includes("Visitors can browse device data"), "About still promises public catalog browsing");
assert(!html.includes("separates public data access"), "About intro still implies public data access");
assert(html.includes("Visitors can generate QA compatibility fixtures with the public User-Agent generator."),
  "About does not describe the public generator accurately");
assert(html.includes("Browsing the device and application catalogs requires administrator sign-in."),
  "About does not state the sign-in requirement for catalogs");

console.log("about-access-truth.test.js: all assertions passed");
