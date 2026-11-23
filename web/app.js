"use strict";

/* NEXORA Next public workspace + private admin control plane. */
const API_BASE = String(window.NEXORA_CONFIG?.apiBase || "").replace(/\/$/, "");
const APP_NAMES = {
  facebook: "Facebook", fblite: "Facebook Lite", instagram: "Instagram", tiktok: "TikTok",
  x: "X", threads: "Threads", linkedin: "LinkedIn", pinterest: "Pinterest", reddit: "Reddit",
  snapchat: "Snapchat", weibo: "Weibo", telegram: "Telegram", messenger: "Messenger",
  wechat: "WeChat", line: "LINE", kik: "Kik", discord: "Discord", viber: "Viber"
};
const state = {
  data: null, devices: [], apiOnline: false, apiError: "", token: sessionStorage.getItem("nexora_admin_token") || "",
  isAdmin: false, dataset: [], appFilter: "all", theme: localStorage.getItem("nexora_theme") || (window.matchMedia && window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark")
};
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dateText = (value) => value ? new Date(value).toLocaleString() : "Not checked";
const numberText = (value) => Number(value || 0).toLocaleString();

async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
  let body = {};
  const text = await response.text();
  try { body = text ? JSON.parse(text) : {}; } catch { body = { detail: text || response.statusText }; }
  if (!response.ok) {
    if (response.status === 401 && state.token) clearAdmin();
    const detail = body.detail;
    throw new Error(typeof detail === "string" ? detail : detail?.message || JSON.stringify(detail) || `Request failed (${response.status})`);
  }
  return body;
}

async function loadData() {
  try {
    state.data = await api("/api/v1/data");
    state.apiOnline = true; state.apiError = "";
  } catch (error) {
    state.apiOnline = false; state.apiError = error.message;
    const response = await fetch("./devices.json", { cache: "no-store" });
    if (!response.ok) throw new Error("Could not load the local catalog or connect to the NEXORA API.");
    state.data = await response.json();
  }
  state.devices = [
    ...(state.data.android || []).map(record => ({ platform: "android", record, sourceStatus: record.sourceStatus || "bundled" })),
    ...(state.data.ios_emulation || []).map(record => ({ platform: "ios_emulation", record, sourceStatus: record.sourceStatus || "bundled" }))
  ];
  renderAll();
  updateHealth();
}

function allRecords() { return state.devices.map(item => ({ ...item.record, platform: item.platform, sourceStatus: item.sourceStatus })); }
function meta() { return state.data?.meta || {}; }
function versions() { return meta().versions || {}; }
function apps() { return meta().apps || {}; }

function updateHealth() {
  const dot = $("healthDot"), topDot = $("topStatusDot");
  if (state.apiOnline) {
    dot?.classList.add("good"); topDot?.classList.add("good");
    $("healthText").textContent = "API connected"; $("healthSub").textContent = "Live central database";
    $("topStatusText").textContent = "LIVE DATA";
  } else {
    dot?.classList.remove("good"); topDot?.classList.remove("good");
    $("healthText").textContent = "Offline fallback"; $("healthSub").textContent = "Using bundled read-only data";
    $("topStatusText").textContent = "BUNDLED DATA";
  }
  $("activityHealth").textContent = state.apiOnline ? "Central data service connected" : "Local catalog loaded";
  $("activityHealthDetail").textContent = state.apiOnline ? "API is responding normally" : "Admin editing and source automation require the API";
}

function renderAll() {
  renderOverview(); renderDeviceFilters(); renderDevices(); renderApplications(); renderCompatibility(); renderGeneratorFilters();
  $("dataVersionTag").textContent = meta().dataVersion || "Unversioned";
  $("activityVersion").textContent = meta().dataVersion || "—";
  $("activityDate").textContent = meta().updatedAt ? `Updated ${meta().updatedAt}` : "No update date";
  $("activitySource").textContent = meta().source || "NEXORA catalog";
  if (state.isAdmin) refreshAdminViews();
}

function renderOverview() {
  const list = allRecords();
  const appList = Object.entries(apps());
  const known = appList.filter(([, value]) => Boolean(value)).length;
  $("statDevices").textContent = numberText(list.length);
  $("statAndroid").textContent = numberText(list.filter(x => x.platform === "android").length);
  $("statIos").textContent = numberText(list.filter(x => x.platform === "ios_emulation").length);
  $("statCoverage").textContent = `${known}/${appList.length}`;
  $("statDeviceFoot").textContent = `${list.length} records in current catalog`;
  $("statCoverageFoot").textContent = `${appList.length - known} version fields need verification`;
}

