// Wave 4 regression test for B01 (owner decision, 2026-11-28):
// verified-only generation. Script-candidate apps stay visible in the
// picker with their supplied formats, but they cannot be selected and
// no code path may route them into generation. Before the fix, the
// picker enabled candidate apps and the generator built UAs from the
// candidate formats. Run: node tests/candidate-generation.test.js
const fs = require("fs");
const path = require("path");

const app = fs.readFileSync(path.join(__dirname, "..", "web", "app.js"), "utf8");
const assert = (condition, message) => { if (!condition) throw new Error(message); };

// The candidate generation path is gone entirely.
assert(!app.includes("buildCandidateAppUa"), "candidate UA builder still present");
assert(!app.includes("candidateFormatFor"), "candidate format resolver still present");
assert(!app.includes("hasCandidate"), "candidate eligibility helper still present");
assert(!app.includes("appSource = isVerified") && !app.includes(': "script-candidate-app";'), "generator still assigns the script-candidate source");

// The picker enables only apps with a verified template.
assert(app.includes('${ready ? "" : "disabled"}'), "picker does not disable non-ready apps");
assert(!app.includes("ready || candidate"), "picker still enables candidate apps");
assert(app.includes("Not selectable for generation"), "candidate preview does not state the verified-only rule");

// Selection for generation is filtered to verified templates, with a
// warning naming anything dropped.
assert(app.includes("state.selectedApps.filter(id => verifiedTemplates(id, platform).length)"),
  "generation selection is not filtered to verified templates");
assert(app.includes("only verified templates generate"), "dropped-app warning missing");

// The generation branch hard-refuses an unverified app instead of
// falling back to a candidate format.
const branchStart = app.indexOf("appId = selectedAppIds[");
assert(branchStart >= 0, "generation app branch not found");
const branch = app.slice(branchStart, branchStart + 700);
assert(branch.includes("script candidates cannot generate"), "generation guard missing");
assert(branch.includes('appSource = "verified-app-template"'), "generation does not fix the verified source");
assert(branch.includes("buildVerifiedAppUa(appId, platform, device, osVersion)"), "verified builder not used");

// The candidate formats themselves remain visible for reference.
assert(app.includes("UA_CANDIDATE_FORMATS"), "candidate format data removed");
assert(app.includes("View supplied format"), "candidate format preview removed");
assert(app.includes('"SCRIPT CANDIDATE"'), "candidate status label removed");

console.log("candidate-generation.test.js: all assertions passed");