function renderDeviceFilters() {
  const brands = [...new Set(allRecords().map(d => d.brand).filter(Boolean))].sort();
  const options = '<option value="all">All manufacturers</option>' + brands.map(x => `<option value="${esc(x)}">${esc(x)}</option>`).join("");
  const deviceBrand = $("deviceBrand"), genBrand = $("genBrand");
  if (deviceBrand) { const old = deviceBrand.value; deviceBrand.innerHTML = options; if (brands.includes(old)) deviceBrand.value = old; }
  if (genBrand) { const old = genBrand.value; genBrand.innerHTML = options; if (brands.includes(old)) genBrand.value = old; }
}

function filteredDevices() {
  const query = ($("deviceSearch")?.value || "").trim().toLowerCase();
  const platform = $("devicePlatform")?.value || "all";
  const brand = $("deviceBrand")?.value || "all";
  return state.devices.filter(item => {
    const d = item.record;
    if (platform !== "all" && item.platform !== platform) return false;
    if (brand !== "all" && d.brand !== brand) return false;
    if (query && ![d.id, d.brand, d.model, d.uaModel, d.soc].join(" ").toLowerCase().includes(query)) return false;
    return true;
  });
}

function renderDevices() {
  const rows = filteredDevices();
  $("deviceCount").textContent = `${rows.length} records`;
  $("deviceTableFoot").textContent = `Showing ${rows.length} of ${state.devices.length} catalog records`;
  const tbody = $("deviceTable");
  if (!rows.length) { tbody.innerHTML = '<tr><td colspan="7" class="empty-cell">No matching device records.</td></tr>'; return; }
  tbody.innerHTML = rows.map(item => {
    const d = item.record, display = d.display || {};
    const status = item.sourceStatus === "admin_verified" ? '<span class="tag">ADMIN VERIFIED</span>' : item.sourceStatus === "ai_verified" ? '<span class="tag">AI REVIEWED</span>' : '<span class="tag neutral">LEGACY RECORD</span>';
    const action = state.isAdmin ? `<td class="admin-column"><button class="action-link" data-edit-device="${esc(d.id)}">Edit</button> <button class="action-link" data-delete-device="${esc(d.id)}">Delete</button></td>` : "";
    return `<tr><td><div class="device-cell"><span class="device-glyph">▯</span><span><strong>${esc(d.brand)} ${esc(d.model)}</strong><small>${esc(d.id)}</small></span></div></td><td><span class="tag neutral">${item.platform === "android" ? "ANDROID" : "IOS"}</span></td><td>${esc(d.uaModel || "—")}</td><td>${esc((d.osVersions || []).join(", "))}</td><td>${numberText(display.w)} × ${numberText(display.h)} · ${esc(display.dpr || "—")}x</td><td>${status}</td>${action}</tr>`;
  }).join("");
  tbody.querySelectorAll("[data-edit-device]").forEach(button => button.addEventListener("click", () => openDeviceEditor(button.dataset.editDevice)));
  tbody.querySelectorAll("[data-delete-device]").forEach(button => button.addEventListener("click", () => deleteDevice(button.dataset.deleteDevice)));
}

function renderApplications() {
  const entries = Object.entries(apps());
  const filtered = entries.filter(([id, value]) => state.appFilter === "all" || (state.appFilter === "known" ? Boolean(value) : !value));
  $("applicationGrid").innerHTML = filtered.map(([id, raw]) => {
    const version = typeof raw === "string" ? raw : raw?.version || "";
    const known = Boolean(version);
    const label = APP_NAMES[id] || id;
    const token = typeof raw === "object" && raw ? raw.token : "";
    const sourceUrl = typeof raw === "object" && raw ? raw.sourceUrl || "" : "";
    return `<article class="application-card"><div class="app-card-top"><div class="app-logo">${esc(label.slice(0, 2).toUpperCase())}</div><div><strong>${esc(label)}</strong><small>${esc(id)}</small></div></div><div class="app-card-release"><span>Version metadata</span><strong>${known ? esc(version) : "Not verified"}</strong></div><div class="app-card-link">${known ? "Version value stored" : "No verified version stored"}${token ? ` · token: ${esc(token)}` : ""}${sourceUrl ? `<br><a href="${esc(sourceUrl)}" target="_blank" rel="noopener noreferrer">Open official source ↗</a>` : ""}</div>${state.isAdmin ? `<div style="margin-top:auto;padding-top:12px"><button class="action-link" data-edit-app="${esc(id)}">Edit metadata</button></div>` : ""}</article>`;
  }).join("") || '<div class="panel output-empty"><strong>No applications match this filter.</strong></div>';
  $("applicationGrid").querySelectorAll("[data-edit-app]").forEach(button => button.addEventListener("click", () => openAppEditor(button.dataset.editApp)));
}

function renderCompatibility() {
  const rows = allRecords();
  const android = new Set(rows.filter(x => x.platform === "android").flatMap(x => x.osVersions || []));
  const ios = new Set(rows.filter(x => x.platform === "ios_emulation").flatMap(x => x.osVersions || []));
  const withBuild = rows.filter(x => Object.keys(x.builds || {}).length).length;
  $("compatAndroid").textContent = android.size; $("compatIos").textContent = ios.size;
  $("compatBuilds").textContent = withBuild; $("compatMissing").textContent = rows.length - withBuild;
  $("compatTable").innerHTML = rows.map(d => `<tr><td><div class="device-cell"><span class="device-glyph">▯</span><span><strong>${esc(d.brand)} ${esc(d.model)}</strong><small>${esc(d.id)}</small></span></div></td><td>${esc(d.platform === "android" ? "Android" : "iOS")}</td><td>${esc((d.osVersions || []).join(", "))}</td><td>${Object.keys(d.builds || {}).length ? esc(Object.keys(d.builds).join(", ")) : '<span class="tag warn">Not supplied</span>'}</td><td>${esc((d.ramGb || []).join(", "))} GB</td><td>${esc((d.storageGb || []).join(", "))} GB</td></tr>`).join("");
}

function renderGeneratorFilters() {
  const records = allRecords();
  const allOs = [...new Set(records.flatMap(d => d.osVersions || []))].sort((a, b) => Number(a) - Number(b));
  const select = $("genOs"), previous = select.value;
  select.innerHTML = '<option value="all">All supported versions</option>' + allOs.map(v => `<option value="${esc(v)}">${esc(v)}</option>`).join("");
  if (allOs.includes(previous)) select.value = previous;
}

function makeDataset() {
  const platform = $("genPlatform").value, brand = $("genBrand").value, search = $("genSearch").value.trim().toLowerCase();
  const os = $("genOs").value, quantity = Number($("genQuantity").value) || 50;
  const records = allRecords().filter(d => {
    if (platform !== "all" && d.platform !== platform) return false;
    if (brand !== "all" && d.brand !== brand) return false;
    if (search && ![d.id, d.brand, d.model, d.uaModel, d.soc].join(" ").toLowerCase().includes(search)) return false;
    return true;
  });
  const combinations = [];
  records.forEach(d => (d.osVersions || []).forEach(version => {
    if (os === "all" || os === version) combinations.push({ d, version });
  }));
  return combinations.slice(0, quantity).map(({ d, version }, index) => ({
    fixtureId: `nexora-${String(index + 1).padStart(4, "0")}`,
    deviceId: d.id, brand: d.brand, model: d.model, modelIdentifier: d.uaModel,
    platform: d.platform === "android" ? "Android" : "iOS",
    osVersion: version, chipset: d.soc, gpu: d.gpu, cpuCores: d.cores,
    display: d.display, ramOptionsGb: d.ramGb, storageOptionsGb: d.storageGb,
    firmwareBuildId: (d.builds || {})[version] || null,
    marketCodes: d.markets || [], sourceStatus: d.sourceStatus || "bundled",
    catalogDataVersion: meta().dataVersion || null
  }));
}

function renderDatasetPreview() {
  const rows = state.dataset;
  $("outputCount").textContent = `${rows.length} RECORDS`;
  const preview = $("generatorPreview");
  if (!rows.length) {
    preview.className = "output-empty";
    preview.innerHTML = '<div class="empty-art">!</div><strong>No matching combinations</strong><span>Try a broader platform, manufacturer or OS filter.</span>';
    $("exportActions").classList.add("hidden"); return;
  }
  preview.className = "output-preview";
  preview.innerHTML = `<div class="preview-meta">Previewing ${Math.min(15, rows.length)} of ${rows.length} fixtures · ${esc(meta().dataVersion || "local data")}</div><div class="table-scroll"><table><thead><tr><th>FIXTURE</th><th>DEVICE</th><th>OS</th><th>DISPLAY</th><th>BUILD</th></tr></thead><tbody>${rows.slice(0, 15).map(r => `<tr><td>${esc(r.fixtureId)}</td><td>${esc(r.brand)} ${esc(r.model)}</td><td>${esc(r.osVersion)}</td><td>${r.display.w} × ${r.display.h}</td><td>${r.firmwareBuildId ? esc(r.firmwareBuildId) : '<span class="tag warn">Not supplied</span>'}</td></tr>`).join("")}</tbody></table></div>`;
  $("exportActions").classList.remove("hidden");
}

function downloadText(filename, text, mime) {
  const blob = new Blob([text], { type: mime || "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob); const a = document.createElement("a");
  a.href = url; a.download = filename; a.click(); URL.revokeObjectURL(url);
}
function csvValue(value) { const text = typeof value === "object" ? JSON.stringify(value) : String(value ?? ""); return `"${text.replace(/"/g, '""')}"`; }
function exportDataset() {
  if (!state.dataset.length) return;
  const format = $("genFormat").value;
  if (format === "json") downloadText("nexora-compatibility-dataset.json", JSON.stringify({ meta: { dataVersion: meta().dataVersion, generatedAt: new Date().toISOString(), count: state.dataset.length }, records: state.dataset }, null, 2), "application/json");
  else if (format === "csv") {
    const keys = Object.keys(state.dataset[0]);
    const csv = [keys.map(csvValue).join(","), ...state.dataset.map(row => keys.map(key => csvValue(row[key])).join(","))].join("\n");
    downloadText("nexora-compatibility-dataset.csv", csv, "text/csv;charset=utf-8");
  } else downloadText("nexora-compatibility-dataset.ndjson", state.dataset.map(row => JSON.stringify(row)).join("\n"), "application/x-ndjson");
}

function showToast(message, type = "") {
  const toast = document.createElement("div"); toast.className = `toast ${type}`; toast.textContent = message;
  $("toastRegion").appendChild(toast); setTimeout(() => toast.remove(), 3800);
}
function go(view) {
  document.querySelectorAll(".view").forEach(el => el.classList.toggle("active", el.id === `view-${view}`));
  document.querySelectorAll(".nav-item[data-view]").forEach(el => el.classList.toggle("active", el.dataset.view === view));
  const active = document.querySelector(`.nav-item[data-view="${view}"]`);
  $("breadcrumbCurrent").textContent = active ? active.textContent.trim().replace(/\s+\d+$/, "") : view[0].toUpperCase() + view.slice(1);
  $("sidebar").classList.remove("open");
  if (view === "automation" && state.isAdmin) loadAutomation();
  if (view === "review" && state.isAdmin) loadReview();
  if (view === "audit" && state.isAdmin) loadAudit();
}

function setAdmin(token) {
  state.token = token; state.isAdmin = true; sessionStorage.setItem("nexora_admin_token", token);
  document.body.classList.add("is-admin");
  document.querySelectorAll(".admin-only").forEach(el => el.classList.remove("hidden"));
  $("adminLabel").textContent = "Administrator"; $("adminAvatar").textContent = "A"; $("authButton").textContent = "Sign out";
  renderDevices(); renderApplications(); renderOverview(); refreshAdminViews();
}
function clearAdmin() {
  state.token = ""; state.isAdmin = false; sessionStorage.removeItem("nexora_admin_token");
  document.body.classList.remove("is-admin"); document.querySelectorAll(".admin-only").forEach(el => el.classList.add("hidden"));
  $("adminLabel").textContent = "Guest access"; $("adminAvatar").textContent = "G"; $("authButton").textContent = "Sign in";
  if (["automation", "review", "assistant", "audit", "settings"].includes(currentView())) go("overview");
  renderDevices(); renderApplications(); renderOverview();
}
function currentView() { return document.querySelector(".view.active")?.id.replace("view-", "") || "overview"; }
async function verifyAdmin() {
  if (!state.token) return;
  try { await api("/api/v1/admin/overview"); setAdmin(state.token); }
  catch { clearAdmin(); }
}

function openModal(id) { $(id).classList.remove("hidden"); }
function closeModal(id) { $(id).classList.add("hidden"); }
function findDevice(id) { return state.devices.find(item => item.record.id === id); }
function openDeviceEditor(id = "") {
  if (!state.isAdmin) return;
  const item = id ? findDevice(id) : null, d = item?.record || {};
  $("deviceModalTitle").textContent = id ? "Edit device record" : "Add device record";
  $("editOriginalId").value = id;
  $("editPlatform").value = item?.platform || "android";
  $("editId").value = d.id || ""; $("editBrand").value = d.brand || ""; $("editModel").value = d.model || "";
  $("editUaModel").value = d.uaModel || ""; $("editSoc").value = d.soc || ""; $("editGpu").value = d.gpu || "";
  $("editCores").value = d.cores || ""; $("editWidth").value = d.display?.w || ""; $("editHeight").value = d.display?.h || ""; $("editDpr").value = d.display?.dpr || "";
  $("editRam").value = (d.ramGb || []).join(", "); $("editStorage").value = (d.storageGb || []).join(", "); $("editOs").value = (d.osVersions || []).join(", ");
  $("editBuilds").value = JSON.stringify(d.builds || {}, null, 2); $("editMarkets").value = (d.markets || []).join(", "); $("editSourceUrl").value = d.sourceUrl || d.source_url || ""; $("deviceError").classList.add("hidden"); openModal("deviceModal");
}
function parseNumbers(value, allowFloat = false) {
  return value.split(",").map(x => allowFloat ? Number(x.trim()) : parseInt(x.trim(), 10)).filter(x => Number.isFinite(x) && x > 0);
}
async function saveDevice(event) {
  event.preventDefault();
  const originalId = $("editOriginalId").value;
  let builds;
  try { builds = JSON.parse($("editBuilds").value || "{}"); } catch { $("deviceError").textContent = "Build IDs must be valid JSON."; $("deviceError").classList.remove("hidden"); return; }
  const record = {
    id: $("editId").value.trim(), brand: $("editBrand").value.trim(), model: $("editModel").value.trim(),
    uaModel: $("editUaModel").value.trim(), soc: $("editSoc").value.trim(), gpu: $("editGpu").value.trim(), cores: Number($("editCores").value),
    display: { w: Number($("editWidth").value), h: Number($("editHeight").value), dpr: Number($("editDpr").value) },
    ramGb: parseNumbers($("editRam").value), storageGb: parseNumbers($("editStorage").value),
    osVersions: $("editOs").value.split(",").map(x => x.trim()).filter(Boolean), builds,
    markets: $("editMarkets").value.split(",").map(x => x.trim().toUpperCase()).filter(Boolean),
    source_url: $("editSourceUrl").value.trim()
  };
  const errors = [];
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(record.id)) errors.push("Record ID must use lowercase kebab-case.");
  ["brand", "model", "uaModel", "soc", "gpu"].forEach(k => { if (!record[k]) errors.push(`${k} is required.`); });
  if (!record.cores || record.cores < 1) errors.push("CPU cores must be positive.");
  if (!record.display.w || !record.display.h || !record.display.dpr) errors.push("Display width, height and DPR must be positive.");
  if (!record.ramGb.length || !record.storageGb.length || !record.osVersions.length) errors.push("RAM, storage and OS versions are required.");
  if (!originalId && !record.source_url) errors.push("An official source URL is required for new records.");
  if (record.source_url && !/^https:\/\//i.test(record.source_url)) errors.push("Source URL must use HTTPS.");
  if (Object.keys(builds).some(k => !record.osVersions.includes(k))) errors.push("Every build ID key must be a supported OS version.");
  if (errors.length) { $("deviceError").textContent = errors.join(" "); $("deviceError").classList.remove("hidden"); return; }
  try {
    const body = JSON.stringify({ platform: $("editPlatform").value, record });
    if (originalId) await api(`/api/v1/admin/devices/${encodeURIComponent(originalId)}`, { method: "PUT", body });
    else await api("/api/v1/admin/devices", { method: "POST", body });
    closeModal("deviceModal"); showToast(originalId ? "Device updated." : "Device added."); await loadData();
  } catch (error) { $("deviceError").textContent = error.message; $("deviceError").classList.remove("hidden"); }
}
async function deleteDevice(id) {
  if (!state.isAdmin || !confirm(`Delete ${id} from the shared catalog? This action will be recorded.`)) return;
  try { await api(`/api/v1/admin/devices/${encodeURIComponent(id)}`, { method: "DELETE" }); showToast("Device deleted."); await loadData(); }
  catch (error) { showToast(error.message, "error"); }
}

function openAppEditor(id = "") {
  if (!state.isAdmin) return;
  const select = $("editAppId"); select.innerHTML = Object.keys(apps()).map(key => `<option value="${esc(key)}">${esc(APP_NAMES[key] || key)}</option>`).join("");
  if (id) select.value = id;
  const value = apps()[select.value]; $("editAppVersion").value = typeof value === "string" ? value : value?.version || "";
  $("editAppToken").value = typeof value === "object" && value ? value.token || "" : "";
  $("editAppSourceUrl").value = typeof value === "object" && value ? value.sourceUrl || "" : "";
  $("appVersionError").classList.add("hidden"); openModal("appVersionModal");
}
async function saveAppVersion(event) {
  event.preventDefault();
  try {
    await api("/api/v1/admin/app-versions", { method: "POST", body: JSON.stringify({ app_id: $("editAppId").value, version: $("editAppVersion").value.trim(), token: $("editAppToken").value.trim(), source_url: $("editAppSourceUrl").value.trim() }) });
    closeModal("appVersionModal"); showToast("Application version metadata saved."); await loadData();
  } catch (error) { $("appVersionError").textContent = error.message; $("appVersionError").classList.remove("hidden"); }
}

async function refreshAdminViews() {
  if (!state.isAdmin) return;
  try {
    const overview = await api("/api/v1/admin/overview");
    $("reviewCount").textContent = overview.pendingProposals;
    $("reviewCount").classList.toggle("hidden", !overview.pendingProposals);
  } catch { /* token may have expired */ }
}
async function loadAutomation() {
  try {
    const [sources, runs] = await Promise.all([api("/api/v1/admin/sources"), api("/api/v1/admin/runs")]);
    $("sourceCount").textContent = `${sources.length} sources`;
    $("sourceTable").innerHTML = sources.map(s => `<tr><td><strong>${esc(s.name)}</strong><br><small class="muted-cell">${esc(s.url)}</small></td><td>${esc(s.category.replaceAll("_", " "))}</td><td>${s.trusted ? '<span class="tag">OFFICIAL</span>' : '<span class="tag neutral">UNVERIFIED</span>'}</td><td><span class="tag ${s.status === "healthy" ? "" : s.status === "error" ? "bad" : "warn"}">${esc(s.status.toUpperCase())}</span></td><td>${esc(dateText(s.lastChecked))}</td><td>${esc(s.details?.latestVersion || s.details?.error || s.details?.note || (s.details?.aiExtraction === "not_configured" ? "AI key not configured" : s.details?.lastObservation || "—"))}</td><td><button class="action-link" data-toggle-source="${esc(s.id)}" data-enabled="${s.enabled ? "true" : "false"}">${s.enabled ? "Pause" : "Enable"}</button></td></tr>`).join("") || '<tr><td colspan="7" class="empty-cell">No sources configured.</td></tr>';
    $("sourceTable").querySelectorAll("[data-toggle-source]").forEach(button => button.addEventListener("click", () => toggleSource(button.dataset.toggleSource, button.dataset.enabled !== "true")));
    renderRuns(runs);
  } catch (error) { $("sourceTable").innerHTML = `<tr><td colspan="6" class="empty-cell">${esc(error.message)}</td></tr>`; }
}
function renderRuns(runs) {
  $("runsTable").innerHTML = runs.map(r => `<tr><td>#${r.id}</td><td>${esc(r.source)}</td><td><span class="tag ${r.status === "completed" ? "" : r.status === "failed" ? "bad" : "warn"}">${esc(r.status.toUpperCase())}</span></td><td>${esc(dateText(r.startedAt))}</td><td>${esc(r.summary)}</td></tr>`).join("") || '<tr><td colspan="5" class="empty-cell">No automation runs yet.</td></tr>';
}
async function toggleSource(id, enabled) {
  try { await api(`/api/v1/admin/sources/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ enabled }) }); showToast(enabled ? "Source connector enabled." : "Source connector paused."); await loadAutomation(); }
  catch (error) { showToast(error.message, "error"); }
}

async function runRefresh() {
  try { const result = await api("/api/v1/admin/refresh", { method: "POST" }); showToast(result.message || "Source check queued."); await loadAutomation(); }
  catch (error) { showToast(error.message, "error"); }
}
async function reloadRuns() { try { renderRuns(await api("/api/v1/admin/runs")); } catch (error) { showToast(error.message, "error"); } }

async function loadReview() {
  try {
    const [pending, approved, rejected] = await Promise.all(["pending", "approved", "rejected"].map(s => api(`/api/v1/admin/proposals?status=${s}`)));
    $("pendingTotal").textContent = pending.length; $("approvedTotal").textContent = approved.length; $("rejectedTotal").textContent = rejected.length;
    $("reviewCount").textContent = pending.length; $("reviewCount").classList.toggle("hidden", !pending.length);
    $("reviewList").innerHTML = pending.map(p => `<article class="proposal-card"><div class="proposal-top"><div><h3>${esc(p.title)}</h3><p>Proposal #${p.id} · ${esc(p.kind)} · ${esc(dateText(p.createdAt))}</p></div><span class="tag warn">PENDING</span></div><div class="proposal-meta"><span class="tag neutral">Confidence ${Math.round(p.confidence * 100)}%</span><span class="tag neutral">${p.evidence.length} evidence item(s)</span></div><pre class="proposal-payload">${esc(JSON.stringify(p.payload, null, 2))}</pre><details class="proposal-evidence"><summary>View source evidence</summary><pre class="proposal-payload">${esc(JSON.stringify(p.evidence, null, 2))}</pre></details><div class="proposal-actions"><button class="button button-secondary button-small" data-reject-proposal="${p.id}">Reject</button><button class="button button-primary button-small" data-approve-proposal="${p.id}">Approve and publish</button></div></article>`).join("") || '<div class="panel output-empty"><div class="empty-art">✓</div><strong>No pending changes</strong><span>New source findings and AI proposals will appear here.</span></div>';
    $("reviewList").querySelectorAll("[data-approve-proposal]").forEach(b => b.addEventListener("click", () => decideProposal(b.dataset.approveProposal, "approve")));
    $("reviewList").querySelectorAll("[data-reject-proposal]").forEach(b => b.addEventListener("click", () => decideProposal(b.dataset.rejectProposal, "reject")));
  } catch (error) { $("reviewList").innerHTML = `<div class="panel output-empty">${esc(error.message)}</div>`; }
}
async function decideProposal(id, decision) {
  const message = decision === "approve" ? "Approve this change and publish it to the public catalog?" : "Reject this proposal?";
  if (!confirm(message)) return;
  try { await api(`/api/v1/admin/proposals/${id}/decision?decision=${decision}`, { method: "POST" }); showToast(`Proposal ${decision}d.`); await loadReview(); await loadData(); }
  catch (error) { showToast(error.message, "error"); }
}
async function loadAudit() {
  try {
    const rows = await api("/api/v1/admin/audit");
    $("auditTable").innerHTML = rows.map(r => `<tr><td>${esc(dateText(r.createdAt))}</td><td>${esc(r.actor)}</td><td>${esc(r.action)}</td><td>${esc(r.target)}</td><td>${esc(JSON.stringify(r.details))}</td></tr>`).join("") || '<tr><td colspan="5" class="empty-cell">No administrative changes recorded yet.</td></tr>';
  } catch (error) { $("auditTable").innerHTML = `<tr><td colspan="5" class="empty-cell">${esc(error.message)}</td></tr>`; }
}

function appendAssistantMessage(text, who = "ai") {
  const row = document.createElement("div"); row.className = `assistant-message ${who === "user" ? "assistant-message-user" : ""}`;
  const avatar = document.createElement("div"); avatar.className = "message-avatar"; avatar.textContent = who === "user" ? "YOU" : "NX";
  const body = document.createElement("div"); const strong = document.createElement("strong"); strong.textContent = who === "user" ? "YOU" : "NEXORA AI";
  const p = document.createElement("p"); p.textContent = text; body.append(strong, p); row.append(avatar, body); $("assistantMessages").appendChild(row); $("assistantMessages").scrollTop = $("assistantMessages").scrollHeight;
}
async function askAssistant(event) {
  event.preventDefault(); const prompt = $("assistantPrompt").value.trim(); if (!prompt) return;
  appendAssistantMessage(prompt, "user"); $("assistantPrompt").value = "";
  try { const result = await api("/api/v1/admin/ai/assist", { method: "POST", body: JSON.stringify({ prompt }) }); appendAssistantMessage(result.answer); $("aiModelLabel").textContent = result.model.toUpperCase(); }
  catch (error) { appendAssistantMessage(`I could not process that request: ${error.message}. Configure AI_API_KEY on the backend and try again.`); }
}

function bindEvents() {
  document.querySelectorAll("[data-view]").forEach(button => button.addEventListener("click", () => go(button.dataset.view)));
  document.querySelectorAll("[data-go]").forEach(button => button.addEventListener("click", () => go(button.dataset.go)));
  document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => closeModal(button.dataset.close)));
  $("mobileMenu").addEventListener("click", () => $("sidebar").classList.toggle("open"));
  $("authButton").addEventListener("click", () => state.isAdmin ? (clearAdmin(), showToast("Signed out.")) : openModal("loginModal"));
  $("loginForm").addEventListener("submit", async event => {
    event.preventDefault(); $("loginError").classList.add("hidden");
    try { const result = await api("/api/v1/admin/login", { method: "POST", body: JSON.stringify({ password: $("adminPassword").value }) }); setAdmin(result.accessToken); closeModal("loginModal"); $("adminPassword").value = ""; showToast("Administrator session started."); }
    catch (error) { $("loginError").textContent = error.message; $("loginError").classList.remove("hidden"); }
  });
  $("deviceSearch").addEventListener("input", renderDevices); $("devicePlatform").addEventListener("change", renderDevices); $("deviceBrand").addEventListener("change", renderDevices);
  $("addDevice").addEventListener("click", () => openDeviceEditor()); $("deviceForm").addEventListener("submit", saveDevice);
  $("exportDevices").addEventListener("click", () => downloadText("nexora-device-catalog.json", JSON.stringify(state.data, null, 2), "application/json"));
  $("appFilters").querySelectorAll("[data-app-filter]").forEach(button => button.addEventListener("click", () => { state.appFilter = button.dataset.appFilter; $("appFilters").querySelectorAll(".filter-pill").forEach(b => b.classList.toggle("active", b === button)); renderApplications(); }));
  $("editAppVersions").addEventListener("click", () => openAppEditor()); $("appVersionForm").addEventListener("submit", saveAppVersion);
  $("genPlatform").addEventListener("change", () => {}); $("genBrand").addEventListener("change", () => {}); $("genSearch").addEventListener("input", () => {}); $("genOs").addEventListener("change", () => {});
  $("genQuantity").addEventListener("input", () => $("genQuantityValue").textContent = $("genQuantity").value);
  $("generateButton").addEventListener("click", () => { state.dataset = makeDataset(); renderDatasetPreview(); if (!state.dataset.length) showToast("No valid device and OS combinations matched your filters.", "error"); });
  $("downloadDataset").addEventListener("click", exportDataset);
  $("copyDataset").addEventListener("click", async () => { try { await navigator.clipboard.writeText(JSON.stringify(state.dataset, null, 2)); showToast("Dataset JSON copied."); } catch { showToast("Clipboard access is unavailable in this browser.", "error"); } });
  $("runRefresh").addEventListener("click", runRefresh); $("reloadRuns").addEventListener("click", reloadRuns); $("reloadAudit").addEventListener("click", loadAudit);
  $("assistantForm").addEventListener("submit", askAssistant);
  document.querySelectorAll("[data-prompt]").forEach(button => button.addEventListener("click", () => { $("assistantPrompt").value = button.dataset.prompt; $("assistantPrompt").focus(); }));
  $("themeToggle").addEventListener("click", () => { state.theme = state.theme === "dark" ? "light" : "dark"; document.body.classList.toggle("light", state.theme === "light"); localStorage.setItem("nexora_theme", state.theme); });
}

async function init() {
  document.body.classList.toggle("light", state.theme === "light");
  bindEvents();
  await loadData();
  await verifyAdmin();
}

document.addEventListener("DOMContentLoaded", init);
